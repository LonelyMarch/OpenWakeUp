package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 强智 2017 的 Element UI 网格解析器。 */
object Qz2017Parser : Parser {
    /** 解析 `el-table__header` 与 `el-table__body`，并读取每个课程子块。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val document = Jsoup.parse(input.text)
        val header = document.selectFirst(".el-table__header")
            ?: throw ParserException.parse("强智 2017 页面中缺少表头")
        val body = document.selectFirst(".el-table__body tbody")
            ?: throw ParserException.parse("强智 2017 页面中缺少表体")
        val headerTexts = header.select("div").map { it.text() }
        val sundayFirst = headerTexts.indexOfFirst { it.contains("星期日") } >= 0 &&
                headerTexts.indexOfFirst { it.contains("星期日") } < headerTexts.indexOfFirst {
            it.contains(
                "星期一"
            )
        }
        val rows = body.select("tr")
        val courses = mutableListOf<CoursePreview>()
        rows.forEachIndexed { rowIndex, row ->
            row.select(".cell[style=text-align: center;]").forEachIndexed { columnIndex, cell ->
                cell.children().forEach { block ->
                    val children = block.children()
                    val weekIndex = children.indexOfLast { WEEK_MARKER.containsMatchIn(it.text()) }
                    require(weekIndex >= 3 && weekIndex + 1 < children.size) { "强智 2017 课程块字段不足" }
                    val time = children[weekIndex].text().trim()
                    val explicitNodes = NODE_PATTERN.find(time)?.groupValues?.get(1)
                    val nodeRange = explicitNodes?.let { parseNodeRange(it) } ?: run {
                        val start = rowIndex * 2 + 1
                        start..(if (rowIndex == rows.lastIndex) start + 2 else start + 1)
                    }
                    val weekText = time.substringAfter('(', "").substringBefore('周', "")
                    require(weekText.isNotBlank()) { "强智 2017 时间字段缺少周次：$time" }
                    val day = if (sundayFirst) {
                        if (columnIndex == 0) 7 else columnIndex
                    } else columnIndex + 1
                    WeekUtils.parse(weekText).forEach { week ->
                        courses += CoursePreview(
                            name = children[weekIndex - 3].text().trim(),
                            teacher = children[weekIndex - 2].text().trim(),
                            room = children[weekIndex + 1].text().trim(),
                            day = day,
                            startNode = nodeRange.first,
                            step = nodeRange.last - nodeRange.first + 1,
                            startWeek = week.startWeek,
                            endWeek = week.endWeek,
                            type = when {
                                time.contains('单') && !time.contains("单双") -> 1; time.contains(
                                    '双'
                                ) && !time.contains("单双") -> 2; else -> week.type
                            },
                        )
                    }
                }
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("强智 2017 课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("强智 2017 课表解析失败：${error.message}", error)
    }

    /** 解析明确单节或范围节次。 */
    private fun parseNodeRange(text: String): IntRange {
        val values = Regex("""\d+""").findAll(text).map { it.value.toInt() }.toList()
        require(values.size in 1..2) { "强智 2017 节次范围无效：$text" }
        return values.first()..values.last()
    }

    private val WEEK_MARKER = Regex("""\d+(?:-\d+)?(?:单|双)?周""")
    private val NODE_PATTERN = Regex("""第?\[?(\d+(?:-\d+)?)\]?节""")
}
