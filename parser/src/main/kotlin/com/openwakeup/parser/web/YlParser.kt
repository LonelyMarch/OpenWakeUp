package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `yl` 使用的 CSS Modules 时间表解析器。 */
object YlParser : Parser {

    /**
     * 解析类名包含 `app-components-CourseTimeTable-styles-timeTable` 的七日表格。
     *
     * @param input 完整课表 HTML
     * @return 所有课程盒子的预览记录
     * @throws ParserException 页面指纹缺失或课程盒子字段不足
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val timetable = Jsoup.parse(input.text).select("div").firstOrNull { element ->
            element.className().contains(TIMETABLE_CLASS_MARKER)
        } ?: throw ParserException.parse("页面中缺少 yl CSS Modules 时间表")
        val body = timetable.selectFirst("tbody")
            ?: throw IllegalArgumentException("yl 时间表缺少 tbody")
        val courses = mutableListOf<CoursePreview>()
        body.select("tr").forEach { row ->
            val cells = row.children().filter { child -> child.tagName() == "td" }
            cells.drop(1).forEachIndexed { dayOffset, cell ->
                val day = dayOffset + 1
                require(day in 1..7) { "yl 时间表的星期列超过 7 列" }
                cell.select("div").filter { block ->
                    block.className().contains(COURSE_BOX_CLASS_MARKER)
                }.forEach { block ->
                    courses += parseCourseBox(block, day)
                }
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("yl 时间表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("yl 时间表解析失败：${error.message}", error)
    }

    /** 读取原版固定子节点布局中的名称、周次、节次、教室与教师。 */
    private fun parseCourseBox(block: Element, day: Int): List<CoursePreview> {
        val fields = block.children()
        require(fields.size >= 5) { "yl 课程盒子字段不足" }
        val name = fields[0].text().trim()
        require(name.isNotEmpty()) { "yl 课程名为空" }
        val schedule = fields[1]
        require(schedule.childrenSize() >= 3) { "课程 $name 的时间字段不足" }
        val weekText = schedule.child(1).text().trim()
        require(weekText.isNotEmpty()) { "课程 $name 缺少周次" }
        val nodeText = schedule.child(2).text().trim()
        val nodes = TextUtils.requirePositiveRange(nodeText, "课程 $name 的节次")
        val room = fields[3].text().substringBefore('(').substringBefore('（').trim()
        val teacher = fields[4].text().trim()

        return WeekUtils.parse(weekText).map { week ->
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

    private const val TIMETABLE_CLASS_MARKER = "app-components-CourseTimeTable-styles-timeTable"
    private const val COURSE_BOX_CLASS_MARKER = "app-components-CourseTimeTable-styles-courseBox"
}
