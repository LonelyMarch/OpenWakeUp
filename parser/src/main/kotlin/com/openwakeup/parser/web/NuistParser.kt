package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** `nuist` 使用的 `#TABLE1` 菱形分隔网格解析器。 */
object NuistParser : Parser {

    /**
     * 解析表头星期列、行节次和由 `◆/◇` 编码的课程字段。
     *
     * 表头中“星期”之前的单元格数量决定课程列偏移。数据行的节次标签若包含 `~`，则该页面
     * 每一行代表连续两节；否则每行代表一节。课程自身的 `{节次}` 可覆盖行节次。
     *
     * @param input 完整 `TABLE1` HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹、星期列、课程字段、节次或周次无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("TABLE1")
            ?: throw ParserException.parse("nuist 页面中缺少 TABLE1")
        val rows = table.select("tr")
        require(rows.size > 1) { "nuist TABLE1 缺少课程行" }
        // 上方已经确认至少存在表头和一行课程，下标访问可避免 jsoup 的可空 first() 返回值。
        val headerCells = rows[0].select(":scope > td")
        val courseOffset =
            headerCells.indexOfFirst { cell -> cell.text().trim().startsWith("星期") }
        require(courseOffset >= 0) { "nuist TABLE1 表头缺少星期列" }

        val courses = mutableListOf<CoursePreview>()
        var doubleNodeRows = false
        rows.drop(1).forEachIndexed { dataRowIndex, row ->
            val rowNumber = dataRowIndex + 1
            val cells = row.select(":scope > td")
            if (cells.take(courseOffset).any { cell -> cell.text().contains('~') }) {
                doubleNodeRows = true
            }
            val defaultNodes = if (doubleNodeRows) {
                val start = (rowNumber - 1) * 2 + 1
                start..(start + 1)
            } else {
                rowNumber..rowNumber
            }
            cells.drop(courseOffset).forEachIndexed { dayIndex, cell ->
                val day = dayIndex + 1
                require(day in 1..7) { "nuist TABLE1 存在超过星期日的课程列" }
                if (cell.text().isNotBlank()) {
                    courses += parseCell(cell.html(), day, defaultNodes)
                }
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("nuist TABLE1 中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("nuist 课表解析失败：${error.message}", error)
    }

    /** 将一个单元格中由 `◆` 或换行分隔的多门课程逐项解析。 */
    private fun parseCell(source: String, day: Int, defaultNodes: IntRange): List<CoursePreview> =
        source.split(COURSE_SEPARATOR_PATTERN)
            .map { fragment -> Jsoup.parse(fragment).text().trim() }
            .filter { fragment -> fragment.isNotEmpty() }
            .flatMap { fragment -> parseFragment(fragment, day, defaultNodes) }

    /**
     * 解析 `课程名◇教师(周次)◇...◇教室◇周次{节次}` 字段。
     *
     * 某些页面把周次放在第 2 项教师括号中，另一些放在第 4 项括号中；只有内容首尾均为数字
     * 时才接受为公共周次，否则必须由最后一项的每个排课片段分别提供。
     */
    private fun parseFragment(
        source: String,
        day: Int,
        defaultNodes: IntRange
    ): List<CoursePreview> {
        val fields = source.split('◇').map { field -> field.trim() }
        require(fields.size >= 2) { "nuist 课程字段不足：$source" }
        val name = fields.first()
        require(name.isNotEmpty()) { "nuist 课程名为空" }
        val teacherField = fields.getOrElse(1) { "" }
        val teacher = teacherField.substringBefore('(').trim()
        val primaryWeeks = parentheticalContent(teacherField)
        val alternateWeeks = parentheticalContent(fields.getOrElse(3) { "" })
        val commonWeeks = listOf(primaryWeeks, alternateWeeks)
            .firstOrNull { value -> EXPLICIT_WEEK_RANGE_PATTERN.matches(value) }
            .orEmpty()
        val primaryRoom = fields.getOrElse(4) { "" }.substringAfter('}').trim()
        val fallbackRoom = fields.getOrElse(2) { "" }.substringAfter('}').trim()
        val room = primaryRoom.ifEmpty { fallbackRoom }
        val schedules = fields.last().split(',').map { value -> value.trim() }
            .filter { value -> value.isNotEmpty() }
        require(schedules.isNotEmpty()) { "课程 $name 缺少排课字段" }

        return schedules.flatMap { schedule ->
            val nodes = parseScheduleNodes(schedule, name) ?: defaultNodes
            val scheduleWeeks = schedule.substringBefore('{').trim()
            val weekText = commonWeeks.ifEmpty { scheduleWeeks }
            require(weekText.isNotEmpty()) { "课程 $name 缺少周次" }
            val parity = when {
                schedule.contains('单') -> "单周"
                schedule.contains('双') -> "双周"
                else -> ""
            }
            WeekUtils.parse(weekText + parity).map { week ->
                CoursePreview(
                    name = name,
                    teacher = teacher,
                    room = room,
                    day = day,
                    startNode = nodes.first,
                    step = nodes.last - nodes.first + 1,
                    startWeek = week.startWeek,
                    endWeek = week.endWeek,
                    type = week.type,
                )
            }
        }
    }

    /** 从圆括号中读取候选周次；不存在完整括号时返回空字符串。 */
    private fun parentheticalContent(source: String): String {
        val start = source.indexOf('(')
        val end = source.indexOf(')', start + 1)
        return if (start >= 0 && end > start) source.substring(start + 1, end).trim() else ""
    }

    /** 从 `{12节}`、`{1-2节}` 一类课程级覆盖字段读取节次。 */
    private fun parseScheduleNodes(source: String, courseName: String): IntRange? {
        val content = source.substringAfter('{', "").substringBefore('}', "")
            .removeSuffix("节").trim()
        if (content.isEmpty()) return null
        if (NODE_SEPARATOR_PATTERN.containsMatchIn(content)) {
            return TextUtils.requirePositiveRange(content, "课程 $courseName 的节次")
        }
        require(content.all { character -> character.isDigit() } && content.length <= 4) {
            "课程 $courseName 的紧凑节次无效：$content"
        }
        val (start, end) = when (content.length) {
            1 -> content.toInt() to content.toInt()
            2 -> content.take(1).toInt() to content.takeLast(1).toInt()
            3 -> content.take(1).toInt() to content.takeLast(2).toInt()
            else -> content.take(2).toInt() to content.takeLast(2).toInt()
        }
        require(start > 0 && end >= start) { "课程 $courseName 的节次范围无效：$content" }
        return start..end
    }

    private val COURSE_SEPARATOR_PATTERN = Regex("(?i)◆|(?:<br\\s*/?>)+")
    private val EXPLICIT_WEEK_RANGE_PATTERN = Regex("^\\d.*\\d$")
    private val NODE_SEPARATOR_PATTERN = Regex("[-~～至—–]")
}
