package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `aic` 使用的学期课程表解析器。 */
object AicParser : Parser {

    /**
     * 从可能拼接了多个 `table#table` 的页面中选择学期课表。
     *
     * 原版用 `getScheduleNew.do` 区分学期课表与周课表，本实现保持同一判据。Parser 只消费已取得的
     * HTML，不发起该 URL 请求。
     *
     * @param input 完整课表 HTML
     * @return 学期课表中的课程预览
     * @throws ParserException 输入不是学期课表或课程字段不完整
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = findSemesterTable(input.text)
            ?: throw ParserException.parse("请不要选择“周课程表”，请直接选择“学期课表”")
        val courses = mutableListOf<CoursePreview>()
        val rows = table.select("tr")
        rows.drop(1).forEachIndexed { rowOffset, row ->
            val startNode = rowOffset + 1
            val cells = row.children().filter { child -> child.tagName() == "td" }
            cells.drop(1).forEachIndexed { dayOffset, cell ->
                val day = dayOffset + 1
                require(day in 1..7) { "aic 学期课表的星期列超过 7 列" }
                cell.getElementsByClass("courseInfo").forEach { info ->
                    courses += parseCourse(info, day, startNode)
                }
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("aic 学期课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("aic 学期课表解析失败：${error.message}", error)
    }

    /** 按原版原始字符串分段方式找到包含 `getScheduleNew.do` 的目标表格。 */
    private fun findSemesterTable(source: String): Element? {
        val starts = TABLE_START_PATTERN.findAll(source).toList()
        val fragment = starts.mapIndexed { index, match ->
            val end = starts.getOrNull(index + 1)?.range?.first ?: source.length
            source.substring(match.range.first, end)
        }.firstOrNull { part -> part.contains("getScheduleNew.do") } ?: return null
        return Jsoup.parse(fragment).selectFirst("table#table")
    }

    /** 解析一个 `.courseInfo` 块，并按明确周次拆分。 */
    private fun parseCourse(info: Element, day: Int, startNode: Int): List<CoursePreview> {
        val name = info.children().firstOrNull()?.text()?.trim().orEmpty()
        require(name.isNotEmpty()) { "aic 课程块缺少名称" }
        val weekText = info.getElementsByClass("weekDetail").firstOrNull()?.text()?.trim()
            ?: throw IllegalArgumentException("课程 $name 缺少学期周次；当前页面可能是周课表")
        require(weekText.isNotEmpty()) { "课程 $name 的周次字段为空" }
        val teacher = info.getElementsByClass("teacher").lastOrNull()?.text()?.trim().orEmpty()
        val room = info.getElementsByClass("place").lastOrNull()?.text()?.trim().orEmpty()
        return WeekUtils.parse(weekText).map { week ->
            CoursePreview(
                name = name,
                teacher = teacher,
                room = room,
                day = day,
                startNode = startNode,
                startWeek = week.startWeek,
                endWeek = week.endWeek,
                type = week.type,
            )
        }
    }

    private val TABLE_START_PATTERN = Regex("(?i)<table\\b[^>]*\\bid\\s*=\\s*[\"']table[\"']")
}
