package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils

/** 微人大“我的课程表（本+研）”内嵌数据解析器。 */
object RucParser : Parser {
    /** 直接解析课程对象，不再生成中间 CSV 或依赖 CsvParser。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val courseList = COURSE_LIST_PATTERN.find(input.text)?.groupValues?.get(1)
            ?: throw ParserException.parse("微人大页面中缺少 course 数组")
        val result = COURSE_OBJECT_PATTERN.findAll(courseList).map { objectMatch ->
            val fields = ATTRIBUTE_PATTERN.findAll(objectMatch.value).associate { match ->
                match.groupValues[1] to filter(match.groupValues[2])
            }
            val name = fields["title"].orEmpty().ifBlank { error("微人大课程缺少名称") }
            val startNode = fields["start"]?.toIntOrNull()?.takeIf { it > 0 }
                ?: error("课程 $name 缺少起始节次")
            val step = fields["quittingTime"]?.toIntOrNull()?.takeIf { it > 0 }
                ?: error("课程 $name 缺少持续节数")
            val day = TextUtils.requireDay(fields["week"].orEmpty())
            val weekly = fields["weekly"].orEmpty()
            val match = WEEKLY_PATTERN.find(weekly) ?: error("课程 $name 的周次格式无效：$weekly")
            val weekNumbers =
                (match.groupValues[1].toInt()..match.groupValues[2].toInt()).filter { week ->
                    when (match.groupValues[3]) {
                        "单" -> week % 2 == 1; "双" -> week % 2 == 0; else -> true
                    }
                }
            WeekUtils.compact(weekNumbers).map { week ->
                CoursePreview(
                    name = name,
                    teacher = fields["teacher"].orEmpty(),
                    room = fields["place"].orEmpty(),
                    day = day,
                    startNode = startNode,
                    step = step,
                    startWeek = week.startWeek,
                    endWeek = week.endWeek,
                    type = week.type
                )
            }
        }.flatten().toList()
        if (result.isEmpty()) throw ParserException.empty("微人大课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("微人大课表解析失败：${error.message}", error)
    }

    /** 清理属性前缀和中文冒号，不制造“无”等占位内容。 */
    private fun filter(value: String): String =
        value.replace(',', '、').substringAfter(": ", value).substringAfter('：', value).trim()

    private val COURSE_LIST_PATTERN = Regex(""""course"\s*:\s*\[([\s\S]*?)]""")

    // Android ICU 会把未转义的右花括号解释为非法量词结尾，因此左右花括号都必须显式转义。
    private val COURSE_OBJECT_PATTERN = Regex("""\{[\s\S]*?\}""")
    private val ATTRIBUTE_PATTERN = Regex(""""(\S+)"\s*:\s*"([\s\S]*?)"""")
    private val WEEKLY_PATTERN = Regex("""第(\d{1,2})-(\d{1,2})周([单全双])周""")
}
