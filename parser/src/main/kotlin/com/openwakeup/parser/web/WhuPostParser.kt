package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `whu_post` 使用的研究生 rowspan 网格解析器。 */
object WhuPostParser : Parser {

    /**
     * 解析第一张 `.table_con` 表格，并根据 rowspan 恢复课程所在的逻辑列。
     *
     * 原页面前两列不是星期，逻辑列 2～8 才依次对应周一至周日。课程内容按 `<br>` 分条，
     * 每条记录的核心时间字段格式为 `起始周-结束周周起始节-结束节节`。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 目标表格、rowspan 坐标或时间字段无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementsByClass("table_con").firstOrNull()
            ?: throw ParserException.parse("武大研究生页面中缺少 table_con 课表")
        val rows = table.select("tr")
        if (rows.isEmpty()) throw ParserException.parse("武大研究生课表中缺少表格行")
        val occupiedUntilRow = IntArray(LOGICAL_COLUMN_COUNT) { -1 }
        val courses = mutableListOf<CoursePreview>()

        rows.forEachIndexed { rowIndex, row ->
            var columnCursor = 0
            row.select("td").forEach { cell ->
                while (columnCursor < LOGICAL_COLUMN_COUNT && occupiedUntilRow[columnCursor] >= rowIndex) {
                    columnCursor += 1
                }
                require(columnCursor < LOGICAL_COLUMN_COUNT) {
                    "武大研究生课表第 ${rowIndex + 1} 行超过 $LOGICAL_COLUMN_COUNT 个逻辑列"
                }
                val rowSpan = cell.attr("rowspan").toIntOrNull() ?: 1
                require(rowSpan > 0) { "武大研究生课表 rowspan 必须为正整数" }
                occupiedUntilRow[columnCursor] = rowIndex + rowSpan - 1

                val day = columnCursor - NON_DAY_COLUMN_COUNT + 1
                if (day in 1..7 && cell.text().isNotBlank()) {
                    courses += parseCell(cell, day)
                }
                columnCursor += 1
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("武大研究生课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("武大研究生课表解析失败：${error.message}", error)
    }

    /** 把课程格中的每个 `<br>` 片段解析为一条独立课程。 */
    private fun parseCell(cell: Element, day: Int): List<CoursePreview> = cell.html()
        .split(BR_PATTERN)
        .map { fragment -> Jsoup.parse(fragment).text().trim() }
        .filter { line -> line.length >= MIN_COURSE_TEXT_LENGTH }
        .map { line -> parseCourseLine(line, day) }

    /**
     * 解析一条以空白分隔的课程记录。
     *
     * 课程名可能被拆成多个 token，因此以首个包含“周”的时间 token 为边界；名称末尾附带的
     * 序号沿用原版规则删除。时间 token 后一个字段表示教师，两个字段表示教室和教师。
     */
    private fun parseCourseLine(source: String, day: Int): CoursePreview {
        val tokens = source.split(WHITESPACE_PATTERN).filter { token -> token.isNotBlank() }
        val scheduleIndex = tokens.indexOfFirst { token -> token.contains('周') }
        require(scheduleIndex > 0) { "武大研究生课程记录缺少名称或周次：$source" }
        val name =
            tokens.take(scheduleIndex).joinToString("").replace(TRAILING_NUMBER_PATTERN, "").trim()
        require(name.isNotEmpty()) { "武大研究生课程名称为空：$source" }
        val schedule = SCHEDULE_PATTERN.matchEntire(tokens[scheduleIndex])
            ?: throw IllegalArgumentException("课程 $name 的周次节次格式无效：${tokens[scheduleIndex]}")
        val startWeek = schedule.groupValues[1].toInt()
        val endWeek = schedule.groupValues[2].toInt()
        val startNode = schedule.groupValues[3].toInt()
        val endNode = schedule.groupValues[4].toInt()
        require(startWeek > 0 && endWeek >= startWeek) { "课程 $name 的周次范围无效" }
        require(startNode > 0 && endNode >= startNode) { "课程 $name 的节次范围无效" }

        val trailing = tokens.drop(scheduleIndex + 1)
        val room = if (trailing.size == 2) trailing[0].replace('，', ',') else ""
        val teacher = if (trailing.size in 1..2) trailing.last() else ""
        return CoursePreview(
            name = name,
            teacher = teacher,
            room = room,
            day = day,
            startNode = startNode,
            step = endNode - startNode + 1,
            startWeek = startWeek,
            endWeek = endWeek,
        )
    }

    private const val LOGICAL_COLUMN_COUNT = 9
    private const val NON_DAY_COLUMN_COUNT = 2
    private const val MIN_COURSE_TEXT_LENGTH = 3
    private val BR_PATTERN = Regex("(?i)<br\\s*/?>")
    private val WHITESPACE_PATTERN = Regex("\\s+")
    private val TRAILING_NUMBER_PATTERN = Regex("\\d+$")
    private val SCHEDULE_PATTERN = Regex("^(\\d+)-(\\d+)周(\\d+)-(\\d+)节$")
}
