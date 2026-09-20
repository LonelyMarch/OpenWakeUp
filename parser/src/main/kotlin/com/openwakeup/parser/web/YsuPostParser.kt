package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `ysu_post` 使用的研究生 DataGrid 课程块解析器。 */
object YsuPostParser : Parser {

    /**
     * 解析 `DataGrid1` 或 `MainWork_DataGrid1` 中的双换行课程块。
     *
     * 原响应可能把多个完整 HTML 片段拼接在一起，因此仍按 `<head>` 边界尝试各片段，并在
     * 第一张含有效课程的表格处停止。Parser 不执行生成该响应所需的额外 POST。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹、rowspan 网格或课程时间字段无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val fragments = input.text.split(HEAD_BOUNDARY_PATTERN)
            .filter { fragment -> fragment.isNotBlank() }
            .ifEmpty { listOf(input.text) }
        val courses = fragments.asSequence().mapNotNull { fragment ->
            val document = Jsoup.parse(fragment)
            val table = document.getElementById("DataGrid1")
                ?: document.getElementById("MainWork_DataGrid1")
                ?: return@mapNotNull null
            parseTable(table).takeIf { parsed -> parsed.isNotEmpty() }
        }.firstOrNull().orEmpty()
        if (courses.isEmpty()) throw ParserException.empty("燕大研究生 DataGrid 中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("燕大研究生课表解析失败：${error.message}", error)
    }

    /** 根据每列已占用到的节次恢复 rowspan 课程格的星期和起止节次。 */
    private fun parseTable(table: Element): List<CoursePreview> {
        val occupiedUntilNode = IntArray(7)
        val courses = mutableListOf<CoursePreview>()
        var currentNode = 1

        table.select("tr").forEach rowLoop@{ row ->
            if (row.attr("align").equals("center", ignoreCase = true)) return@rowLoop
            var columnCursor = 0
            row.select("td").forEach cellLoop@{ cell ->
                if (cell.attr("align").equals("center", ignoreCase = true)) return@cellLoop
                while (columnCursor < 7 && occupiedUntilNode[columnCursor] >= currentNode) {
                    columnCursor += 1
                }
                require(columnCursor in 0..6) { "燕大研究生第 $currentNode 节的星期列超过 7 列" }
                val rowSpan = cell.attr("rowspan").toIntOrNull() ?: 1
                require(rowSpan > 0) { "燕大研究生 DataGrid rowspan 必须为正整数" }
                occupiedUntilNode[columnCursor] = currentNode + rowSpan - 1
                if (cell.text().isNotBlank()) {
                    courses += parseCell(cell, columnCursor + 1, currentNode, rowSpan)
                }
                columnCursor += 1
            }
            currentNode += 1
        }
        return courses
    }

    /** 扫描一个课程格内由连续 `<br>` 分隔的全部课程块。 */
    private fun parseCell(
        cell: Element,
        day: Int,
        startNode: Int,
        step: Int,
    ): List<CoursePreview> = cell.html().split(DOUBLE_BR_PATTERN).flatMap { block ->
        val lines = block.split(BR_PATTERN)
            .map { fragment -> Jsoup.parse(fragment).text().trim() }
            .filter { line -> line.isNotEmpty() }
        lines.mapIndexedNotNull { index, line ->
            if (!line.contains("周]")) return@mapIndexedNotNull null
            require(index > 0) { "燕大研究生课程时间行前缺少课程名称" }
            parseScheduleLine(
                name = lines[index - 1],
                source = line,
                day = day,
                startNode = startNode,
                step = step,
            )
        }.flatten()
    }

    /** 从 `[第 X-Y 周]` 及其教师、教室标签生成精确周次课程。 */
    private fun parseScheduleLine(
        name: String,
        source: String,
        day: Int,
        startNode: Int,
        step: Int,
    ): List<CoursePreview> {
        require(name.isNotBlank()) { "燕大研究生课程名称为空" }
        val weekBody = WEEK_FIELD_PATTERN.find(source)?.groupValues?.get(1)?.trim()
            ?: throw IllegalArgumentException("课程 $name 缺少 [周次] 字段")
        val weekParts = weekBody.split(WEEK_SEPARATOR_PATTERN).filter { part -> part.isNotBlank() }
        require(weekParts.isNotEmpty()) { "课程 $name 的周次字段为空" }
        val room = labeledValue(source, "教室")
        val teacher = labeledValue(source, "教师")

        return weekParts.flatMap { rawPart ->
            // “单双”在原版中表示不限奇偶，必须先删除，避免被 WeekUtils 误判为单周。
            val part = rawPart.replace("单双", "").removePrefix("第").trim() + "周"
            WeekUtils.parse(part).map { week ->
                CoursePreview(
                    name = name.trim(),
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

    /** 读取 `(教室:...)` 或 `(教师：...)` 标签值；字段缺失时保留为空。 */
    private fun labeledValue(source: String, label: String): String =
        Regex("$label\\s*[:：]\\s*([^)]*)").find(source)?.groupValues?.get(1)?.trim().orEmpty()

    private val HEAD_BOUNDARY_PATTERN = Regex("(?i)</?head(?:\\s[^>]*)?>")
    private val DOUBLE_BR_PATTERN = Regex("(?i)(?:<br\\s*/?>\\s*){2,}")
    private val BR_PATTERN = Regex("(?i)<br\\s*/?>")
    private val WEEK_FIELD_PATTERN = Regex("\\[([^]]*?)周\\]")
    private val WEEK_SEPARATOR_PATTERN = Regex("[、,，]")
}
