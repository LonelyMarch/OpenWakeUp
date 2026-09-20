package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import org.jsoup.Jsoup

/** 安徽师范大学新版 `lessons` 行式课表解析器。 */
object AhnuNewParser : Parser {
    /** 解析每行课程名及第三列的多个排课时间，过滤明确的“不排课”。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val body = Jsoup.parse(input.text).selectFirst("table#lessons tbody")
            ?: throw ParserException.parse("安徽师大新版页面中缺少 lessons")
        val courses = mutableListOf<CoursePreview>()
        body.select("tr:not(.semester_tr)").forEach { row ->
            val cells = row.select("td")
            if (cells.isEmpty()) return@forEach
            require(cells.size >= 3) { "安徽师大新版课程行字段不足" }
            val name = cells[0].select(".showSchedules").text().trim()
            require(name.isNotEmpty()) { "安徽师大新版课程行缺少名称" }
            cells[2].html().split(Regex("(?i)<br\\s*/?>")).forEach { fragment ->
                val text = Jsoup.parse(fragment).text().trim()
                if (text.isEmpty() || text == "不排课") return@forEach
                val parts = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
                require(parts.size >= 4) { "课程 $name 的排课字段不足：$text" }
                val weeks = TextUtils.requirePositiveRange(parts[0], "周次")
                val nodes = parseChineseNodes(parts[2])
                val room: String
                val teacher: String
                if (parts[3] == "花津校区") {
                    require(parts.size >= 6) { "课程 $name 的校区排课字段不足" }
                    room = parts[4]
                    teacher = parts[5].substringBefore(';')
                } else {
                    room = ""
                    teacher = parts[3].substringBefore(';')
                }
                courses += CoursePreview(
                    name,
                    teacher = teacher,
                    room = room,
                    day = TextUtils.requireDay(parts[1]),
                    startNode = nodes.first,
                    step = nodes.last - nodes.first + 1,
                    startWeek = weeks.first,
                    endWeek = weeks.last
                )
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("安徽师大新版课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("安徽师大新版课表解析失败：${error.message}", error)
    }

    /** 将一至二十的中文节次范围转换为数字边界。 */
    private fun parseChineseNodes(text: String): IntRange {
        val values = text.split('~', '～', '-', '－', '—').map { token ->
            CHINESE_NUMBERS[token.filter { it in "一二三四五六七八九十" }]
                ?: token.filter(Char::isDigit).toIntOrNull()
                ?: throw IllegalArgumentException("中文节次无法识别：$text")
        }
        require(values.size in 1..2 && values.last() >= values.first()) { "节次范围无效：$text" }
        return values.first()..values.last()
    }

    private val CHINESE_NUMBERS = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6,
        "七" to 7, "八" to 8, "九" to 9, "十" to 10, "十一" to 11, "十二" to 12,
        "十三" to 13, "十四" to 14, "十五" to 15, "十六" to 16, "十七" to 17, "十八" to 18,
        "十九" to 19, "二十" to 20
    )
}
