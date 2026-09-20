package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 广西工业职业技术学院固定两节组课表解析器。 */
object GxicParser : Parser {
    /** 解析 `width=98%` 表格中 a[title] 携带的教师、地点和周次。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).selectFirst("table[width=98%]")
            ?: throw ParserException.parse("广西工职院页面中缺少目标课表")
        val result = mutableListOf<CoursePreview>()
        table.select("tr").forEach { row ->
            val cells = row.select("td")
            if (cells.isEmpty()) return@forEach
            val startNode = NODE_MAP[cells[0].text().trim()] ?: return@forEach
            cells.drop(1).forEachIndexed { index, cell ->
                cell.select("a[title]").forEach { link ->
                    val title = link.attr("title")
                    val name =
                        link.ownText().ifBlank { link.text().substringBefore("授课教师").trim() }
                    require(name.isNotEmpty()) { "广西工职院课程缺少名称" }
                    val weekText =
                        title.substringAfter("上课周次：", "").substringBefore('\n').trim()
                    require(weekText.isNotEmpty()) { "课程 $name 缺少周次" }
                    WeekUtils.parse(weekText).forEach { week ->
                        result += CoursePreview(
                            name = name,
                            teacher = title.substringAfter("授课教师：", "").substringBefore('\n')
                                .trim(),
                            room = title.substringAfter("开课地点：", "").substringBefore('\n')
                                .trim(),
                            day = index + 1, startNode = startNode, step = 2,
                            startWeek = week.startWeek, endWeek = week.endWeek, type = week.type
                        )
                    }
                }
            }
        }
        if (result.isEmpty()) throw ParserException.empty("广西工职院课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("广西工职院课表解析失败：${error.message}", error)
    }

    private val NODE_MAP =
        mapOf("第0102节" to 1, "第0304节" to 3, "第0506节" to 5, "第0708节" to 7, "第0910节" to 9)
}
