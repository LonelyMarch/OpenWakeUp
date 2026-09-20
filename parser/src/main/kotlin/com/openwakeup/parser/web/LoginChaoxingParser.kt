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

/** 超星教务主/备响应解析器，对应唯一 type=`login_chaoxing`。 */
object LoginChaoxingParser : Parser {
    /**
     * 依次尝试主响应 `data.kckbData` 与备用根数组。
     *
     * @param input 主响应位于 [ParserInput.text]，备用响应按原始顺序位于 [ParserInput.additionalTexts]
     * @return 展开周次后的课程预览
     */
    override fun parse(input: ParserInput): List<CoursePreview> {
        val sources = input.allTexts
        val errors = mutableListOf<Throwable>()
        sources.forEach { source ->
            try {
                val courses = parseSource(source)
                if (courses.isNotEmpty()) return courses
            } catch (error: Throwable) {
                errors += error
            }
        }
        throw ParserException.parse("超星课表主响应和备用响应均没有有效课程", errors.firstOrNull())
    }

    /** 解析单个 JSON 响应，兼容主接口封装和备用数组。 */
    private fun parseSource(source: String): List<CoursePreview> {
        val root = Json.parseToJsonElement(source)
        val rows = when (root) {
            is JsonArray -> root
            is JsonObject -> root["data"]
                ?.let { it as? JsonObject }
                ?.get("kckbData") as? JsonArray
                ?: return emptyList()

            else -> return emptyList()
        }
        return rows.mapIndexed { index, element ->
            val row = element as? JsonObject
                ?: throw IllegalArgumentException("第 ${index + 1} 条超星课程不是对象")
            val name = JsonUtils.requiredString(row, "kcmc")
            val day = JsonUtils.requiredString(row, "xq").toIntOrNull()
                ?: throw IllegalArgumentException("课程 $name 的 xq 不是整数")
            val startNode = JsonUtils.requiredPositiveInt(row, "djc")
            require(day in 1..7) { "课程 $name 的星期不在 1～7 范围内" }
            require(startNode > 0) { "课程 $name 的节次必须大于 0" }
            val weekType = JsonUtils.optionalString(row, "zctype").toIntOrNull() ?: 0
            require(weekType in 0..2) { "课程 $name 的单双周类型非法" }
            val weekText = JsonUtils.requiredString(row, "zc")
            val weeks = weekText.split(',', '，')
                .filter { it.isNotBlank() }
                .flatMap { parseWeeks(it, weekType, name) }
            require(weeks.isNotEmpty()) { "课程 $name 的周次为空" }
            weeks.map { week ->
                CoursePreview(
                    name = name,
                    teacher = JsonUtils.optionalString(row, "tmc").orEmpty(),
                    room = JsonUtils.optionalString(row, "croommc").orEmpty(),
                    day = day,
                    startNode = startNode,
                    step = 1,
                    startWeek = week.startWeek,
                    endWeek = week.endWeek,
                    type = week.type,
                )
            }
        }.flatten()
    }

    /** 清除原版周次片段中的括号说明，再交给共享周次算法。 */
    private fun parseWeeks(raw: String, weekType: Int, name: String) = runCatching {
        val normalized = raw.replace(Regex("[（(].*?[）)]"), "").trim()
        val suffix = when (weekType) {
            1 -> "单周"
            2 -> "双周"
            else -> ""
        }
        WeekUtils.parse(normalized.removeSuffix("周").trim() + suffix)
    }.getOrElse { error ->
        throw IllegalArgumentException("课程 $name 的周次无法解析：$raw", error)
    }
}
