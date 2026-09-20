package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.JsonUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** `shsmu` 使用的上海交大医学院日历响应解析器。 */
object ShsmuParser : Parser {

    /**
     * 解析带学期起始日的本地 JSON 输入封套。
     *
     * 封套格式为 `{"response":{"List":[...]},"semesterStart":"2026-09-14"}`。其中
     * `response` 是调用方取得的 `GetCurriculumTable` 最终响应；Parser 不发起该请求。原版把
     * `2025-09-15` 写死在源码中，本实现要求调用方提供实际学期日期，避免跨学期周次全部错误。
     *
     * @param input 包含最终响应和实际学期起始日的 JSON 封套
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 封套、日期时间、周次或钟点到节次映射无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val envelope = Json.parseToJsonElement(input.text).jsonObject
        val response = JsonUtils.requiredObject(envelope, "response")
        val semesterStart = LocalDate.parse(JsonUtils.requiredString(envelope, "semesterStart"))
        val rows = JsonUtils.requiredArray(response, "List")
        val courses = rows.mapIndexed { index, element ->
            val row = element.jsonObject
            parseCourse(row, semesterStart, index + 1)
        }
        if (courses.isEmpty()) throw ParserException.empty("上海交大医学院响应中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("上海交大医学院课表解析失败：${error.message}", error)
    }

    /**
     * 把一条日历事件转换为单周课程。
     *
     * 原版无法匹配作息钟点时会伪造第 1～2 节；当前模型又不能保存自定义钟点，因此本实现必须
     * 失败关闭。教室继续读取原版实际使用的 `ClassroomAcademy`，不猜测其他可空字段的语义。
     */
    private fun parseCourse(
        row: kotlinx.serialization.json.JsonObject,
        semesterStart: LocalDate,
        rowNumber: Int,
    ): CoursePreview {
        val name = JsonUtils.requiredString(row, "Curriculum")
        val start =
            parseDateTime(JsonUtils.requiredString(row, "Start"), "第 $rowNumber 条课程的开始时间")
        val end =
            parseDateTime(JsonUtils.requiredString(row, "End"), "第 $rowNumber 条课程的结束时间")
        require(start.date == end.date) { "课程 $name 的开始和结束时间不在同一天" }
        val startNode = BELL_TIMES.indexOfFirst { bell -> bell.first == start.time } + 1
        val endNode = BELL_TIMES.indexOfFirst { bell -> bell.second == end.time } + 1
        require(startNode > 0 && endNode >= startNode) {
            "课程 $name 的钟点 ${start.time}-${end.time} 无法映射到医学院作息"
        }
        val week = calculateWeek(semesterStart, start.date)
        require(week > 0) { "课程 $name 的日期早于学期起始周" }

        return CoursePreview(
            name = name,
            teacher = JsonUtils.optionalString(row, "Teacher"),
            room = JsonUtils.optionalString(row, "ClassroomAcademy"),
            day = start.date.dayOfWeek.value,
            startNode = startNode,
            step = endNode - startNode + 1,
            startWeek = week,
            endWeek = week,
        )
    }

    /**
     * 按原版算法计算课程日期所在教学周。
     *
     * 算法以 `semesterStart` 的星期为每周边界，因此调用方应传入教务系统定义的第一教学周
     * 起始日，而不是任意开学日期。
     */
    private fun calculateWeek(semesterStart: LocalDate, courseDate: LocalDate): Int {
        val days = ChronoUnit.DAYS.between(semesterStart, courseDate).toInt()
        val dayOffset = courseDate.dayOfWeek.value - semesterStart.dayOfWeek.value
        val alignedDays = if (dayOffset >= 0) days - dayOffset else days - (dayOffset + 7)
        return alignedDays / 7 + 1
    }

    /** 从原版响应时间戳中读取日期和精确到分钟的时间。 */
    private fun parseDateTime(source: String, fieldName: String): CalendarTime {
        val match = DATE_TIME_PATTERN.find(source)
            ?: throw IllegalArgumentException("$fieldName 格式无效：$source")
        return CalendarTime(
            date = LocalDate.parse(match.groupValues[1]),
            time = match.groupValues[2],
        )
    }

    /** 日历事件中参与课程映射的日期与分钟。 */
    private data class CalendarTime(
        val date: LocalDate,
        val time: String,
    )

    private val DATE_TIME_PATTERN = Regex("(\\d{4}-\\d{2}-\\d{2}).*?(\\d{2}:\\d{2}):\\d{2}")
    private val BELL_TIMES = listOf(
        "08:00" to "08:40",
        "08:50" to "09:30",
        "09:40" to "10:20",
        "10:30" to "11:10",
        "11:20" to "12:00",
        "13:30" to "14:10",
        "14:20" to "15:00",
        "15:10" to "15:50",
        "16:00" to "16:40",
        "16:50" to "17:30",
        "17:40" to "18:20",
        "18:30" to "19:10",
        "19:20" to "20:00",
    )
}
