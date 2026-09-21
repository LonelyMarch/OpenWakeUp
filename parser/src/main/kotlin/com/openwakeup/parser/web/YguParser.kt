package com.openwakeup.parser.web

import com.openwakeup.parser.*
import com.openwakeup.parser.utils.JsonUtils
import com.openwakeup.parser.utils.WeekUtils
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 阳光学院最终课程 JSON 响应解析器。 */
object YguParser : Parser {
    /**
     * 依次解析候选学期的 `data[].weekList[].kcbVoList[]`。
     *
     * App 只负责按原版顺序取得最多两个原始响应；本方法根据 `code=0` 和课程是否非空
     * 选择第一个可用响应，不读取或依赖 App 层的课程字段判断。
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        var lastFailure: Exception? = null
        input.allTexts.forEach { text ->
            try {
                val root = Json.parseToJsonElement(text).jsonObject
                val code = (root["code"] as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: throw IllegalArgumentException("缺少合法 code")
                if (code != 0) return@forEach
                val result = JsonUtils.requiredArray(root, "data").flatMap { course ->
                    JsonUtils.requiredArray(course.jsonObject, "weekList").flatMap { week ->
                        JsonUtils.requiredArray(week.jsonObject, "kcbVoList")
                            .flatMap { item -> parseItem(item.jsonObject) }
                    }
                }
                if (result.isNotEmpty()) return result
                lastFailure = ParserException.empty("阳光学院候选学期课表为空")
            } catch (error: CancellationException) {
                // 取消是协程的控制流信号，不能当作候选响应解析失败后继续尝试。
                throw error
            } catch (error: Exception) {
                // 只记录可恢复的输入或解析异常，避免吞掉 OutOfMemoryError 等 JVM 严重错误。
                lastFailure = error
            }
        }
        throw ParserException.parse("阳光学院所有候选学期均未返回有效课程", lastFailure)
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("阳光学院课表解析失败：${error.message}", error)
    }

    /** 将单条 kcbVo 记录按精确周集合拆分。 */
    private fun parseItem(row: kotlinx.serialization.json.JsonObject): List<CoursePreview> {
        val name = JsonUtils.requiredString(row, "kcmc")
        val nodes = JsonUtils.requiredString(row, "jcs").split(',').map { it.trim().toInt() }
        require(nodes.isNotEmpty() && nodes.last() >= nodes.first()) { "课程 $name 节次无效" }
        val weeks = JsonUtils.requiredString(row, "zcs").split(',').map { it.trim().toInt() }
        val day = JsonUtils.requiredPositiveInt(row, "xq"); require(day in 1..7)
        return WeekUtils.compact(weeks).map { week ->
            CoursePreview(
                name,
                teacher = JsonUtils.optionalString(row, "rkls"),
                room = JsonUtils.optionalString(row, "jsmc").replace(" ", ""),
                day = day,
                startNode = nodes.first(),
                step = nodes.last() - nodes.first() + 1,
                startWeek = week.startWeek,
                endWeek = week.endWeek,
                type = when (JsonUtils.optionalString(row, "dsz")) {
                    "单" -> 1; "双" -> 2; else -> week.type
                }
            )
        }
    }
}
