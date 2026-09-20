package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import org.jsoup.Jsoup

/** 北京电影学院研究生课表解析器。 */
object BfaPostParser : Parser {
    /** 解析 `#table` 内按星期分组的非空 `_nk` 课程格。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("table")
            ?: throw ParserException.parse("北京电影学院页面中缺少 table")
        val result = mutableListOf<CoursePreview>()
        table.getElementsByClass("pages-sec-student-scheme-pages-student-timetable-query-timetable-index-tableContent")
            .forEach { dayBlock ->
                dayBlock.select("td[_nk]:not([_nk=''])").forEachIndexed { index, cell ->
                    val children = cell.children()
                    require(children.size >= 2) { "北电研究生课程格结构不完整" }
                    val fields =
                        children[0].children().map { it.text().trim() } + children[1].text().trim()
                    require(fields.size >= 6) { "北电研究生课程字段不足" }
                    val weeks = TextUtils.requirePositiveRange(
                        fields[4].substringAfter('[', "").substringBefore(']', ""), "周次"
                    )
                    val nodes = TextUtils.requirePositiveRange(fields[5], "节次")
                    val day = index + 1
                    require(day in 1..7) { "北电研究生星期列超过 7 列" }
                    result += CoursePreview(
                        name = fields[1],
                        teacher = fields[3],
                        room = fields[4].substringBefore('[').trim(),
                        day = day,
                        startNode = nodes.first,
                        step = nodes.last - nodes.first + 1,
                        startWeek = weeks.first,
                        endWeek = weeks.last,
                    )
                    // 校区、备注、学分和作息字段在当前 CoursePreview 中无对应字段，明确不伪装保存。
                }
            }
        if (result.isEmpty()) throw ParserException.empty("北电研究生课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("北电研究生课表解析失败：${error.message}", error)
    }
}
