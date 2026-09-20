package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** 广东第二师范学院课程块解析器。 */
object GdeiParser : Parser {
    /** 解析 tbody 每行星期列中一至两门固定五字段课程。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val body = Jsoup.parse(input.text).selectFirst("tbody")
            ?: throw ParserException.parse("广东第二师范学院页面中缺少表体")
        val result = mutableListOf<CoursePreview>()
        body.select("tr").forEach { row ->
            row.select("td").drop(1).forEachIndexed { index, cell ->
                val fields = cell.select("div div")
                if (fields.isEmpty()) return@forEachIndexed
                result += parseGroup(fields, 0, index + 1)
                if (fields.size > 5) result += parseGroup(fields, 5, index + 1)
            }
        }
        if (result.isEmpty()) throw ParserException.empty("广东第二师范学院课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("广东第二师范学院课表解析失败：${error.message}", error)
    }

    /** 从固定五元素分组中读取课程。 */
    private fun parseGroup(fields: List<Element>, offset: Int, day: Int): CoursePreview {
        require(fields.size >= offset + 5) { "广东第二师范学院课程字段不足" }
        val numbers =
            Regex("""\d+""").findAll(fields[offset + 1].text()).map { it.value.toInt() }.toList()
        require(numbers.size >= 4) { "课程时间字段必须含起止周和起止节次" }
        return CoursePreview(
            name = fields[offset].text(), teacher = fields[offset + 4].text(),
            room = fields[offset + 3].text().substringBeforeLast('(').trim(), day = day,
            startNode = numbers[2], step = numbers[3] - numbers[2] + 1,
            startWeek = numbers[0], endWeek = numbers[1],
        )
    }
}
