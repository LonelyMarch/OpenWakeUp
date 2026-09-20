package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `scau` 使用的两版纯 HTML 课表解析器。 */
object ScauParser : Parser {

    /**
     * 按原版顺序先尝试普通 `table[border=1]`，无课程时再尝试黑边框备用布局。
     *
     * @param input 完整课表 HTML，允许包含调用方拼接的多个页面片段
     * @return 首个命中布局中的课程预览
     * @throws ParserException 两种页面均未命中或命中后的课程字段不完整
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val primary = parsePrimaryLayout(input.text)
        val courses = if (primary.isNotEmpty()) primary else parseBlackBorderLayout(input.text)
        if (courses.isEmpty()) throw ParserException.empty("scau 页面中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("scau 课表解析失败：${error.message}", error)
    }

    /**
     * 解析原版前置 `o00OOO00` 的纵向课程字段布局。
     *
     * 每个时间标记决定节次；课程名、教师、教室和周次位于其相邻行。时间标记位于首行时使用
     * 原版的无教师布局。所有周次必须明确，不能用原版缺失结束周时的第 20 周默认值。
     */
    private fun parsePrimaryLayout(source: String): List<CoursePreview> {
        val table = Jsoup.parse(source).selectFirst("table[border=1]") ?: return emptyList()
        val courses = mutableListOf<CoursePreview>()
        table.select("tr").forEachIndexed { rowIndex, row ->
            row.select("td[valign=top]").forEachIndexed { columnIndex, cell ->
                val day = columnIndex + 1
                require(day in 1..7) { "scau 主布局的星期列超过 7 列" }
                val lines = htmlLines(cell.html())
                val timeIndexes =
                    lines.indices.filter { index -> TIME_PATTERN.containsMatchIn(lines[index]) }
                timeIndexes.forEach { timeIndex ->
                    courses += parsePrimaryCourse(lines, timeIndex, rowIndex, day)
                }
            }
        }
        return courses
    }

    /** 根据时间行在字段组中的位置解析一门主布局课程。 */
    private fun parsePrimaryCourse(
        lines: List<String>,
        timeIndex: Int,
        rowIndex: Int,
        day: Int,
    ): List<CoursePreview> {
        val timeLine = lines[timeIndex]
        val timeAtFirstLine = timeIndex == 0
        val defaultStart = (rowIndex - 1) * 2 + 1
        val defaultNodes = defaultStart..(defaultStart + 1)
        val nodes = parseCompactNodes(timeLine) ?: defaultNodes
        require(nodes.first > 0) { "scau 主布局节次必须为正整数" }

        val name = if (timeAtFirstLine) {
            nameBeforeTime(timeLine)
        } else {
            lines.getOrNull(timeIndex - 1).orEmpty().trim()
        }
        require(name.isNotEmpty()) { "scau 主布局课程名为空" }
        val teacher = if (timeAtFirstLine) "" else lines.getOrNull(timeIndex + 1).orEmpty().trim()
        val roomIndex = if (timeAtFirstLine) timeIndex + 1 else timeIndex + 2
        val room = lines.getOrNull(roomIndex).orEmpty().trim()
        val weekText = lines.getOrNull(timeIndex + 3).orEmpty().trim()
        require(weekText.isNotEmpty()) { "课程 $name 缺少周次" }
        val parity = when {
            timeLine.contains("（单）") || timeLine.contains("(单)") -> "单周"
            timeLine.contains("（双）") || timeLine.contains("(双)") -> "双周"
            else -> ""
        }

        return WeekUtils.parse(weekText + parity).map { week ->
            CoursePreview(
                name = name,
                teacher = teacher,
                room = room,
                day = day,
                startNode = nodes.first,
                step = nodes.last - nodes.first + 1,
                startWeek = week.startWeek,
                endWeek = week.endWeek,
                type = week.type,
            )
        }
    }

    /**
     * 解析 `bordercolor=#000000` 的备用双节网格。
     *
     * 页面可能被 `<head>` 边界拼接；Jsoup 对完整输入解析后仍可找到目标表，因此无需手工拼 HTML。
     */
    private fun parseBlackBorderLayout(source: String): List<CoursePreview> {
        val tables = Jsoup.parse(source).select("table[border=1]").filter { table ->
            table.attr("bordercolor").equals("#000000", ignoreCase = true)
        }
        tables.forEach { table ->
            val courses = parseBlackBorderTable(table)
            if (courses.isNotEmpty()) return courses
        }
        return emptyList()
    }

