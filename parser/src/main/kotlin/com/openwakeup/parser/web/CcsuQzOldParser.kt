package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 长沙学院旧强智固定 id 网格解析器。 */
object CcsuQzOldParser : Parser {
    /** 解析 `节组-星期-2` 标识的五乘七课程格。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val document = Jsoup.parse(input.text)
        val courses = mutableListOf<CoursePreview>()
        for (group in 1..5) for (day in 1..7) {
            val cell = document.getElementById("$group-$day-2")
                ?: throw ParserException.parse("长沙学院课表缺少单元格 $group-$day-2")
            val parts = cell.html().split(Regex("(?i)<br\\s*/?>"))
            if (parts.firstOrNull()?.trim() in setOf(null, "", "&nbsp;")) continue
            var index = 0
            while (index < parts.size - 1) {
                require(index + 4 < parts.size) { "单元格 $group-$day-2 的课程字段不足" }
                val name = Jsoup.parse(parts[index]).text().trim(); index += 2
                val teacher = Jsoup.parse(parts[index++]).text().trim().trim('(', ')', '[', ']')
                val weekText =
                    Jsoup.parse(parts[index++]).text().trim().trim('(', ')', '[', ']', '周')
                val room = Jsoup.parse(parts[index++]).text().trim()
                WeekUtils.parse(weekText).forEach { week ->
                    courses += CoursePreview(
                        name = name, teacher = teacher, room = room, day = day,
                        startNode = group * 2 - 1, step = 2,
                        startWeek = week.startWeek, endWeek = week.endWeek, type = week.type,
                    )
                }
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("长沙学院旧强智课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("长沙学院旧强智课表解析失败：${error.message}", error)
    }
}
