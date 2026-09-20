package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 湖南信息职业技术学院顶部对齐网格解析器。 */
object HniuParser : Parser {
    /** 拆分每个 `valign=top` 单元格内以周次节次行为边界的课程片段。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val body = Jsoup.parse(input.text).selectFirst("[bordercolordark=#FFFFFF] tbody")
            ?: throw ParserException.parse("湖南信息职院页面中缺少目标课表")
        val result = mutableListOf<CoursePreview>()
        body.select("tr").forEach { row ->
            var day = 0
            row.select("td").forEach { cell ->
                if (cell.attr("align") == "center" || cell.attr("valign") != "top") return@forEach
                day++
                val lines =
                    cell.html().split(Regex("(?i)<br\\s*/?>")).map { Jsoup.parse(it).text().trim() }
                        .filter { it.isNotEmpty() }
                val anchors =
                    lines.indices.filter { lines[it].contains("周][") && lines[it].contains('节') }
                anchors.forEachIndexed { anchorIndex, index ->
                    val start = if (anchorIndex == 0) 0 else anchors[anchorIndex - 1] + 1
                    require(start < lines.size && index < lines.size) { "湖南信息职院课程片段边界无效" }
                    result += parseBlock(day, lines.subList(start, minOf(index + 2, lines.size)))
                }
            }
        }
        if (result.isEmpty()) throw ParserException.empty("湖南信息职院课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("湖南信息职院课表解析失败：${error.message}", error)
    }

    /** 将一个课程片段转换为一个或多个周段。 */
    private fun parseBlock(day: Int, lines: List<String>): List<CoursePreview> {
        require(lines.size >= 2) { "湖南信息职院课程字段不足" }
        val name = lines[0].substringBefore(' ').trim()
        val teacherLine = lines[1]
        val time = teacherLine.substringAfter('[', "").substringBeforeLast("节", "")
        val components = time.split("周][")
        require(components.size == 2) { "课程 $name 的周次节次格式无效" }
        val nodes = TextUtils.requirePositiveRange(components[1], "节次")
        val room = lines.getOrNull(2)?.trim().orEmpty()
            .ifEmpty { teacherLine.substringAfterLast(' ').trim() }
        return WeekUtils.parse(components[0]).map { week ->
            CoursePreview(
                name = name,
                teacher = teacherLine.substringBefore(' ').trim(),
                room = room,
                day = day,
                startNode = nodes.first,
                step = nodes.last - nodes.first + 1,
                startWeek = week.startWeek,
                endWeek = week.endWeek,
                type = week.type
            )
        }
    }
}
