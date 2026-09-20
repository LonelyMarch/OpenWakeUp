package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 湖南科技大学及潇湘学院旧网格课表解析器。 */
object HnustParser : Parser {
    /** 以周次行为锚点解析名称、教师、教室，并由 div id 计算两节课组。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("kbtable")
            ?: throw ParserException.parse("湖南科大页面中缺少 kbtable")
        val result = mutableListOf<CoursePreview>()
        table.select("tr").forEach { row ->
            var day = -1
            row.select("td").forEach { cell ->
                day++
                cell.select("div").forEach { block ->
                    val lines = block.html().split(Regex("(?i)<br\\s*/?>"))
                    val anchors = lines.indices.filter {
                        WEEK_PATTERN.containsMatchIn(
                            Jsoup.parse(lines[it]).text()
                        )
                    }
                    anchors.forEachIndexed { position, index ->
                        val previous = if (position == 0) -1 else anchors[position - 1]
                        val nameIndex = previous + 1
                        require(day in 1..7 && nameIndex < index && index + 1 < lines.size) { "湖南科大课程片段字段不足" }
                        val startNode =
                            block.id().substringBefore('-').toIntOrNull()?.let { it * 2 - 1 }
                                ?: throw IllegalArgumentException("湖南科大课程块 id 缺少节次")
                        val time = Jsoup.parse(lines[index]).text().trim()
                        WeekUtils.parse(time.substringBefore('周') + "周").forEach { week ->
                            result += CoursePreview(
                                name = Jsoup.parse(lines[nameIndex]).text().trim(),
                                teacher = Jsoup.parse(lines[index - 1]).text().trim(),
                                room = Jsoup.parse(lines[index + 1]).text().trim(),
                                day = day,
                                startNode = startNode,
                                step = 2,
                                startWeek = week.startWeek,
                                endWeek = week.endWeek,
                                type = week.type
                            )
                        }
                    }
                }
            }
        }
        if (result.isEmpty()) throw ParserException.empty("湖南科大课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("湖南科大课表解析失败：${error.message}", error)
    }

    private val WEEK_PATTERN = Regex("""\d+(?:-\d+)?(?:单|双)?周""")
}
