package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.MttGridUtils
import org.jsoup.Jsoup

/** 南京大学教务服务平台金智课表解析器。 */
object NjuParser : Parser {
    /**
     * 校验南京大学 MTT 页面指纹并解析课程。
     *
     * @param input 已由调用方取得的完整课表页 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面结构错误、课表为空或字段解析失败
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).selectFirst("#kcb_container .wut_table")
            ?: throw ParserException.parse("南京大学页面中缺少 kcb_container MTT 课表")
        val rows = table.select("tbody tr")
        if (rows.size <= 1) {
            throw ParserException.parse("南京大学 MTT 课表中缺少课程行")
        }

        // 入口层保留学校自己的页面指纹；共享工具只接收已经确认的课程行。
        if (table.select("td[data-role=item] .mtt_arrange_item .mtt_item_sksj").isEmpty()) {
            throw ParserException.parse("南京大学页面缺少 MTT 上课时间字段")
        }
        val result = MttGridUtils.parseStandardRows(rows.drop(1), "南京大学")
        if (result.isEmpty()) throw ParserException.empty("南京大学课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("南京大学课表解析失败：${error.message}", error)
    }
}
