package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.JsonUtils
import com.openwakeup.parser.utils.WeekUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 乘方教务课表解析器。
 *
 * 页面需包含 `var kbxx = [...]` 脚本变量；解析器不读取学校名称或 URL 来选择变体。
 */
object CfParser : Parser {

    /**
     * 提取页面脚本中的课程数组并转换为课程预览。
     *
     * @param input 包含 `kbxx` 变量的完整页面文本
     * @return 按离散周次无损拆分后的课程列表
     * @throws ParserException 页面指纹缺失、JSON 字段错误或课程为空
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val match = KBXX_PATTERN.find(input.text)
            ?: throw ParserException.parse("乘方教务页面中缺少 kbxx 课表数据")
        val rows = Json.parseToJsonElement(match.groupValues[1]).jsonArray
        val courses = rows.flatMapIndexed { index, element ->
            val row = element.jsonObject
            val day = JsonUtils.requiredPositiveInt(row, "xq")
            require(day in 1..7) { "第 ${index + 1} 门课程的星期不在 1～7 范围内" }
            val nodes = JsonUtils.requiredString(row, "jcdm2")
                .split(',')
                .map { it.trim().toIntOrNull() ?: error("节次不是整数：$it") }
            require(nodes.isNotEmpty() && nodes.all { it > 0 }) { "第 ${index + 1} 门课程的节次为空或非法" }
            val startNode = nodes.first()
            val endNode = nodes.last()
            require(endNode >= startNode) { "第 ${index + 1} 门课程的结束节次小于开始节次" }
            val weekNumbers = JsonUtils.requiredString(row, "zcs")
                .split(',')
                .map { it.trim().toIntOrNull() ?: error("周次不是整数：$it") }
            WeekUtils.compact(weekNumbers).map { week ->
                CoursePreview(
                    name = JsonUtils.requiredString(row, "kcmc"),
                    teacher = JsonUtils.optionalString(row, "teaxms"),
                    room = JsonUtils.optionalString(row, "jxcdmcs"),
                    day = day,
                    startNode = startNode,
                    step = endNode - startNode + 1,
                    startWeek = week.startWeek,
                    endWeek = week.endWeek,
                    type = week.type,
                )
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("乘方教务课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("乘方教务课表解析失败：${error.message}", error)
    }

    /** 匹配页面中以分号结束的 `kbxx` JSON 数组。 */
    private val KBXX_PATTERN =
        Regex("""var\s+kbxx\s*=\s*(\[.*?])\s*;""", RegexOption.DOT_MATCHES_ALL)
}
