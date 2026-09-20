package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import org.jsoup.Jsoup

/** 河南经贸职业学院微信端课表解析器。 */
object HnjmParser : Parser {
    /** 按 Sub-kcbt 星期标题关联其后每天的 table。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val document = Jsoup.parse(input.text)
        val dayHeaders = document.select(".Sub-kcbt")
        val tables = document.select("table")
        if (dayHeaders.isEmpty()) throw ParserException.parse("河南经贸页面中缺少星期标题")
        val result = mutableListOf<CoursePreview>()
        var tableIndex = 0
        dayHeaders.forEach { header ->
            if (header.nextElementSibling()?.tagName() == "br") return@forEach
            require(tableIndex < tables.size) { "河南经贸星期标题与日课表数量不一致" }
            val day = TextUtils.requireDay(header.text())
            tables[tableIndex++].select("tr").forEach { row ->
                val cells = row.select("td")
                require(cells.size >= 3) { "河南经贸课程行字段不足" }
                val nodes = TextUtils.requirePositiveRange(cells[0].text(), "节次")
                val detail = cells[1].text()
                val weekMatch = WEEK_PATTERN.find(detail) ?: error("河南经贸课程缺少周次：$detail")
                val weeks = TextUtils.requirePositiveRange(weekMatch.value, "周次")
                val name = detail.substring(0, weekMatch.range.first).trim()
                val room = ROOM_PATTERN.find(detail)?.groupValues?.get(1).orEmpty()
                require(name.isNotEmpty()) { "河南经贸课程缺少名称" }
                result += CoursePreview(
                    name, teacher = cells[2].text(), room = room, day = day,
                    startNode = nodes.first, step = nodes.last - nodes.first + 1,
                    startWeek = weeks.first, endWeek = weeks.last
                )
            }
        }
        if (result.isEmpty()) throw ParserException.empty("河南经贸课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("河南经贸课表解析失败：${error.message}", error)
    }

    private val WEEK_PATTERN = Regex("""\d{1,2}-\d{1,2}周""")
    private val ROOM_PATTERN = Regex("""[（(]([^）)]+)[）)]""")
}
