package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.JsonUtils
import com.openwakeup.parser.utils.WeekUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 清华大学旧版选课页面脚本解析器。 */
object ThuParser : Parser {

    /**
     * 解析原始 HTML，或带学期周数和调课信息的本地 JSON 封套。
     *
     * JSON 封套格式为 `{"html":"...","weekCount":16,"reschedule":[[原周,原星期,目标周,目标星期]]}`。
     * 调用方未提供 `weekCount` 时，解析器会先扫描整张课表，从明确周次范围的最大结束周推导
     * 学期边界，再展开“全周、后八周、单双周”等概括表达。若页面没有任何明确边界，则拒绝
     * 猜测默认周数。
     *
     * @param input 原始页面 HTML 或上述 JSON 封套
     * @return 已应用封套调课规则的课程预览
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val source = parseSource(input.text)
        val secondaryDetails = parseSecondaryDetails(source.html)
        val script = MAIN_SCRIPT_PATTERN.find(source.html)?.value
            ?: throw ParserException.parse("清华课表页面中缺少 setInitValue 课程脚本")
        val courses = parseScript(script, source, secondaryDetails)
        if (courses.isEmpty()) throw ParserException.empty("清华课表页面中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("清华课表解析失败：${error.message}", error)
    }

    /** 根据首字符区分原始 HTML 与结构化输入封套。 */
    private fun parseSource(text: String): SourceData {
        if (!text.trimStart().startsWith('{')) return SourceData(html = text)
        val root = Json.parseToJsonElement(text).jsonObject
        val html = JsonUtils.requiredString(root, "html")
        val weekCount = root["weekCount"]?.jsonPrimitive?.intOrNull
        require(weekCount == null || weekCount > 0) { "weekCount 必须为正整数" }
        val reschedules =
            (root["reschedule"] as? JsonArray).orEmpty().mapIndexed { index, element ->
                val values = element.jsonArray.map { value -> value.jsonPrimitive.content.toInt() }
                require(values.size == 2 || values.size == 4) { "第 ${index + 1} 条调课规则必须包含 2 或 4 个整数" }
                val rule = if (values.size == 2) {
                    Reschedule(values[0], values[1])
                } else {
                    Reschedule(values[0], values[1], values[2], values[3])
                }
                require(rule.fromWeek > 0 && rule.fromDay in 1..7) { "第 ${index + 1} 条调课来源无效" }
                require(rule.toWeek >= 0 && (rule.toWeek == 0 || rule.toDay in 1..7)) { "第 ${index + 1} 条调课目标无效" }
                rule
            }
        return SourceData(html, weekCount, reschedules)
    }

    /**
     * 顺序读取脚本状态，并在取得全部课程后统一解析周次。
     *
     * 清华页面允许一门课写“1-16周”，另一门课只写“全周”。若在遇到“全周”时立即解析，
     * 后续课程中的明确结束周还不可见。因此这里先冻结所有课程，再从整张课表推导总周数。
     */
    private fun parseScript(
        script: String,
        source: SourceData,
        secondaryDetails: Map<String, String>,
    ): List<CoursePreview> {
        val pendingCourses = mutableListOf<PendingCourse>()
        var current = CourseDetails()
        script.lineSequence().forEach { line ->
            when {
                line.contains("strHTML += \"") && line.contains("<b>") -> {
                    current.name = line.substringAfter("<b>").substringBefore("</b>").trim()
                }

                line.contains("strHTML1 +=") -> {
                    current.params += line.substringAfter('；').substringBefore('"').trim()
                }

                line.contains("blue_red_none") -> parseSecondaryCourseLine(
                    line,
                    current,
                    secondaryDetails
                )

                line.contains("getElementById") -> {
                    prepareCourseDetails(current)
                    pendingCourses += PendingCourse(positionLine = line, details = current)
                    current = CourseDetails()
                }
            }
        }
        val resolvedSource = source.copy(
            weekCount = source.weekCount ?: inferWeekCount(pendingCourses),
        )
        return pendingCourses.flatMap { pending ->
            finalizeCourse(pending.positionLine, pending.details, resolvedSource)
        }
    }

    /** 解析蓝色二级课程的名称、地点、周次和教师补充信息。 */
    private fun parseSecondaryCourseLine(
        line: String,
        current: CourseDetails,
        secondaryDetails: Map<String, String>,
    ) {
        val values =
            BLUE_TEXT_PATTERN.findAll(line).map { match -> match.groupValues[1].trim() }.toList()
        require(values.size >= 2) { "清华二级课程字段不足" }
        current.name = values[0]
        var details = values[1]
        val topic = details.substringBeforeLast('(', "").trim()
        details = details.substringAfterLast('(', details).substringBefore(')').trim()
        details.split('；').map { value -> value.trim() }.forEach { value ->
            when {
                value.contains('周') -> current.weeks = value
                value.startsWith("时间：") -> Unit // 当前 CoursePreview 不表达独立起止时刻。
                current.room.isBlank() -> current.room = value
            }
        }
        if (topic.isNotBlank()) current.room = "${current.room}($topic)"
        current.teacher = secondaryDetails[current.name].orEmpty()
    }

