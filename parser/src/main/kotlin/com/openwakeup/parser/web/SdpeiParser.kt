package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** 山东石油化工学院 `tableMain` 网格课表解析器。 */
object SdpeiParser : Parser {
    /**
     * 解析网格中 `divOneClass` 课程块。
     *
     * @param input 完整课表 HTML
     * @return 所有课程块
     * @throws ParserException 表格指纹、周次或课程名称缺失
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val body = Jsoup.parse(input.text).selectFirst("table#tableMain tbody")
            ?: throw ParserException.parse("山东石油化工学院页面中缺少 tableMain")
        val courses = mutableListOf<CoursePreview>()
        var nodeIndex = 0
        body.select("tr").forEach { row ->
            if (row.select("th.thNewTitle, th.thRest").isNotEmpty()) return@forEach
            row.select("td").forEachIndexed { dayIndex, cell ->
                cell.select("div.divOneClass").forEach { block ->
                    courses += parseBlock(block, nodeIndex + 1, dayIndex + 1)
                }
            }
            nodeIndex++
        }
        if (courses.isEmpty()) throw ParserException.empty("山东石油化工学院课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("山东石油化工学院课表解析失败：${error.message}", error)
    }

    /**
     * 解析单个课程块。
     *
     * @param block `divOneClass` 元素
     * @param startNode 所在网格行对应的起始节次
     * @param day 所在网格列对应的星期
     * @return 校验后的课程预览
     */
    private fun parseBlock(block: Element, startNode: Int, day: Int): CoursePreview {
        val name = block.select("span.spLUName").text().removeSurrounding("《", "》").trim()
        require(name.isNotEmpty()) { "周$day 第 $startNode 节缺少课程名称" }
        val weeksText = block.select("span.spWeekInfo").text().trim()
        val weeks = TextUtils.requirePositiveRange(weeksText, "周次")
        val building = block.select("span.spBuilding").text().trim()
        val classroom = block.select("span.spClassroom").text().trim()
        val room = listOf(building, classroom).filter { it.isNotEmpty() }.joinToString("-")
        val rowSpan = block.parent()?.attr("rowspan")?.toIntOrNull() ?: 1
        require(rowSpan > 0) { "课程 $name 的 rowspan 非法" }
        return CoursePreview(
            name = name,
            teacher = block.select("span.spTeacherName").text().trim(),
            room = room,
            day = day,
            startNode = startNode,
            step = rowSpan,
            startWeek = weeks.first,
            endWeek = weeks.last,
            type = when {
                weeksText.contains("单周") -> 1
                weeksText.contains("双周") -> 2
                else -> 0
            },
        )
    }
}
