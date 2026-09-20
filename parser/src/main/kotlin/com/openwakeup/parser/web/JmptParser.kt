package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** `jmpt` 使用的第二张 `.admintable` 课程明细解析器。 */
object JmptParser : Parser {

    /**
     * 解析带星期 rowspan 的课程明细行。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 目标表格缺失、续行没有星期上下文或时间字段无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val tables = Jsoup.parse(input.text).getElementsByClass("admintable")
        if (tables.size < 2) throw ParserException.parse("江门职院页面中缺少第二张 admintable 课表")
        val courses = mutableListOf<CoursePreview>()
        var currentDay: Int? = null

        tables[1].select("tr").forEach { row ->
            val cells = row.select("td")
            // 原页面的说明行和表头不足 11 列，不属于课程记录。
            if (cells.size < 11) return@forEach
            val hasDayCell = cells[0].hasAttr("rowspan")
            val offset = if (hasDayCell) 1 else 0
            if (hasDayCell) currentDay = TextUtils.requireDay(cells[0].text().trim())
            val day = currentDay
                ?: throw IllegalArgumentException("江门职院课程续行缺少星期上下文")
            val startNodeText = cells[offset + 1].text().trim()
            if (startNodeText.isEmpty()) return@forEach
            val startNode = startNodeText.toIntOrNull()
                ?: throw IllegalArgumentException("江门职院起始节次不是整数：$startNodeText")
            val endNodeText = cells[offset + 2].text().trim()
            val endNode = endNodeText.toIntOrNull()
                ?: throw IllegalArgumentException("江门职院结束节次不是整数：$endNodeText")
            require(startNode > 0 && endNode >= startNode) { "江门职院节次范围无效" }

            val name = cells[offset + 3].text().trim()
            require(name.isNotEmpty()) { "江门职院课程名称为空" }
            val weekText = cells[offset].text().trim()
            require(weekText.isNotEmpty()) { "课程 $name 缺少明确周次" }
            val weekType = parseWeekType(cells[offset + 8].text().trim())
            val weekSuffix = when (weekType) {
                1 -> "单"
                2 -> "双"
                else -> ""
            }
            val weekParts = weekText.split(WEEK_SEPARATOR_PATTERN).filter { it.isNotBlank() }
            require(weekParts.isNotEmpty()) { "课程 $name 的周次字段为空" }

            weekParts.forEach { part ->
                // 单双周由独立列决定，先去除周次文本中可能残留的旧标记，避免冲突。
                val normalized = part.substringBefore('(').substringBefore('（')
                    .replace("单", "").replace("双", "").trim() + weekSuffix
                WeekUtils.parse(normalized).forEach { week ->
                    courses += CoursePreview(
                        name = name,
                        teacher = cells[offset + 5].text().trim(),
                        room = cells[offset + 6].text().trim(),
                        day = day,
                        startNode = startNode,
                        step = endNode - startNode + 1,
                        startWeek = week.startWeek,
                        endWeek = week.endWeek,
                        type = week.type,
                    )
                }
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("江门职院课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("江门职院课表解析失败：${error.message}", error)
    }

    /**
     * 把独立单双周说明转换为周类型；“双周不上”等价于只在单周上课，反之亦然。
     */
    private fun parseWeekType(source: String): Int = when {
        source.contains('双') && source.contains('不') -> 1
        source.contains('双') -> 2
        source.contains('单') && source.contains('不') -> 2
        source.contains('单') -> 1
        else -> 0
    }

    private val WEEK_SEPARATOR_PATTERN = Regex("[,，、\\\\]+")
}
