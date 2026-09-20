package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import org.jsoup.Jsoup

/** 暨南大学报表 XML/HTML 课表解析器。 */
object JnuParser : Parser {

    /**
     * 解析 `oReportCell` 中课程网格的周一至周日七行。
     *
     * @param input 包含报表片段的完整响应文本
     * @return 合并同一课程连续节次后的课程列表
     * @throws ParserException 报表指纹缺失、单元格字段不完整或课程为空
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val reportSource = input.text.substringAfter("</html>", input.text)
        val document = Jsoup.parse(reportSource)
        val frame = document.getElementById("oReportCell")
            ?: throw ParserException.parse("暨南大学课表中缺少 oReportCell 报表区域")
        val table = frame.selectFirst("table.a8, .a8")
            ?: throw ParserException.parse("暨南大学课表中缺少 a8 课程表")
        val rows = table.select("tr")
        if (rows.size < 10) throw ParserException.parse("暨南大学课程表行数不足")

        val courses = mutableListOf<CoursePreview>()
        rows.subList(3, 10).forEachIndexed { dayIndex, row ->
            row.select("td").forEachIndexed cellLoop@{ nodeIndex, cell ->
                if (nodeIndex == 0) return@cellLoop
                val text = cell.select("div").text().trim()
                if (text.isEmpty()) return@cellLoop
                val name = text.substringAfter("课程：", "").substringBeforeLast('(', "").trim()
                require(name.isNotEmpty()) { "周${dayIndex + 1}第 $nodeIndex 节缺少课程名称" }
                val type = when {
                    text.contains("单周") -> 1
                    text.contains("双周") -> 2
                    else -> 0
                }
                val weekRange = parseWeekRange(text)
                val room = if (type == 0) {
                    text.substringBefore(' ').trim()
                } else {
                    text.substringAfter('周', "").trim().substringBefore(' ').trim()
                }
                val current = CoursePreview(
                    name = name,
                    room = room,
                    day = dayIndex + 1,
                    startNode = nodeIndex,
                    startWeek = weekRange.first,
                    endWeek = weekRange.last,
                    type = type,
                )
                val previous = courses.lastOrNull()
                if (previous != null && canMerge(previous, current)) {
                    courses[courses.lastIndex] = previous.copy(step = previous.step + 1)
                } else {
                    courses += current
                }
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("暨南大学课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("暨南大学课表解析失败：${error.message}", error)
    }

    /**
     * 判断两个课程片段是否属于同一课程的相邻节次。
     *
     * @param previous 已加入结果的上一片段
     * @param current 当前片段
     * @return 所有课程属性相同且节次连续时返回 `true`
     */
    private fun canMerge(previous: CoursePreview, current: CoursePreview): Boolean =
        previous.name == current.name &&
                previous.day == current.day &&
                previous.room == current.room &&
                previous.teacher == current.teacher &&
                previous.startWeek == current.startWeek &&
                previous.endWeek == current.endWeek &&
                previous.type == current.type &&
                previous.startNode + previous.step == current.startNode

    /**
     * 从课程文本中读取明确的周次范围。
     *
     * 上游实现固定写入 1～18 周，但该范围不一定属于当前学期。这里要求响应自身携带周次，
     * 缺少范围时失败，避免制造无法由页面证实的课程。
     *
     * @param text 完整课程单元格文本
     * @return 页面声明的闭区间周次
     */
    private fun parseWeekRange(text: String): IntRange {
        val match = WEEK_RANGE_PATTERN.find(text)
            ?: throw IllegalArgumentException("课程文本缺少明确周次范围：$text")
        val start = match.groupValues[1].toInt()
        val end = match.groupValues[2].toInt()
        require(start > 0 && end >= start) { "课程周次范围无效：$text" }
        return start..end
    }

    /** 匹配 `1-18周` 等明确周次范围，不接受无来源的固定学期长度。 */
    private val WEEK_RANGE_PATTERN = Regex("""(\d+)\s*[-－—~～]\s*(\d+)\s*周""")
}
