package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `umooc` 使用的 `infolist_hr_common` 编码课表解析器。 */
object UmoocParser : Parser {

    /**
     * 解析 `#timetable` 中通过 `&lt;&lt;` 分隔多门课程的网格。
     *
     * @param input 完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹、坐标 class、节次或周次字段无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("timetable")
            ?: throw ParserException.parse("页面中缺少 umooc timetable")
        val rows = table.getElementsByClass("infolist_hr_common")
        require(rows.isNotEmpty()) { "umooc timetable 缺少 infolist_hr_common" }
        val courses = mutableListOf<CoursePreview>()
        rows.forEach { row ->
            var currentNodes: IntRange? = null
            row.children().forEach { cell ->
                if (cell.tagName().equals("th", ignoreCase = true)) {
                    currentNodes = parseHeaderNodes(cell.text())
                }
                if (COURSE_SEPARATOR_PATTERN.containsMatchIn(cell.html())) {
                    courses += parseCourseCell(cell, currentNodes)
                }
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("umooc timetable 中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("umooc 课表解析失败：${error.message}", error)
    }

    /** 从 `第 N 节` 表头读取当前节次；无法识别时允许课程格 class 提供备用节次。 */
    private fun parseHeaderNodes(source: String): IntRange? {
        val text = source.substringAfter('第', "").substringBefore('节', "").trim()
        if (text.isEmpty()) return null
        return TextUtils.requirePositiveRange(text, "umooc 表头节次")
    }

    /**
     * 根据数字坐标 class 与编码课程片段解析一个网格单元格。
     *
     * class 的 `星期-节次` 是原版备用坐标；表头可识别时优先使用表头节次。
     */
    private fun parseCourseCell(cell: Element, headerNodes: IntRange?): List<CoursePreview> {
        val coordinate = COORDINATE_PATTERN.find(cell.className())
            ?: throw IllegalArgumentException("umooc 课程格缺少“星期-节次”数字 class")
        val day = coordinate.groupValues[1].toInt()
        require(day in 1..7) { "umooc 课程格星期不在 1～7 范围内" }
        val fallbackNode = coordinate.groupValues[2].toInt()
        require(fallbackNode > 0) { "umooc 课程格备用节次必须为正整数" }
        val nodes = headerNodes ?: (fallbackNode..fallbackNode)

        return cell.html().split(COURSE_SEPARATOR_PATTERN)
            .filter { fragment -> fragment.isNotBlank() }
            .flatMap { fragment -> parseCourseFragment(fragment, day, nodes) }
    }

    /** 解析一段课程名称、教师、教室和周次字段。 */
    private fun parseCourseFragment(
        source: String,
        day: Int,
        nodes: IntRange
    ): List<CoursePreview> {
        val lines = source.split(BR_PATTERN)
            .map { fragment -> Jsoup.parse(fragment).text().trim() }
            .filter { line -> line.isNotEmpty() }
        require(lines.size >= 2) { "umooc 课程片段字段不足" }
        val name = lines[0].substringBefore(">>").trim()
        require(name.isNotEmpty()) { "umooc 课程名为空" }
        val room = lines[1]
        val teacher = if (lines.size < 5) lines[1] else lines[2]
        val weekLine = lines.asReversed().firstOrNull { line -> isWeekLine(line) }
            ?: throw IllegalArgumentException("课程 $name 缺少周次")
        val normalizedWeeks = weekLine
            .trim().trim('【', '】', '[', ']')
            .replace('.', ',')
        require(normalizedWeeks !in setOf("全周", "单周", "双周")) {
            "课程 $name 的 $normalizedWeeks 缺少学期结束周"
        }
        require(!normalizedWeeks.contains('除')) {
            "课程 $name 的排除周表达式无法在无样本条件下无损解析：$normalizedWeeks"
        }

        return WeekUtils.parse(normalizedWeeks).map { week ->
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

    /** 排除学时、补课、节次和 T 编号后识别真正的周次行。 */
    private fun isWeekLine(source: String): Boolean {
        if (FULL_WEEK_PATTERN.containsMatchIn(source)) return true
        return source.any { character -> character.isDigit() } &&
                !source.contains("学时") &&
                !source.contains("补课") &&
                !NODE_TEXT_PATTERN.containsMatchIn(source) &&
                !T_CODE_PATTERN.containsMatchIn(source)
    }

    private val COURSE_SEPARATOR_PATTERN = Regex("(?i)&lt;&lt;|<<")
    private val BR_PATTERN = Regex("(?i)<br\\s*/?>")
    private val COORDINATE_PATTERN = Regex("(?:^|\\s)(\\d+)-(\\d+)(?:\\s|$)")
    private val FULL_WEEK_PATTERN = Regex("[全单双]周")
    private val NODE_TEXT_PATTERN = Regex("\\d.*节")
    private val T_CODE_PATTERN = Regex("T\\d", RegexOption.IGNORE_CASE)
}