    /** 解析一张备用表格，前两行是表头，后续每行表示两个连续节次。 */
    private fun parseBlackBorderTable(table: Element): List<CoursePreview> {
        val courses = mutableListOf<CoursePreview>()
        table.select("tr").drop(2).forEachIndexed { rowIndex, row ->
            row.select("td[valign=top]").forEachIndexed { columnIndex, cell ->
                val day = columnIndex + 1
                require(day in 1..7) { "scau 备用布局的星期列超过 7 列" }
                val lines = htmlLines(cell.html())
                if (lines.size <= 1) return@forEachIndexed

                val firstLine = lines[0]
                val firstParts = firstLine.split(Regex("\\s+")).filter { part -> part.isNotEmpty() }
                val embeddedWeeks =
                    firstParts.lastOrNull()?.takeIf { part -> WEEK_TOKEN_PATTERN.matches(part) }
                val nameWithTeacher =
                    if (embeddedWeeks == null) firstLine else firstParts.dropLast(1)
                        .joinToString(" ")
                val name = nameWithTeacher.substringBefore('：').trim()
                val teacher = nameWithTeacher.substringAfter('：', "").trim()
                require(name.isNotEmpty()) { "scau 备用布局课程名为空" }
                val room = lines.getOrNull(1).orEmpty().trim()
                val scheduleLine = lines.getOrNull(2).orEmpty()
                val weekText = embeddedWeeks ?: scheduleLine.substringBefore('周').trim()
                require(weekText.isNotEmpty()) { "课程 $name 缺少周次" }
                val parity = when {
                    scheduleLine.contains('单') -> "单周"
                    scheduleLine.contains('双') -> "双周"
                    else -> ""
                }
                val startNode = rowIndex * 2 + 1
                WeekUtils.parse(weekText + parity).forEach { week ->
                    courses += CoursePreview(
                        name = name,
                        teacher = teacher,
                        room = room,
                        day = day,
                        startNode = startNode,
                        step = 2,
                        startWeek = week.startWeek,
                        endWeek = week.endWeek,
                        type = week.type,
                    )
                }
            }
        }
        return courses
    }

    /** 解析 `1-2节)` 或 `12节)`、`910节)`、`1112节)` 等原版紧凑节次。 */
    private fun parseCompactNodes(source: String): IntRange? {
        val match = TIME_PATTERN.find(source) ?: return null
        val value = match.groupValues[1].trim()
        if (RANGE_SEPARATOR_PATTERN.containsMatchIn(value)) {
            return TextUtils.requirePositiveRange(value, "scau 课程节次")
        }
        val digits = value.filter { character -> character.isDigit() }
        require(digits.isNotEmpty() && digits.length <= 4) { "scau 紧凑节次无效：$value" }
        val (start, end) = when (digits.length) {
            1 -> digits.toInt() to digits.toInt()
            2 -> digits.take(1).toInt() to digits.takeLast(1).toInt()
            3 -> digits.take(1).toInt() to digits.takeLast(2).toInt()
            else -> digits.take(2).toInt() to digits.takeLast(2).toInt()
        }
        require(start > 0 && end >= start) { "scau 节次范围无效：$value" }
        return start..end
    }

    /** 删除首行末尾的节次括号，仅保留课程名称。 */
    private fun nameBeforeTime(source: String): String {
        val match = TIME_PATTERN.find(source) ?: return source.trim()
        val asciiBracket = source.lastIndexOf('(', match.range.first)
        val chineseBracket = source.lastIndexOf('（', match.range.first)
        val cutIndex = maxOf(asciiBracket, chineseBracket)
        return source.substring(0, if (cutIndex >= 0) cutIndex else match.range.first).trim()
    }

    /** 将 `<br>` 分隔的 HTML 转成有序纯文本字段。 */
    private fun htmlLines(source: String): List<String> = source.split(BR_PATTERN)
        .map { fragment -> Jsoup.parse(fragment).text().trim() }
        .filter { line -> line.isNotEmpty() }

    private val BR_PATTERN = Regex("(?i)<br\\s*/?>")
    private val TIME_PATTERN = Regex("([\\d\\s,，\\-~～至—–]+)节[)）]")
    private val RANGE_SEPARATOR_PATTERN = Regex("[-~～至—–]")
    private val WEEK_TOKEN_PATTERN = Regex("[\\d,，\\-~～至—–]+")
}
