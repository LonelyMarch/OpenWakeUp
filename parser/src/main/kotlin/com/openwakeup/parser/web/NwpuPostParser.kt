package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element


object NwpuPostParser : Parser {

    /**
     * 解析研究生选课结果页面的动态表头与上课时间字段。
     *
     * @param input `text` 为最终“选课结果查询”页面 HTML
     * @return 按周次、星期和连续节次拆分后的课程预览
     * @throws ParserException 页面指纹、课程字段、周次或节次无法识别时抛出
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val document = Jsoup.parse(input.text)
        val table = document.getElementById("sample-table-1")
            ?: throw ParserException.parse("西工大研究生页面中缺少 sample-table-1")
        val headers = table.selectFirst("thead tr")?.select("th")?.map { header ->
            header.text().trim()
        } ?: throw ParserException.parse("西工大研究生课表缺少表头")
        require(headers.isNotEmpty()) { "西工大研究生课表表头为空" }

        val courses = table.select("tbody tr").flatMap { row -> parseRow(row, headers) }
        if (courses.isEmpty()) throw ParserException.empty("西工大研究生课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("西工大研究生课表解析失败：${error.message}", error)
    }

    /**
     * 根据动态表头读取一行课程，再展开其中的每条时间安排。
     *
     * @param row 当前课程数据行
     * @param headers 与数据列同序的页面表头
     * @return 当前课程行展开后的全部课程预览
     */
    private fun parseRow(row: Element, headers: List<String>): List<CoursePreview> {
        val cells = row.select("td")
        require(cells.size <= headers.size) { "课表数据列数大于表头列数" }
        val values = headers.indices.associate { index ->
            headers[index] to cells.getOrNull(index)?.let(::htmlWithLineBreaks).orEmpty()
        }
        val courseName = valueByHeader(values, "课程名称")
        val className = valueByHeader(values, "班级名称", required = false)
        val teacher = valueByHeader(values, "主讲教师", required = false)
        val scheduleDetails = valueByHeader(values, "上课时间")
        val displayName = if (className.isBlank()) courseName else "$courseName($className)"

        return scheduleDetails.lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
            .flatMap { line -> parseScheduleLine(displayName, teacher, line).asSequence() }
            .toList()
    }

    /**
     * 解析一条地点与时间描述。
     *
     * 原页面允许同一行包含多个星期，也允许用“上/中/下/晚”节次前缀。节次不连续时必须拆成
     * 多条课程，不能把中间没有课的时段错误合并进去。
     *
     * @param name 已组合可选班级名称的课程名
     * @param teacher 主讲教师
     * @param line 一条完整上课时间描述
     * @return 当前描述展开后的课程预览
     */
    private fun parseScheduleLine(
        name: String,
        teacher: String,
        line: String,
    ): List<CoursePreview> {
        val openParenthesis = line.indexOf('(')
        val closeParenthesis = line.lastIndexOf(')')
        require(openParenthesis >= 0 && closeParenthesis > openParenthesis) {
            "课程 $name 的时间缺少括号：$line"
        }
        val room = if (openParenthesis == 0) {
            ""
        } else {
            line.substring(0, openParenthesis).replace('-', ' ').trim()
        }
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

        val daySegments = DAY_SEGMENT_PATTERN.findAll(timeText)
            .map { match -> match.value.trim() }
            .toList()
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

    /**
     * 把“上3”“中1”“下2”“晚1”或直接数字节次转换为统一的 1 起始节次。
     *
     * @param text 页面中的单个节次标记
     * @return 标准节次编号
     */
    private fun parseNode(text: String): Int {
        val prefix = text.firstOrNull()?.takeIf { character -> !character.isDigit() }
        val number = text.filter { character -> character.isDigit() }.toIntOrNull()
            ?: throw IllegalArgumentException("节次无法识别：$text")
        val offset = when (prefix) {
            null, '上' -> 0
            '中' -> 4
            '下' -> 6
            '晚' -> 10
            else -> throw IllegalArgumentException("节次前缀无法识别：$text")
        }
        val node = offset + number
        require(node > 0) { "节次必须大于 0：$text" }
        return node
    }

    /**
     * 将不连续节次集合拆成多个连续闭区间。
     *
     * @param nodes 已排序前的节次集合
     * @return 一个或多个连续节次区间
     */
    private fun splitConsecutive(nodes: Set<Int>): List<IntRange> {
        val sorted = nodes.sorted()
        require(sorted.isNotEmpty()) { "待拆分节次不能为空" }
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

    /**
     * 保留单元格中的换行语义后提取文本。
     *
     * @param cell 原始表格单元格
     * @return 每个 `br` 分段占一行的纯文本
     */
    private fun htmlWithLineBreaks(cell: Element): String = cell.html()
        .split(BREAK_PATTERN)
        .joinToString("\n") { fragment -> Jsoup.parse(fragment).text().trim() }

    /**
     * 按表头包含关系取值，以兼容表头前后的空格或附加说明。
     *
     * @param values 表头到单元格文本的映射
     * @param name 所需表头关键词
     * @param required 是否要求字段非空
     * @return 匹配字段的去空白文本
     */
    private fun valueByHeader(
        values: Map<String, String>,
        name: String,
        required: Boolean = true,
    ): String {
        val value =
            values.entries.firstOrNull { entry -> entry.key.contains(name) }?.value.orEmpty().trim()
        require(!required || value.isNotEmpty()) { "课表行缺少$name" }
        return value
    }

    /** 教学周范围，例如“第2-13周”。 */
    private val WEEK_PATTERN = Regex("""第\s*(\d+)\s*[-—–]\s*(\d+)\s*周""")

    /** 页面使用的中文星期。 */
    private val DAY_PATTERN = Regex("""星期[一二三四五六日天]""")

    /** 从一条时间文本中分离每个星期及其节次，停止于钟点或下一个星期。 */
    private val DAY_SEGMENT_PATTERN = Regex("""星期[一二三四五六日天].+?(?=<|星期|$)""")

    /** 支持带时段前缀和不带前缀的节次编号。 */
    private val NODE_PATTERN = Regex("""[上中下晚]?\d+""")

    /** HTML 中的换行标签。 */
    private val BREAK_PATTERN = Regex("""(?i)<br\s*/?>""")
}
