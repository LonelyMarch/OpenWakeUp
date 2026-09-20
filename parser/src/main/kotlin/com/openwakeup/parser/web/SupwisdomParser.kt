package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.SupwisdomUtils

/** Supwisdom 旧版 `TaskActivity` 脚本课表解析器。 */
object SupwisdomParser : Parser {
    /**
     * 解析页面脚本中课程名、教师、活动参数、周位图和网格坐标。
     *
     * @param input 包含 `table0.marshalTable` 前置脚本的完整 HTML
     * @return 周位图无损拆分后的课程预览
     * @throws ParserException 脚本指纹或活动字段不完整
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        SupwisdomUtils.parse(input.text)
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("Supwisdom 课表解析失败：${error.message}", error)
    }

}
