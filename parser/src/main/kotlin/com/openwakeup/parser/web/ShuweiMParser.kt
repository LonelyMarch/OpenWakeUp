package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.MttGridUtils
import org.jsoup.Jsoup

/** `shuwei_m` 使用的树维移动入口 MTT 课表解析器。 */
object ShuweiMParser : Parser {

    /**
     * 独立校验 `shuwei_m` 最终课表页，再调用无学校状态的 MTT 解码算法。
     *
     * @param input 已由调用方取得的完整移动入口课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹、课程行或时间字段不符合 MTT 契约
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).selectFirst("#kcb_container .wut_table")
            ?: throw ParserException.parse("shuwei_m 页面中缺少 kcb_container MTT 课表")
        val rows = table.select("tbody tr")
        if (rows.size <= 1) throw ParserException.parse("shuwei_m MTT 课表中缺少课程行")
        if (table.select("td[data-role=item] .mtt_arrange_item .mtt_item_sksj").isEmpty()) {
            throw ParserException.parse("shuwei_m 页面缺少 MTT 上课时间字段")
        }

        val courses = MttGridUtils.parseStandardRows(
            rows = rows.drop(1),
            sourceName = "shuwei_m",
        )
        if (courses.isEmpty()) throw ParserException.empty("shuwei_m 课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("shuwei_m 课表解析失败：${error.message}", error)
    }
}
