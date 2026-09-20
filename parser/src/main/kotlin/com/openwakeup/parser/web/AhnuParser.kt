package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import org.jsoup.Jsoup

/** 安徽师范大学旧版 `kcb` 课表解析器。 */
object AhnuParser : Parser {
    /** 以时间行为锚点读取其前课程名及后续教师、教室，避免上游可变状态跨课程残留。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val body = Jsoup.parse(input.text).selectFirst("table#kcb tbody")
            ?: throw ParserException.parse("安徽师大旧版页面中缺少 kcb")
        val courses = mutableListOf<CoursePreview>()
        body.select("tr:not(.thtd) td").forEach { cell ->
            val fields =
                cell.html().split(Regex("(?i)<br\\s*/?>")).map { Jsoup.parse(it).text().trim() }
            fields.indices.filter { TIME_PATTERN.containsMatchIn(fields[it]) }.forEach { index ->
                require(index >= 1 && index + 2 < fields.size) { "安徽师大课程字段不足" }
                val time = fields[index]
                val weeks = WEEK_PATTERN.find(time)?.groupValues
                    ?: throw IllegalArgumentException("课程时间缺少周次：$time")
                val nodesText = time.substringAfter('第', "").substringBefore('节', "")
                val nodes = TextUtils.requirePositiveRange(nodesText.replace(',', '-'), "节次")
                courses += CoursePreview(
                    name = fields[index - 1].removeSurrounding("[", "]"),
                    teacher = fields[index + 1].removeSurrounding("[", "]"),
                    room = fields[index + 2].removeSurrounding("[", "]"),
                    day = TextUtils.requireDay(time), startNode = nodes.first,
                    step = nodes.last - nodes.first + 1,
                    startWeek = weeks[1].toInt(), endWeek = weeks[2].toInt(),
                    type = when {
                        time.contains('单') -> 1; time.contains('双') -> 2; else -> 0
                    },
                )
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("安徽师大旧版课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("安徽师大旧版课表解析失败：${error.message}", error)
    }

    private val TIME_PATTERN = Regex("""第\d+\s*[-,－—~～]\s*\d+周.*第.+节|第\d+\s*[-－—~～]\s*\d+周""")
    private val WEEK_PATTERN = Regex("""第(\d+)\s*[-－—~～]\s*(\d+)周""")
}
