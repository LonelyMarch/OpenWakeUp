package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `cidp` 使用的防灾科技学院课程卡片解析器。 */
object CidpParser : Parser {

    /**
     * 解析 `#scheduleTable` 下带 `data-week` 与 `data-class` 坐标的课程容器。
     *
     * 每个 `.Content` 可以包含多个 `.divOneClass`。普通周次保留连续、单周和双周语义；
     * “隔①周”至“隔⑳周”按原版步长规则生成精确集合，不通过扩大区间引入额外周次。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹、坐标、课程字段或周次无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("scheduleTable")
            ?: throw ParserException.parse("防灾科技学院课表缺少 scheduleTable")
        val courses = table.select(".Content").flatMap { container -> parseContainer(container) }
        if (courses.isEmpty()) throw ParserException.empty("防灾科技学院课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("防灾科技学院课表解析失败：${error.message}", error)
    }

    /** 读取课程容器坐标，并解析其内部所有独立课程卡片。 */
    private fun parseContainer(container: Element): List<CoursePreview> {
        val day = container.attr("data-week").trim().toIntOrNull()
        val startNode = container.attr("data-class").trim().toIntOrNull()
        require(day != null && day in 1..7) { "防灾科技学院课程星期无效：${container.attr("data-week")}" }
        require(startNode != null && startNode > 0) {
            "防灾科技学院课程起始节次无效：${container.attr("data-class")}"
        }
        val cards = container.select(".divOneClass")
        require(cards.isNotEmpty()) { "防灾科技学院课程容器中没有 divOneClass" }
        return cards.flatMap { card -> parseCard(card, day, startNode) }
    }

    /** 把单张课程卡片按周次片段转换为一个或多个课程预览。 */
    private fun parseCard(
        card: Element,
        day: Int,
        startNode: Int,
    ): List<CoursePreview> {
        val name = requiredText(card, ".spLUName", "课程名称", allowBlank = false)
        val teacher = requiredText(card, ".spTeacherName", "教师", allowBlank = true)
        val weekText = requiredText(card, ".spWeekInfo", "周次", allowBlank = false)
        val building = requiredText(card, ".spBuilding", "教学楼", allowBlank = true)
        val classroom = requiredText(card, ".spClassroom", "教室", allowBlank = true)
        val weeks = parseWeeks(weekText)

        return weeks.map { week ->
            CoursePreview(
                name = name,
                teacher = teacher,
                room = building + classroom,
                day = day,
                startNode = startNode,
                step = 2,
                startWeek = week.startWeek,
                endWeek = week.endWeek,
                type = week.type,
            )
        }
    }

    /**
     * 解析普通周次或带圈数字表示的间隔周次。
     *
     * 原版将带圈字符在 `①..⑳` 中的索引加一作为步长，本实现保持该定义。
     */
    private fun parseWeeks(text: String): List<com.openwakeup.parser.utils.WeekSegment> {
        if (!text.contains('隔')) return WeekUtils.parse(text)

        val rangeMatch = WEEK_RANGE_PATTERN.find(text)
            ?: throw IllegalArgumentException("无法识别间隔周次范围：$text")
        val startWeek = rangeMatch.groupValues[1].toInt()
        val endWeek = rangeMatch.groupValues[2].toInt()
        require(startWeek > 0 && endWeek >= startWeek) { "间隔周次范围无效：$text" }
        val circled = text.substringAfter('隔').substringBefore('周').trim().firstOrNull()
            ?: throw IllegalArgumentException("间隔周次缺少步长：$text")
        val step = CIRCLED_NUMBERS.indexOf(circled) + 1
        require(step > 0) { "无法识别间隔周次步长：$circled" }

        // 先构造源页面声明的精确周集合，再交给公共工具选择连续或奇偶区间表达。
        return WeekUtils.compact((startWeek..endWeek step step).toList())
    }

    /** 读取必须存在的卡片字段；可空业务字段允许元素内容为空，但不允许结构节点缺失。 */
    private fun requiredText(
        card: Element,
        selector: String,
        fieldName: String,
        allowBlank: Boolean,
    ): String {
        val element = card.selectFirst(selector)
            ?: throw IllegalArgumentException("防灾科技学院课程缺少$fieldName 节点")
        val value = element.text().trim()
        require(allowBlank || value.isNotEmpty()) { "防灾科技学院课程$fieldName 为空" }
        return value
    }

    private val WEEK_RANGE_PATTERN = Regex("""(\d+)\s*-\s*(\d+)\s*周""")
    private const val CIRCLED_NUMBERS = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳"
}
