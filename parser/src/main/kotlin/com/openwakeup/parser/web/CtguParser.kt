package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import org.jsoup.Jsoup

/** 三峡大学字段化表格解析器。 */
object CtguParser : Parser {
    /** 解析第四个 tbody 中带 field 属性的课程行。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val bodies = Jsoup.parse(input.text).select("tbody")
        require(bodies.size >= 4) { "三峡大学页面少于四个表体" }
        val result = bodies[3].select("tr").map { row ->
            fun field(name: String): String =
                row.selectFirst("td[field=$name]")?.children()?.firstOrNull()?.text()?.trim()
                    ?: throw IllegalArgumentException("三峡大学课程行缺少字段 $name")

            val timeParts = field("sjms").split(Regex("\\s+")).filter { it.isNotEmpty() }
            require(timeParts.size >= 3) { "三峡大学上课时间字段不足" }
            val nodes = TextUtils.requirePositiveRange(timeParts[2], "节次")
            val weeks = TextUtils.requirePositiveRange(field("ksz"), "周次")
            CoursePreview(
                name = field("kcmc"), teacher = field("rkjsxm"), room = field("dz"),
                day = TextUtils.requireDay(timeParts[1]), startNode = nodes.first,
                step = nodes.last - nodes.first + 1, startWeek = weeks.first, endWeek = weeks.last,
                type = when (timeParts[0]) {
                    "连续周" -> 0; "单周" -> 1; "双周" -> 2; else -> error("未知周类型：${timeParts[0]}")
                },
            )
        }
        if (result.isEmpty()) throw ParserException.empty("三峡大学课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("三峡大学课表解析失败：${error.message}", error)
    }
}
