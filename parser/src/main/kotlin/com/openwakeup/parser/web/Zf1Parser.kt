package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.ZfTimeParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * `zf_1` 对应的旧版正方纯文本单元格解析器。
 *
 * 页面网格仍使用 `Table1` 或 `kbgrid_table`，但单元格不依赖 `<br>`，而是以包含花括号周次的
 * 时间 token 作为课程边界。该入口与 [ZfParser] 独立，禁止在 Parser 内根据 type 切换模式。
 */
object Zf1Parser : Parser {

    /**
     * 解析正方 type 1 课表 HTML。
     *
     * @param input `text` 为完整课表页 HTML
     * @return 非空课程预览列表
     * @throws ParserException 页面指纹不匹配或课程字段无法无损转换
     */
    override fun parse(input: ParserInput): List<CoursePreview> {
        try {
            val document = Jsoup.parse(input.text)
            val table = document.getElementById("Table1")
                ?: document.getElementById("kbgrid_table")
                ?: throw ParserException.parse("页面中没有正方 type 1 课表表格")
            requireGridFingerprint(table)

            val result = mutableListOf<CoursePreview>()
            var nodeRowCount = 0
            table.select("tr").forEach { row ->
                var startNode = -1
                var day = 0
                val cells = row.children()
                    .filter { child -> child.tagName().equals("td", ignoreCase = true) }
                cells.forEach cellLoop@{ cell ->
                    val text = cell.text().trim()
                    parseHeaderNode(text)?.let { node ->
                        startNode = node
                        nodeRowCount++
                        return@cellLoop
                    }
                    if (startNode < 1) return@cellLoop

                    day++
                    if (text.isBlank() || text in OTHER_HEADERS) return@cellLoop
                    if (day !in 1..7) {
                        throw ParserException.parse("正方 type 1 课表出现第 $day 个星期列")
                    }
                    val fallbackStep = cell.attr("rowspan").toIntOrNull()?.takeIf { it > 0 } ?: 1
                    parseTextCell(text).forEach { course ->
                        val times = try {
                            ZfTimeParser.parse(course.time, day, startNode, fallbackStep)
                        } catch (error: IllegalArgumentException) {
                            throw ParserException.parse(
                                "课程“${course.name}”的时间字段无法解析",
                                error
                            )
                        }
                        times.forEach { time ->
                            result += CoursePreview(
                                name = course.name,
                                teacher = course.teacher,
                                room = course.room,
                                day = time.day,
                                startNode = time.startNode,
                                step = time.step,
                                startWeek = time.week.startWeek,
                                endWeek = time.week.endWeek,
                                type = time.week.type,
                            )
                        }
                    }
                }
            }
            if (nodeRowCount == 0) throw ParserException.parse("正方 type 1 课表没有节次行")
            if (result.isEmpty()) throw ParserException.empty("正方 type 1 课表中没有课程")
            return result
        } catch (error: ParserException) {
            throw error
        } catch (error: Exception) {
            throw ParserException.parse("正方 type 1 课表解析失败", error)
        }
    }

    /**
     * 按时间 token 边界还原文本单元格中的多门课程。
     *
     * @param text 单元格的完整可见文本
     * @return 课程名称、时间、教师和教室记录
     * @throws ParserException 文本存在但找不到完整课程边界
     */
    private fun parseTextCell(text: String): List<TextZfCourse> {
        val tokens = text.split(WHITESPACE_PATTERN).filter(String::isNotBlank)
        val timeIndexes = tokens.indices.filter { index -> isTimeToken(tokens[index]) }
        if (timeIndexes.isEmpty()) {
            throw ParserException.parse("正方 type 1 单元格没有周次时间标记：$text")
        }

        return timeIndexes.mapIndexed { courseIndex, timeIndex ->
            val propertyIndex = timeIndex - 1
            val nameIndex = if (
                tokens.getOrNull(propertyIndex)?.let(COURSE_PROPERTIES::contains) == true
            ) {
                timeIndex - 2
            } else {
                propertyIndex
            }
            val name = tokens.getOrNull(nameIndex)?.trim().orEmpty()
            if (name.isEmpty()) throw ParserException.parse("正方 type 1 课程名称为空")

            val nextTimeIndex = timeIndexes.getOrNull(courseIndex + 1)
            val nextNameIndex = nextTimeIndex?.let { index ->
                if (tokens.getOrNull(index - 1)?.let(COURSE_PROPERTIES::contains) == true) {
                    index - 2
                } else {
                    index - 1
                }
            } ?: tokens.size
            if (nextNameIndex < timeIndex + 1) {
                throw ParserException.parse("正方 type 1 课程边界发生重叠：$text")
            }
            val details = tokens.subList(timeIndex + 1, nextNameIndex)
            TextZfCourse(
                name = name,
                time = tokens[timeIndex],
                teacher = details.firstOrNull().orEmpty(),
                room = details.drop(1).joinToString(" "),
            )
        }
    }

    /** 判断 token 是否同时包含花括号和周次语义。 */
    private fun isTimeToken(token: String): Boolean =
        token.contains('{') && token.contains('}') && token.contains('周')

    /** 验证 type 1 页面仍属于旧版正方星期网格。 */
    private fun requireGridFingerprint(table: Element) {
        val text = table.text()
        if (!text.contains("星期一") || !text.contains("星期二") || !text.contains("节")) {
            throw ParserException.parse("页面中的表格不符合正方 type 1 结构")
        }
    }

    /** 从行标题中读取正整数节次。 */
    private fun parseHeaderNode(text: String): Int? = HEADER_NODE_PATTERN.matchEntire(text)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

    private data class TextZfCourse(
        val name: String,
        val time: String,
        val teacher: String,
        val room: String,
    )

    private val HEADER_NODE_PATTERN = Regex("""第\s*(\d{1,2})\s*节""")
    private val WHITESPACE_PATTERN = Regex("""\s+""")
    private val OTHER_HEADERS = setOf(
        "时间", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日",
        "早晨", "上午", "下午", "晚上", "节次",
    )
    private val COURSE_PROPERTIES = setOf(
        "任选", "限选", "实践选修", "必修课", "选修课", "必修", "选修", "专基", "专选",
        "公必", "公选", "义修", "选", "必", "主干", "公共必修", "专业必修", "专业选修",
    )
}
