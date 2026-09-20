package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import org.jsoup.Jsoup

/** 西安交通大学研究生 `GridViewStyle` 课表解析器。 */
object XjtuPostParser : Parser {
    /** 拆分同一单元格内以双换行分隔的多门课程，并删除 File 调试入口。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).selectFirst("table.GridViewStyle")
            ?: throw ParserException.parse("西安交大研究生页面中缺少 GridViewStyle")
        val result = mutableListOf<CoursePreview>()
        table.select("td[id]").forEach { cell ->
            val html = cell.html().trim()
            if (!html.contains("课程")) return@forEach
            val day = Regex("""\d""").find(cell.id().drop(3))?.value?.toInt()?.takeIf { it in 1..7 }
                ?: throw IllegalArgumentException("西安交大研究生单元格 id 无法定位星期")
            html.split(Regex("(?i)<br\\s*/?>\\s*<br\\s*/?>")).forEach { courseHtml ->
                val fields =
                    courseHtml.split(Regex("(?i)<br\\s*/?>")).map { Jsoup.parse(it).text().trim() }
                require(fields.size >= 6) { "西安交大研究生课程字段不足" }
                val name =
                    fields[0].substringAfter('：').trim() + fields[1].substringAfter('：').trim()
                val nodes = TextUtils.requirePositiveRange(fields[4].substringAfter('：'), "节次")
                val weeks = TextUtils.requirePositiveRange(
                    fields[5].substringAfter('：').replace("第", "").replace("周", ""), "周次"
                )
                result += CoursePreview(
                    name, teacher = fields[2].substringAfter('：').trim(),
                    room = fields[3].substringAfter('：').trim(), day = day,
                    startNode = nodes.first, step = nodes.last - nodes.first + 1,
                    startWeek = weeks.first, endWeek = weeks.last
                )
            }
        }
        if (result.isEmpty()) throw ParserException.empty("西安交大研究生课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("西安交大研究生课表解析失败：${error.message}", error)
    }
}
