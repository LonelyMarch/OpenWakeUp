package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 辽宁轨道交通职业学院新旧 React 课表结构解析器。 */
object LngdParser : Parser {
    /** 识别 root 哈希类名或 wdkb-kb 稳定类名结构，统一解析课程详情文本。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val document = Jsoup.parse(input.text)
        val dayColumns =
            document.select("#root [class*=TimetableDayColumnRoot], #wdkb-kb .kbappTimetableDayColumnRoot")
        if (dayColumns.isEmpty()) throw ParserException.parse("辽宁轨道职院页面中缺少星期列")
        val result = mutableListOf<CoursePreview>()
        dayColumns.forEachIndexed { dayIndex, day ->
            day.select("[class*=TimetableCourseRenderCourseItem]").forEach { item ->
                val title = item.selectFirst("[class*=title]")?.text().orEmpty()
                val name =
                    title.substringBefore(' ').trim(); require(name.isNotEmpty()) { "课程缺少名称" }
                val details = item.select("[class*=TimetableCourseRenderCourseItemInfoText]")
                    .map { it.text().trim() }.filter { it.isNotEmpty() }
                details.drop(1).forEach { text ->
                    val nodeMatch = Regex("""第(\d+)节-第(\d+)节""").find(text) ?: return@forEach
                    val weeks =
                        Regex("""\d+(?:-\d+)?周""").findAll(text).joinToString(",") { it.value }
                    require(weeks.isNotEmpty()) { "课程 $name 缺少周次" }
                    WeekUtils.parse(weeks).forEach { week ->
                        result += CoursePreview(
                            name,
                            teacher = Regex("""周([\u4e00-\u9fa5]+)""").find(text)?.groupValues?.get(
                                1
                            ).orEmpty(),
                            room = text.split(Regex("\\s+")).lastOrNull().orEmpty(),
                            day = dayIndex + 1,
                            startNode = nodeMatch.groupValues[1].toInt(),
                            step = nodeMatch.groupValues[2].toInt() - nodeMatch.groupValues[1].toInt() + 1,
                            startWeek = week.startWeek,
                            endWeek = week.endWeek,
                            type = week.type
                        )
                    }
                }
            }
        }
        if (result.isEmpty()) throw ParserException.empty("辽宁轨道职院课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("辽宁轨道职院课表解析失败：${error.message}", error)
    }
}
