package com.openwakeup.parser.ics

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.ParserException
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

/**
 * ICS 事件映射时使用的一节作息时间。
 *
 * @property node OpenWakeUp 中从 1 开始的节次
 * @property startTime 本节开始时间
 * @property endTime 本节结束时间
 */
data class IcsTimeSlot(
    val node: Int,
    val startTime: LocalTime,
    val endTime: LocalTime,
)

/**
 * 一份 ICS 课表的解析结果。
 *
 * @property courses 已映射为星期、节次和周次的课程片段
 * @property semesterStart 实际用于计算周次的第一周周一
 * @property sourceNodeCount ICS 声明的每日总节次数；普通 ICS 未声明时为空
 */
data class IcsParseResult(
    val courses: List<CoursePreview>,
    val semesterStart: LocalDate,
    val sourceNodeCount: Int? = null,
)

/**
 * 将 RFC 5545 iCalendar 事件转换成 OpenWakeUp 的课程预览。
 *
 * 解析器支持折行、文本转义、UTC/TZID 时间、每周 RRULE 的 COUNT/UNTIL/INTERVAL、
 * RDATE 与 EXDATE。OpenWakeUp 自有生成器的精确节次扩展字段拥有最高优先级；旧版 Course Table
 * ICS Formatter 文件则按当前课表的实际节次数逆向还原坐标，其他 ICS 文件映射到当前作息。
 *
 * @param currentTimeSlots 当前课表作息，用于兼容普通日历软件导出的 ICS
 * @param targetZone UTC 事件转换到本地课程时间时使用的时区
 */
