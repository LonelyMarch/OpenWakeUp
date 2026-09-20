package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 金智 `wut_table` 列表式课表解析器。 */
object JzParser : Parser {
    /** 解析每个 mtt_arrange_item，任何星期、节次或周次错误都不再默认第 1 项。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).selectFirst(".wut_table")
            ?: throw ParserException.parse("金智页面中缺少 wut_table")
        val result = mutableListOf<CoursePreview>()
        table.select(".mtt_arrange_item").forEach { item ->
            val name =
                item.selectFirst(".mtt_item_kcmc")?.ownText()?.trim()?.substringAfter(' ').orEmpty()
            require(name.isNotEmpty()) { "金智课程缺少名称" }
            val details = item.selectFirst(".mtt_item_room")?.text()?.split(',')?.map { it.trim() }
                ?: throw IllegalArgumentException("课程 $name 缺少时间地点")
            val dayIndex = details.indexOfFirst { it.startsWith("星期") || it.startsWith('周') }
            require(dayIndex > 0 && dayIndex + 1 < details.size) { "课程 $name 的星期位置无效" }
            val nodes = TextUtils.requirePositiveRange(details[dayIndex + 1], "节次")
            val room =
                if (details.size - dayIndex > 3) details[details.lastIndex - 1] else details.last()
            details.take(dayIndex).forEach { weekText ->
                WeekUtils.parse(weekText).forEach { week ->
                    result += CoursePreview(
                        name = name,
                        teacher = item.select(".mtt_item_jxbmc").text().trim(),
                        room = room,
                        day = TextUtils.requireDay(details[dayIndex]),
                        startNode = nodes.first,
                        step = nodes.last - nodes.first + 1,
                        startWeek = week.startWeek,
                        endWeek = week.endWeek,
                        type = week.type
                    )
                }
            }
        }
        if (result.isEmpty()) throw ParserException.empty("金智课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("金智课表解析失败：${error.message}", error)
    }
}
