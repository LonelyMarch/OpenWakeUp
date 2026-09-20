package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.QzGridUtils

/** 使用 `kbcontent1` 内容类的强智变体解析器。 */
object QzCrazyParser : Parser {
    /** 仅选择 `kbcontent1`，不回落标准内容类。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        QzGridUtils.parse(input.text, contentClass = "kbcontent1")
            .ifEmpty { throw ParserException.empty("强智 Crazy 课表中没有课程") }
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("强智 Crazy 课表解析失败：${error.message}", error)
    }
}
