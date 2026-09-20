package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.JsonUtils
import com.openwakeup.parser.utils.WeekUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 上海工程技术大学新版 `/print-data` 响应解析器。 */
object SuesParser : Parser {

    /**
     * 解析调用方已经捕获的新版树维课表 JSON。
     *
     * 5、6 节之间存在学校固定午休，本实现保留原版拆分规则；一个活动的周次先压缩成
     * 连续/单双周片段，再为每个节次片段生成课程预览。
     *
     * @param input `text` 必须是完整 `print-data` JSON 原文
     * @return 尚未写入数据库的课程预览
     * @throws ParserException 响应根结构、字段或课程内容无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val root = Json.parseToJsonElement(input.text).jsonObject
        val tableVms = JsonUtils.requiredArray(root, "studentTableVms")
        require(tableVms.isNotEmpty()) { "studentTableVms 为空" }
        val table = tableVms.first() as? JsonObject
            ?: throw IllegalArgumentException("studentTableVms 第一项不是对象")
        val courses =
            JsonUtils.requiredArray(table, "activities").flatMapIndexed { index, element ->
                val activity = element as? JsonObject
                    ?: throw IllegalArgumentException("activities 第 ${index + 1} 项不是对象")
                parseActivity(activity, index + 1)
            }
        if (courses.isEmpty()) throw ParserException.empty("上海工程技术大学新版响应中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("上海工程技术大学新版课表解析失败：${error.message}", error)
    }

    /** 解析一条活动，并在第 5、6 节之间拆成两条时间段。 */
    private fun parseActivity(row: JsonObject, activityNumber: Int): List<CoursePreview> {
        val name = JsonUtils.requiredString(row, "courseName")
        val context = "课程 $name（活动 $activityNumber）"
        val day = JsonUtils.requiredPositiveInt(row, "weekday")
        require(day in 1..7) { "$context 的星期越界：$day" }
        val startNode = JsonUtils.requiredPositiveInt(row, "startUnit")
        val endNode = JsonUtils.requiredPositiveInt(row, "endUnit")
        require(endNode >= startNode) { "$context 的结束节次早于开始节次" }
        val weeks = (row["weekIndexes"] as? JsonArray)
            ?.mapIndexed { index, value ->
                value.jsonPrimitive.content.toIntOrNull()
                    ?.takeIf { week -> week > 0 }
                    ?: throw IllegalArgumentException("$context 的第 ${index + 1} 个周次无效")
            }
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("$context 缺少非空 weekIndexes")
        val teachers = (row["teachers"] as? JsonArray)
            ?.joinToString(", ") { value -> value.jsonPrimitive.content.trim() }
            ?: throw IllegalArgumentException("$context 缺少 teachers 数组")
        val nodeRanges = if (startNode <= 5 && endNode >= 6) {
            listOf(startNode..5, 6..endNode)
        } else {
            listOf(startNode..endNode)
        }

        return WeekUtils.compact(weeks).flatMap { week ->
            nodeRanges.map { nodes ->
                CoursePreview(
                    name = name,
                    teacher = teachers,
                    room = JsonUtils.optionalString(row, "room"),
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
}
