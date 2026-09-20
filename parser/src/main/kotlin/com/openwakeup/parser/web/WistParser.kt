package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 武汉船舶职业技术学院金智课表解析器，规范 type 为 `wist`。 */
object WistParser : Parser {
    /** 解析 data-role=item 课程格，并分别合并精确周集合和相邻节次。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val document = Jsoup.parse(input.text)
        val cells = document.select("td[data-role=item]")
        if (cells.isEmpty()) throw ParserException.parse("武船页面中缺少 data-role=item 课程格")
        val fragments = mutableListOf<CoursePreview>()
        cells.forEach { cell ->
            val day = cell.attr("data-week").toIntOrNull()?.takeIf { it in 1..7 }
                ?: throw IllegalArgumentException("武船课程格星期属性无效")
            val beginAttr = cell.attr("data-begin-unit").toIntOrNull()
            val endAttr = cell.attr("data-end-unit").toIntOrNull()
            cell.select("div.mtt_arrange_item").forEach { block ->
                val name = block.selectFirst(".mtt_item_kcmc")?.ownText()?.trim().orEmpty()
                require(name.isNotEmpty()) { "武船课程块缺少名称" }
                val teacher = block.select(".mtt_item_jxbmc").text().trim()
                val parts =
                    block.select(".mtt_item_room").text().split(Regex("[,，]")).map { it.trim() }
                        .filter { it.isNotEmpty() }
                val weekText = parts.filter { it.contains('周') }.joinToString(",")
                require(weekText.isNotEmpty()) { "课程 $name 缺少周次" }
                val nodes = extractNodes(beginAttr, endAttr, parts)
                val room = parts.firstOrNull {
                    !it.contains('周') && !NODE_PATTERN.matches(it) && ROOM_PATTERN.containsMatchIn(
                        it
                    )
                }
                    ?: parts.firstOrNull { !it.contains('周') && !NODE_PATTERN.matches(it) }
                        .orEmpty()
                WeekUtils.parse(weekText).forEach { week ->
                    fragments += CoursePreview(
                        name, teacher = teacher, room = room, day = day,
                        startNode = nodes.first, step = nodes.last - nodes.first + 1,
                        startWeek = week.startWeek, endWeek = week.endWeek, type = week.type
                    )
                }
            }
        }
        val result = mergeAdjacent(fragments)
        if (result.isEmpty()) throw ParserException.empty("武船课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("武船课表解析失败：${error.message}", error)
    }

    /** 优先使用 data 属性，否则从房间信息片段读取中午节次或数字节次。 */
    private fun extractNodes(begin: Int?, end: Int?, parts: List<String>): IntRange {
        if (begin != null && end != null) {
            require(begin > 0 && end >= begin); return begin..end
        }
        val text = parts.firstOrNull(NODE_PATTERN::matches) ?: error("课程缺少节次")
        val values = text.split('-').map(::parseNode)
        require(values.first() > 0 && values.last() >= values.first()) { "课程节次无效：$text" }
        return values.first()..values.last()
    }

    /** 将中午两节映射为第 5、6 节，其余取明确数字。 */
    private fun parseNode(text: String): Int = when {
        text.contains("中1") -> 5
        text.contains("中2") -> 6
        else -> text.filter(Char::isDigit).toIntOrNull() ?: -1
    }

    /** 对空列表安全地合并属性一致且节次相邻的课程。 */
    private fun mergeAdjacent(courses: List<CoursePreview>): List<CoursePreview> {
        if (courses.isEmpty()) return emptyList()
        val result = mutableListOf<CoursePreview>()
        courses.sortedWith(
            compareBy(
                { it.name },
                { it.teacher },
                { it.room },
                { it.day },
                { it.startWeek },
                { it.startNode })
        )
            .forEach { next ->
                val current = result.lastOrNull()
                if (current != null && current.copy(
                        startNode = next.startNode,
                        step = next.step
                    ) == next &&
                    current.startNode + current.step == next.startNode
                ) {
                    result[result.lastIndex] = current.copy(step = current.step + next.step)
                } else result += next
            }
        return result
    }

    private val NODE_PATTERN = Regex("""^(中?[1-9]\d?)(-(中?[1-9]\d?))?$""")
    private val ROOM_PATTERN = Regex("实验室|教室|机房")
}
