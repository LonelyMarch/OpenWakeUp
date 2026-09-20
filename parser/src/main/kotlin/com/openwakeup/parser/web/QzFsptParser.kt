package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.QzGridUtils

/** 福建商贸学校长分隔线强智课表解析器。 */
object QzFsptParser : Parser {
    /** 使用 FSPT 的长横线与周次文本格式解析课程网格。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        QzGridUtils.parse(input.text, separator = "---------------------", weekBeforeZhou = true)
            .ifEmpty { throw ParserException.empty("FSPT 课表中没有课程") }
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("FSPT 课表解析失败：${error.message}", error)
    }
}
