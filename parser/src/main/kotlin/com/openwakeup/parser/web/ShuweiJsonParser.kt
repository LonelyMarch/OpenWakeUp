package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.MttGridUtils
import org.jsoup.Jsoup

/** `shuwei_json` 使用的树维 MTT 行式课表解析器。 */
object ShuweiJsonParser : Parser {


    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).selectFirst("#kcb_container .wut_table")
            ?: throw ParserException.parse("shuwei_json 页面中缺少 kcb_container MTT 课表")
        val rows = table.select("tbody tr")
        if (rows.size <= 1) throw ParserException.parse("shuwei_json MTT 课表中缺少课程行")
        if (table.select("td[data-role=item] .mtt_arrange_item .mtt_item_sksj").isEmpty()) {
            throw ParserException.parse("shuwei_json 页面缺少 MTT 上课时间字段")
        }

        val courses = MttGridUtils.parseStandardRows(
            rows = rows.drop(1),
            sourceName = "shuwei_json",
        )
        if (courses.isEmpty()) throw ParserException.empty("shuwei_json 课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("shuwei_json 课表解析失败：${error.message}", error)
    }
}
