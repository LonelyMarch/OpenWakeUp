package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `qmu` 使用的齐齐哈尔医学院旧版课表解析器。 */
object QmuParser : Parser {

    /**
     * 解析 `table[frame=box]` 中按双节排列的七日课表。
     *
     * 原版输入可能把多个页面片段连接在一起，因此仍按 `<head>` 边界逐段尝试。课程格中的每条
     * 安排由“课程名、教师、教室、周次/节数”四行组成；本实现保留 `<br>` 的分组语义，再把
     * 每个片段转换为纯文本，避免内嵌标签污染字段。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹、周次、节数或课程字段无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val fragments = input.text.split(HEAD_BOUNDARY_PATTERN)
            .filter { fragment -> fragment.isNotBlank() }
            .ifEmpty { listOf(input.text) }
        val courses = fragments.asSequence().mapNotNull { fragment ->
            val table = Jsoup.parse(fragment).select("table").firstOrNull { candidate ->
                candidate.hasAttr("frame") && candidate.attr("frame") == "box"
            } ?: return@mapNotNull null
            parseTable(table).takeIf { parsed -> parsed.isNotEmpty() }
        }.firstOrNull().orEmpty()

        if (courses.isEmpty()) throw ParserException.empty("齐齐哈尔医学院课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("齐齐哈尔医学院课表解析失败：${error.message}", error)
    }

    /**
     * 按有效行恢复起始节次，并按课程格所在列恢复星期。
     *
     * @param table 已通过 `frame=box` 指纹校验的课表
     * @return 该表格内的全部课程安排
     */
    private fun parseTable(table: Element): List<CoursePreview> {
        val courses = mutableListOf<CoursePreview>()
        var validRowIndex = 0
        table.select("tr").forEach { row ->
            val cells = row.select("td[valign=top]")
            if (cells.isEmpty()) return@forEach
            require(cells.size <= 7) { "齐齐哈尔医学院课表星期列超过 7 列" }

            val startNode = validRowIndex * 2 + 1
            cells.forEachIndexed { dayIndex, cell ->
                courses += parseCell(cell, dayIndex + 1, startNode)
            }
            // 只有包含课程格的行才代表下一组两节，表头和分隔行不参与节次累计。
            validRowIndex += 1
        }
        return courses
    }

    /**
     * 解析一个可能包含多门课程的单元格。
     *
     * @param cell 当前星期与双节组合对应的课程格
     * @param day 星期，1 表示周一
     * @param startNode 当前行对应的起始节次
     * @return 单元格内按周次片段展开的课程列表
     */
    private fun parseCell(cell: Element, day: Int, startNode: Int): List<CoursePreview> {
        val lines = cell.html().split(BR_PATTERN)
            .map { fragment -> Jsoup.parse(fragment).text().trim() }
        val courses = mutableListOf<CoursePreview>()

        lines.forEachIndexed { index, line ->
            if (!WEEK_LINE_PATTERN.containsMatchIn(line) || !NODE_LINE_PATTERN.containsMatchIn(line)) {
                return@forEachIndexed
            }
            require(index >= 3) { "齐齐哈尔医学院课程时间行之前缺少课程字段" }
            val name = lines[index - 3]
            val teacher = lines[index - 2]
            val room = lines[index - 1]
            require(name.isNotEmpty()) { "齐齐哈尔医学院课程名称为空" }

            val tokens = line.split(WHITESPACE_PATTERN).filter { token -> token.isNotBlank() }
            require(tokens.size >= 2) { "课程 $name 的时间字段无法分段：$line" }
            val rawStep = tokens.last().substringBefore('节').trim().toIntOrNull()
            require(rawStep != null && rawStep > 0) { "课程 $name 的连续节数无效：${tokens.last()}" }
            // 原页面一行最多覆盖当前双节组合；更大的声明按原版语义截断为两节。
            val step = rawStep.coerceAtMost(2)
            val weekTypeSuffix = when {
                tokens[1].contains('单') -> "单"
                tokens[1].contains('双') -> "双"
                else -> ""
            }
            val weekParts = tokens.first().substringBefore('周').split('.')
                .map { part -> part.trim() }
                .filter { part -> part.isNotEmpty() }
            require(weekParts.isNotEmpty()) { "课程 $name 缺少周次范围" }

            weekParts.forEach { part ->
                WeekUtils.parse("$part$weekTypeSuffix").forEach { week ->
                    courses += CoursePreview(
                        name = name,
                        teacher = teacher,
                        room = room,
                        day = day,
                        startNode = startNode,
                        step = step,
                        startWeek = week.startWeek,
                        endWeek = week.endWeek,
                        type = week.type,
                    )
                }
            }
        }
        return courses
    }

    private val HEAD_BOUNDARY_PATTERN = Regex("""(?i)</?head(?:\s[^>]*)?>""")
    private val BR_PATTERN = Regex("""(?i)<br\s*/?>""")
    private val WHITESPACE_PATTERN = Regex("""\s+""")
    private val WEEK_LINE_PATTERN = Regex("""\d.*周""")
    private val NODE_LINE_PATTERN = Regex("""\d+\s*节""")
}
