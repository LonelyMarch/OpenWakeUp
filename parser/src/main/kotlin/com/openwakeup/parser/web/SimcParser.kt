package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.EamsScriptUtils

/** 上海电影艺术职业学院旧 EAMS 课程脚本解析器。 */
object SimcParser : Parser {

    /**
     * 解析 `table.marshalTable` 和 `TaskActivity` 脚本。
     *
     * @param input 完整课表 HTML
     * @return 按周次和连续节次无损拆分的课程
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        EamsScriptUtils.parse(
            source = input.text,
            tableVariable = "table",
            nodeMapper = ::mapNode,
            splitAfter = setOf(4),
            nameTransform = { name -> name.replace(COURSE_SUFFIX_PATTERN, "") },
        )
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("SIMC 课表解析失败：${error.message}", error)
    }

    /**
     * 把页面零基节次转换为应用节次，并展开四种跨时段特殊占位。
     *
     * @param rawNode 页面脚本中的零基节次
     * @return 一个或多个真实应用节次
     */
    private fun mapNode(rawNode: Int): List<Int> {
        val mapped = when {
            rawNode == 13 -> 10
            rawNode < 9 -> rawNode + 1
            else -> rawNode + 2
        }
        return when (mapped) {
            11 -> (1..5).toList()
            12 -> (6..10).toList()
            13 -> (1..8).toList()
            14 -> listOf(9, 10)
            else -> listOf(mapped)
        }
    }

    private val COURSE_SUFFIX_PATTERN = Regex("""\(\d{4}\)\(.*?\)""")
}