    /**
     * 补齐一门课程在脚本中分散保存的教师、教室和周次字段。
     *
     * 此步骤不展开周次，只把可参与全表边界推导的原始周次文本稳定下来。
     */
    private fun prepareCourseDetails(current: CourseDetails) {
        if (current.params.isNotEmpty()) {
            current.params.asReversed().forEach { value ->
                when {
                    value.endsWith('周') && current.weeks.isBlank() -> current.weeks = value
                    current.weeks.isBlank() -> current.room = value
                    value !in COURSE_PROPERTIES && current.teacher.isBlank() -> current.teacher =
                        value
                }
            }
        }
        require(current.name.isNotBlank()) { "清华课程名为空" }
        require(current.weeks.isNotBlank()) { "课程 ${current.name} 缺少周次" }
    }

    /**
     * 从整张课表的明确排课范围推导学期总周数。
     *
     * “前八周”只能证明该课程到第 8 周，不能证明整个学期在第 8 周结束；它和“全周、后八周、
     * 单周、双周”都不参与总周数推导。若这些概括表达存在但整张课表没有任何带数字终点的
     * 明确范围，返回 `null`，后续解析会明确失败，不恢复原版固定 16 周的行为。
     */
    private fun inferWeekCount(pendingCourses: List<PendingCourse>): Int? = pendingCourses
        .mapNotNull { pending -> explicitEndWeek(pending.details.weeks) }
        .maxOrNull()

    /** 读取单个周次表达中能够被文本直接证明的最大结束周。 */
    private fun explicitEndWeek(text: String): Int? {
        val normalized = text.trim()
        return when (normalized) {
            "全周", "前八周", "后八周", "单周", "双周" -> null
            else -> WeekUtils.parse(normalized).maxOfOrNull { segment -> segment.endWeek }
        }
    }

    /** 将已补齐字段的课程转换为预览，并应用可选调课规则。 */
    private fun finalizeCourse(
        line: String,
        current: CourseDetails,
        source: SourceData,
    ): List<CoursePreview> {
        val position = CELL_POSITION_PATTERN.find(line)
            ?: throw IllegalArgumentException("清华课程缺少网格坐标：$line")
        val block = position.groupValues[1].toInt()
        val day = position.groupValues[2].toInt()
        require(block in 1 until START_NODE_MAP.size && day in 1..7) { "清华课程的时段或星期越界" }
        val exactWeeks = parseWeeks(current.weeks, source.weekCount)
        val weeksByDay = linkedMapOf(day to exactWeeks.toMutableSet())

        source.reschedules.forEach { rule ->
            // 调入日原有的同课程安排会被上游规则清除，避免和移动后的记录重复。
            if (rule.toWeek > 0 && rule.toDay == day) weeksByDay[day]?.remove(rule.toWeek)
            if (rule.fromDay == day && rule.fromWeek in exactWeeks) {
                weeksByDay[day]?.remove(rule.fromWeek)
                if (rule.toWeek > 0) {
                    weeksByDay.getOrPut(rule.toDay) { sortedSetOf() }.add(rule.toWeek)
                }
            }
        }

        return weeksByDay.flatMap { (courseDay, weeks) ->
            if (weeks.isEmpty()) emptyList() else WeekUtils.compact(weeks).map { week ->
                CoursePreview(
                    name = current.name,
                    teacher = current.teacher,
                    room = current.room,
                    day = courseDay,
                    startNode = START_NODE_MAP[block],
                    step = END_NODE_MAP[block] - START_NODE_MAP[block] + 1,
                    startWeek = week.startWeek,
                    endWeek = week.endWeek,
                    type = week.type,
                )
            }
        }
    }

