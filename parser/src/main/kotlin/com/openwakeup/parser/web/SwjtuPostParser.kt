package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import org.jsoup.Jsoup

/** 西南交通大学研究生 `mtt_table1` 课表解析器。 */
object SwjtuPostParser : Parser {
    /** 解析含“讲授”的 arrage 课程格，并移除 println 与作息构造。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).selectFirst("table.mtt_table1")
            ?: throw ParserException.parse("西南交大研究生页面中缺少 mtt_table1")
        val result = table.select("td[xq][jc]").mapNotNull { cell ->
            if (!cell.html().contains("讲授")) return@mapNotNull null
            val fields = cell.selectFirst(".arrage")?.select("div")
                ?: throw IllegalArgumentException("西南交大研究生课程缺少 arrage")
            require(fields.size >= 5) { "西南交大研究生课程字段不足" }
            val name = fields[2].text().substringAfter('(', "").substringBefore(')', "").trim()
            val weeks = TextUtils.requirePositiveRange(
                fields[1].text().replace("第", "").replace("周", ""),
                "周次"
            )
            val day = cell.attr("xq").toIntOrNull()?.takeIf { it in 1..7 } ?: error("星期属性无效")
            val node = cell.attr("jc").toIntOrNull()?.takeIf { it > 0 } ?: error("节次属性无效")
            require(name.isNotEmpty()) { "西南交大研究生课程缺少名称" }
            CoursePreview(
                name, teacher = fields[3].text(), room = fields[4].text(), day = day,
                startNode = node, startWeek = weeks.first, endWeek = weeks.last
            )
        }
        if (result.isEmpty()) throw ParserException.empty("西南交大研究生课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("西南交大研究生课表解析失败：${error.message}", error)
    }
}
