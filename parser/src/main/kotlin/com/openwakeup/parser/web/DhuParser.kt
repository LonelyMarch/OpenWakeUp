package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `dhu` 使用的东华大学 rowspan 周课表解析器。 */
object DhuParser : Parser {

    /**
     * 解析页面第一张表格，并根据每个星期列的 rowspan 占用终点恢复逻辑坐标。
     *
     * 每个非空课程格按“课程名、周次、教师、教室”四项一组读取。无法识别的表头行会跳过，
     * 但一旦进入合法节次行，列越界、分组残缺、周次无效或 rowspan 非法都会终止解析。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 表格、网格坐标或课程字段无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).selectFirst("table")
            ?: throw ParserException.parse("东华大学课表缺少表格")
        val courses = parseTable(table)
        if (courses.isEmpty()) throw ParserException.empty("东华大学课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("东华大学课表解析失败：${error.message}", error)
    }

    /** 使用七列占用终点表解码一张东华大学课表。 */
    private fun parseTable(table: Element): List<CoursePreview> {
        val courses = mutableListOf<CoursePreview>()
        val occupiedUntilNode = IntArray(7)

        table.select("tr").forEach { row ->
            val cells = row.select("td")
            if (cells.isEmpty()) return@forEach
            // Jsoup 的 Elements.first() 带可空类型；此处已经确认集合非空，按下标读取首格。
            val startNode = parseNode(cells[0].text().trim().removeSuffix("节"))
                ?: return@forEach
            var logicalColumn = 0

            cells.drop(1).forEach { cell ->
                while (logicalColumn < 7 && occupiedUntilNode[logicalColumn] >= startNode) {
                    logicalColumn += 1
                }
                require(logicalColumn in 0..6) { "东华大学第 $startNode 节的星期列超过 7 列" }
                val rawRowSpan = cell.attr("rowspan").trim()
                val rowSpan = if (rawRowSpan.isEmpty()) {
                    1
                } else {
                    rawRowSpan.toIntOrNull()
                        ?: throw IllegalArgumentException("东华大学 rowspan 不是整数：$rawRowSpan")
                }
                require(rowSpan > 0 && startNode + rowSpan - 1 <= 20) {
                    "东华大学第 $startNode 节的 rowspan 无效"
                }
                occupiedUntilNode[logicalColumn] = startNode + rowSpan - 1

                if (cell.text().isNotBlank()) {
                    courses += parseCell(
                        cell = cell,
                        day = logicalColumn + 1,
                        startNode = startNode,
                        step = rowSpan,
                    )
                }
                logicalColumn += 1
            }
        }
        return courses
    }

    /** 将单元格内连续的四字段课程组转换为课程预览。 */
    private fun parseCell(
        cell: Element,
        day: Int,
        startNode: Int,
        step: Int,
    ): List<CoursePreview> {
        val fields = cell.text().trim().split(WHITESPACE_PATTERN)
            .filter { field -> field.isNotBlank() }
        require(fields.size % 4 == 0) { "东华大学课程格不是完整的四字段分组：${cell.text()}" }

        return fields.chunked(4).flatMap { group ->
            val name = group[0]
            val weekText = group[1]
            val teacher = group[2]
            val room = group[3]
            require(name.isNotEmpty()) { "东华大学课程名称为空" }
            WeekUtils.parse(weekText).map { week ->
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

    /** 把页面使用的一至二十中文节次转换为正整数。 */
    private fun parseNode(text: String): Int? = when (text) {
        "一" -> 1
        "二" -> 2
        "三" -> 3
        "四" -> 4
        "五" -> 5
        "六" -> 6
        "七" -> 7
        "八" -> 8
        "九" -> 9
        "十" -> 10
        "十一" -> 11
        "十二" -> 12
        "十三" -> 13
        "十四" -> 14
        "十五" -> 15
        "十六" -> 16
        "十七" -> 17
        "十八" -> 18
        "十九" -> 19
        "二十" -> 20
        else -> text.toIntOrNull()?.takeIf { node -> node in 1..20 }
    }

    private val WHITESPACE_PATTERN = Regex("""\s+""")
}
