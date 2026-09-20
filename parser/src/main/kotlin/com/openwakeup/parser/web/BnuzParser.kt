package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 北京师范大学珠海分校旧版 `table1` 网格解析器。 */
object BnuzParser : Parser {
    /** 解析数字节次行和每个星期格内的教师、周次、教室分组。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("table1")
            ?: throw ParserException.parse("北师大珠海分校页面中缺少 table1")
        val courses = mutableListOf<CoursePreview>()
        table.select("tr").forEach { row ->
            var node: Int? = null
            var day = 1
            var started = false
            row.select("td").forEach { cell ->
                val text = cell.text().trim()
                if (text.isEmpty()) {
                    if (started) day++; return@forEach
                }
                text.toIntOrNull()?.let { value -> node = value; started = true; return@forEach }
                if (!started || text in HEADERS) return@forEach
                val startNode = node ?: throw IllegalArgumentException("课程行缺少节次")
                val fields = cell.html().substringAfter("</span>").substringBeforeLast("<br>")
                    .split(Regex("(?i)<br\\s*/?>"))
                require(fields.isNotEmpty() && fields[0].isNotBlank()) { "周$day 第 $startNode 节缺少课程名" }
                for (index in 1 until fields.size step 2) {
                    require(index + 1 < fields.size) { "课程 ${fields[0]} 的教师和教室字段不成对" }
                    val teacherWeek = Jsoup.parse(fields[index]).text().trim()
                    require(teacherWeek.contains('{') && teacherWeek.contains('}')) { "课程 ${fields[0]} 缺少周次大括号" }
                    val room = Jsoup.parse(fields[index + 1]).text().trim()
                    val step = Regex("""\((\d+)节\)""").find(room)?.groupValues?.get(1)?.toInt()
                        ?: throw IllegalArgumentException("课程 ${fields[0]} 的教室字段缺少节数")
                    WeekUtils.parse(teacherWeek.substringAfter('{').substringBefore('}'))
                        .forEach { week ->
                            courses += CoursePreview(
                                name = Jsoup.parse(fields[0]).text().trim(),
                                teacher = teacherWeek.substringBefore('{').trim(),
                                room = room,
                                day = day,
                                startNode = startNode,
                                step = step,
                                startWeek = week.startWeek,
                                endWeek = week.endWeek,
                                type = week.type,
                            )
                        }
                }
                day++
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("北师大珠海分校课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("北师大珠海分校课表解析失败：${error.message}", error)
    }

    private val HEADERS = setOf("时间", "节次", "星期", "上午", "下午", "晚上")
}
