package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** 上海应用技术大学表单网格课表解析器。 */
object SitParser : Parser {
    /** 解析课表主表，并使用上一行 rowspan 为缩短行补齐星期列。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val form = Jsoup.parse(input.text.substringAfterLast("<body>")).selectFirst("form")
            ?: throw ParserException.parse("上海应用技术大学页面中缺少课表表单")
        val table = form.selectFirst("table") ?: throw ParserException.parse("课表表单中缺少主表")
        val rows = table.select("tr")
        if (rows.size <= 2) throw ParserException.parse("课表主表没有节次行")
        val courses = mutableListOf<CoursePreview>()
        for (rowIndex in 2 until rows.size) {
            val cells = rows[rowIndex].select("td")
            if (cells.isEmpty()) continue
            val normalized = if (rowIndex == 2 || cells.size == 8) cells.toList() else {
                completeCells(rows[rowIndex - 1].select("td").toList(), cells.toList())
            }
            courses += parseRow(normalized)
        }
        if (courses.isEmpty()) throw ParserException.empty("上海应用技术大学课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("上海应用技术大学课表解析失败：${error.message}", error)
    }

    /** 根据上一行仍占位的单元格，为当前行插入空星期格。 */
    private fun completeCells(previous: List<Element>, current: List<Element>): List<Element> {
        val iterator = current.iterator()
        return previous.map { cell ->
            val rowSpan = cell.attr("rowspan").toIntOrNull() ?: 1
            if (rowSpan == 1 && iterator.hasNext()) iterator.next() else Element("td")
        }
    }

    /** 将一节次行转换为一周课程列表。 */
    private fun parseRow(cells: List<Element>): List<CoursePreview> {
        require(cells.size >= 2) { "课表节次行字段不足" }
        val startNode = cells[0].text().trim().toIntOrNull()
            ?: throw IllegalArgumentException("节次标题不是整数：${cells[0].text()}")
        return cells.drop(1).flatMapIndexed { index, cell ->
            val span = cell.attr("rowspan").toIntOrNull() ?: return@flatMapIndexed emptyList()
            require(span > 0) { "rowspan 必须为正整数" }
            cell.select("[name=d1]").map { block -> parseCourse(block, index + 1, startNode, span) }
        }
    }

    /** 解析一个课程 div 的名称、周次、教室和教师字段。 */
    private fun parseCourse(block: Element, day: Int, startNode: Int, step: Int): CoursePreview {
        val fields = block.text().split(Regex("\\s+")).filter { it.isNotEmpty() }
        require(fields.size >= 5) { "课程字段不足：${block.text()}" }
        val weeks = TextUtils.requirePositiveRange(
            fields[1].replace("第", "").replace("周", "").replace("*", ""), "周次"
        )
        return CoursePreview(
            name = fields[0], teacher = fields[4], room = fields[2], day = day,
            startNode = startNode, step = step, startWeek = weeks.first, endWeek = weeks.last,
            type = when {
                fields[1].contains("**") -> 2; fields[1].contains('*') -> 1; else -> 0
            },
        )
    }
}
