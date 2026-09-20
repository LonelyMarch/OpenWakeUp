package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** 西北工业大学研究生“选课结果查询”页面解析器。 */
object NwpuGraduateParser : Parser {

    /**
     * 解析 `sample-table-1` 的动态列头和“上课时间”文本。
     *
     * @param input 选课结果查询最终页面 HTML
     * @return 支持一行多地点、多星期及不连续节次的课程预览
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val document = Jsoup.parse(input.text)
        val table = document.getElementById("sample-table-1")
            ?: throw ParserException.parse("西工大研究生页面中缺少 sample-table-1")
        val headers =
            table.selectFirst("thead tr")?.select("th")?.map { header -> header.text().trim() }
                ?: throw ParserException.parse("西工大研究生课表缺少表头")
        val courses = table.select("tbody tr").flatMap { row -> parseRow(row, headers) }
        if (courses.isEmpty()) throw ParserException.empty("西工大研究生课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("西工大研究生课表解析失败：${error.message}", error)
    }

    /** 读取一行的课程身份字段，再逐行解析其全部时间安排。 */
    private fun parseRow(row: Element, headers: List<String>): List<CoursePreview> {
        val cells = row.select("td")
        require(cells.size <= headers.size) { "课表数据列数大于表头列数" }
        val values = headers.indices.associate { index ->
            headers[index] to cells.getOrNull(index)?.let(::htmlWithLineBreaks).orEmpty()
        }
        val courseName = valueByHeader(values, "课程名称")
        val className = valueByHeader(values, "班级名称", required = false)
        val teacher = valueByHeader(values, "主讲教师", required = false)
        val detail = valueByHeader(values, "上课时间")
        val displayName = if (className.isBlank()) courseName else "$courseName($className)"
        return detail.lineSequence().map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
            .flatMap { line -> parseScheduleLine(displayName, teacher, line).asSequence() }
            .toList()
    }

    /**
     * 解析一个地点与周次行；一行可同时包含多个星期。
     *
     * @param name 已组合班级信息的课程名
     * @param teacher 主讲教师
     * @param line 一条完整上课时间文本
     */
    private fun parseScheduleLine(
        name: String,
        teacher: String,
        line: String
    ): List<CoursePreview> {
        val openParenthesis = line.indexOf('(')
        val closeParenthesis = line.lastIndexOf(')')
        require(openParenthesis >= 0 && closeParenthesis > openParenthesis) { "课程 $name 的时间缺少括号：$line" }
        val room =
            if (openParenthesis == 0) "" else line.substring(0, openParenthesis).replace('-', ' ')
                .trim()
        val timeText = line.substring(openParenthesis + 1, closeParenthesis)
        val weekMatch = WEEK_PATTERN.find(timeText)
            ?: throw IllegalArgumentException("课程 $name 缺少明确周次范围：$line")
        val startWeek = weekMatch.groupValues[1].toInt()
        val endWeek = weekMatch.groupValues[2].toInt()
        require(startWeek > 0 && endWeek >= startWeek) { "课程 $name 的周次范围无效" }
        val exactWeeks = (startWeek..endWeek).filter { week ->
            when {
                timeText.contains("单周") -> week % 2 == 1
                timeText.contains("双周") -> week % 2 == 0
                else -> true
            }
        }
        require(exactWeeks.isNotEmpty()) { "课程 $name 的周次与单双周标记冲突" }
        val daySegments =
            DAY_SEGMENT_PATTERN.findAll(timeText).map { match -> match.value.trim() }.toList()
        require(daySegments.isNotEmpty()) { "课程 $name 缺少星期和节次" }

        return daySegments.flatMap { segment ->
            val dayText = DAY_PATTERN.find(segment)?.value
                ?: throw IllegalArgumentException("课程 $name 的星期无法识别：$segment")
            val day = TextUtils.requireDay(dayText)
            val nodes = NODE_PATTERN.findAll(segment.substringAfter(dayText))
                .map { match -> parseNode(match.value) }
                .toSortedSet()
            require(nodes.isNotEmpty()) { "课程 $name 缺少节次：$segment" }
            val nodeRanges = splitConsecutive(nodes)
            WeekUtils.compact(exactWeeks).flatMap { week ->
                nodeRanges.map { range ->
                    CoursePreview(
                        name = name,
                        teacher = teacher,
                        room = room,
                        day = day,
                        startNode = range.first,
                        step = range.last - range.first + 1,
                        startWeek = week.startWeek,
                        endWeek = week.endWeek,
                        type = week.type,
                    )
                }
            }
        }
    }

    /** 把“上3、下1、11”等页面节次转换为统一节次。 */
    private fun parseNode(text: String): Int {
        val prefix = text.firstOrNull()?.takeIf { value -> !value.isDigit() }
        val number = text.filter { value -> value.isDigit() }.toIntOrNull()
            ?: throw IllegalArgumentException("节次无法识别：$text")
        val offset = when (prefix) {
            null -> 0
            '上' -> 0
            '中' -> 4
            '下' -> 6
            '晚' -> 10
            else -> throw IllegalArgumentException("节次前缀无法识别：$text")
        }
        return offset + number
    }

    /** 将不连续的节次集合拆成多个闭区间。 */
    private fun splitConsecutive(nodes: Set<Int>): List<IntRange> {
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

    /** 把 `<br>` 保留为换行后提取纯文本。 */
    private fun htmlWithLineBreaks(cell: Element): String = cell.html()
        .split(BREAK_PATTERN)
        .joinToString("\n") { fragment -> Jsoup.parse(fragment).text().trim() }

    /** 按包含关系读取动态表头字段。 */
    private fun valueByHeader(
        values: Map<String, String>,
        name: String,
        required: Boolean = true
    ): String {
        val value =
            values.entries.firstOrNull { entry -> entry.key.contains(name) }?.value.orEmpty().trim()
        require(!required || value.isNotEmpty()) { "课表行缺少$name" }
        return value
    }

    private val WEEK_PATTERN = Regex("""第\s*(\d+)\s*[-—–]\s*(\d+)\s*周""")
    private val DAY_PATTERN = Regex("""星期[一二三四五六日天]""")
    private val DAY_SEGMENT_PATTERN = Regex("""星期[一二三四五六日天].+?(?=<|星期|$)""")
    private val NODE_PATTERN = Regex("""[上中下晚]?\d+""")
    private val BREAK_PATTERN = Regex("""(?i)<br\s*/?>""")
}
