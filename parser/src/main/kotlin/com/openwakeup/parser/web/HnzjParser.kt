package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 河南职业技术学院青果选课结果解析器。 */
object HnzjParser : Parser {
    /** 解析 `reportArea` 第 13 列 font 的各个文本节点。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val body = Jsoup.parse(input.text).selectFirst("table#reportArea tbody")
            ?: throw ParserException.parse("河南职院页面中缺少 reportArea")
        val result = mutableListOf<CoursePreview>()
        body.select("tr").forEach { row ->
            val cells = row.select("td")
            if (cells.isEmpty()) return@forEach
            require(cells.size > 12) { "河南职院课程行少于 13 列" }
            val name = cells[1].text().replace(Regex("""\[[\w\s]*]"""), "").trim()
            val teacher = cells[4].text().trim()
            val font = cells[12].selectFirst("font")
                ?: throw IllegalArgumentException("课程 $name 缺少时间 font")
            font.textNodes().forEach { node -> result += parseTime(name, teacher, node.text()) }
        }
        if (result.isEmpty()) throw ParserException.empty("河南职院课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("河南职院课表解析失败：${error.message}", error)
    }

    /** 解析 `周次周星期节次 教室` 文本。 */
    private fun parseTime(name: String, teacher: String, text: String): List<CoursePreview> {
        val parts = text.trim().split('\u2002', limit = 2)
        val date = parts[0].trim().removeSuffix("；")
        val room = parts.getOrNull(1)?.trim().orEmpty()
        val weekMarker = date.indexOf('周')
        require(weekMarker > 0 && weekMarker + 2 < date.length) { "课程 $name 的时间字段无效：$text" }
        val weekText = date.substring(1, weekMarker - 1)
        val day = TextUtils.requireDay(date.substring(weekMarker + 1, weekMarker + 2))
        val nodes = TextUtils.requirePositiveRange(date.substring(weekMarker + 2), "节次")
        return WeekUtils.parse(weekText).map { week ->
            CoursePreview(
                name = name, teacher = teacher, room = room, day = day,
                startNode = nodes.first, step = nodes.last - nodes.first + 1,
                startWeek = week.startWeek, endWeek = week.endWeek, type = week.type
            )
        }
    }
}
