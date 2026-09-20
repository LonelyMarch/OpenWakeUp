package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.JsonUtils
import com.openwakeup.parser.utils.WeekUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

/** `shcc` 使用的上海海关学院页面内嵌 JSON 解析器。 */
object ShccParser : Parser {

    /**
     * 从 `var ypData = ...;` 脚本赋值中提取 JSON 数组并转换课程。
     *
     * `js` 字段同时保存教师和括号内周次，例如 `张老师(1-16周)`；`djj` 是起始节次，
     * `skcd` 是连续节数。Parser 不执行页面 JavaScript，只读取这一段静态数据。
     *
     * @param input 含 `ypData` 静态赋值的完整页面 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 脚本标记、JSON 字段或课程时间范围无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val jsonText = extractYpData(input.text)
        val rows = Json.parseToJsonElement(jsonText) as? JsonArray
            ?: throw IllegalArgumentException("上海海关学院 ypData 不是 JSON 数组")
        val courses = rows.flatMapIndexed { rowIndex, element ->
            val row = element.jsonObject
            val name = JsonUtils.requiredString(row, "kcS")
            val rawTeacherAndWeeks = JsonUtils.requiredString(row, "js")
            require(rawTeacherAndWeeks.contains('(') && rawTeacherAndWeeks.contains(')')) {
                "课程 $name 的教师周次字段缺少括号"
            }
            val teacher = rawTeacherAndWeeks.substringBefore('(').trim()
            val weekText = rawTeacherAndWeeks.substringAfter('(').substringBefore(')').trim()
            require(weekText.isNotEmpty()) { "课程 $name 的周次字段为空" }
            val day = JsonUtils.requiredPositiveInt(row, "xqj")
            val startNode = JsonUtils.requiredPositiveInt(row, "djj")
            val step = JsonUtils.requiredPositiveInt(row, "skcd")
            require(day in 1..7) { "第 ${rowIndex + 1} 条课程的星期无效：$day" }
            val endNode = startNode.toLong() + step.toLong() - 1L
            require(endNode <= Int.MAX_VALUE) { "课程 $name 的节次范围溢出" }

            WeekUtils.parse(weekText).map { week ->
                CoursePreview(
                    name = name,
                    teacher = teacher,
                    room = JsonUtils.optionalString(row, "jsmc"),
                    day = day,
                    startNode = startNode,
                    step = step,
                    startWeek = week.startWeek,
                    endWeek = week.endWeek,
                    type = week.type,
                )
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("上海海关学院课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("上海海关学院课表解析失败：${error.message}", error)
    }

    /** 提取原版实际读取的 `ypData` 赋值右侧，不执行后续 jQuery 初始化代码。 */
    private fun extractYpData(source: String): String {
        val markerIndex = source.indexOf(DATA_MARKER)
        require(markerIndex >= 0) { "上海海关学院页面缺少 ypData 标记" }
        val assignment = source.substring(markerIndex + DATA_MARKER.length)
            .substringBefore("\$(document)")
        val terminatorIndex = assignment.indexOf(';')
        require(terminatorIndex >= 0) { "上海海关学院 ypData 赋值缺少结束分号" }
        return assignment.substring(0, terminatorIndex).trim()
            .also { value -> require(value.isNotEmpty()) { "上海海关学院 ypData 为空" } }
    }

    private const val DATA_MARKER = "var ypData ="
}