    /** 解析显式周次，或在封套提供总周数时展开学校的概括写法。 */
    private fun parseWeeks(text: String, weekCount: Int?): Set<Int> {
        val normalized = text.trim()
        val weeks = when (normalized) {
            "全周" -> 1..requireWeekCount(normalized, weekCount)
            "前八周" -> 1..8
            "后八周" -> {
                val total = requireWeekCount(normalized, weekCount)
                require(total >= 8) { "学期总周数不足 8 周" }
                (total - 7)..total
            }

            "单周" -> (1..requireWeekCount(normalized, weekCount)).filter { week -> week % 2 == 1 }
            "双周" -> (1..requireWeekCount(normalized, weekCount)).filter { week -> week % 2 == 0 }
            else -> WeekUtils.parse(normalized).flatMap { segment ->
                when (segment.type) {
                    0 -> (segment.startWeek..segment.endWeek).toList()
                    1, 2 -> (segment.startWeek..segment.endWeek).filter { week -> week % 2 == segment.type % 2 }
                    else -> emptyList()
                }
            }
        }.toSortedSet()
        require(weeks.isNotEmpty()) { "周次字段中没有上课周：$text" }
        return weeks
    }

    /** 对依赖学期长度的概括周次要求输入封套或同表明确排课范围提供 `weekCount`。 */
    private fun requireWeekCount(label: String, weekCount: Int?): Int = weekCount
        ?: throw IllegalArgumentException("周次“$label”无法从同一张课表的明确排课范围推导总周数")

    /** 从选课明细脚本中提取二级课程教师。 */
    private fun parseSecondaryDetails(html: String): Map<String, String> {
        val header = SECONDARY_HEADER_PATTERN.find(html)?.groupValues?.get(1) ?: return emptyMap()
        val data = SECONDARY_DATA_PATTERN.find(html)?.groupValues?.get(1) ?: return emptyMap()
        val headers = header.split(',')
        val nameIndex = headers.indexOfFirst { value -> value.contains("课程名") }
        val teacherIndex = headers.indexOfFirst { value -> value.contains("任课教师") }
        if (nameIndex < 0 || teacherIndex < 0) return emptyMap()
        return ARRAY_PATTERN.findAll(data).mapNotNull { match ->
            val row = match.groupValues[1]
            if (row.contains("北大") || row.contains("北外")) return@mapNotNull null
            val fields = row.split(',').map { value -> value.trim().removeSurrounding("\"") }
            if (nameIndex !in fields.indices || teacherIndex !in fields.indices) null
            else fields[nameIndex] to fields[teacherIndex]
        }.toMap()
    }

    private data class SourceData(
        val html: String,
        val weekCount: Int? = null,
        val reschedules: List<Reschedule> = emptyList(),
    )

    private data class Reschedule(
        val fromWeek: Int,
        val fromDay: Int,
        val toWeek: Int = 0,
        val toDay: Int = 0,
    )

    private data class CourseDetails(
        var name: String = "",
        var teacher: String = "",
        var weeks: String = "",
        var room: String = "",
        val params: MutableList<String> = mutableListOf(),
    )

    /** 第一遍脚本扫描后冻结的网格位置与课程字段。 */
    private data class PendingCourse(
        val positionLine: String,
        val details: CourseDetails,
    )

    private val START_NODE_MAP = intArrayOf(0, 1, 3, 6, 8, 10, 12)
    private val END_NODE_MAP = intArrayOf(0, 2, 5, 7, 9, 11, 14)
    private val MAIN_SCRIPT_PATTERN = Regex("""setInitValue\(\)[\s\S]+setInitValue""")
    private val CELL_POSITION_PATTERN = Regex("""a(\d)_(\d)""")
    private val BLUE_TEXT_PATTERN = Regex("""<font color=['"]blue['"]>([^<>]+?)</font>""")
    private val SECONDARY_HEADER_PATTERN =
        Regex("""var gridColumns = \[(.+?)];""", RegexOption.DOT_MATCHES_ALL)
    private val SECONDARY_DATA_PATTERN =
        Regex("""var gridData = \[(.+?)];""", RegexOption.DOT_MATCHES_ALL)
    private val ARRAY_PATTERN = Regex("""\[([^\[\]]+)]""")
    private val COURSE_PROPERTIES = setOf(
        "任选",
        "限选",
        "实践选修",
        "必修课",
        "选修课",
        "必修",
        "选修",
        "专基",
        "专选",
        "公必",
        "公选",
        "义修",
        "选",
        "必",
        "主干",
        "专限",
        "公基",
        "值班",
        "通选",
        "思政必",
        "思政选",
        "自基必",
        "自基选",
        "语技必",
        "语技选",
        "体育必",
        "体育选",
        "专业基础课",
        "双创必",
        "双创选",
        "新生必",
        "新生选",
        "学科必修",
        "学科选修",
        "通识必修",
        "通识选修",
        "公共基础",
        "第二课堂",
        "学科实践",
        "专业实践",
        "专业必修",
        "辅修",
        "专业选修",
        "外语",
        "方向",
        "专业必修课",
        "全选",
    )
}
