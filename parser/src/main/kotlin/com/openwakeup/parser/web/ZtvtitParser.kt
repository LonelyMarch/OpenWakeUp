package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 浙江交通职业技术学院 scheduleTable 解析器，规范 type 保留为 `ztvtit`。 */
object ZtvtitParser : Parser {
    /** 直接生成课程片段并合并属性相同的相邻节次，不使用中间数据库 Bean。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text)
            .selectFirst("table.scheduleTable.table.table-bordered.table-hover")
            ?: throw ParserException.parse("浙江交职院页面中缺少 scheduleTable")
        val fragments = mutableListOf<CoursePreview>()
        table.select("[week]").forEach { row ->
            val day = TextUtils.requireDay(row.selectFirst("td")?.attr("week").orEmpty())
            val node = row.selectFirst("[lesson]")?.attr("lesson")?.toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("浙江交职院课程行缺少 lesson")
            row.select(".courseInfo").filter { it.text().isNotBlank() }.forEach { info ->
                val name = info.select("span:not([class])").text().trim()
                require(name.isNotEmpty()) { "浙江交职院课程缺少名称" }
                val weeksText = info.selectFirst(".WeekDetail")?.text().orEmpty()
                WeekUtils.parse(weeksText).forEach { week ->
                    fragments += CoursePreview(
                        name = name,
                        teacher = info.select(".teacher").text(),
                        room = info.select(".place").text(),
                        day = day,
                        startNode = node,
                        startWeek = week.startWeek,
                        endWeek = week.endWeek,
                        type = week.type
                    )
                }
            }
        }
        val result = mergeAdjacent(fragments)
        if (result.isEmpty()) throw ParserException.empty("浙江交职院课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("浙江交职院课表解析失败：${error.message}", error)
    }

    /** 合并同一课程连续节次。 */
    private fun mergeAdjacent(courses: List<CoursePreview>): List<CoursePreview> {
        val result = mutableListOf<CoursePreview>()
        courses.sortedWith(compareBy({ it.day }, { it.startNode })).forEach { course ->
            val previous = result.lastOrNull()
            if (previous != null && previous.copy(startNode = course.startNode) == course && previous.startNode + previous.step == course.startNode) {
                result[result.lastIndex] = previous.copy(step = previous.step + 1)
            } else result += course
        }
        return result
    }
}
