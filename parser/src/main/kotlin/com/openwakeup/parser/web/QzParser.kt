package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.QzGridUtils

/** 标准旧版强智 `kbcontent` 课表解析器。 */
object QzParser : Parser {
    /** 解析标准强智网格，不读取 [ParserInput.type] 选择其他变体。 */
    override fun parse(input: ParserInput): List<CoursePreview> = parseQz("标准强智") {
        QzGridUtils.parse(input.text)
    }
}

/** 执行强智纯解析并统一包装结构错误。 */
private inline fun parseQz(label: String, block: () -> List<CoursePreview>): List<CoursePreview> =
    try {
        block().ifEmpty { throw ParserException.empty("$label 课表中没有课程") }
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("$label 课表解析失败：${error.message}", error)
    }
