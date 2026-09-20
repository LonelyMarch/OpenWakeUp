package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 广东白云学院 `pageRpt` 报表解析器。 */
object GdbyxyParser : Parser {
    /** 解析课程代码行后的教师、时间和地点，并删除上游 File/main/println。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val report = Jsoup.parse(input.text).getElementById("pageRpt")
            ?: throw ParserException.parse("广东白云学院页面中缺少 pageRpt")
        val result = mutableListOf<CoursePreview>()
        report.select("td").forEach { cell ->
            val lines =
                cell.html().split(Regex("(?i)<br\\s*/?>")).map { Jsoup.parse(it).text().trim() }
            lines.forEachIndexed { index, line ->
                val codeMatch = COURSE_PATTERN.matchEntire(line) ?: return@forEachIndexed
                require(index + 3 < lines.size) { "课程 ${codeMatch.groupValues[1]} 的后续字段不足" }
                val timeMatch = TIME_PATTERN.matchEntire(lines[index + 2])
                    ?: throw IllegalArgumentException("课程时间格式无效：${lines[index + 2]}")
                val nodes = TextUtils.requirePositiveRange(timeMatch.groupValues[3], "节次")
                val rawRoom = lines[index + 3]
                val room = if ('_' in rawRoom) rawRoom.split('_').take(2).joinToString("-")
                else rawRoom + lines.getOrNull(index + 4).orEmpty()
                WeekUtils.parse(timeMatch.groupValues[1]).forEach { week ->
                    result += CoursePreview(
                        name = codeMatch.groupValues[1],
                        teacher = lines[index + 1],
                        room = room,
                        day = TextUtils.requireDay(timeMatch.groupValues[2]),
                        startNode = nodes.first,
                        step = nodes.last - nodes.first + 1,
                        startWeek = week.startWeek,
                        endWeek = week.endWeek,
                        type = when (timeMatch.groupValues[4]) {
                            "单" -> 1; "双" -> 2; else -> week.type
                        }
                    )
                }
            }
        }
        if (result.isEmpty()) throw ParserException.empty("广东白云学院课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("广东白云学院课表解析失败：${error.message}", error)
    }

    private val COURSE_PATTERN = Regex("""^\[[A-Z]\d+]\s*(.+)$""")
    private val TIME_PATTERN = Regex("""^\[(.+)周]([一二三四五六日天])\[(.+)节]([单双]?)$""")
}
