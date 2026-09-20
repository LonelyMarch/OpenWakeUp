package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import org.jsoup.Jsoup

/** 浙江邮电职业技术学院 `#kb .pbtd` 课表解析器。 */
object ZptcParser : Parser {
    /**
     * 解析课程格中的斜杠分段字段和方括号详情。
     *
     * @param input 完整课表 HTML
     * @return 每个 `.pbtd` 课程格对应的课程预览
     * @throws ParserException 页面指纹或课程字段不符合上游结构
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).selectFirst("#kb")
            ?: throw ParserException.parse("浙江邮电职业技术学院页面中缺少 kb 课表")
        val courses = table.select(".pbtd").mapIndexedNotNull { index, cell ->
            val text = cell.text().trim()
            if (text.isEmpty()) return@mapIndexedNotNull null
            val sections = text.split('/').map { it.trim() }
            require(sections.size >= 3 && sections[0].isNotEmpty()) { "第 ${index + 1} 个课程格字段不足" }
            val details = sections[2]
            val brackets =
                BRACKET_PATTERN.findAll(details).map { it.groupValues[1].trim() }.toList()
            require(brackets.size >= 3) { "课程 ${sections[0]} 的教师、周次或教室字段不足" }
            val nodeMatch = NODE_PATTERN.find(details)
                ?: throw IllegalArgumentException("课程 ${sections[0]} 缺少起始节次")
            val dayColumn = cell.id().substringAfter('x', "").substringBefore('_').toIntOrNull()
                ?: throw IllegalArgumentException("课程 ${sections[0]} 的单元格 id 无法定位星期")
            val dayText =
                table.selectFirst("tr:first-child th:nth-child(${dayColumn + 1})")?.text().orEmpty()
            val weeks = TextUtils.requirePositiveRange(brackets[1], "周次")
            CoursePreview(
                name = sections[0],
                teacher = brackets[0],
                room = brackets[2],
                day = TextUtils.requireDay(dayText),
                startNode = nodeMatch.groupValues[1].toInt(),
                step = 2,
                startWeek = weeks.first,
                endWeek = weeks.last,
                type = when (sections[1]) {
                    "每周" -> 0
                    "单周" -> 1
                    "双周" -> 2
                    else -> throw IllegalArgumentException("课程 ${sections[0]} 的周类型无法识别：${sections[1]}")
                },
            )
        }
        if (courses.isEmpty()) throw ParserException.empty("浙江邮电职业技术学院课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("浙江邮电职业技术学院课表解析失败：${error.message}", error)
    }

    private val BRACKET_PATTERN = Regex("""[【\[](.*?)[】\]]""")
    private val NODE_PATTERN = Regex("""第\s*(\d+)\s*(?:[-－—]\s*\d+)?\s*节""")
}
