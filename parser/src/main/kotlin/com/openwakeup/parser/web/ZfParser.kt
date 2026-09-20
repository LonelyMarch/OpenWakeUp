package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.ZfTimeParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * `zf` 对应的旧版正方 HTML 网格解析器。
 *
 * 只接受带 `Table1` 或 `kbgrid_table` 的锚点页面。课程单元格以两个及以上 `<br>` 分隔课程，
 * 单个 `<br>` 分隔课程名称、属性、时间、教师和教室；不再回落到页面中的任意表格。
 */
object ZfParser : Parser {

    /**
     * 解析旧版正方课表 HTML。
     *
     * @param input `text` 为完整课表页 HTML；`type` 已由静态工厂消费
     * @return 非空课程预览列表
     * @throws ParserException 页面指纹、课程字段或时间字段不符合契约
     */
    override fun parse(input: ParserInput): List<CoursePreview> {
        try {
            val document = Jsoup.parse(input.text)
            val table = document.getElementById("Table1")
                ?: document.getElementById("kbgrid_table")
                ?: throw ParserException.parse("页面中没有旧版正方课表表格（Table1/kbgrid_table）")
            requireZfGridFingerprint(table)

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
                        throw ParserException.parse("旧版正方课表出现第 $day 个星期列")
                    }
                    val fallbackStep = cell.attr("rowspan").toIntOrNull()?.takeIf { it > 0 } ?: 1
                    parseHtmlCell(cell).forEach { course ->
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
            if (nodeRowCount == 0) throw ParserException.parse("旧版正方课表没有节次行")
            if (result.isEmpty()) throw ParserException.empty("旧版正方课表中没有课程")
            return result
        } catch (error: ParserException) {
            throw error
        } catch (error: Exception) {
            throw ParserException.parse("旧版正方课表解析失败", error)
        }
    }

    /**
     * 将一个 HTML 单元格拆成课程记录。
     *
     * @param cell 星期网格中的课程单元格
     * @return 单元格内按原顺序排列的课程记录
     * @throws ParserException 单元格存在课程文本但缺少名称或时间
     */
    private fun parseHtmlCell(cell: Element): List<LegacyZfCourse> {
        val html = cell.html().replace("\r", "").replace("\n", "")
        val abnormalSingleDetail = THREE_OR_MORE_BREAKS.containsMatchIn(html)
        return html.split(COURSE_SEPARATOR)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { block ->
                val lines = block.split(SINGLE_BREAK)
                    .map { fragment -> Jsoup.parseBodyFragment(fragment).text().trim() }
                    .filter(String::isNotEmpty)
                if (lines.isEmpty()) throw ParserException.parse("旧版正方课程单元格为空")

                val name = lines.first().trim()
                var timeIndex = 1
                if (lines.getOrNull(timeIndex)
                        ?.let(COURSE_PROPERTIES::contains) == true
                ) timeIndex++
                val timeLines = lines.drop(timeIndex).takeWhile(::isTimeLine)
                if (timeLines.isEmpty()) throw ParserException.parse("课程“$name”缺少时间字段")
                val time = timeLines.joinToString(";")
                val details = lines.drop(timeIndex + timeLines.size)
                val teacher: String
                val room: String
                when {
                    details.isEmpty() -> {
                        teacher = ""
                        room = ""
                    }

                    details.size == 1 && abnormalSingleDetail -> {
                        teacher = details.first()
                        room = ""
                    }

                    details.size == 1 -> {
                        teacher = ""
                        room = details.first()
                    }

                    else -> {
                        teacher = details.first()
                        room = details.drop(1).joinToString(" ")
                    }
                }
                if (name.isBlank()) throw ParserException.parse("正方课程名称为空")
                LegacyZfCourse(name, time, teacher, room)
            }
    }

    /** 验证表格至少包含星期标题，避免把登录页或普通数据表误判为课表。 */
    private fun requireZfGridFingerprint(table: Element) {
        val text = table.text()
        if (!text.contains("星期一") || !text.contains("星期二") || !text.contains("节")) {
            throw ParserException.parse("页面中的表格不符合旧版正方课表结构")
        }
    }

    /** 判断一行是否同时具备周次和数字时间语义，避免把姓周的教师误当作时间段。 */
    private fun isTimeLine(text: String): Boolean =
        text.contains('周') && text.any(Char::isDigit) && (text.contains('{') || text.contains('节'))

    /** 从行标题中读取正整数节次。 */
    private fun parseHeaderNode(text: String): Int? = HEADER_NODE_PATTERN.matchEntire(text)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

    private data class LegacyZfCourse(
        val name: String,
        val time: String,
        val teacher: String,
        val room: String,
    )

    private val HEADER_NODE_PATTERN = Regex("""第\s*(\d{1,2})\s*节""")
    private val SINGLE_BREAK = Regex("""(?i)<br\s*/?>""")
    private val COURSE_SEPARATOR = Regex("""(?i)(?:<br\s*/?>\s*){2,}""")
    private val THREE_OR_MORE_BREAKS = Regex("""(?i)(?:<br\s*/?>\s*){3,}""")
    private val OTHER_HEADERS = setOf(
        "时间", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日",
        "早晨", "上午", "下午", "晚上", "节次",
    )
    private val COURSE_PROPERTIES = setOf(
        "任选", "限选", "实践选修", "必修课", "选修课", "必修", "选修", "专基", "专选",
        "公必", "公选", "义修", "选", "必", "主干", "公共必修", "专业必修", "专业选修",
    )
}
