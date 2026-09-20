package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `cqu` 使用的 `print-schedule-table` 卡片式课表解析器。 */
object CquParser : Parser {

    /**
     * 解析 `.item-box` 中的课程名称、地点、教师和时间字段。
     *
     * `.item-time` 必须同时给出周次、节次和星期。原版在字段不足时把课程复制到周一至周五
     * 的第 1～8 节，这无法证明真实时间，因此本实现直接失败关闭。
     *
     * @param input 完整“我的课表”页面 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹或任一课程的名称、时间字段无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        require(input.text.contains("print-schedule-table")) { "页面不是 cqu 我的课表打印视图" }
        val document = Jsoup.parse(input.text)
        val cards = document.getElementsByClass("item-box")
        require(cards.isNotEmpty()) { "cqu 页面中缺少 item-box" }
        val courses = cards.flatMap { card -> parseCard(card) }
        if (courses.isEmpty()) throw ParserException.empty("cqu 页面中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("cqu 课表解析失败：${error.message}", error)
    }

    /** 解析一张课程卡片，并按离散或连续周次拆成无损课程片段。 */
    private fun parseCard(card: Element): List<CoursePreview> {
        val name = card.getElementsByClass("item-name").firstOrNull()?.text()
            ?.substringBefore('[')?.trim().orEmpty()
        require(name.isNotEmpty()) { "cqu 课程名为空" }
        val room = card.getElementsByClass("item-location").firstOrNull()?.text()?.trim().orEmpty()
        val teacher = card.getElementsByClass("item-instr").firstOrNull()?.text()
            ?.substringBefore('-')?.trim().orEmpty()
        val time = card.getElementsByClass("item-time").firstOrNull()?.text()?.trim().orEmpty()
        val fields = time.split(Regex("\\s+")).filter { field -> field.isNotEmpty() }
        require(fields.size >= 3) { "课程 $name 的时间字段必须包含周次、节次和星期" }
        val weekText = fields[0].removeSuffix("周").trim()
        val nodeText = fields[1].removeSuffix("节").trim()
        val nodes = TextUtils.requirePositiveRange(nodeText, "课程 $name 的节次")
        val day = TextUtils.requireDay(fields[2])

        return WeekUtils.parse(weekText).map { week ->
            CoursePreview(
                name = name,
                teacher = teacher,
                room = room,
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
