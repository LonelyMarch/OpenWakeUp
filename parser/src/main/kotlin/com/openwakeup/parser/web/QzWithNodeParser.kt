package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.QzGridUtils

/** 从课程属性读取明确节次的强智变体解析器。 */
object QzWithNodeParser : Parser {
    /** 解析周次(节次)组合属性或独立周次、节次属性。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        QzGridUtils.parse(input.text, nameBeforeBreak = true, explicitNodes = true)
            .ifEmpty { throw ParserException.empty("带节次强智课表中没有课程") }
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("带节次强智课表解析失败：${error.message}", error)
    }
}
