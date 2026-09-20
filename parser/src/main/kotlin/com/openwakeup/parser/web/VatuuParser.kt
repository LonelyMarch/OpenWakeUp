package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** `vatuu` 使用的为途教务网格解析器。 */
object VatuuParser : Parser {

    /**
     * 解析 `.table_border` 中固定尾部四字段的课程格。
     *
     * 首行“星期一”的列位置决定课程列偏移；后续行号直接表示节次。`#table3` 是显示学分的
     * 另一种视图，字段布局与本 Parser 不同，按原版行为明确拒绝。
     *
     * @param input 不显示学分视图的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面视图、表头、字段或周次无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementsByClass("table_border").firstOrNull()
            ?: throw ParserException.parse("vatuu 页面中缺少 table_border")
        require(table.id() != "table3") { "请选择不显示学分的课表视图" }
        val rows = table.select("tr")
        require(rows.size > 1) { "vatuu 课表缺少课程行" }
        val header = rows[0].select("td")
        val mondayColumn = header.indexOfFirst { cell -> cell.text().contains("星期一") }
        require(mondayColumn >= 0) { "vatuu 表头缺少星期一" }

        val courses = mutableListOf<CoursePreview>()
        rows.drop(1).forEachIndexed { dataRowIndex, row ->
            val node = dataRowIndex + 1
            row.select("td").forEachIndexed cellLoop@{ columnIndex, cell ->
                if (columnIndex < mondayColumn || cell.text().isBlank()) return@cellLoop
                val day = columnIndex - mondayColumn + 1
                require(day in 1..7) { "vatuu 存在超过星期日的课程列" }
                courses += parseCell(cell.html(), day, node)
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("vatuu 页面中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("vatuu 课表解析失败：${error.message}", error)
    }

    /**
     * 从尾部四项读取 `课程名（教师）/周次/教室/附加字段`。
     *
     * 页面同时存在中文和英文圆括号，必须按当前名称字段实际使用的括号配对，不能跨字段沿用。
     */
    private fun parseCell(source: String, day: Int, node: Int): List<CoursePreview> {
        val fields = source.split(FIELD_SEPARATOR_PATTERN)
            .map { fragment -> Jsoup.parse(fragment).text().trim() }
        require(fields.size >= 4) { "vatuu 课程格字段不足" }
        val nameAndTeacher = fields[fields.lastIndex - 3]
        val (leftBracket, rightBracket) = when {
            nameAndTeacher.contains('（') && nameAndTeacher.contains('）') -> '（' to '）'
            nameAndTeacher.contains('(') && nameAndTeacher.contains(')') -> '(' to ')'
            else -> throw IllegalArgumentException("vatuu 课程名称字段缺少教师括号：$nameAndTeacher")
        }
        val name = nameAndTeacher.substringBefore(leftBracket).trim()
        val teacher =
            nameAndTeacher.substringAfter(leftBracket).substringBefore(rightBracket).trim()
        val weekText = fields[fields.lastIndex - 2]
        val room = fields[fields.lastIndex - 1]
        require(name.isNotEmpty()) { "vatuu 课程名为空" }
        require(weekText.isNotEmpty()) { "课程 $name 缺少周次" }

        return WeekUtils.parse(weekText).map { week ->
            CoursePreview(
                name = name,
                teacher = teacher,
                room = room,
                day = day,
                startNode = node,
                startWeek = week.startWeek,
                endWeek = week.endWeek,
                type = week.type,
            )
        }
    }

    private val FIELD_SEPARATOR_PATTERN = Regex("(?i)&nbsp;|<br\\s*/?>")
}
