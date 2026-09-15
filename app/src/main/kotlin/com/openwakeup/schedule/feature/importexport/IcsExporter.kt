package com.openwakeup.schedule.feature.importexport

import com.openwakeup.schedule.core.database.entity.CourseDetailEntity
import com.openwakeup.schedule.core.database.entity.CourseEntity
import com.openwakeup.schedule.core.database.entity.TableEntity
import com.openwakeup.schedule.core.database.entity.TimeDetailEntity
import com.openwakeup.schedule.core.util.DateUtils
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/**
 * 将 OpenWakeUp 课表导出为 RFC 5545 iCalendar 文本。
 *
 * 标准日历软件依赖 `DTSTART`、`DTEND` 与 `RRULE` 显示课程；OpenWakeUp 在相同事件中额外写入
 * `X-OPENWAKEUP-*` 属性，以便再次导入时精确恢复学期起点、每日节次数、星期、节次和离散周次。
 * `X-` 属性属于 iCalendar 允许的扩展属性，普通日历客户端会安全忽略未知字段。
 */
object IcsExporter {

    /**
     * 构建一份完整的 ICS 日历。
     *
     * @param table 待导出的课表配置
     * @param courses 课表中的课程基本信息
     * @param details 课程的星期、节次与周次明细
     * @param timeDetails 当前课表使用的作息时间
     * @param exportedAt 导出时间；默认使用当前时刻，独立参数便于稳定验证输出
     * @return 使用 CRLF 换行且按 75 字节规则折行的完整 VCALENDAR 文本
     */
    fun build(
        table: TableEntity,
        courses: List<CourseEntity>,
        details: List<CourseDetailEntity>,
        timeDetails: List<TimeDetailEntity>,
        exportedAt: Instant = Instant.now(),
    ): String {
        val semesterStart = LocalDate.parse(table.startDate)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val coursesById = courses.associateBy(CourseEntity::id)
        val timesByNode = timeDetails.associateBy(TimeDetailEntity::node)
        val sourceNodeCount = table.nodes.coerceIn(1, MAX_NODE_COUNT)
        val contentLines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH",
            "PRODID:-//OpenWakeUp//Schedule Export 1.0//CN",
            "X-WR-CALNAME:${escapeText(table.tableName)}",
            "X-WR-TIMEZONE:$CALENDAR_ZONE_ID",
            "X-OPENWAKEUP-NODE-COUNT:$sourceNodeCount",
            "X-OPENWAKEUP-SEMESTER-START:${semesterStart.format(BASIC_DATE_FORMATTER)}",
        )
        val timestamp = UTC_DATE_TIME_FORMATTER.format(exportedAt)

        details.forEachIndexed { index, detail ->
            val course = coursesById[detail.courseId] ?: return@forEachIndexed
            val activeWeeks = activeWeeks(detail, table.maxWeek)
            if (activeWeeks.isEmpty() || detail.day !in 1..DAYS_PER_WEEK) return@forEachIndexed

            val endNode = detail.startNode + detail.step - 1
            require(detail.startNode > 0 && detail.step > 0 && endNode <= sourceNodeCount) {
                "课程“${course.courseName}”的节次超出当前课表声明的 $sourceNodeCount 节"
            }

            val (startTime, endTime) = resolveEventTimes(detail, timesByNode)
            require(endTime.isAfter(startTime)) {
                "课程“${course.courseName}”的结束时间必须晚于开始时间"
            }
            val firstDate = semesterStart
                .plusWeeks((activeWeeks.first() - 1).toLong())
                .plusDays((detail.day - 1).toLong())
            val recurrenceInterval = when (detail.type) {
                CourseDetailEntity.TYPE_ODD, CourseDetailEntity.TYPE_EVEN -> 2
                else -> 1
            }
            val teacher = detail.teacher.trim()
            val room = detail.room.trim()
            val description = listOf(course.courseName, room, teacher)
                .filter(String::isNotBlank)
                .joinToString(" ")
            val eventUid = createEventUid(
                table = table,
                course = course,
                detail = detail,
                fallbackIndex = index,
            )

            contentLines += "BEGIN:VEVENT"
            contentLines += "UID:$eventUid"
            contentLines += "DTSTAMP:$timestamp"
            contentLines += "DTSTART:${formatUtcDateTime(firstDate, startTime)}"
            contentLines += "DTEND:${formatUtcDateTime(firstDate, endTime)}"
            if (activeWeeks.size > 1) {
                contentLines += "RRULE:FREQ=WEEKLY;COUNT=${activeWeeks.size};" +
                        "INTERVAL=$recurrenceInterval"
            }
            contentLines += "SUMMARY:${escapeText(course.courseName)}"
            contentLines += "DESCRIPTION:${escapeText(description)}"
            contentLines += "LOCATION:${escapeText(room)}"
            contentLines += if (teacher.isBlank()) {
                "CATEGORIES:$OPENWAKEUP_CATEGORY"
            } else {
                "CATEGORIES:$OPENWAKEUP_CATEGORY,${escapeText(teacher)}"
            }
            contentLines += "X-OPENWAKEUP-DAY:${detail.day}"
            contentLines += "X-OPENWAKEUP-START-NODE:${detail.startNode}"
            contentLines += "X-OPENWAKEUP-STEP:${detail.step}"
            contentLines += "X-OPENWAKEUP-WEEKS:${activeWeeks.joinToString(",")}"
            contentLines += "END:VEVENT"
        }