class IcsParser(
    currentTimeSlots: List<IcsTimeSlot>,
    private val targetZone: ZoneId = ZoneId.systemDefault(),
) {

    private val fallbackTimeSlots = currentTimeSlots.sortedBy(IcsTimeSlot::node)

    /**
     * 解析 ICS 文本，并将事件日期约束在目标学期内。
     *
     * @param text 完整 ICS 文本
     * @param semesterStart 指定学期第一周周一；传空时从最早事件所在周推断
     * @param maxWeek 允许导入的最大周次
     * @return 可直接写入课程数据库的解析结果
     * @throws ParserException 文件结构、时间或课程内容不可用时抛出
     */
    fun parse(
        text: String,
        semesterStart: LocalDate? = null,
        maxWeek: Int = DEFAULT_MAX_WEEK,
    ): IcsParseResult {
        val lines = unfoldLines(text)
        if (lines.none { it.equals("BEGIN:VCALENDAR", ignoreCase = true) }) {
            throw ParserException.parse("所选文件不是有效的 ICS 日历")
        }
        val events = extractEvents(lines).mapNotNull(::parseEvent)
        if (events.isEmpty()) throw ParserException.empty("ICS 文件中没有可导入的日历事件")
        if (fallbackTimeSlots.isEmpty()) throw ParserException.parse("当前课表没有可用于映射 ICS 时间的作息")

        val sourceNodeCount = parseCalendarNodeCount(lines)
        val importedStart = semesterStart
            ?: parseCalendarSemesterStart(lines)
            ?: inferSemesterStart(events)
        val normalizedStart = importedStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val formatterProfile = chooseFormatterProfile(events)
        // 文件显式声明的源节次数最可靠；旧文件没有扩展字段时才回退到当前作息数量。
        val formatterSourceNodeCount = formatterProfile?.let {
            sourceNodeCount ?: fallbackTimeSlots.size
        }
        val slots = formatterProfile ?: fallbackTimeSlots

        val fragments = events.mapNotNull { event ->
            eventToFragment(
                event = event,
                semesterStart = normalizedStart,
                maxWeek = maxWeek,
                slots = slots,
                formatterSourceNodeCount = formatterSourceNodeCount,
                sourceNodeCount = sourceNodeCount,
            )
        }
        if (fragments.isEmpty()) {
            throw ParserException.empty("ICS 文件中没有位于当前学期范围内的课程")
        }
        return IcsParseResult(
            courses = mergeFragments(fragments),
            semesterStart = normalizedStart,
            sourceNodeCount = sourceNodeCount,
        )
    }

    /**
     * 读取生成端声明的每日总节次数。
     *
     * 该字段描述 `X-OPENWAKEUP-START-NODE` 所在的源课表网格，也是旧 Formatter
     * 线性坐标还原时每天应跨过的行数。字段缺失时保留普通 ICS 的兼容路径；字段存在但
     * 不是 1~60 的整数时直接报告格式错误，避免使用错误基数把课程排到其他星期。
     *
     * @param lines 已展开的 ICS 属性行
     * @return 文件声明的每日总节次数；未声明时返回空
     */
    private fun parseCalendarNodeCount(lines: List<String>): Int? {
        val rawValue = lines.asSequence()
            .mapNotNull(::parseProperty)
            .firstOrNull { property -> property.name == PROPERTY_NODE_COUNT }
            ?.value
            ?.trim()
            ?: return null
        return rawValue.toIntOrNull()?.takeIf { count -> count in 1..MAX_NODE_COUNT }
            ?: throw ParserException.parse("X-OPENWAKEUP-NODE-COUNT 必须是 1~$MAX_NODE_COUNT 的整数")
    }

    /**
     * 读取 OpenWakeUp 生成器写在 VCALENDAR 顶层的第一周起始日期。
     *
     * @param lines 已展开的 ICS 属性行
     * @return 合法的 yyyyMMdd/日期；旧文件没有该字段时返回空
     */
    private fun parseCalendarSemesterStart(lines: List<String>): LocalDate? {
        val rawValue = lines.asSequence()
            .mapNotNull(::parseProperty)
            .firstOrNull { property -> property.name == PROPERTY_SEMESTER_START }
            ?.value
            ?.trim()
            ?: return null
        val compactValue = rawValue.replace("-", "")
        return runCatching {
            LocalDate.parse(compactValue, DateTimeFormatter.BASIC_ISO_DATE)
        }.getOrNull()
    }

    /**
     * 推断学期第一周周一。
     *
     * OpenWakeUp 生成器同时写入事件对应的真实周次，因此即使所有课程都从第二周以后开始，
     * 也能用“首次事件日期 - (周次 - 1)”还原学期起点；普通 ICS 则回退到最早事件所在周。
     */
    private fun inferSemesterStart(events: List<IcsEvent>): LocalDate {
        val explicitCandidates = events.mapNotNull { event ->
            event.explicitWeeks.minOrNull()?.let { firstWeek ->
                event.start.toLocalDate().minusWeeks((firstWeek - 1).toLong())
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            }
        }
        if (explicitCandidates.isNotEmpty()) {
            // 正常文件中的候选日期完全一致；多数票可容忍用户手工修改过个别事件。
            return explicitCandidates.groupingBy { date -> date }.eachCount()
                .maxBy { (_, count) -> count }
                .key
        }
        return events.minOf { event -> event.start.toLocalDate() }
    }

    /** 按 RFC 5545 规则把空格或制表符开头的续行拼回上一属性行。 */
    private fun unfoldLines(text: String): List<String> {
        val unfolded = mutableListOf<String>()
        // 兼容 Windows 编辑器及旧版生成脚本写入的 UTF-8 BOM，确保首属性名仍为 BEGIN。
        text.removePrefix(UTF8_BOM.toString())
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .forEach { line ->
                if ((line.startsWith(' ') || line.startsWith('\t')) && unfolded.isNotEmpty()) {
                    unfolded[unfolded.lastIndex] += line.drop(1)
                } else if (line.isNotBlank()) {
                    unfolded += line.trimEnd()
                }
            }
        return unfolded
    }

    /** 提取 VEVENT 顶层属性；VALARM 等嵌套组件不会污染事件 DESCRIPTION。 */
    private fun extractEvents(lines: List<String>): List<List<IcsProperty>> {
        val events = mutableListOf<List<IcsProperty>>()
        var current: MutableList<IcsProperty>? = null
        var nestedDepth = 0
        lines.forEach { line ->
            val property = parseProperty(line) ?: return@forEach
            when {
                current == null && property.name == "BEGIN" && property.value.equals(
                    "VEVENT",
                    true
                ) -> {
                    current = mutableListOf()
                    nestedDepth = 0
                }

                current != null && property.name == "BEGIN" -> nestedDepth++
                current != null && property.name == "END" && nestedDepth > 0 -> nestedDepth--
                current != null && property.name == "END" && property.value.equals(
                    "VEVENT",
                    true
                ) -> {
                    // 当前分支已经由 `current != null` 完成类型收窄，可直接保存事件属性快照。
                    events += current.toList()
                    current = null
                }
                // 仅收集 VEVENT 顶层属性；上方非空条件已保证可以直接追加，无需强制断言。
                current != null && nestedDepth == 0 -> current.add(property)
            }
        }
        return events
    }

    /** 将单行 ICS 属性拆成名称、参数和值，属性名统一转为大写。 */
    private fun parseProperty(line: String): IcsProperty? {
        val separator = line.indexOf(':')
        if (separator <= 0) return null
        val head = line.substring(0, separator).split(';')
        val parameters = head.drop(1).mapNotNull { token ->
            val equals = token.indexOf('=')
            if (equals <= 0) null else token.substring(0, equals).uppercase() to
                    token.substring(equals + 1).trim().trim('"')
        }.toMap()
        return IcsProperty(
            name = head.first().uppercase(),
            parameters = parameters,
            value = line.substring(separator + 1),
        )
    }

    /** 把一个 VEVENT 转为内部事件；缺少标题或起止时间的非课程事件会被忽略。 */
    private fun parseEvent(properties: List<IcsProperty>): IcsEvent? {
        val startProperty = properties.firstOrNull { it.name == "DTSTART" } ?: return null
        val endProperty = properties.firstOrNull { it.name == "DTEND" } ?: return null
        val summary = properties.firstOrNull { it.name == "SUMMARY" }
            ?.value?.let(::unescapeText)?.trim().orEmpty()
        if (summary.isBlank()) return null

        val start = parseDateTime(startProperty)
        val end = parseDateTime(endProperty)
        if (!end.isAfter(start)) return null
        val location = properties.firstOrNull { it.name == "LOCATION" }
            ?.value?.let(::unescapeText)?.trim().orEmpty()
        val description = properties.firstOrNull { it.name == "DESCRIPTION" }
            ?.value?.let(::unescapeText)?.trim().orEmpty()
        val categories = properties.firstOrNull { it.name == "CATEGORIES" }
            ?.value?.let(::splitEscapedList).orEmpty()
        val recurrence = properties.firstOrNull { it.name == "RRULE" }
            ?.value?.let(::parseRecurrence)
        val includedDates = properties.filter { it.name == "RDATE" }
            .flatMap { property -> parseDateList(property) }
        val excludedDates = properties.filter { it.name == "EXDATE" }
            .flatMap { property -> parseDateList(property) }
            .map(LocalDateTime::toLocalDate)
            .toSet()
        // OpenWakeUp 自有生成器会写入精确网格坐标；标准日历软件会忽略这些 X- 属性。
        val explicitDay =
            properties.positiveIntValue("X-OPENWAKEUP-DAY")?.takeIf { it in 1..DAYS_PER_WEEK }
        val explicitStartNode = properties.positiveIntValue("X-OPENWAKEUP-START-NODE")
        val explicitStep = properties.positiveIntValue("X-OPENWAKEUP-STEP")
        val explicitWeeks =
            properties.firstOrNull { property -> property.name == "X-OPENWAKEUP-WEEKS" }
                ?.value
                ?.split(',')
                ?.mapNotNull { token -> token.trim().toIntOrNull() }
                ?.filter { week -> week > 0 }
                ?.toSet()
                .orEmpty()
        return IcsEvent(
            name = summary,
            teacher = extractTeacher(summary, location, description, categories),
            room = location,
            start = start,
            end = end,
            recurrence = recurrence,
            includedDates = includedDates,
            excludedDates = excludedDates,
            isFormatterFile = categories.any { it.equals(FORMATTER_CATEGORY, ignoreCase = true) },
            explicitDay = explicitDay,
            explicitStartNode = explicitStartNode,
            explicitStep = explicitStep,
            explicitWeeks = explicitWeeks,
        )
    }

    /** 读取指定 ICS 扩展属性中的正整数；缺失、非数字或非正数均返回空。 */
    private fun List<IcsProperty>.positiveIntValue(name: String): Int? =
        firstOrNull { property -> property.name == name }
            ?.value
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { value -> value > 0 }

    /**
     * 解析基本日期时间格式。UTC 值先换算到设备时区；TZID 值也按真实瞬间换算，
     * 从而避免教程脚本导出的 `Z` 时间被误当成本地凌晨课程。
     */
    private fun parseDateTime(property: IcsProperty): LocalDateTime {
        val raw = property.value.trim()
        if (raw.length < BASIC_DATE_LENGTH) throw ParserException.parse("ICS 日期时间格式无效：$raw")
        return runCatching {
            val date =
                LocalDate.parse(raw.take(BASIC_DATE_LENGTH), DateTimeFormatter.BASIC_ISO_DATE)
            val timeMarker = raw.indexOf('T')
            if (timeMarker < 0) return@runCatching date.atStartOfDay()
            val timeDigits = raw.substring(timeMarker + 1).takeWhile(Char::isDigit)
            val time = when (timeDigits.length) {
                4 -> LocalTime.of(timeDigits.take(2).toInt(), timeDigits.drop(2).toInt())
                in 6..Int.MAX_VALUE -> LocalTime.of(
                    timeDigits.take(2).toInt(),
                    timeDigits.substring(2, 4).toInt(),
                    timeDigits.substring(4, 6).toInt(),
                )

                else -> error("unsupported time")
            }
            val local = LocalDateTime.of(date, time)
            when {
                raw.endsWith('Z', ignoreCase = true) ->
                    local.atZone(ZoneOffset.UTC).withZoneSameInstant(targetZone).toLocalDateTime()

                property.parameters["TZID"].isNullOrBlank() -> local
                else -> local.atZone(ZoneId.of(property.parameters.getValue("TZID")))
                    .withZoneSameInstant(targetZone)
                    .toLocalDateTime()
            }
        }.getOrElse { cause ->
            throw ParserException.parse("ICS 日期时间格式无效：$raw", cause)
        }
    }

    /** 解析以逗号分隔的 RDATE/EXDATE 属性。 */
    private fun parseDateList(property: IcsProperty): List<LocalDateTime> =
        splitEscaped(property.value, ',').mapNotNull { value ->
            runCatching { parseDateTime(property.copy(value = value)) }.getOrNull()
        }

    /** 解析每周重复所需的 RRULE 字段；其他频率会退化为单次事件。 */
    private fun parseRecurrence(value: String): IcsRecurrence {
        val fields = value.split(';').mapNotNull { token ->
            val equals = token.indexOf('=')
            if (equals <= 0) null else token.substring(0, equals).uppercase() to token.substring(
                equals + 1
            )
        }.toMap()
        return IcsRecurrence(
            weekly = fields["FREQ"].equals("WEEKLY", ignoreCase = true),
            count = fields["COUNT"]?.toIntOrNull()?.coerceAtLeast(1),
            interval = fields["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            until = fields["UNTIL"]?.let { raw ->
                parseDateTime(IcsProperty("UNTIL", emptyMap(), raw))
            },
        )
    }

    /**
     * 从新旧 Formatter 的分类字段或描述尾部提取教师。
     *
     * 旧生成器顺序是 `ShanghaiTech,教师,Course Table ICS Formatter`，新生成器顺序是
     * `OpenWakeUp ICS Formatter,教师`；没有地点时，描述中的课程名后剩余文本就是教师。
     */
    private fun extractTeacher(
        name: String,
        room: String,
        description: String,
        categories: List<String>,
    ): String {
        val openWakeUpMarkerIndex = categories.indexOfFirst {
            it.equals(OPENWAKEUP_FORMATTER_CATEGORY, ignoreCase = true)
        }
        if (openWakeUpMarkerIndex >= 0) {
            return categories.getOrNull(openWakeUpMarkerIndex + 1)?.trim().orEmpty()
        }
        val legacyMarkerIndex = categories.indexOfFirst {
            it.equals(FORMATTER_CATEGORY, ignoreCase = true)
        }
        if (legacyMarkerIndex > 0) return categories[legacyMarkerIndex - 1].trim()
        if (description.startsWith(name)) {
            val remainder = description.removePrefix(name).trim()
            return if (room.isNotBlank() && remainder.startsWith(room)) {
                remainder.removePrefix(room).trim()
            } else {
                // 无地点课程的描述格式为“课程名 教师”，剩余文本可直接作为教师名称。
                remainder.takeIf { room.isBlank() }.orEmpty()
            }
        }
        return ""
    }

    /** Formatter 文件在两套公开作息中选择与事件时间误差最小的一套。 */
    private fun chooseFormatterProfile(events: List<IcsEvent>): List<IcsTimeSlot>? {
        val formatterEvents = events.filter(IcsEvent::isFormatterFile)
        if (formatterEvents.isEmpty()) return null
        return FORMATTER_TIME_PROFILES.minBy { profile ->
            formatterEvents.sumOf { event ->
                nearestMinuteDistance(
                    event.start.toLocalTime(),
                    profile.map(IcsTimeSlot::startTime)
                ) +
                        nearestMinuteDistance(
                            event.end.toLocalTime(),
                            profile.map(IcsTimeSlot::endTime)
                        )
            }
        }
    }

    /**
     * 将 Formatter 的“13 节制周内线性下标”还原为源网页的星期和节次。
     *
     * @param event 当前日历事件
     * @param profile Formatter 生成事件时间时采用的 13 节作息
     * @param sourceNodeCount 源教务课表每天的真实节数
     * @return 还原后的星期、起始节次和连续节数；跨越真实日边界时返回空
     */
    private fun mapFormatterPosition(
        event: IcsEvent,
        profile: List<IcsTimeSlot>,
        sourceNodeCount: Int,
    ): FormatterPosition? {
        val exportedStart = profile.minBy { slot ->
            minuteDistance(event.start.toLocalTime(), slot.startTime)
        }
        val exportedEnd = profile.filter { slot -> slot.node >= exportedStart.node }
            .minByOrNull { slot -> minuteDistance(event.end.toLocalTime(), slot.endTime) }
            ?: exportedStart
        val step = exportedEnd.node - exportedStart.node + 1
        val linearIndex = (event.start.dayOfWeek.value - 1) * FORMATTER_EXPORTED_NODE_COUNT +
                exportedStart.node - 1
        val sourceDay = linearIndex / sourceNodeCount + 1
        val sourceStartNode = linearIndex % sourceNodeCount + 1
        if (sourceDay !in 1..DAYS_PER_WEEK || sourceStartNode + step - 1 > sourceNodeCount) return null
        return FormatterPosition(sourceDay, sourceStartNode, step)
    }

    /** 将事件的重复日期、星期和时间范围转换为一个待合并片段。 */
    private fun eventToFragment(
        event: IcsEvent,
        semesterStart: LocalDate,
        maxWeek: Int,
        slots: List<IcsTimeSlot>,
        formatterSourceNodeCount: Int?,
        sourceNodeCount: Int?,
    ): CourseFragment? {
        val startSlot = slots.minBy { minuteDistance(event.start.toLocalTime(), it.startTime) }
        val endSlot = slots.filter { it.node >= startSlot.node }
            .minByOrNull { minuteDistance(event.end.toLocalTime(), it.endTime) }
            ?: startSlot
        val explicitPosition = if (
            event.explicitDay != null &&
            event.explicitStartNode != null &&
            event.explicitStep != null
        ) {
            FormatterPosition(event.explicitDay, event.explicitStartNode, event.explicitStep)
        } else {
            null
        }
        if (explicitPosition != null && sourceNodeCount != null) {
            val exceedsSourceGrid = explicitPosition.startNode > sourceNodeCount ||
                    explicitPosition.step > sourceNodeCount - explicitPosition.startNode + 1
            if (exceedsSourceGrid) {
                throw ParserException.parse(
                    "课程“${event.name}”的节次超出 X-OPENWAKEUP-NODE-COUNT 声明的 $sourceNodeCount 节",
                )
            }
        }
        // 自有 X-OPENWAKEUP 坐标优先级最高；旧 Formatter 文件按源文件声明的节次数执行坐标逆变换。
        val resolvedPosition =
            explicitPosition ?: formatterSourceNodeCount?.let { sourceNodeCount ->
                mapFormatterPosition(event, slots, sourceNodeCount)
            }
        val weeks = if (event.explicitWeeks.isNotEmpty()) {
            event.explicitWeeks.filter { week -> week in 1..maxWeek }.toSortedSet()
        } else {
            occurrenceDates(event, semesterStart, maxWeek)
                .map { date ->
                    ChronoUnit.WEEKS.between(
                        semesterStart,
                        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    ).toInt() + 1
                }
                .filter { it in 1..maxWeek }
                .toSortedSet()
        }
        if (weeks.isEmpty()) return null
        return CourseFragment(
            name = event.name,
            teacher = event.teacher,
            room = event.room,
            day = resolvedPosition?.day ?: event.start.dayOfWeek.value,
            startNode = resolvedPosition?.startNode ?: startSlot.node,
            step = resolvedPosition?.step ?: (endSlot.node - startSlot.node + 1),
            weeks = weeks,
        )
    }

    /** 展开每周 RRULE，并合并 RDATE、排除 EXDATE；展开范围始终受学期周数限制。 */
    private fun occurrenceDates(
        event: IcsEvent,
        semesterStart: LocalDate,
        maxWeek: Int,
    ): Set<LocalDate> {
        val dates = linkedSetOf(event.start.toLocalDate())
        val recurrence = event.recurrence
        if (recurrence?.weekly == true) {
            var occurrence = event.start
            var generated = 1
            val semesterEnd = semesterStart.plusWeeks(maxWeek.toLong()).minusDays(1)
            while (true) {
                occurrence = occurrence.plusWeeks(recurrence.interval.toLong())
                if (recurrence.count != null && generated >= recurrence.count) break
                if (recurrence.until != null && occurrence.isAfter(recurrence.until)) break
                if (recurrence.count == null && recurrence.until == null && occurrence.toLocalDate()
                        .isAfter(semesterEnd)
                ) break
                if (occurrence.toLocalDate().isAfter(semesterEnd) && recurrence.count == null) break
                dates += occurrence.toLocalDate()
                generated++
                // COUNT 可能异常巨大；超过目标学期后无需继续生成。
                if (occurrence.toLocalDate()
                        .isAfter(semesterEnd.plusWeeks(recurrence.interval.toLong()))
                ) break
            }
        }
        dates += event.includedDates.map(LocalDateTime::toLocalDate)
        dates -= event.excludedDates
        return dates
    }

    /** 将同一课程、地点和节次的散周压缩为连续周或单双周片段。 */
    private fun mergeFragments(fragments: List<CourseFragment>): List<CoursePreview> =
        fragments.groupBy { fragment ->
            CourseKey(
                fragment.name,
                fragment.teacher,
                fragment.room,
                fragment.day,
                fragment.startNode,
                fragment.step,
            )
        }.flatMap { (key, sameCourse) ->
            val weeks = sameCourse.flatMap { it.weeks }.toSortedSet()
            compressWeeks(weeks).map { range ->
                CoursePreview(
                    name = key.name,
                    teacher = key.teacher,
                    room = key.room,
                    day = key.day,
                    startNode = key.startNode,
                    step = key.step,
                    startWeek = range.first,
                    endWeek = range.last,
                    type = range.type,
                )
            }
        }

    /** 贪心压缩周集合：优先连续周，其次同奇偶周，剩余项保留为单周。 */
    private fun compressWeeks(weeks: Set<Int>): List<WeekRange> {
        val remaining = weeks.toMutableSet()
        val ranges = mutableListOf<WeekRange>()
        while (remaining.isNotEmpty()) {
            val first = remaining.min()
            val step = when {
                first + 1 in remaining -> 1
                first + 2 in remaining -> 2
                else -> 0
            }
            var last = first
            if (step > 0) {
                while (last + step in remaining) last += step
            }
            for (week in first..last step step.coerceAtLeast(1)) remaining -= week
            ranges += WeekRange(
                first = first,
                last = last,
                type = if (step == 2) if (first % 2 == 1) ODD_WEEK else EVEN_WEEK else EVERY_WEEK,
            )
        }
        return ranges
    }

    /** 计算两个时刻的绝对分钟差。 */
    private fun minuteDistance(first: LocalTime, second: LocalTime): Long =
        abs(Duration.between(first, second).toMinutes())

    /** 计算目标时刻与一组候选时刻之间的最小分钟差。 */
    private fun nearestMinuteDistance(target: LocalTime, candidates: List<LocalTime>): Long =
        candidates.minOf { candidate -> minuteDistance(target, candidate) }

    /** 解析 ICS TEXT 转义，包括换行、逗号、分号与反斜杠。 */
    private fun unescapeText(value: String): String {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            if (value[index] == '\\' && index + 1 < value.length) {
                val next = value[index + 1]
                result.append(if (next == 'n' || next == 'N') '\n' else next)
                index += 2
            } else {
                result.append(value[index])
                index++
            }
        }
        return result.toString()
    }

    /** 在不切开反斜杠转义分隔符的前提下拆分属性列表。 */
    private fun splitEscapedList(value: String): List<String> =
        splitEscaped(value, ',').map(::unescapeText).map(String::trim).filter(String::isNotEmpty)

    /** 通用的反斜杠感知列表拆分。 */
    private fun splitEscaped(value: String, separator: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        value.forEach { char ->
            when {
                escaped -> {
                    current.append('\\').append(char)
                    escaped = false
                }

                char == '\\' -> escaped = true
                char == separator -> {
                    parts += current.toString()
                    current.clear()
                }

                else -> current.append(char)
            }
        }
        if (escaped) current.append('\\')
        parts += current.toString()
        return parts
    }

    private data class IcsProperty(
        val name: String,
        val parameters: Map<String, String>,
        val value: String,
    )

    private data class IcsRecurrence(
        val weekly: Boolean,
        val count: Int?,
        val interval: Int,
        val until: LocalDateTime?,
    )

    private data class IcsEvent(
        val name: String,
        val teacher: String,
        val room: String,
        val start: LocalDateTime,
        val end: LocalDateTime,
        val recurrence: IcsRecurrence?,
        val includedDates: List<LocalDateTime>,
        val excludedDates: Set<LocalDate>,
        val isFormatterFile: Boolean,
        val explicitDay: Int?,
        val explicitStartNode: Int?,
        val explicitStep: Int?,
        val explicitWeeks: Set<Int>,
    )

    private data class CourseFragment(
        val name: String,
        val teacher: String,
        val room: String,
        val day: Int,
        val startNode: Int,
        val step: Int,
        val weeks: Set<Int>,
    )

    private data class CourseKey(
        val name: String,
        val teacher: String,
        val room: String,
        val day: Int,
        val startNode: Int,
        val step: Int,
    )

    private data class WeekRange(
        val first: Int,
        val last: Int,
        val type: Int,
    )

    /** Formatter 线性坐标逆变换后的课程位置。 */
    private data class FormatterPosition(
        val day: Int,
        val startNode: Int,
        val step: Int,
    )

    private companion object {
        const val BASIC_DATE_LENGTH = 8
        const val UTF8_BOM = '\uFEFF'
        const val DEFAULT_MAX_WEEK = 25
        const val EVERY_WEEK = 0
        const val ODD_WEEK = 1
        const val EVEN_WEEK = 2
        const val FORMATTER_CATEGORY = "Course Table ICS Formatter"
        const val OPENWAKEUP_FORMATTER_CATEGORY = "OpenWakeUp ICS Formatter"
        const val PROPERTY_NODE_COUNT = "X-OPENWAKEUP-NODE-COUNT"
        const val PROPERTY_SEMESTER_START = "X-OPENWAKEUP-SEMESTER-START"
        const val DAYS_PER_WEEK = 7
        const val MAX_NODE_COUNT = 60
        const val FORMATTER_EXPORTED_NODE_COUNT = 13

        /** 教程仓库公开的常规作息与 2020 新作息，顺序就是其导出的 1~13 节。 */
        val FORMATTER_TIME_PROFILES: List<List<IcsTimeSlot>> = listOf(
            formatterProfile(
                "08:15-09:00", "09:10-09:55", "10:15-11:00", "11:10-11:55",
                "13:00-13:45", "13:55-14:40", "15:00-15:45", "15:55-16:40",
                "16:50-17:35", "17:45-18:30", "18:40-19:25", "19:35-20:20",
                "20:30-21:15",
            ),
            formatterProfile(
                "08:15-09:00", "09:10-09:55", "10:15-11:00", "11:10-11:55",
                "13:00-13:45", "13:55-14:40", "15:00-15:45", "15:55-16:40",
                "16:50-17:35", "18:00-18:45", "18:55-19:40", "19:50-20:35",
                "20:45-21:30",
            ),
        )

        /** 把紧凑的 `HH:mm-HH:mm` 常量转为带节次的作息列表。 */
        fun formatterProfile(vararg ranges: String): List<IcsTimeSlot> =
            ranges.mapIndexed { index, range ->
                val (start, end) = range.split('-', limit = 2)
                IcsTimeSlot(index + 1, LocalTime.parse(start), LocalTime.parse(end))
            }
    }
}
