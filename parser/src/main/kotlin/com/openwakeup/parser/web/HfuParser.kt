package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.HfuScheduleUtils

/** 合肥工业大学排课 JSON 解析器。 */
object HfuParser : Parser {
    /**
     * 关联 `scheduleList` 与 `lessonList`，并合并同一时段的明确周集合。
     *
     * @param input 完整排课 JSON 响应
     * @return 每条日程对应的课程预览
     * @throws ParserException 关联课程缺失或时间无法映射到明确节次
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val courses = HfuScheduleUtils.parse(input.text, "合肥工业大学")
        if (courses.isEmpty()) throw ParserException.empty("合肥工业大学课表响应中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("合肥工业大学课表 JSON 解析失败：${error.message}", error)
    }
}
