package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.ZfTimeParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * `zf_new` 对应的新版正方卡片式课表解析器。
 *
 * 页面契约为 `table#table1`、节次类 `.festival`、课程标题 `.title`，以及 title 属性分别为
 * `教师`、`上课地点`、`节/周` 或 `周/节` 的段落。每门课程的临时状态都在当前循环内创建。
 */
object ZfNewParser : Parser {

    /**
     * 解析新版正方课表 HTML。
     *
     * @param input `text` 为完整新版正方课表页 HTML
     * @return 非空课程预览列表
     * @throws ParserException 页面指纹错误或任一课程缺少星期、节次、周次等必填字段
     */
    override fun parse(input: ParserInput): List<CoursePreview> {
        try {
            val document = Jsoup.parse(input.text)
            val table = document.getElementById("table1")
                ?: throw ParserException.parse("页面中没有新版正方课表表格（table1）")
            requireNewZfFingerprint(table)

            val result = mutableListOf<CoursePreview>()
            table.select("tr").forEach { row ->
                val rowNode = row.selectFirst(".festival")?.text()?.trim()?.toIntOrNull()
                val cells = row.children()
                    .filter { child -> child.tagName().equals("td", ignoreCase = true) }
                cells.forEach { cell ->
                    val courseTitles = cell.select(".title")
                    courseTitles.forEach { titleElement ->
                        val courseName = titleElement.text().trim()
                        if (courseName.isEmpty()) {
                            throw ParserException.parse("新版正方课表存在空课程名称")
                        }
                        val container = titleElement.parent()
                            ?: throw ParserException.parse("课程“$courseName”缺少课程容器")
                        val day = parseDayFromCell(cell)
                        val teacher = titledParagraphText(container, "教师")
                        val room = titledParagraphText(container, "上课地点")
                        val timeText = listOf("节/周", "周/节")
                            .flatMap { title -> titledParagraphTexts(container, title) }
                            .joinToString(";")
                            .takeIf(String::isNotBlank)
                            ?: throw ParserException.parse("课程“$courseName”缺少节次和周次")
                        val fallbackNode = rowNode
                            ?: throw ParserException.parse("课程“$courseName”所在行缺少 festival 节次")
                        val fallbackStep =
                            cell.attr("rowspan").toIntOrNull()?.takeIf { it > 0 } ?: 1
                        val times = try {
                            ZfTimeParser.parse(timeText, day, fallbackNode, fallbackStep)
                        } catch (error: IllegalArgumentException) {
                            throw ParserException.parse(
                                "课程“$courseName”的时间字段无法解析",
                                error
                            )
                        }
                        times.forEach { time ->
                            result += CoursePreview(
                                name = courseName,
                                teacher = teacher,
                                room = room,
                                day = time.day,
                                startNode = time.startNode,
                                step = time.step,
                                startWeek = time.week.startWeek,
                                endWeek = time.week.endWeek,
                                type = time.week.type,
                            )
                        }
                    }
                }
            }
            if (result.isEmpty()) throw ParserException.empty("新版正方课表中没有课程")
            return result
        } catch (error: ParserException) {
            throw error
        } catch (error: Exception) {
            throw ParserException.parse("新版正方课表解析失败", error)
        }
    }

    /** 从课程所在单元格 id 中读取星期数字。 */
    private fun parseDayFromCell(cell: Element): Int {
        val day = DAY_ID_PATTERN.find(cell.id())?.groupValues?.get(1)?.toIntOrNull()
        return day?.takeIf { it in 1..7 }
            ?: throw ParserException.parse("新版正方课程单元格缺少有效星期 id：${cell.id()}")
    }

    /** 读取课程容器中指定 title 的全部段落，并保留页面顺序。 */
    private fun titledParagraphText(container: Element, title: String): String =
        titledParagraphTexts(container, title).joinToString(" ")

    /** 读取课程容器中指定 title 的段落列表，供多个时间段分别解析。 */
    private fun titledParagraphTexts(container: Element, title: String): List<String> =
        container.select("p[title=\"$title\"]")
            .map { paragraph -> paragraph.text().trim() }
            .filter(String::isNotEmpty)

    /** 验证新版正方特有的节次和星期结构。 */
    private fun requireNewZfFingerprint(table: Element) {
        val text = table.text()
        if (table.select(".festival")
                .isEmpty() || !text.contains("星期一") || !text.contains("星期二")
        ) {
            throw ParserException.parse("页面中的 table1 不符合新版正方课表结构")
        }
    }

    private val DAY_ID_PATTERN = Regex("""(?:^|\D)([1-7])(?:\D|$)""")
}
