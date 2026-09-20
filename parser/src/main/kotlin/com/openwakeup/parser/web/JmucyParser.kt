package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `jmucy` 使用的集美大学诚毅学院双节课表解析器。 */
object JmucyParser : Parser {

    /**
     * 解析 `#ctl00_ContentPlaceHolder3_ScheduleTable` 中按双节排列的课表。
     *
     * 每行首格是节次标题，之后七格依次对应周一至周日；同一课程格使用 `★` 分隔多门课程。
     * 课程文本至少包含课程名、教室和末尾周次，四个及以上字段时第二项按原版解释为教师。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹、星期列或课程字段无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val fragments = input.text.split(HEAD_BOUNDARY_PATTERN)
            .filter { fragment -> fragment.isNotBlank() }
            .ifEmpty { listOf(input.text) }
        val courses = fragments.asSequence().mapNotNull { fragment ->
            val table = Jsoup.parse(fragment).getElementById(TABLE_ID) ?: return@mapNotNull null
            parseTable(table).takeIf { parsed -> parsed.isNotEmpty() }
        }.firstOrNull().orEmpty()

        if (courses.isEmpty()) throw ParserException.empty("集美大学诚毅学院课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("集美大学诚毅学院课表解析失败：${error.message}", error)
    }

    /** 按行号恢复双节起点，并跳过每行第一个节次标题格。 */
    private fun parseTable(table: Element): List<CoursePreview> {
        val courses = mutableListOf<CoursePreview>()
        table.select("tr").drop(1).forEachIndexed { rowIndex, row ->
            val startNode = rowIndex * 2 + 1
            row.select("td").drop(1).forEachIndexed cellLoop@{ dayIndex, cell ->
                val day = dayIndex + 1
                require(day in 1..7) { "集美大学诚毅学院课表星期列超过 7 列" }
                if (cell.text().isBlank()) return@cellLoop
                cell.text().split('★').filter { block -> block.isNotBlank() }.forEach { block ->
                    courses += parseCourseBlock(block.trim(), day, startNode)
                }
            }
        }
        return courses
    }

    /** 将一个空白分隔的课程块转换为精确周次课程。 */
    private fun parseCourseBlock(
        source: String,
        day: Int,
        startNode: Int,
    ): List<CoursePreview> {
        val fields = source.split(WHITESPACE_PATTERN).filter { field -> field.isNotBlank() }
        require(fields.size >= 3) { "集美大学诚毅学院课程字段不足：$source" }
        val name = fields.first()
        val teacher = if (fields.size > 3) fields[1] else ""
        val room = fields[fields.size - 2]
        val weekText = fields.last()
        require(name.isNotEmpty()) { "集美大学诚毅学院课程名称为空" }

        return WeekUtils.parse(weekText).map { week ->
            CoursePreview(
                name = name,
                teacher = teacher,
                room = room,
                day = day,
                startNode = startNode,
                step = 2,
                startWeek = week.startWeek,
                endWeek = week.endWeek,
                type = week.type,
            )
        }
    }

    private const val TABLE_ID = "ctl00_ContentPlaceHolder3_ScheduleTable"
    private val HEAD_BOUNDARY_PATTERN = Regex("""(?i)</?head(?:\s[^>]*)?>""")
    private val WHITESPACE_PATTERN = Regex("""\s+""")
}
