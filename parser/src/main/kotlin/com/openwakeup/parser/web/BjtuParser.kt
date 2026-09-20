package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import org.jsoup.Jsoup

/** 北京交通大学文本型边框表格解析器。 */
object BjtuParser : Parser {
    /** 解析每个节次行的七个星期单元格；缺少周界时不再默认 1～16 周。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).selectFirst("table.table.table-bordered")
            ?: throw ParserException.parse("北京交通大学页面中缺少边框课表")
        val result = mutableListOf<CoursePreview>()
        table.select("tr").forEachIndexed { rowIndex, row ->
            var day = 0
            row.select("td").forEachIndexed { cellIndex, cell ->
                if (cellIndex == 0) return@forEachIndexed
                day++
                COURSE_PATTERN.findAll(cell.text().trim()).forEach { match ->
                    val value = match.value
                    val nameMatch = NAME_PATTERN.find(value)
                        ?: throw IllegalArgumentException("北交大课程名无法识别：$value")
                    val startWeek = START_WEEK_PATTERN.find(value)?.groupValues?.get(1)?.toInt()
                        ?: throw IllegalArgumentException("北交大课程缺少开始周：$value")
                    val endWeek = END_WEEK_PATTERN.find(value)?.groupValues?.get(1)?.toInt()
                        ?: throw IllegalArgumentException("北交大课程缺少结束周：$value")
                    result += CoursePreview(
                        name = nameMatch.groupValues[1].trim(),
                        teacher = TEACHER_PATTERN.find(value)?.groupValues?.get(1).orEmpty(),
                        room = ROOM_PATTERN.find(value)?.value.orEmpty(), day = day,
                        startNode = rowIndex, startWeek = startWeek, endWeek = endWeek,
                        type = if (value.contains(',')) if (endWeek % 2 == 0) 2 else 1 else 0,
                    )
                }
            }
        }
        if (result.isEmpty()) throw ParserException.empty("北京交通大学课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("北京交通大学课表解析失败：${error.message}", error)
    }

    private val COURSE_PATTERN = Regex("""\w{7}\s\S+\s[^\[]+\[.]\s[^A-Z]+\w{5}""")
    private val NAME_PATTERN = Regex("""\]\s(.+?)\s\[""")
    private val ROOM_PATTERN = Regex("""[A-Z]{2}\d{3}""")
    private val TEACHER_PATTERN = Regex("""\d{2}周\s+(\S+)""")
    private val START_WEEK_PATTERN = Regex("""\]\s第(\d{2})""")
    private val END_WEEK_PATTERN = Regex("""(\d{2})周""")
}
