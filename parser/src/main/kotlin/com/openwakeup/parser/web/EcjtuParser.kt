package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import org.jsoup.Jsoup

/** 华东交通大学 `table_border` 课表解析器。 */
object EcjtuParser : Parser {
    /** 以每个末尾为节次数字的详情行为边界拆分同格多课程。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).selectFirst("table.table_border")
            ?: throw ParserException.parse("华东交大页面中缺少 table_border")
        val result = mutableListOf<CoursePreview>()
        table.select("tr").forEach { row ->
            val cells = row.select("td")
            if (cells.size != 8 || cells[0].text().trim() == "节次") return@forEach
            cells.drop(1).forEachIndexed { index, cell ->
                val lines =
                    cell.html().split(Regex("(?i)<br\\s*/?>")).map { Jsoup.parse(it).text().trim() }
                        .filter { it.isNotEmpty() }
                var nameIndex = 0
                lines.forEachIndexed { lineIndex, line ->
                    if (line.contains('@') || !line.last().isDigit()) return@forEachIndexed
                    require(nameIndex < lineIndex) { "华东交大课程详情前缺少名称" }
                    val fields = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    require(fields.size >= 2) { "课程详情字段不足：$line" }
                    val weeksText = fields[fields.lastIndex - 1]
                    val weeks = TextUtils.requirePositiveRange(weeksText, "周次")
                    val nodes =
                        TextUtils.requirePositiveRange(fields.last().replace(',', '-'), "节次")
                    val teacherRoom =
                        lines.getOrNull(lineIndex - 1).orEmpty().takeIf { it.contains('@') }
                    result += CoursePreview(
                        name = lines[nameIndex],
                        teacher = teacherRoom?.substringBefore('@')?.trim().orEmpty(),
                        room = teacherRoom?.substringAfter('@')?.trim().orEmpty(),
                        day = index + 1,
                        startNode = nodes.first,
                        step = nodes.last - nodes.first + 1,
                        startWeek = weeks.first,
                        endWeek = weeks.last,
                        type = when {
                            weeksText.contains('单') -> 1; weeksText.contains('双') -> 2; else -> 0
                        },
                    )
                    nameIndex = lineIndex + 1
                }
            }
        }
        if (result.isEmpty()) throw ParserException.empty("华东交大课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("华东交大课表解析失败：${error.message}", error)
    }
}
