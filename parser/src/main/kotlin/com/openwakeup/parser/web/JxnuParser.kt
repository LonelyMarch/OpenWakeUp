package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 江西师范大学 `_ctl1_NewKcb` 旧课表解析器。 */
object JxnuParser : Parser {
    /** 解析绿色课程格；页面不含明确周次时以可解释错误失败。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("_ctl1_NewKcb")
            ?: throw ParserException.parse("江西师大页面中缺少 _ctl1_NewKcb")
        val result = mutableListOf<CoursePreview>()
        table.select("tr").drop(1).forEachIndexed { index, row ->
            val sourceRow = index + 1
            val startNode = START_NODE[sourceRow] ?: return@forEachIndexed
            row.select("td[bgcolor=#66FFCC]").forEach { cell ->
                var day = row.select("td").indexOf(cell)
                if (sourceRow == 1 || sourceRow == 6) day--
                require(day in 1..7) { "江西师大课程星期列无效" }
                val match = COURSE_PATTERN.find(cell.html()) ?: error("江西师大课程格结构无效")
                val weekText = WEEK_PATTERN.find(cell.text())?.value
                    ?: throw IllegalArgumentException("课程 ${match.groupValues[1].trim()} 缺少明确周次，不能沿用固定 1～20 周")
                WeekUtils.parse(weekText).forEach { week ->
                    result += CoursePreview(
                        name = match.groupValues[1].trim(),
                        room = match.groupValues[2].trim(),
                        teacher = Jsoup.parse(match.groupValues[3]).text().replace(" ", "").trim(),
                        day = day,
                        startNode = startNode,
                        step = if (startNode in setOf(1, 6, 8, 10)) 2 else 1,
                        startWeek = week.startWeek,
                        endWeek = week.endWeek,
                        type = week.type
                    )
                }
            }
        }
        if (result.isEmpty()) throw ParserException.empty("江西师大课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("江西师大课表解析失败：${error.message}", error)
    }

    private val START_NODE = mapOf(1 to 1, 2 to 3, 3 to 4, 4 to 5, 6 to 6, 7 to 8, 8 to 10)
    private val COURSE_PATTERN = Regex(
        """>\s*(.*?)\s*<br\s*/?>\((.*?)\)\s*<br\s*/?>(.*?)\s*</div>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val WEEK_PATTERN = Regex("""\d+(?:-\d+)?(?:单|双)?周""")
}
