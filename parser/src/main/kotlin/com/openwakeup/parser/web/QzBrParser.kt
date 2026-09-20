package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.QzGridUtils

/** 课程名位于首个换行前的强智变体解析器。 */
object QzBrParser : Parser {
    /** 使用独立入口解析 `qz_br` 页面，不在运行时按 type 分派。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        QzGridUtils.parse(input.text, nameBeforeBreak = true)
            .ifEmpty { throw ParserException.empty("强智 BR 课表中没有课程") }
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("强智 BR 课表解析失败：${error.message}", error)
    }
}
