package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import org.jsoup.Jsoup


object JxauParser : Parser {

    /**
     * 解析按星期分行、每格两节的课程表。
     *
     * @param input 完整课表 HTML
     * @return 每个课程时间段对应的课程预览
     * @throws ParserException 页面结构、星期、节次或周次字段不符合预期
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("KebiaoTable1")
            ?: throw ParserException.parse("江西农业大学页面中缺少 KebiaoTable1")
        val rows = table.select("tr")
        if (rows.size < 9) throw ParserException.parse("江西农业大学课程表行数不足")
        val courses = mutableListOf<CoursePreview>()
        rows.subList(2, 9).forEachIndexed { rowIndex, row ->
            val dayText = row.selectFirst(".left1")?.text().orEmpty()
            val day = parseDay(dayText)
            require(day in 1..7) { "第 ${rowIndex + 3} 行星期无法识别：$dayText" }
            val cells = row.select("td").drop(1)
            var startNode = 1
            cells.forEachIndexed { cellIndex, cell ->
                // 上游页面的第 5、7 个数据格是非课程占位，不消耗节次编号。
                if (cellIndex == 4 || cellIndex == 6) return@forEachIndexed
                cell.select("dl").forEach { block ->
                    val name = block.selectFirst("a")?.text()?.trim().orEmpty()
                    val details = block.select("dd")
                    val room = block.selectFirst("b")?.text()?.trim().orEmpty()
                    require(name.isNotEmpty() && details.size >= 2) { "周$day 第 $startNode 节课程字段不完整" }
                    parseWeeks(details[1].text()).forEach { range ->
                        courses += CoursePreview(
                            name = name,
                            teacher = details[0].text().trim(),
                            room = room,
                            day = day,
                            startNode = startNode,
                            step = 2,
                            startWeek = range.first,
                            endWeek = range.last,
                        )
                    }
                }
                startNode += 2
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("江西农业大学课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("江西农业大学课表解析失败：${error.message}", error)
    }

    /**
     * 将星期标题转换为 1～7。
     *
     * @param text 星期标题，例如 `星期一`
     * @return 星期编号，无法识别时返回 -1
     */
    private fun parseDay(text: String): Int =
        when (text.removePrefix("星期").removePrefix("周").trim()) {
            "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5; "六" -> 6
            "日", "天" -> 7
            else -> -1
        }

    /**
     * 解析逗号分隔的单周或连续周范围。
     *
     * @param text 原始周次字段，括号后的备注会被忽略
     * @return 一个或多个闭区间周次
     */
    private fun parseWeeks(text: String): List<IntRange> = text.substringBefore('(')
        .replace('，', ',')
        .split(',')
        .filter { it.isNotBlank() }
        .map { part ->
            val bounds = part.trim().split('-', '－', '—').map { it.trim() }
            require(bounds.size in 1..2) { "周次格式无效：$part" }
            val start = bounds.first().toIntOrNull()
            val end = bounds.last().toIntOrNull()
            require(start != null && end != null && start > 0 && end >= start) { "周次范围无效：$part" }
            start..end
        }
        .also { require(it.isNotEmpty()) { "周次字段为空" } }
}
