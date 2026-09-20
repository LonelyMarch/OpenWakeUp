package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.MttGridUtils
import org.jsoup.Jsoup

/** `xsyu_shuwei` 使用的树维 MTT 行式课表解析器。 */
object XsyuShuweiParser : Parser {

    /**
     * 独立校验西安石油大学树维页面，再调用无学校状态的 MTT 网格算法。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面并非预期 MTT 课表、课程为空或字段不完整
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).selectFirst("#kcb_container .wut_table")
            ?: throw ParserException.parse("西安石油大学页面中缺少 kcb_container MTT 课表")
        val rows = table.select("tbody tr")
        if (rows.size <= 1) throw ParserException.parse("西安石油大学 MTT 课表中缺少课程行")
        if (table.select("td[data-role=item] .mtt_arrange_item .mtt_item_sksj").isEmpty()) {
            throw ParserException.parse("西安石油大学页面缺少 MTT 上课时间字段")
        }

        val courses = MttGridUtils.parseStandardRows(
            rows = rows.drop(1),
            sourceName = "西安石油大学树维",
        )
        if (courses.isEmpty()) throw ParserException.empty("西安石油大学课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("西安石油大学课表解析失败：${error.message}", error)
    }
}
