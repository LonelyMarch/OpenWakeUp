package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** `fdu` 使用的个人数据中心课程明细表解析器。 */
object FduParser : Parser {

    /**
     * 解析 `table.table-hover` 中支持 rowspan 式课程名续行的课程记录。
     *
     * 六列行提供新的课程名，后续较短行沿用上一课程名；每行尾部固定给出地点、星期节次和
     * 周次。离散周、连续周与单双周统一交给 [WeekUtils] 无损转换。
     *
     * @param input 本科或研究生个人数据中心的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 表格、续行关系、星期、节次或周次无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).selectFirst("table.table-hover")
            ?: throw ParserException.parse("fdu 页面中缺少 table.table-hover")
        val body = table.selectFirst("tbody")
            ?: throw ParserException.parse("fdu 课程表缺少 tbody")
        val courses = mutableListOf<CoursePreview>()
        var currentName = ""
        body.select("tr").forEachIndexed { rowIndex, row ->
            val cells = row.select("td")
            if (cells.isEmpty()) return@forEachIndexed
            require(cells.size >= 4) { "fdu 第 ${rowIndex + 1} 行字段不足" }
            if (cells.size == 6) currentName = cells[1].text().trim()
            require(currentName.isNotEmpty()) { "fdu 第 ${rowIndex + 1} 行缺少可继承的课程名" }

            val room = cells[cells.size - 4].text().trim()
            val schedule = cells[cells.size - 3].text().trim()
            val weekText = cells[cells.size - 2].text().trim()
            if (schedule.isEmpty() || weekText.isEmpty()) return@forEachIndexed
            val dayMatch = DAY_PATTERN.find(schedule)
                ?: throw IllegalArgumentException("课程 $currentName 缺少星期：$schedule")
            val day = TextUtils.requireDay(dayMatch.value)
            val nodeText = schedule.substringAfter('第', "").substringBefore('节', "").trim()
            val nodes = TextUtils.requirePositiveRange(nodeText, "课程 $currentName 的节次")
            WeekUtils.parse(weekText).forEach { week ->
                courses += CoursePreview(
                    name = currentName,
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
        if (courses.isEmpty()) throw ParserException.empty("fdu 页面中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("fdu 课表解析失败：${error.message}", error)
    }

    private val DAY_PATTERN = Regex("星期[一二三四五六日天]")
}
