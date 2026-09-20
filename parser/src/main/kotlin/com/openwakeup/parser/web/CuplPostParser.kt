package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `cupl_post` 使用的 `#tabCT` 最终响应解析器。 */
object CuplPostParser : Parser {

    /**
     * 解析带 rowspan 的节次行式课表。
     *
     * `ParserInput.text` 必须是调用方已经取得的最终 HTML 响应；本类不发起 POST，也不访问
     * Cookie。首行用于判断星期日是否位于星期一之前，后续行号和 rowspan 共同确定连续节次。
     *
     * @param input 调用方提供的最终 `#tabCT` HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 表格、星期映射、周次或 rowspan 结构无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("tabCT")
            ?: throw ParserException.parse("cupl_post 响应中缺少 tabCT")
        val rows = table.select("tr")
        require(rows.size > 1) { "cupl_post tabCT 缺少课程行" }
        // 上方已经确认至少存在表头和一行课程，下标访问可避免 jsoup 的可空 first() 返回值。
        val sundayFirst = detectSundayFirst(rows[0])
        val occupiedUntilRow = IntArray(7)
        val courses = mutableListOf<CoursePreview>()

        rows.drop(1).forEachIndexed { dataRowIndex, row ->
            val node = dataRowIndex + 1
            var logicalColumn = 0
            row.select(":scope > td").forEach { cell ->
                if (NODE_LABEL_PATTERN.matches(cell.text().trim())) return@forEach
                while (logicalColumn < occupiedUntilRow.size && occupiedUntilRow[logicalColumn] >= node) {
                    logicalColumn++
                }
                require(logicalColumn in occupiedUntilRow.indices) {
                    "cupl_post 第 $node 节存在超过 7 个星期列"
                }
                val rowSpan = cell.attr("rowspan").takeIf { value -> value.isNotBlank() }
                    ?.toIntOrNull() ?: 1
                require(rowSpan > 0) { "cupl_post rowspan 必须为正整数" }
                occupiedUntilRow[logicalColumn] = node + rowSpan - 1
                if (cell.text().isNotBlank()) {
                    val day = mapDay(logicalColumn, sundayFirst)
                    courses += parseCell(cell, day, node, rowSpan)
                }
                logicalColumn++
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("cupl_post 响应中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("cupl_post 课表解析失败：${error.message}", error)
    }

    /** 比较星期日和星期一表头位置，识别周日优先的列顺序。 */
    private fun detectSundayFirst(header: Element): Boolean {
        val headers = header.select("th")
        val sunday = headers.indexOfFirst { cell -> cell.text().contains("星期日") }
        val monday = headers.indexOfFirst { cell -> cell.text().contains("星期一") }
        require(sunday >= 0 && monday >= 0) { "cupl_post 表头缺少星期一或星期日" }
        return sunday < monday
    }

    /** 把零起始逻辑列转换为当前项目统一的 1=周一、7=周日。 */
    private fun mapDay(column: Int, sundayFirst: Boolean): Int {
        val day = if (sundayFirst) SUNDAY_FIRST_DAY_MAP[column] else column + 1
        require(day in 1..7) { "cupl_post 星期列无效：$column" }
        return day
    }

    /**
     * 将一个课程格中由 `<b>` 分隔的多门课程逐项解析。
     *
     * 原版字段顺序允许在周次之后插入一行“校区”；教室和教师因此分别存在两种固定偏移。
     */
    private fun parseCell(
        cell: Element,
        day: Int,
        startNode: Int,
        rowSpan: Int
    ): List<CoursePreview> =
        cell.html().split(BOLD_SEPARATOR_PATTERN)
            .filter { fragment -> fragment.isNotBlank() }
            .flatMap { fragment -> parseCourseFragment(fragment, day, startNode, rowSpan) }

    /** 解析一段课程名称、周次、可选校区、教室和教师字段。 */
    private fun parseCourseFragment(
        source: String,
        day: Int,
        startNode: Int,
        rowSpan: Int,
    ): List<CoursePreview> {
        val fields = source.split(TAG_SEPARATOR_PATTERN)
            .map { part -> Jsoup.parse(part).text().trim() }
            .filter { part -> part.isNotEmpty() }
        if (fields.isEmpty()) return emptyList()
        val name = fields.first()
        require(name.isNotEmpty()) { "cupl_post 课程名为空" }
        val weekIndexes = fields.indices.filter { index -> isWeekField(fields[index]) }
        require(weekIndexes.isNotEmpty()) { "课程 $name 缺少周次字段" }
        return weekIndexes.flatMap { weekIndex ->
            val weekText = fields[weekIndex]
                .removePrefix("周次：").removePrefix("周次:")
                .trim()
            val hasCampus = fields.getOrNull(weekIndex + 1)?.contains("校区") == true
            val roomIndex = weekIndex + if (hasCampus) 2 else 1
            val teacherIndex = roomIndex + 1
            val room = fields.getOrNull(roomIndex).orEmpty()
                .removePrefix("教室：").removePrefix("教室:").trim()
            val teacher = fields.getOrNull(teacherIndex).orEmpty()
                .removePrefix("教师：").removePrefix("教师:").trim()
            WeekUtils.parse(weekText).map { week ->
                CoursePreview(
                    name = name,
                    teacher = teacher,
                    room = room,
                    day = day,
                    startNode = startNode,
                    step = rowSpan,
                    startWeek = week.startWeek,
                    endWeek = week.endWeek,
                    type = week.type,
                )
            }
        }
    }

    /** 判断字段是否为原版支持的“周次：”或“第…周”排课起点。 */
    private fun isWeekField(source: String): Boolean =
        source.contains("周次：") || source.contains("周次:") ||
                (source.contains('第') && source.contains('周'))

    private val SUNDAY_FIRST_DAY_MAP = intArrayOf(7, 1, 2, 3, 4, 5, 6)
    private val NODE_LABEL_PATTERN = Regex("^第.+节课?(（虚拟）)?.*")
    private val BOLD_SEPARATOR_PATTERN = Regex("(?i)<b(?:\\s[^>]*)?>")
    private val TAG_SEPARATOR_PATTERN = Regex("<[^>]+>")
}
