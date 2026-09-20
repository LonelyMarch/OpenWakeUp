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

/** 强智 2024 手工课表 JSON 解析器，规范 type 为 `qz_2024`。 */
object Qz2024Parser : Parser {

    /**
     * 解析 `JZHandCourseInfoItem` 数组。
     *
     * 当前模型实际使用 `kcmc/xqj/jsxm/skdd/djj/qmz/dsz` 七个字段；其余原版序列化字段不参与
     * 课程时间生成，因此不要求调用方提供。周次和单双周会通过 [WeekUtils] 无损规范化。
     *
     * @param input 完整 JSON 数组响应
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException JSON 结构或有效课程字段不合法
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val rows = Json.parseToJsonElement(input.text) as? JsonArray
            ?: throw IllegalArgumentException("qz_2024 输入必须是 JSON 数组")
        val courses = rows.flatMapIndexed { index, element ->
            val row = element.jsonObject
            val name = JsonUtils.requiredString(row, "kcmc")
            val day = JsonUtils.requiredPositiveInt(row, "xqj")
            require(day in 1..7) { "第 ${index + 1} 条课程的星期不在 1～7 范围内" }
            val node = JsonUtils.requiredPositiveInt(row, "djj")
            val weekText = JsonUtils.requiredString(row, "qmz")
            // 原版约定 1=单周、2=全周，其余整数均=双周；这里不能擅自把“其余”缩成 3。
            val weekType = JsonUtils.requiredString(row, "dsz").toIntOrNull()
                ?: throw IllegalArgumentException("课程 $name 的 dsz 必须是整数")
            parseWeeks(weekText, weekType).map { week ->
                CoursePreview(
                    name = name,
                    teacher = JsonUtils.optionalString(row, "jsxm"),
                    room = JsonUtils.optionalString(row, "skdd"),
                    day = day,
                    startNode = node,
                    startWeek = week.startWeek,
                    endWeek = week.endWeek,
                    type = week.type,
                )
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("强智 2024 JSON 中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("强智 2024 JSON 解析失败：${error.message}", error)
    }

    /**
     * 按原版 `dsz` 枚举解释周类型：1 为单周、2 为全周、其他整数为双周。
     *
     * @param source 用分号分隔的一个或多个周次范围
     * @param weekType 原版单双周枚举
     */
    private fun parseWeeks(source: String, weekType: Int) = source
        .split(';', '；')
        .filter { part -> part.isNotBlank() }
        .flatMap { rawPart ->
            val suffix = when (weekType) {
                1 -> "单周"
                2 -> ""
                else -> "双周"
            }
            WeekUtils.parse(rawPart.trim() + suffix)
        }
        .also { weeks -> require(weeks.isNotEmpty()) { "qz_2024 周次字段为空" } }
}
