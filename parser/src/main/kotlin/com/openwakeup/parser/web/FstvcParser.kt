package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.JsonUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** 福州软件职业技术学院培养计划 JSON 解析器。 */
object FstvcParser : Parser {
    /**
     * 解析 App 按页取得的原始 JSON 响应。
     *
     * 每个响应都必须包含 `rows` 和 `total`。Parser 在这里合并分页结果并校验总数，避免 App
     * 为了适配解析器而改写响应结构；Cookie、分页请求和 API URL 发现全部留在 App 层。
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val pages = input.allTexts.mapIndexed { index, text ->
            require(text.isNotBlank()) { "福软培养计划第 ${index + 1} 页为空" }
            Json.parseToJsonElement(text).jsonObject
        }
        require(pages.isNotEmpty()) { "福软培养计划分页响应为空" }
        val expectedTotal = pages.first().requiredNonNegativeInt("total")
        require(pages.all { it.requiredNonNegativeInt("total") == expectedTotal }) {
            "福软培养计划分页 total 不一致"
        }
        val rows = pages.flatMap { root -> JsonUtils.requiredArray(root, "rows") }
        require(rows.size == expectedTotal) {
            "福软培养计划分页数量不完整：实际 ${rows.size}，应为 $expectedTotal"
        }
        val uniqueRows = rows.map { it.toString() }.toSet()
        require(uniqueRows.size == rows.size) { "福软培养计划分页包含重复记录" }
        val result = rows.mapIndexed { index, element ->
            val row = element.jsonObject
            val nodes = JsonUtils.requiredString(row, "jcshow").split('-')
                .map { it.toIntOrNull() ?: error("节次无效") }
            require(nodes.isNotEmpty() && nodes.last() >= nodes.first()) { "第 ${index + 1} 条培养计划节次无效" }
            val day = JsonUtils.requiredPositiveInt(row, "xqs"); require(day in 1..7) { "星期无效" }
            val week = JsonUtils.requiredPositiveInt(row, "zc")
            CoursePreview(
                JsonUtils.requiredString(row, "kcmc"),
                teacher = JsonUtils.optionalString(row, "skjsxm"),
                room = JsonUtils.optionalString(row, "skcdmc"),
                day = day,
                startNode = nodes.first(),
                step = nodes.last() - nodes.first() + 1,
                startWeek = week,
                endWeek = week
            )
        }
        if (result.isEmpty()) throw ParserException.empty("福软培养计划响应中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("福软培养计划解析失败：${error.message}", error)
    }

    /** 读取允许为零的分页总数，拒绝浮点数、负数和缺失字段。 */
    private fun JsonObject.requiredNonNegativeInt(key: String): Int {
        val primitive = this[key] as? JsonPrimitive
            ?: throw IllegalArgumentException("缺少 JSON 字段：$key")
        val value = primitive.intOrNull ?: primitive.content.trim().toIntOrNull()
        require(value != null && value >= 0) { "JSON 字段 $key 必须是非负整数" }
        return value
    }
}
