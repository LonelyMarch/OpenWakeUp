package com.openwakeup.parser.web

import com.openwakeup.parser.*
import com.openwakeup.parser.utils.JsonUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** 桂林电子科技大学北海校区最终课表 JSON 响应解析器。 */
object GdbhParser : Parser {
    /** 仅解析调用方已取得的 `data` 数组，不迁移 Cookie、学期请求或内网 GET。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val root = Json.parseToJsonElement(input.text).jsonObject
        val result = JsonUtils.requiredArray(root, "data").mapIndexed { index, element ->
            val row = element.jsonObject
            val sequence = JsonUtils.requiredPositiveInt(row, "seq")
            val startNode = SEQUENCE_NODES[sequence]
                ?: throw IllegalArgumentException("第 ${index + 1} 门课程的课时段编号无效：$sequence")
            val day = JsonUtils.requiredPositiveInt(row, "week")
            require(day in 1..7) { "第 ${index + 1} 门课程星期无效" }
            val startWeek = JsonUtils.requiredPositiveInt(row, "startweek")
            val endWeek = JsonUtils.requiredPositiveInt(row, "endweek")
            require(endWeek >= startWeek) { "第 ${index + 1} 门课程周次倒置" }
            CoursePreview(
                name = JsonUtils.requiredString(row, "cname"),
                teacher = JsonUtils.optionalString(row, "name"),
                room = JsonUtils.optionalString(row, "croomno"),
                day = day,
                startNode = startNode,
                step = 2,
                startWeek = startWeek,
                endWeek = endWeek,
            )
        }
        if (result.isEmpty()) throw ParserException.empty("桂电北海课表响应中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("桂电北海课表 JSON 解析失败：${error.message}", error)
    }

    private val SEQUENCE_NODES = mapOf(1 to 1, 2 to 3, 3 to 5, 4 to 7, 5 to 9, 6 to 11)
}
