package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 青果正选结果报表解析器。 */
object KgZxParser : Parser {
    /** 在多个 pageRpt 片段中只处理以“选定”开头的表格，并删除 File/main。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val result = mutableListOf<CoursePreview>()
        input.text.split("<head>", "</head>").forEach { html ->
            Jsoup.parse(html).getElementById("pageRpt")?.select("table")
                ?.filter { it.text().trimStart().startsWith("选定") }
                ?.forEach { table ->
                    table.select("tr[style]").forEach { row ->
                        require(row.childrenSize() > 10) { "青果正选课程行字段不足" }
                        val name = row.child(1).text().trim().substringAfterLast(']')
                        if (name.isEmpty()) return@forEach
                        row.child(10).html().split(Regex("(?i)<br\\s*/?>"))
                            .filter { it.isNotBlank() }.forEach { line ->
                            val parts = Jsoup.parse(line).text().split('/').map { it.trim() }
                            val time = parts[0]
                            val dayPart = time.substringAfter("星期", "")
                            require(dayPart.isNotEmpty()) { "课程 $name 缺少星期" }
                            val nodes = TextUtils.requirePositiveRange(
                                dayPart.substringAfter('[', "").substringBefore('节', ""), "节次"
                            )
                            val weekText = time.substringBefore("星期").substringAfterLast('[')
                                .substringBefore('周')
                            WeekUtils.parse(weekText).forEach { week ->
                                result += CoursePreview(
                                    name = name,
                                    teacher = row.child(4).text().substringBefore('[').trim(),
                                    room = parts.getOrNull(1).orEmpty(),
                                    day = TextUtils.requireDay(dayPart.take(1)),
                                    startNode = nodes.first,
                                    step = nodes.last - nodes.first + 1,
                                    startWeek = week.startWeek,
                                    endWeek = week.endWeek,
                                    type = week.type
                                )
                            }
                        }
                    }
                }
        }
        if (result.isEmpty()) throw ParserException.empty("青果正选结果中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("青果正选结果解析失败：${error.message}", error)
    }
}
