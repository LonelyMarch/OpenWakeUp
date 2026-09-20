package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** `hbmzu` 使用的 `.list_table.tac` 双节行式课表解析器。 */
object HbmzuParser : Parser {

    /**
     * 依次检查由头标签分隔的页面片段，并采用第一张能够产出课程的目标表格。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹缺失、课程字段不足或时间范围无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        var tableFound = false
        HEAD_TAG_PATTERN.split(input.text).forEach { fragment ->
            val table = Jsoup.parse(fragment).selectFirst(".list_table.tac") ?: return@forEach
            tableFound = true
            val courses = parseTable(table.select("tr"))
            if (courses.isNotEmpty()) return courses
        }
        if (!tableFound) throw ParserException.parse("湖北民大页面中缺少 list_table tac 课表")
        throw ParserException.empty("湖北民大课表中没有课程")
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("湖北民大课表解析失败：${error.message}", error)
    }

    /**
     * 将行号映射为连续双节，将单元格序号映射为星期，并解析其中的每个 `p` 课程块。
     *
     * @param rows 目标表格中的全部行；空表头仍计入原版行号语义
     */
    private fun parseTable(rows: Iterable<org.jsoup.nodes.Element>): List<CoursePreview> {
        val courses = mutableListOf<CoursePreview>()
        rows.forEachIndexed { rowIndex, row ->
            val startNode = rowIndex * 2 + 1
            val endNode = startNode + 1
            row.select("td").forEachIndexed { columnIndex, cell ->
                val day = columnIndex + 1
                cell.select("p").forEach { block ->
                    if (block.text().isBlank()) return@forEach
                    require(day in 1..7) { "湖北民大课表星期列超过 7 列" }
                    val spans = block.select("span")
                    require(spans.size >= 4) { "湖北民大课程块字段不足" }
                    val name = spans[0].text().trim()
                    require(name.isNotEmpty()) { "湖北民大课程名称为空" }
                    val weekText = spans[1].text().trim()
                    require(weekText.isNotEmpty()) { "课程 $name 缺少明确周次" }

                    weekText.split(',', '，').filter { part -> part.isNotBlank() }.forEach { part ->
                        val parity = when {
                            part.contains('单') && !part.contains('双') -> "单"
                            part.contains('双') && !part.contains('单') -> "双"
                            else -> ""
                        }
                        // 括号后可能是单双周或学时说明；先保留 parity，再移除非周次附注。
                        val normalizedWeek =
                            part.substringBefore('(').substringBefore('（').trim() + parity
                        WeekUtils.parse(normalizedWeek).forEach { week ->
                            courses += CoursePreview(
                                name = name,
                                teacher = spans[3].text().trim(),
                                room = spans[2].text().trim(),
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
            }
        }
        return courses
    }

    private val HEAD_TAG_PATTERN = Regex("(?i)</?head>")
}
