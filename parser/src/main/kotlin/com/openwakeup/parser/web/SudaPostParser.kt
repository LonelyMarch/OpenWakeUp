package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** 苏大式研究生 DataGrid 课表解析器，规范 type 为 `suda_post`。 */
object SudaPostParser : Parser {

    /**
     * 解析 `DataGrid1` 或 `MainWork_DataGrid1` 七日网格。
     *
     * 原版通过每列的 rowspan 占用终点恢复因合并单元格而缺失的星期列。本实现保持该算法，
     * 但对列越界、节次越界、课程名缺失和周次缺失全部失败关闭。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹缺失、课表为空或字段解析失败
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val fragments =
            input.text.split(HEAD_BOUNDARY_PATTERN).filter { fragment -> fragment.isNotBlank() }
                .ifEmpty { listOf(input.text) }
        val courses = fragments.asSequence().mapNotNull { fragment ->
            val document = Jsoup.parse(fragment)
            val table = document.getElementById("DataGrid1")
                ?: document.getElementById("MainWork_DataGrid1")
                ?: return@mapNotNull null
            parseTable(table).takeIf { parsed -> parsed.isNotEmpty() }
        }.firstOrNull().orEmpty()

        if (courses.isEmpty()) throw ParserException.empty("研究生 DataGrid 课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("研究生 DataGrid 课表解析失败：${error.message}", error)
    }

    /**
     * 解码一个 DataGrid 网格及其 rowspan 坐标。
     *
     * @param table 已通过 ID 指纹识别的课表元素
     * @return 该表格中的课程预览
     */
    private fun parseTable(table: Element): List<CoursePreview> {
        val courses = mutableListOf<CoursePreview>()
        val occupiedUntilNode = IntArray(7)
        var currentNode = 1

        table.select("tr").forEach rowLoop@{ row ->
            if (row.attr("align").equals("center", ignoreCase = true)) return@rowLoop
            var columnCursor = 0

            row.select("td").forEach cellLoop@{ cell ->
                if (cell.attr("align").equals("center", ignoreCase = true)) return@cellLoop
                while (columnCursor < occupiedUntilNode.size && occupiedUntilNode[columnCursor] >= currentNode) {
                    columnCursor += 1
                }
                require(columnCursor in occupiedUntilNode.indices) {
                    "研究生 DataGrid 第 $currentNode 节的星期列超过 7 列"
                }

                val rowSpan = cell.attr("rowspan").toIntOrNull() ?: 1
                require(rowSpan > 0) { "研究生 DataGrid rowspan 必须为正整数" }
                occupiedUntilNode[columnCursor] += rowSpan
                if (cell.text().isNotBlank()) {
                    cell.html().split(MULTI_COURSE_SEPARATOR_PATTERN).forEach { courseHtml ->
                        courses += parseCourseBlock(
                            html = courseHtml,
                            day = columnCursor + 1,
                            startNode = currentNode,
                            step = rowSpan,
                        )
                    }
                }
                columnCursor += 1
            }

            currentNode += 1
        }

        return courses
    }

    /**
     * 解析一个由连续 `<br>` 分隔出的课程块。
     *
     * @param html 单门课程的 HTML 片段
     * @param day 根据网格列和 rowspan 占用恢复的星期
     * @param startNode 根据网格行恢复的起始节次
     * @param step 单元格 rowspan 表示的连续节数
     * @return 课程在各个明确周次片段上的预览列表
     */
    private fun parseCourseBlock(
        html: String,
        day: Int,
        startNode: Int,
        step: Int,
    ): List<CoursePreview> {
        val lines = html.split(BR_PATTERN).map { line -> Jsoup.parse(line).text().trim() }
        if (lines.all { line -> line.isEmpty() }) return emptyList()

        var name = ""
        var teacher = ""
        var room = ""
        var weekLine = ""
        lines.forEach { line ->
            when {
                line.contains("课程:") -> name = line.substringAfter("课程:").trim()
                line.contains("课程：") -> name = line.substringAfter("课程：").trim()
                line.startsWith('(') && !line.contains("辅讲教师") -> {
                    room = line.substringAfter('(').substringBefore(')').trim()
                }

                WEEK_MARKER_PATTERN.containsMatchIn(line) -> weekLine = line
                line.contains("主讲教师:") -> teacher = line.substringAfter("主讲教师:").trim()
                line.contains("主讲教师：") -> teacher = line.substringAfter("主讲教师：").trim()
            }
        }
        if (name.isEmpty()) name = lines.firstOrNull().orEmpty().trim()
        require(name.isNotEmpty()) { "研究生 DataGrid 课程块缺少名称" }
        require(weekLine.isNotEmpty()) { "课程 $name 缺少周次" }

        val weekParts = weekLine.split(WEEK_SEPARATOR_PATTERN).filter { part -> part.isNotBlank() }
        require(weekParts.isNotEmpty()) { "课程 $name 的周次字段为空" }
        return weekParts.flatMap { part ->
            require(WEEK_MARKER_PATTERN.containsMatchIn(part)) { "课程 $name 的周次无法识别：$part" }
            // “单双周”在原版中表示不限制奇偶；先清除组合标记，避免被误判成单周。
            val normalized = part.substringAfter("第", part)
                .replace("单双周", "周")
                .replace("单双", "")
                .trim()
            WeekUtils.parse(normalized).map { week ->
                CoursePreview(
                    name = name,
                    teacher = teacher,
                    room = room,
                    day = day,
                    startNode = startNode,
                    step = step,
                    startWeek = week.startWeek,
                    endWeek = week.endWeek,
                    type = week.type,
                )
            }
        }
    }

    private val HEAD_BOUNDARY_PATTERN = Regex("""(?i)</?head(?:\s[^>]*)?>""")
    private val MULTI_COURSE_SEPARATOR_PATTERN = Regex("""(?i)(?:<br\s*/?>\s*){2,}""")
    private val BR_PATTERN = Regex("""(?i)<br\s*/?>""")
    private val WEEK_MARKER_PATTERN = Regex("""\d+\s*周""")
    private val WEEK_SEPARATOR_PATTERN = Regex("""[、,，;；]""")
}
