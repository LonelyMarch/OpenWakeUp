package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import org.jsoup.Jsoup

/** 广州大学研究生旧版 `tab_0` 课表解析器。 */
object GzhuyjsParser : Parser {
    /** 解析第 5～7 行、周一至周日列中的课程块。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val body = Jsoup.parse(input.text).selectFirst("table.tab_0 tbody")
            ?: throw ParserException.parse("广州大学研究生页面中缺少 tab_0 课表")
        val rows = body.select("tr")
        if (rows.size < 7) throw ParserException.parse("广州大学研究生课表行数不足")
        val courses = mutableListOf<CoursePreview>()
        rows.subList(4, 7).forEach { row ->
            row.select("td").forEachIndexed { index, cell ->
                if (index !in 1..7) return@forEachIndexed
                cell.select("div").forEach { block ->
                    val fields = block.html().split(Regex("(?i)<br\\s*/?>"))
                        .map { Jsoup.parse(it).text().trim() }
                    require(fields.size >= 6 && fields[0].isNotEmpty()) { "周$index 的课程字段不足" }
                    val match = TIME_PATTERN.find(fields[4])
                        ?: throw IllegalArgumentException("课程 ${fields[0]} 的时间字段无法识别：${fields[4]}")
                    val values = match.groupValues.drop(1).map { it.toInt() }
                    courses += CoursePreview(
                        name = fields[0], teacher = fields[3], room = fields[5], day = index,
                        startNode = values[2], step = values[3] - values[2] + 1,
                        startWeek = values[0], endWeek = values[1],
                    )
                }
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("广州大学研究生课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("广州大学研究生课表解析失败：${error.message}", error)
    }

    private val TIME_PATTERN = Regex("""(\d+)\s*[-－—]\s*(\d+)\s*周\s*(\d+)\s*到\s*(\d+)\s*小""")
}
