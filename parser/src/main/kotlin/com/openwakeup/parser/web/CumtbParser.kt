package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.HfuScheduleUtils
import com.openwakeup.parser.utils.StudentTableScheduleUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 中国矿业大学（北京）等 `cumtb` 配置使用的排课 JSON 解析器。 */
object CumtbParser : Parser {

    /**
     * 使用互斥根指纹选择 HFUInfo 或树维算法。
     *
     * HFUInfo 必须同时包含 `result.lessonList` 与 `result.scheduleList`；树维必须包含
     * `studentTableVms` 或 `studentTableVm`。两种指纹同时命中或均未命中时立即失败，避免把
     * 登录页、错误响应或结构变体静默当成课表。
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val root = Json.parseToJsonElement(input.text).jsonObject
        val result = root["result"] as? JsonObject
        val hfuFingerprint = result?.containsKey("lessonList") == true &&
                result.containsKey("scheduleList")
        val shuweiFingerprint = root.containsKey("studentTableVms") ||
                root.containsKey("studentTableVm")
        require(hfuFingerprint.xor(shuweiFingerprint)) {
            "cumtb 响应未命中唯一课表根结构"
        }
        val courses = if (hfuFingerprint) {
            HfuScheduleUtils.parse(input.text, "cumtb HFUInfo")
        } else {
            StudentTableScheduleUtils.parse(input.text, "cumtb 树维")
        }
        if (courses.isEmpty()) throw ParserException.empty("cumtb 排课响应中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("cumtb 排课 JSON 解析失败：${error.message}", error)
    }
}
