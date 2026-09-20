package com.openwakeup.parser.web

import com.openwakeup.parser.*
import com.openwakeup.parser.utils.JsonUtils
import com.openwakeup.parser.utils.WeekUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** 南方科技大学最终课程 JSON 数组解析器。 */
object SustechParser : Parser {
    /** 解析 queryxszykbzong 响应并过滤 KEY=bz 备注格，不迁移 CAS。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val rows = Json.parseToJsonElement(input.text) as? kotlinx.serialization.json.JsonArray
            ?: throw ParserException.parse("南科大课表响应不是 JSON 数组")
        val result = rows.flatMap { element ->
            val row = element.jsonObject
            if (JsonUtils.requiredString(row, "KEY") == "bz") return@flatMap emptyList()
            parseInfo(JsonUtils.requiredString(row, "SKSJ"), JsonUtils.requiredString(row, "KEY"))
        }
        if (result.isEmpty()) throw ParserException.empty("南科大课表响应中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("南科大课表解析失败：${error.message}", error)
    }

    /** 解析名称后五组方括号字段。 */
    private fun parseInfo(text: String, position: String): List<CoursePreview> {
        val name = text.substringBefore('[').trim(); require(name.isNotEmpty())
        val fields = Regex("""\[(.*?)]""").findAll(text).map { it.groupValues[1] }.toList()
        require(fields.size == 5) { "课程 $name 的方括号字段不是 5 组" }
        val day = Regex("""xq(\d+)_jc\d+""").find(position)?.groupValues?.get(1)?.toInt()
            ?.takeIf { it in 1..7 }
            ?: error("课程 $name 的位置键无效")
        val nodes = Regex("""\d+""").findAll(fields[4]).map { it.value.toInt() }.toList(); require(
            nodes.isNotEmpty()
        )
        return WeekUtils.parse(fields[2]).map { week ->
            CoursePreview(
                name, teacher = fields[0], room = fields[3], day = day,
                startNode = nodes.first(), step = nodes.last() - nodes.first() + 1,
                startWeek = week.startWeek, endWeek = week.endWeek, type = week.type
            )
        }
    }
}
