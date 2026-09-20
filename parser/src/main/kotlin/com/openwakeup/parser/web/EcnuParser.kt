package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 华东师范大学行式时间地点课表解析器。 */
object EcnuParser : Parser {
    /** 解析第二张 table 的第二个 thead，并无损压缩周集合与连续节次。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val tables = Jsoup.parse(input.text).select("table.table")
        require(tables.size >= 2) { "华东师大页面缺少第二张课程表" }
        val heads = tables[1].select("thead")
        require(heads.size >= 2) { "华东师大课程表缺少第二个表头体" }
        val result = mutableListOf<CoursePreview>()
        heads[1].select("tr").drop(1).forEach { row ->
            val cells = row.select("td")
            if (cells.size < 7) return@forEach
            val name = cells[0].text().trim()
            val teacher = cells[4].text().trim()
            cells[5].text().split('；').filter { it.isNotBlank() }.forEach { segment ->
                result += parseSegment(name, teacher, segment)
            }
        }
        if (result.isEmpty()) throw ParserException.empty("华东师大课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("华东师大课表解析失败：${error.message}", error)
    }

    /** 解析一条由中文逗号分隔的周次、星期、节次和地点。 */
    private fun parseSegment(name: String, teacher: String, segment: String): List<CoursePreview> {
        val parts = segment.split('，').map { it.trim() }.filter { it.isNotEmpty() }
        val dayPart = parts.firstOrNull { it.contains("星期") } ?: error("课程 $name 缺少星期")
        val weekPart = parts.firstOrNull { it.contains('周') && !it.contains("星期") }
            ?: error("课程 $name 缺少周次")
        val nodePart = parts.firstOrNull { it.contains('节') } ?: error("课程 $name 缺少节次")
        val room = parts.filterNot { it === dayPart || it === weekPart || it === nodePart }
            .joinToString(",")
        val nodes = TextUtils.requirePositiveRange(nodePart, "节次")
        val weeks = Regex("""\d+(?:-\d+)?""").findAll(weekPart).flatMap { match ->
            val range = TextUtils.requirePositiveRange(match.value, "周次")
            (range.first..range.last).asSequence()
        }.toList()
        require(weeks.isNotEmpty()) { "课程 $name 的周次为空" }
        return WeekUtils.compact(weeks).map { week ->
            CoursePreview(
                name = name, teacher = teacher, room = room, day = TextUtils.requireDay(dayPart),
                startNode = nodes.first, step = nodes.last - nodes.first + 1,
                startWeek = week.startWeek, endWeek = week.endWeek, type = week.type
            )
        }
    }
}
