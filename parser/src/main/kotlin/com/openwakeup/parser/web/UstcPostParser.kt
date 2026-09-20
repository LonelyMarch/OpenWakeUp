package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 中国科学技术大学研究生 iframe 课表解析器。 */
object UstcPostParser : Parser {
    /** 解析 iframe srcdoc 内九列表格，并按节次列表或钟点范围定位课程。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val outer = Jsoup.parse(input.text)
        val srcdoc =
            outer.selectFirst("iframe#iframeContent_kbcxappustcxskbcx")?.attr("srcdoc").orEmpty()
        require(srcdoc.isNotBlank()) { "中科大研究生页面缺少课表 iframe 内容" }
        val table = Jsoup.parse(srcdoc).selectFirst("table")
            ?: throw ParserException.parse("iframe 中缺少课表")
        val result = mutableListOf<CoursePreview>()
        table.select("tr").forEach { row ->
            val cells = row.select("td")
            if (cells.size < 9) return@forEach
            val values = cells.map { it.selectFirst("span")?.text() ?: it.text() }
            val name = values[5].trim(); if (name.isEmpty()) return@forEach
            val weeksGroups = values[6].split(';')
            val periods = values[8].split(';')
            require(weeksGroups.size >= periods.size) { "课程 $name 的周次组少于时间地点组" }
            periods.forEachIndexed { index, period ->
                val parsed = parsePeriod(period)
                val weekNumbers = parseWeekNumbers(weeksGroups[index])
                WeekUtils.compact(weekNumbers).forEach { week ->
                    result += CoursePreview(
                        name = name,
                        teacher = values[7].trim(),
                        room = parsed.room,
                        day = parsed.day,
                        startNode = parsed.nodes.first,
                        step = parsed.nodes.last - parsed.nodes.first + 1,
                        startWeek = week.startWeek,
                        endWeek = week.endWeek,
                        type = week.type
                    )
                }
            }
        }
        if (result.isEmpty()) throw ParserException.empty("中科大研究生课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("中科大研究生课表解析失败：${error.message}", error)
    }

    /** 解析 `教室: 星期(节次)` 或 `教室: 星期(开始~结束)`。 */
    private fun parsePeriod(text: String): Period {
        val match = PERIOD_PATTERN.matchEntire(text.trim()) ?: error("时间地点格式无效：$text")
        val day = match.groupValues[2].toInt().takeIf { it in 1..7 } ?: error("星期无效：$text")
        val body = match.groupValues[3]
        val numbers = Regex("""\d+""").findAll(body).map { it.value.toInt() }.toList()
        val nodes = if (body.contains(':')) mapClockRange(body) else {
            require(numbers.isNotEmpty()) { "节次为空：$text" }; numbers.first()..numbers.last()
        }
        return Period(match.groupValues[1].trim(), day, nodes)
    }

    /** 将非标准钟点范围覆盖到中科大研究生作息的最小节次区间。 */
    private fun mapClockRange(text: String): IntRange {
        val times = Regex("""\d{1,2}:\d{2}""").findAll(text).map { toMinutes(it.value) }.toList()
        require(times.size == 2 && times[1] > times[0]) { "钟点范围无效：$text" }
        val covered =
            BELL_TIMES.indices.filter { index -> times[0] < BELL_TIMES[index].second && times[1] > BELL_TIMES[index].first }
        require(covered.isNotEmpty()) { "钟点范围无法映射到节次：$text" }
        return covered.first() + 1..covered.last() + 1
    }

    /** 将逗号分隔的连续、单周或双周区间展开为精确周集合。 */
    private fun parseWeekNumbers(text: String): List<Int> = text.split(',').flatMap { part ->
        val values = Regex("""\d+""").findAll(part).map { it.value.toInt() }.toList()
        require(values.size in 1..2) { "周次格式无效：$part" }
        val range = values.first()..values.last()
        when {
            part.contains('单') -> range.filter { it % 2 == 1 }; part.contains('双') -> range.filter { it % 2 == 0 }; else -> range.toList()
        }
    }

    private fun toMinutes(text: String): Int =
        text.substringBefore(':').toInt() * 60 + text.substringAfter(':').toInt()

    private data class Period(val room: String, val day: Int, val nodes: IntRange)

    private val PERIOD_PATTERN = Regex("""^(.+?):\s*(\d+)\((.+)\)$""")
    private val BELL_TIMES = listOf(
        "7:50" to "8:35",
        "8:40" to "9:25",
        "9:45" to "10:30",
        "10:35" to "11:20",
        "11:25" to "12:10",
        "14:00" to "14:45",
        "14:50" to "15:35",
        "15:55" to "16:40",
        "16:45" to "17:30",
        "17:35" to "18:20",
        "19:30" to "20:15",
        "20:20" to "21:05",
        "21:10" to "21:55"
    )
        .map { toMinutes(it.first) to toMinutes(it.second) }
}
