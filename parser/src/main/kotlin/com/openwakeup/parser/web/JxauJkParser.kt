package com.openwakeup.parser.web

import com.openwakeup.parser.*
import com.openwakeup.parser.utils.JsonUtils
import com.openwakeup.parser.utils.WeekUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** 江西农业大学接口版最终 JSON 响应解析器。 */
object JxauJkParser : Parser {
    /** 解析 GetStudentKebiaoByXq 的 Data 数组，不迁移账号密码登录和学期请求。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val root = Json.parseToJsonElement(input.text).jsonObject
        val result = JsonUtils.requiredArray(root, "Data").flatMap { element ->
            val row = element.jsonObject
            val name = JsonUtils.requiredString(row, "KcMc")
            val day = JsonUtils.requiredString(row, "XingQi").substringBefore('.').toInt()
                .also { require(it in 1..7) }
            val nodesText = JsonUtils.requiredString(row, "Jieci")
            val match = Regex("""(\d+)-(\d+)节""").find(nodesText) ?: error("课程 $name 节次无效")
            WeekUtils.parse(JsonUtils.requiredString(row, "SkZhou")).map { week ->
                CoursePreview(
                    name,
                    teacher = JsonUtils.optionalString(row, "Rkls"),
                    room = JsonUtils.optionalString(row, "Skdd"),
                    day = day,
                    startNode = match.groupValues[1].toInt(),
                    step = match.groupValues[2].toInt() - match.groupValues[1].toInt() + 1,
                    startWeek = week.startWeek,
                    endWeek = week.endWeek,
                    type = week.type
                )
            }
        }
        if (result.isEmpty()) throw ParserException.empty("江西农大接口响应中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("江西农大接口课表解析失败：${error.message}", error)
    }
}
