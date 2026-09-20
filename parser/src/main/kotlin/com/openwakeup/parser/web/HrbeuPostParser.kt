package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 哈尔滨工程大学研究生培养课表解析器。 */
object HrbeuPostParser : Parser {
    /** 解析 WtbodyZlistS 网格，合并节次和周次区间，并修复上游星期从 0 开始的问题。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("StuCul_TimetableQry_TimeTable")
            ?: throw ParserException.parse("哈工程研究生页面中缺少课程表")
        val body = table.selectFirst(".WtbodyZlistS")
            ?: throw ParserException.parse("课程表中缺少 WtbodyZlistS")
        val result = mutableListOf<CoursePreview>()
        body.select("tr").forEach { row ->
            row.select("td").drop(1).forEachIndexed { index, cell ->
                cell.html().trim().trimBr().split(Regex("(?i)<br\\s*/?>\\s*<br\\s*/?>"))
                    .filter { it.isNotBlank() }.forEach { block ->
                    val fields = block.split(Regex("(?i)<br\\s*/?>"))
                        .map { Jsoup.parse(it).text().substringAfter(':').trim() }
                    if (fields.size < 5) return@forEach
                    val nodes =
                        expandRanges(fields[2]); require(nodes.isNotEmpty()) { "课程节次为空" }
                    val teachers =
                        if (fields[1].contains("一班多师")) fields[3].split(';') else listOf(fields[3])
                    teachers.forEach { teacherItem ->
                        val weeks =
                            expandRanges(teacherItem); require(weeks.isNotEmpty()) { "课程周次为空" }
                        WeekUtils.compact(weeks).forEach { week ->
                            result += CoursePreview(
                                name = fields[0],
                                teacher = Regex("""[（(](.*?)[）)]""").find(teacherItem)?.groupValues?.get(
                                    1
                                )
                                    ?: fields[1].substringBeforeLast(' ').trim(),
                                room = fields[4],
                                day = index + 1,
                                startNode = nodes.min(),
                                step = nodes.max() - nodes.min() + 1,
                                startWeek = week.startWeek,
                                endWeek = week.endWeek,
                                type = week.type
                            )
                        }
                    }
                }
            }
        }
        if (result.isEmpty()) throw ParserException.empty("哈工程研究生课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("哈工程研究生课表解析失败：${error.message}", error)
    }

    /** 展开文本中的所有单值或范围。 */
    private fun expandRanges(text: String): List<Int> =
        Regex("""(\d+)(?:-(\d+))?""").findAll(text).flatMap { match ->
            val start = match.groupValues[1].toInt();
            val end = match.groupValues[2].ifEmpty { match.groupValues[1] }.toInt()
            (start..end).asSequence()
        }.distinct().sorted().toList()

    private fun String.trimBr(): String = replace(Regex("""^(?i:<br\s*/?>)|(?i:<br\s*/?>)$"""), "")
}
