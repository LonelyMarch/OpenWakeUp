package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils

/** 西安建筑科技大学研究生 init 脚本课表解析器。 */
object XauatPostParser : Parser {
    /** 提取 `td_星期_节次` 后的课程 HTML，并合并属性一致的相邻小节。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val init = input.text.substringAfter("function init(){", "").substringBefore("};", "")
        require(init.isNotBlank()) { "西建大研究生页面缺少 init 脚本" }
        val fragments = CELL_PATTERN.findAll(init).map { match ->
            val start = init.indexOf("+=\"课程", match.range.first)
            require(start >= 0) { "课程单元格后缺少课程文本" }
            val end = init.indexOf("\";", start)
            require(end > start) { "课程文本缺少结束标记" }
            parseItem(match.value.substring(3), init.substring(start + 3, end))
        }.toList()
        val result = mergeAdjacent(fragments)
        if (result.isEmpty()) throw ParserException.empty("西建大研究生课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("西建大研究生课表解析失败：${error.message}", error)
    }

    /** 将一个脚本片段转换为单节课程。 */
    private fun parseItem(position: String, html: String): CoursePreview {
        val pos = position.split('_')
        require(pos.size == 2) { "课程位置格式无效：$position" }
        val day = pos[0].toInt().takeIf { it in 1..7 } ?: error("课程星期无效")
        val node = pos[1].toInt() - 40
        require(node > 0) { "课程节次无效" }
        val fields = html.split("<br>")
        require(fields.size >= 4) { "西建大研究生课程字段不足" }
        val name = fields[0].substringAfter("课程:", fields[0]).trim()
        val teacher = fields[2].substringAfter(':', "").trim()
        val roomWeek = fields[3].substringAfter(':', "")
        val room = roomWeek.substringBefore('(').trim()
        val weekText = roomWeek.substringAfter('(', "").substringBeforeLast(')', "")
        val weeks = TextUtils.requirePositiveRange(
            weekText.substringAfter('第').substringBefore('周'),
            "周次"
        )
        return CoursePreview(
            name, teacher = teacher, room = room, day = day, startNode = node,
            startWeek = weeks.first, endWeek = weeks.last,
            type = when {
                weekText.contains("单周") -> 1; weekText.contains("双周") -> 2; else -> 0
            }
        )
    }

    /** 合并课程属性和周范围完全一致的连续节次。 */
    private fun mergeAdjacent(courses: List<CoursePreview>): List<CoursePreview> {
        val result = mutableListOf<CoursePreview>()
        courses.sortedWith(compareBy({ it.day }, { it.startNode })).forEach { course ->
            val index = result.indexOfLast { previous ->
                previous.copy(
                    startNode = course.startNode,
                    step = course.step
                ) == course && previous.startNode + previous.step == course.startNode
            }
            if (index >= 0) result[index] =
                result[index].copy(step = result[index].step + course.step) else result += course
        }
        return result
    }

    private val CELL_PATTERN = Regex("""td_\d_\d{2}""")
}
