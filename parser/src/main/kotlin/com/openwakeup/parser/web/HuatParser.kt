package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import org.jsoup.Jsoup

/** 湖北汽车工业学院横向课程表解析器。 */
object HuatParser : Parser {
    /** 解析 `CourseTable` 的五个时段行；缺少教师或教室时保留空值而非虚构“未知”。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("ctl00_ContentPlaceHolder1_CourseTable")
            ?: throw ParserException.parse("湖北汽院页面中缺少 CourseTable")
        val result = mutableListOf<CoursePreview>()
        table.select("[valign=middle]").forEachIndexed { period, row ->
            row.select("td > a:first-of-type").forEachIndexed { dayIndex, link ->
                result += parseCourse(link.parent()?.text().orEmpty(), period, dayIndex + 1)
            }
        }
        if (result.isEmpty()) throw ParserException.empty("湖北汽院课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("湖北汽院课表解析失败：${error.message}", error)
    }

    /** 根据尾部 `开始 - 结束周` 和时段行还原课程字段。 */
    private fun parseCourse(text: String, period: Int, day: Int): CoursePreview {
        val fields = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        require(fields.size >= 5) { "湖北汽院课程字段不足：$text" }
        val weeks = TextUtils.requirePositiveRange(fields.takeLast(3).joinToString(" "), "周次")
        val startNode = if (period == 4) 9 else period * 2 + 1
        val step = if (period == 4) 3 else 2
        val middle = fields.subList(1, fields.size - 3)
        val hourIndex = middle.indexOfFirst { it.endsWith('H') }
        val room =
            middle.getOrNull(hourIndex + 1)?.takeIf { it.matches(Regex("""\d{4}""")) }.orEmpty()
        val teacher = middle.take(if (hourIndex >= 0) hourIndex else middle.size).joinToString(" ")
        return CoursePreview(
            fields[0], teacher = teacher, room = room, day = day,
            startNode = startNode, step = step, startWeek = weeks.first, endWeek = weeks.last
        )
    }
}
