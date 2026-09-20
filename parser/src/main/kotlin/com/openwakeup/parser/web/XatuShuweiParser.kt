package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.MttGridUtils
import org.jsoup.Jsoup

/** 西安工业大学及历史同型树维页面解析器，规范 type 为 `xatu_shuwei`。 */
object XatuShuweiParser : Parser {


    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val document = Jsoup.parse(input.text)
        val table = document.selectFirst("#kcb_container .wut_table")
            ?: throw ParserException.parse("西安工大树维页面中缺少 kcb_container MTT 课表")
        val rows = table.select("tbody tr")
        if (rows.size <= 1) {
            throw ParserException.parse("西安工大树维 MTT 课表中缺少课程行")
        }

        // 每个 type 的入口独立确认时间字段，禁止直接调用 UestcShuweiParser 或其他学校 Parser。
        if (table.select("td[data-role=item] .mtt_arrange_item .mtt_item_sksj").isEmpty()) {
            throw ParserException.parse("西安工大树维页面缺少 MTT 上课时间字段")
        }

        val courses = MttGridUtils.parseStandardRows(
            rows = rows.drop(1),
            sourceName = "西安工大树维",
        )
        if (courses.isEmpty()) throw ParserException.empty("西安工大树维课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("西安工大树维课表解析失败：${error.message}", error)
    }
}
