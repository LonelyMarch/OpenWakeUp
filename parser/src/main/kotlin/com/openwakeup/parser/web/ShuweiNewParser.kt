package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.StudentTableScheduleUtils

/** `shuwei_new` 使用的新版树维最终 JSON 响应解析器。 */
object ShuweiNewParser : Parser {

    /** 解析已经由 App 捕获的新版树维 `print-data` 原始 JSON。 */
    override fun parse(input: ParserInput): List<CoursePreview> =
        StudentTableScheduleUtils.parse(input.text, "新版树维")
}
