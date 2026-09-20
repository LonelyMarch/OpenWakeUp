package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 黑龙江大学 `ivu-table-tbody` 课程块解析器。 */
object HljuParser : Parser {
    /** 解析每个 `codedd-wrap` 的紧凑课程描述。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val body = Jsoup.parse(input.text).selectFirst(".ivu-table-tbody")
            ?: throw ParserException.parse("黑龙江大学页面中缺少 ivu-table-tbody")
        val result = mutableListOf<CoursePreview>()
        body.select("tr").forEach { row ->
            row.select("td").forEachIndexed { day, cell ->
                cell.select("div.codedd-wrap")
                    .forEach { block -> result += parseBlock(day, block.text()) }
            }
        }
        if (result.isEmpty()) throw ParserException.empty("黑龙江大学课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("黑龙江大学课表解析失败：${error.message}", error)
    }

    /** 从方括号字段中读取教师、教室、周次和节次。 */
    private fun parseBlock(day: Int, text: String): List<CoursePreview> {
        require(day in 1..7) { "黑龙江大学课程所在星期列无效：$day" }
        val fields = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        require(fields.size >= 4) { "黑龙江大学课程字段不足：$text" }
        val name = fields[0].substringBeforeLast('【')
        val teacher = fields[1].substringAfter('[').substringBeforeLast(']')
        val room = fields[2].substringAfter("][", "").substringBeforeLast(']')
        val weekText = fields[2].substringAfter('[').substringBeforeLast('周')
        val nodes = TextUtils.requirePositiveRange(
            fields[3].substringAfter('第').substringBeforeLast('节'),
            "节次"
        )
        return WeekUtils.parse(weekText).map { week ->
            CoursePreview(
                name = name, teacher = teacher, room = room, day = day,
                startNode = nodes.first, step = nodes.last - nodes.first + 1,
                startWeek = week.startWeek, endWeek = week.endWeek, type = week.type
            )
        }
    }
}
