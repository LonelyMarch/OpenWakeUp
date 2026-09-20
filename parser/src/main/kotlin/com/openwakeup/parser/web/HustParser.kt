package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import org.jsoup.Jsoup

/** 华中科技大学微校园课表解析器。 */
object HustParser : Parser {
    /**
     * 解析 `main-page-block` 中每门课程的时间安排。
     *
     * @param input 微校园课表完整 HTML
     * @return 每个时间安排对应的课程预览
     * @throws ParserException 页面指纹或课程字段不符合预期
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val container = Jsoup.parse(input.text).selectFirst(".main-page-block")
            ?: throw ParserException.parse("华中科技大学页面中缺少 main-page-block")
        val courses = mutableListOf<CoursePreview>()
        container.children().forEachIndexed { index, block ->
            val paragraphs = block.select("p")
            require(paragraphs.size >= 3) { "第 ${index + 1} 个课程块段落不足" }
            val name = paragraphs[0].text().trim()
            require(name.isNotEmpty()) { "第 ${index + 1} 个课程块缺少课程名称" }
            block.select(".search-details-body").flatMap { it.children() }.forEach { arrangement ->
                val fields = arrangement.select(".text-box")
                require(fields.size >= 3) { "课程 $name 的时间安排字段不足" }
                val timeParts = fields[0].text().substringAfter("时间:", "")
                    .trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                require(timeParts.size >= 3) { "课程 $name 的时间字段无法识别" }
                val weeks = TextUtils.requirePositiveRange(timeParts[0], "周次")
                val nodes = TextUtils.requirePositiveRange(timeParts[2], "节次")
                courses += CoursePreview(
                    name = name,
                    teacher = fields[1].text().substringAfter("教师:", "").trim(),
                    room = fields[2].text().substringAfter("教室:", "").trim(),
                    day = TextUtils.requireDay(timeParts[1]),
                    startNode = nodes.first,
                    step = nodes.last - nodes.first + 1,
                    startWeek = weeks.first,
                    endWeek = weeks.last,
                )
                // 上游的“开课对象”写入 note；当前 CoursePreview 无该字段，因此明确不伪装保存。
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("华中科技大学课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("华中科技大学课表解析失败：${error.message}", error)
    }
}
