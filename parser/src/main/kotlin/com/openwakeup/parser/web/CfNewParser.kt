package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** `cf_new` 使用的 `lay-tips` 上课任务解析器。 */
object CfNewParser : Parser {

    /**
     * 解析页面元素 `lay-tips` 属性中内嵌的上课任务 HTML。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 每个上课任务对应的课程预览
     * @throws ParserException 页面指纹缺失或必填字段无法解析
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val document = Jsoup.parse(input.text)
        val matchedTips = document.select("[lay-tips]")
            .map { element -> element.attr("lay-tips") }
            .filter { value -> value.contains("上课任务[") }
        if (matchedTips.isEmpty()) throw ParserException.parse("页面中缺少 cf_new 上课任务指纹")

        val courses = matchedTips.flatMap { tips -> parseTask(tips) }
        if (courses.isEmpty()) throw ParserException.empty("cf_new 上课任务中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("cf_new 课表解析失败：${error.message}", error)
    }

    /**
     * 将一段 `lay-tips` HTML 的表头与值按下标配对并构造课程。
     *
     * @param source 单个属性中的内嵌 HTML
     * @return 该上课任务按周次拆分后的课程列表
     */
    private fun parseTask(source: String): List<CoursePreview> {
        val detail = Jsoup.parse(source)
        val headers = detail.select("th")
        val values = detail.select("td")
        require(headers.size == values.size) { "cf_new 字段和值的数量不一致" }

        val fields = headers.indices.associate { index ->
            headers[index].text().trim().trimEnd('：', ':') to values[index].text().trim()
        }
        val rawName = fields["课程"].orEmpty()
        val nameAfterCode = rawName.substringAfter(']', rawName).trim()
        val name = nameAfterCode.ifEmpty { rawName.trim() }
        require(name.isNotEmpty()) { "cf_new 上课任务缺少课程名" }

        val day = fields["星期"]?.trim()?.toIntOrNull()
            ?: throw IllegalArgumentException("课程 $name 缺少数字星期")
        require(day in 1..7) { "课程 $name 的星期不在 1～7 范围内" }

        val nodeText = fields["节次"].orEmpty()
        require(nodeText.isNotEmpty()) { "课程 $name 缺少节次" }
        val nodes = TextUtils.requirePositiveRange(nodeText, "课程 $name 的节次")
        val weekText = fields["上课周次"].orEmpty()
        require(weekText.isNotEmpty()) { "课程 $name 缺少上课周次" }

        return WeekUtils.parse(weekText).map { week ->
            CoursePreview(
                name = name,
                teacher = fields["授课教师"].orEmpty(),
                room = fields["教学场地"].orEmpty(),
                day = day,
                startNode = nodes.first,
                step = nodes.last - nodes.first + 1,
                startWeek = week.startWeek,
                endWeek = week.endWeek,
                type = week.type,
            )
        }
    }
}
