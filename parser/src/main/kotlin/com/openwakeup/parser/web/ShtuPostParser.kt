package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 上海科技大学研究生旧版 `div-table` 课表解析器。 */
object ShtuPostParser : Parser {

    /**
     * 解析调用方取得的最终课表 HTML。
     *
     * @param input 包含 `#div-table` 的页面或同源 iframe HTML
     * @return 精确处理排除周与 rowspan 的课程预览
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).selectFirst("#div-table")
            ?: throw ParserException.parse("上科大旧版页面中缺少 div-table")
        val occupied = mutableMapOf<Int, MutableSet<Int>>()
        val courses = mutableListOf<CoursePreview>()
        table.select("tr").forEachIndexed { rowIndex, row ->
            var visualColumn = 0
            row.select("td").forEach { cell ->
                while (visualColumn in occupied[rowIndex].orEmpty()) visualColumn++
                val rowSpan = cell.attr("rowspan").toIntOrNull()?.takeIf { value -> value > 0 } ?: 1
                require(rowIndex + rowSpan - 1 <= 13 || rowIndex !in 1..13) {
                    "上科大旧版课程 rowspan 超出第 13 节"
                }
                if (rowSpan > 1) {
                    // 后续行缺少被 rowspan 占用的物理 td，需要提前记录其视觉列。
                    ((rowIndex + 1) until (rowIndex + rowSpan)).forEach { nextRow ->
                        occupied.getOrPut(nextRow) { mutableSetOf() }.add(visualColumn)
                    }
                }
                if (rowIndex in 1..13 && visualColumn in 1..7 && cell.html().isNotBlank()) {
                    courses += parseCell(
                        cell.html(),
                        visualColumn,
                        rowIndex,
                        rowIndex + rowSpan - 1
                    )
                }
                visualColumn++
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("上科大旧版课表中没有课程")
        mergeAdjacent(courses)
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("上科大旧版课表解析失败：${error.message}", error)
    }

    /** 解析一个单元格中按四行一组排列的多门课程。 */
    private fun parseCell(
        html: String,
        day: Int,
        startNode: Int,
        endNode: Int
    ): List<CoursePreview> {
        val fields =
            html.split(BREAK_PATTERN).map { fragment -> Jsoup.parse(fragment).text().trim() }
                .dropLastWhile { value -> value.isEmpty() }
        require(fields.size % 4 == 0) { "周$day 第$startNode 节课程字段不是四行一组" }
        return fields.chunked(4).flatMap { values ->
            val className = values[0]
            val teacher = values[1]
            val room = values[2]
            require(className.isNotEmpty()) { "周$day 第$startNode 节课程名为空" }
            val name = COURSE_NAME_PATTERN.find(className)?.groupValues?.get(1)?.trim()
                ?.takeIf { value -> value.isNotEmpty() }
                ?: "班级:$className,教师:$teacher"
            val weeks = parseWeeks(values[3])
            WeekUtils.compact(weeks).map { week ->
                CoursePreview(
                    name = name,
                    teacher = teacher,
                    room = room,
                    day = day,
                    startNode = startNode,
                    step = endNode - startNode + 1,
                    startWeek = week.startWeek,
                    endWeek = week.endWeek,
                    type = week.type,
                )
            }
        }
    }

    /** 解析连续周、离散周及“除第 N 周”排除列表。 */
    private fun parseWeeks(text: String): Set<Int> {
        val exceptionText = EXCEPTION_PATTERN.find(text)?.value.orEmpty()
        val excluded =
            NUMBER_PATTERN.findAll(exceptionText).map { match -> match.value.toInt() }.toSet()
        val baseText = text.replace(exceptionText, "")
        val range = RANGE_PATTERN.find(baseText)
        val weeks = if (range != null) {
            val start = range.groupValues[1].toInt()
            val end = range.groupValues[2].toInt()
            require(start > 0 && end >= start) { "周次范围无效：$text" }
            (start..end).toSet()
        } else {
            val list = LIST_PATTERN.find(baseText)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("无法识别周次：$text")
            NUMBER_PATTERN.findAll(list).map { match -> match.value.toInt() }.toSet()
        }
        val result = weeks - excluded
        require(result.isNotEmpty()) { "排除周后没有任何上课周：$text" }
        return result
    }

    /** 合并字段与周次完全相同且相邻的节次，保留中间空节。 */
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
            val nodes = values.flatMap { course ->
                (course.startNode until course.startNode + course.step).toList()
            }.toSortedSet()
            splitNodes(nodes).map { range ->
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

    /** 把有序节次集合拆成连续范围。 */
    private fun splitNodes(nodes: Set<Int>): List<IntRange> {
        val sorted = nodes.sorted()
        val result = mutableListOf<IntRange>()
        var start = sorted.first()
        var previous = start
        sorted.drop(1).forEach { node ->
            if (node != previous + 1) {
                result += start..previous
                start = node
            }
            previous = node
        }
        result += start..previous
        return result
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

    private val BREAK_PATTERN = Regex("""(?i)<br\s*/?>""")
    private val COURSE_NAME_PATTERN = Regex("""^(.*?)\d+班""")
    private val RANGE_PATTERN = Regex("""第\s*(\d+)\s*[-—–]\s*(\d+)\s*周""")
    private val LIST_PATTERN = Regex("""第\s*([\d,，、\s]+)周""")
    private val EXCEPTION_PATTERN = Regex("""[（(]除[^）)]*[）)]""")
    private val NUMBER_PATTERN = Regex("""\d+""")
}
