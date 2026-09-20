package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * `urp_new` 对应的新 URP JSON 解析器。
 *
 * 输入是单个完整 JSON 响应，而不是 HTML 或网络请求参数。根对象必须包含 `dateList` 数组，每项
 * 包含 `selectCourseList` 数组，课程的每个 `timeAndPlaceList` 时间段使用 `classWeek` 位图表示周次。
 */
object UrpNewParser : Parser {

    /**
     * 解析新 URP 课表 JSON。
     *
     * @param input `text` 为包含 `dateList/selectCourseList/timeAndPlaceList` 的完整 JSON 响应
     * @return 非空课程预览列表；每个时间地点和每个无损周次片段分别输出
     * @throws ParserException JSON 结构错误、必填字段缺失、周次位图非法或课表为空
     */
    override fun parse(input: ParserInput): List<CoursePreview> {
        try {
            val root = Json.parseToJsonElement(input.text).asObject("根节点")
            val dateList = root.requiredArray("dateList", "根节点")
            val result = mutableListOf<CoursePreview>()

            dateList.forEachIndexed { dateIndex, dateElement ->
                val date = dateElement.asObject("dateList[$dateIndex]")
                val courses = date.requiredArray("selectCourseList", "dateList[$dateIndex]")
                courses.forEachIndexed courseLoop@{ courseIndex, courseElement ->
                    val path = "dateList[$dateIndex].selectCourseList[$courseIndex]"
                    val course = courseElement.asObject(path)
                    val name = course.requiredString("courseName", path)
                    val teacher = course.optionalString("attendClassTeacher", path).orEmpty()
                    val timeAndPlaces =
                        course.optionalArray("timeAndPlaceList", path) ?: return@courseLoop

                    timeAndPlaces.forEachIndexed { detailIndex, detailElement ->
                        val detailPath = "$path.timeAndPlaceList[$detailIndex]"
                        val detail = detailElement.asObject(detailPath)
                        val day = detail.requiredInt("classDay", detailPath)
                        val startNode = detail.requiredInt("classSessions", detailPath)
                        val step = detail.requiredInt("continuingSession", detailPath)
                        if (day !in 1..7) throw ParserException.parse("$detailPath.classDay 必须在 1..7")
                        if (startNode < 1) throw ParserException.parse("$detailPath.classSessions 必须大于 0")
                        if (step < 1) throw ParserException.parse("$detailPath.continuingSession 必须大于 0")

                        val bitMask = detail.requiredString("classWeek", detailPath)
                        val weeks = try {
                            WeekUtils.parseBitMask(bitMask)
                        } catch (error: IllegalArgumentException) {
                            throw ParserException.parse("$detailPath.classWeek 无法解析", error)
                        }
                        val room = buildString {
                            append(detail.optionalString("campusName", detailPath).orEmpty())
                            append(
                                detail.optionalString("teachingBuildingName", detailPath).orEmpty()
                            )
                            append(detail.optionalString("classroomName", detailPath).orEmpty())
                        }.trim()

                        weeks.forEach { week ->
                            result += CoursePreview(
                                name = name,
                                teacher = teacher,
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
                }
            }
            if (result.isEmpty()) throw ParserException.empty("新 URP 响应中没有课程")
            return result
        } catch (error: ParserException) {
            throw error
        } catch (error: Exception) {
            throw ParserException.parse("新 URP JSON 解析失败", error)
        }
    }

    /** 将 JSON 节点读取为对象，并在失败时保留字段路径。 */
    private fun JsonElement.asObject(path: String): JsonObject = this as? JsonObject
        ?: throw ParserException.parse("$path 必须是 JSON 对象")

    /** 读取必需数组字段。 */
    private fun JsonObject.requiredArray(name: String, path: String): JsonArray =
        this[name] as? JsonArray
            ?: throw ParserException.parse("$path.$name 必须是 JSON 数组")

    /** 读取可空数组字段；显式 null 与字段缺失都表示没有时间段。 */
    private fun JsonObject.optionalArray(name: String, path: String): JsonArray? {
        val value = this[name] ?: return null
        if (value is JsonNull) return null
        return value as? JsonArray
            ?: throw ParserException.parse("$path.$name 必须是 JSON 数组或 null")
    }

    /** 读取并校验非空必需字符串字段。 */
    private fun JsonObject.requiredString(name: String, path: String): String {
        val value = optionalString(name, path)?.trim().orEmpty()
        if (value.isEmpty()) throw ParserException.parse("$path.$name 不能为空")
        return value
    }

    /** 读取可空字符串字段，JSON null 与字段缺失统一返回 null。 */
    private fun JsonObject.optionalString(name: String, path: String): String? {
        val value = this[name] ?: return null
        if (value is JsonNull) return null
        val primitive = value as? JsonPrimitive
            ?: throw ParserException.parse("$path.$name 必须是 JSON 字符串或 null")
        if (!primitive.isString) throw ParserException.parse("$path.$name 必须是 JSON 字符串")
        return primitive.contentOrNull
    }

    /** 读取必需整数；禁止把浮点数或任意字符串静默截断。 */
    private fun JsonObject.requiredInt(name: String, path: String): Int {
        val primitive = this[name] as? JsonPrimitive
            ?: throw ParserException.parse("$path.$name 必须是整数")
        if (primitive.isString) throw ParserException.parse("$path.$name 必须是 JSON 整数")
        return primitive.intOrNull ?: throw ParserException.parse("$path.$name 必须是整数")
    }
}
