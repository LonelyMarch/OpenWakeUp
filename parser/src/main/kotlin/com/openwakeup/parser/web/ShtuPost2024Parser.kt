package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** 上海科技大学研究生 2024 版 `jsTbl_01` 课表解析器。 */
object ShtuPost2024Parser : Parser {

    /** 解析新版网格中每个课程卡片的周次、教师和地点。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).selectFirst("#jsTbl_01")
            ?: throw ParserException.parse("上科大 2024 页面中缺少 jsTbl_01")
        val courses = mutableListOf<CoursePreview>()
        val occupied = mutableMapOf<Int, MutableSet<Int>>()
        table.select("tr").forEachIndexed { rowIndex, row ->
            var visualColumn = 0
            row.select("td").forEach { cell ->
                while (visualColumn in occupied[rowIndex].orEmpty()) visualColumn++
                val rowSpan = cell.attr("rowspan").toIntOrNull()?.takeIf { value -> value > 0 } ?: 1
                require(rowIndex + rowSpan - 1 <= 13 || rowIndex !in 1..13) {
                    "上科大 2024 课程 rowspan 超出第 13 节"
                }
                if (rowSpan > 1) {
                    // 用视觉列记录 rowspan，避免后续行因缺少物理 td 而把星期整体左移。
                    ((rowIndex + 1) until (rowIndex + rowSpan)).forEach { nextRow ->
                        occupied.getOrPut(nextRow) { mutableSetOf() }.add(visualColumn)
                    }
                }
                if (rowIndex in 1..13 && visualColumn in 2..8 && rowSpan > 1) {
                    courses += parseCell(
                        cell = cell,
                        day = visualColumn - 1,
                        startNode = rowIndex,
                        endNode = rowIndex + rowSpan - 1,
                    )
                }
                visualColumn++
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("上科大 2024 课表中没有课程")
        mergeAdjacent(courses)
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("上科大 2024 课表解析失败：${error.message}", error)
    }

    /** 解析一个节次单元格内的全部课程卡片。 */
    private fun parseCell(
        cell: Element,
        day: Int,
        startNode: Int,
        endNode: Int,
    ): List<CoursePreview> = cell.children().flatMap { item ->
        val fields = item.children()
        require(fields.size >= 4) { "周$day 第$startNode 节课程卡片字段不足" }
        val weekText = fields[0].text().trim()
        val name = fields[1].text().trim()
        require(name.isNotEmpty()) { "周$day 第$startNode 节课程名为空" }
        val exactWeeks = parseExactWeeks(weekText)
        WeekUtils.compact(exactWeeks).map { week ->
            CoursePreview(
                name = name,
                teacher = fields[2].text().trim(),
                room = fields[3].text().trim(),
                day = day,
                startNode = startNode,
                step = endNode - startNode + 1,
                startWeek = week.startWeek,
                endWeek = week.endWeek,
                type = week.type,
            )
        }
    }

    /** 先读取全部数字范围，再按整条字段上的单双周标记过滤。 */
    private fun parseExactWeeks(text: String): Set<Int> {
        val weeks = WEEK_TOKEN_PATTERN.findAll(text).flatMap { match ->
            val token = match.value
            val values =
                NUMBER_PATTERN.findAll(token).map { number -> number.value.toInt() }.toList()
            when (values.size) {
                1 -> sequenceOf(values[0])
                2 -> (values[0]..values[1]).asSequence()
                else -> emptySequence()
            }
        }.filter { week ->
            when {
                text.contains('单') -> week % 2 == 1
                text.contains('双') -> week % 2 == 0
                else -> true
            }
        }.toSortedSet()
        require(weeks.isNotEmpty()) { "无法识别课程周次：$text" }
        return weeks
    }

    /** 合并完全相同课程的相邻单节记录，不跨越缺失节次。 */
    private fun mergeAdjacent(courses: List<CoursePreview>): List<CoursePreview> = courses
        .groupBy { course ->
            MergeKey(
                course.name,
                course.teacher,
                course.room,
                course.day,
                course.startWeek,
                course.endWeek,
                course.type
            )
        }
        .flatMap { (key, values) ->
            val sorted = values.flatMap { course ->
                (course.startNode until course.startNode + course.step).toList()
            }.distinct().sorted()
            val ranges = mutableListOf<IntRange>()
            var start = sorted.first()
            var previous = start
            sorted.drop(1).forEach { node ->
                if (node != previous + 1) {
                    ranges += start..previous
                    start = node
                }
                previous = node
            }
            ranges += start..previous
            ranges.map { range ->
                CoursePreview(
                    name = key.name,
                    teacher = key.teacher,
                    room = key.room,
                    day = key.day,
                    startNode = range.first,
                    step = range.last - range.first + 1,
                    startWeek = key.startWeek,
                    endWeek = key.endWeek,
                    type = key.type,
                )
            }
        }

    private data class MergeKey(
        val name: String,
        val teacher: String,
        val room: String,
        val day: Int,
        val startWeek: Int,
        val endWeek: Int,
        val type: Int,
    )

    private val WEEK_TOKEN_PATTERN = Regex("""\d+\s*[-—–]\s*\d+|\d+""")
    private val NUMBER_PATTERN = Regex("""\d+""")
}