        contentLines += "END:VCALENDAR"
        return contentLines.flatMap(::foldContentLine).joinToString(CRLF, postfix = CRLF)
    }

    /**
     * 为课程时间段生成稳定且碰撞概率极低的 UID。
     *
     * 同一课表重复导出时保持 UID 不变，日历客户端便能识别为同一事件；名称型 UUID 同时
     * 纳入学期、课程与时间段信息，避免仅使用本地数据库自增 id 在不同设备间发生冲突。
     */
    private fun createEventUid(
        table: TableEntity,
        course: CourseEntity,
        detail: CourseDetailEntity,
        fallbackIndex: Int,
    ): String {
        val identity = listOf(
            table.tableName,
            table.startDate,
            course.courseName,
            detail.id.takeIf { id -> id > 0 } ?: fallbackIndex,
            detail.day,
            detail.startNode,
            detail.step,
            detail.startWeek,
            detail.endWeek,
            detail.type,
        ).joinToString("|")
        return "${UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8))}@openwakeup"
    }

    /**
     * 展开课程明细实际生效的周次。
     *
     * 单双周先按自然周奇偶过滤，再限制在当前课表总周数内，使标准 RRULE 的首次日期、
     * 重复间隔和次数与 OpenWakeUp 的 `X-OPENWAKEUP-WEEKS` 完全一致。
     */
    private fun activeWeeks(detail: CourseDetailEntity, maxWeek: Int): List<Int> {
        val firstWeek = detail.startWeek.coerceAtLeast(1)
        val lastWeek = detail.endWeek.coerceAtMost(maxWeek)
        if (lastWeek < firstWeek) return emptyList()
        return (firstWeek..lastWeek).filter { week ->
            DateUtils.weekMatchesType(week, detail.type)
        }
    }

    /**
     * 获取一个课程事件的实际起止时间。
     *
     * 明细启用独立时间且起止值均合法时优先使用独立时间；否则按起始节次和结束节次查询
     * 当前作息。缺少对应作息属于不可安全导出的数据错误，此时终止导出而不是生成错误日历。
     */
    private fun resolveEventTimes(
        detail: CourseDetailEntity,
        timesByNode: Map<Int, TimeDetailEntity>,
    ): Pair<LocalTime, LocalTime> {
        if (detail.ownTime) {
            val customStart = parseTime(detail.startTime)
            val customEnd = parseTime(detail.endTime)
            if (customStart != null && customEnd != null) return customStart to customEnd
        }
        val endNode = detail.startNode + detail.step - 1
        val start = timesByNode[detail.startNode]?.startTime?.let(::parseTime)
        val end = timesByNode[endNode]?.endTime?.let(::parseTime)
        requireNotNull(start) { "找不到第 ${detail.startNode} 节的开始时间" }
        requireNotNull(end) { "找不到第 $endNode 节的结束时间" }
        return start to end
    }

    /** 解析数据库中的 `HH:mm` 时间；损坏值返回空并交由调用方决定是否回退。 */
    private fun parseTime(value: String): LocalTime? =
        runCatching { LocalTime.parse(value.trim(), FLEXIBLE_TIME_FORMATTER) }.getOrNull()

    /** 将上海本地课程时间转换成 UTC，避免依赖日历客户端是否内置 `VTIMEZONE`。 */
    private fun formatUtcDateTime(date: LocalDate, time: LocalTime): String =
        UTC_DATE_TIME_FORMATTER.format(date.atTime(time).atZone(CALENDAR_ZONE).toInstant())

    /**
     * 转义 RFC 5545 TEXT 属性中的保留字符。
     *
     * 反斜杠必须最先处理；换行统一写为 `\n`，逗号和分号加反斜杠，避免教师、地点或
     * 课程名称中的标点被标准客户端误判为属性列表分隔符。
     */
    private fun escapeText(value: String): String {
        val normalized = value.replace("\r\n", "\n").replace('\r', '\n')
        return buildString(normalized.length) {
            normalized.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    ',' -> append("\\,")
                    ';' -> append("\\;")
                    else -> append(character)
                }
            }
        }
    }

    /**
     * 按 RFC 5545 的 75 字节上限折叠内容行。
     *
     * 后续物理行以一个空格开头，因此正文最多占 74 字节；按 Unicode 码点追加可避免在
     * UTF-8 多字节汉字或代理对中间截断。OpenWakeUp 导入器会把这些续行无损展开。
     */
    private fun foldContentLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var currentBytes = 0
        var contentLimit = MAX_CONTENT_LINE_BYTES
        var offset = 0
        while (offset < line.length) {
            val codePoint = line.codePointAt(offset)
            val value = String(Character.toChars(codePoint))
            val valueBytes = value.toByteArray(Charsets.UTF_8).size
            if (current.isNotEmpty() && currentBytes + valueBytes > contentLimit) {
                result += if (result.isEmpty()) current.toString() else " $current"
                current = StringBuilder()
                currentBytes = 0
                contentLimit = MAX_CONTENT_LINE_BYTES - CONTINUATION_PREFIX_BYTES
            }
            current.append(value)
            currentBytes += valueBytes
            offset += Character.charCount(codePoint)
        }
        result += if (result.isEmpty()) current.toString() else " $current"
        return result
    }

    private const val DAYS_PER_WEEK = 7
    private const val MAX_NODE_COUNT = 60
    private const val MAX_CONTENT_LINE_BYTES = 75
    private const val CONTINUATION_PREFIX_BYTES = 1
    private const val CRLF = "\r\n"
    private const val CALENDAR_ZONE_ID = "Asia/Shanghai"
    private const val OPENWAKEUP_CATEGORY = "OpenWakeUp ICS Formatter"
    private val CALENDAR_ZONE: ZoneId = ZoneId.of(CALENDAR_ZONE_ID)
    private val BASIC_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    private val FLEXIBLE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
    private val UTC_DATE_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
}
