package com.openwakeup.parser.utils

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.ParserException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 树维课表活动的无 type 纯解析工具。 */
internal object StudentTableScheduleUtils {

    /** 解析一个或多个 studentTableVms/studentTableVm 课表对象。 */
    fun parse(source: String, sourceName: String): List<CoursePreview> = try {
        val root = Json.parseToJsonElement(source).jsonObject
        val tables = buildList {
            (root["studentTableVms"] as? JsonArray)?.forEachIndexed { index, element ->
                add(
                    element as? JsonObject
                        ?: throw IllegalArgumentException("$sourceName 的 studentTableVms 第 ${index + 1} 项不是对象"),
                )
            }
            (root["studentTableVm"] as? JsonObject)?.let(::add)
        }
        require(tables.isNotEmpty()) { "$sourceName 缺少 studentTableVms/studentTableVm" }
        val courses = tables.flatMapIndexed { tableIndex, table ->
            JsonUtils.requiredArray(table, "activities").flatMapIndexed { activityIndex, element ->
                val activity = element as? JsonObject
                    ?: throw IllegalArgumentException(
                        "$sourceName 第 ${tableIndex + 1} 张课表的第 ${activityIndex + 1} 条活动不是对象",
                    )
                parseActivity(
                    activity,
                    "$sourceName 第 ${tableIndex + 1} 张课表的第 ${activityIndex + 1} 条活动"
                )
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("$sourceName 响应中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("$sourceName 树维课表解析失败：${error.message}", error)
    }

    /** 将活动展开为节次片段与周次片段。 */
    private fun parseActivity(activity: JsonObject, context: String): List<CoursePreview> {
        val name = JsonUtils.requiredString(activity, "courseName")
        val day = JsonUtils.requiredPositiveInt(activity, "weekday")
        require(day in 1..7) { "$context 的星期超出 1～7：$day" }
        val startNode = JsonUtils.requiredPositiveInt(activity, "startUnit")
        val endNode = JsonUtils.requiredPositiveInt(activity, "endUnit")
        require(endNode >= startNode) { "$context 的结束节次小于起始节次" }
        val weeks = JsonUtils.requiredArray(activity, "weekIndexes").mapIndexed { index, value ->
            value.jsonPrimitive.content.toIntOrNull()
                ?.takeIf { week -> week > 0 }
                ?: throw IllegalArgumentException("$context 的第 ${index + 1} 个周次不是正整数")
        }.also { require(it.isNotEmpty()) { "$context 的 weekIndexes 为空" } }
        val teachers = (activity["teachers"] as? JsonArray).orEmpty()
            .joinToString(", ") { value -> value.jsonPrimitive.content.trim() }
        val ranges = if (startNode <= 5 && endNode >= 6) {
            listOf(startNode..5, 6..endNode)
        } else {
            listOf(startNode..endNode)
        }
        return WeekUtils.compact(weeks).flatMap { week ->
            ranges.map { nodes ->
                CoursePreview(
                    name = name,
                    teacher = teachers,
                    room = JsonUtils.optionalString(activity, "room"),
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
