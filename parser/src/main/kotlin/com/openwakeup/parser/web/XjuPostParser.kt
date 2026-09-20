package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `xju_post` 使用的研究生 DataGrid 课表解析器。 */
object XjuPostParser : Parser {

    /**
     * 解析调用方已取得的研究生课表 HTML。
     *
     * @param input 包含 `ctl00_contentParent_dgData` 或 `contentParent_dgData` 的页面
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹缺失、网格坐标越界或课程周次不明确
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val document = Jsoup.parse(input.text)
        val table = document.getElementById("ctl00_contentParent_dgData")
            ?: document.getElementById("contentParent_dgData")
            ?: throw ParserException.parse("页面中缺少 xju_post DataGrid 课表")
        val courses = parseTable(table)
        if (courses.isEmpty()) throw ParserException.empty("xju_post DataGrid 中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("xju_post 课表解析失败：${error.message}", error)
    }

    /**
     * 根据表头顺序和 rowspan 占用恢复七日网格。
     *
     * @param table 已通过固定 ID 识别的课表
     * @return 表格内所有带明确周次的课程
     */
    private fun parseTable(table: Element): List<CoursePreview> {
        val rows = table.select("tr")
        require(rows.isNotEmpty()) { "xju_post DataGrid 没有表格行" }
        val headers = rows.first()?.select("th")?.map { cell -> cell.text().trim() }.orEmpty()
        val sundayIndex = headers.indexOfFirst { header -> header.contains("星期日") }
        val mondayIndex = headers.indexOfFirst { header -> header.contains("星期一") }
        val sundayFirst = sundayIndex >= 0 && mondayIndex >= 0 && sundayIndex < mondayIndex

        val occupiedUntilNode = IntArray(7)
        val courses = mutableListOf<CoursePreview>()
        rows.drop(1).forEachIndexed { rowOffset, row ->
            val currentNode = rowOffset + 1
            var columnCursor = 0
            row.select("td").forEach cellLoop@{ cell ->
                if (cell.attr("align").equals("center", ignoreCase = true)) return@cellLoop
                while (columnCursor < 7 && occupiedUntilNode[columnCursor] >= currentNode) {
                    columnCursor += 1
                }
                require(columnCursor in 0..6) { "xju_post 第 $currentNode 节的星期列超过 7 列" }

                val rowSpan = cell.attr("rowspan").toIntOrNull() ?: 1
                require(rowSpan > 0) { "xju_post rowspan 必须为正整数" }
                occupiedUntilNode[columnCursor] = currentNode + rowSpan - 1
                if (cell.text().isNotBlank()) {
                    val day = if (sundayFirst) SUNDAY_FIRST_DAY[columnCursor] else columnCursor + 1
                    courses += parseCell(cell, day, currentNode, rowSpan)
                }
                columnCursor += 1
            }
        }
        return courses
    }

    /**
     * 解析一个课程单元格中的全角花括号课程块。
     *
     * @param cell 当前课程单元格
     * @param day 已恢复的星期
     * @param startNode 当前表格行对应的起始节次
     * @param step 单元格 rowspan 对应的连续节数
     * @return 单元格内每门课程的全部周次片段
     */
    private fun parseCell(cell: Element, day: Int, startNode: Int, step: Int): List<CoursePreview> {
        val normalized = cell.text().replace('{', '｛').replace('}', '｝').trim()
        return normalized.split('；', ';').filter { block -> block.isNotBlank() }
            .flatMap { rawBlock ->
                val block = rawBlock.trim()
                require('｛' in block && '｝' in block) {
                    "xju_post 课程块缺少明确周次花括号：$block"
                }
                val name = block.substringBefore('｛').trim()
                val detail = block.substringAfter('｛').substringBeforeLast('｝').trim()
                require(name.isNotEmpty()) { "xju_post 课程名为空" }

                val defaultTeacher = labeledValue(detail, "教师")
                val defaultRoom = labeledValue(detail, "地点")
                val weekParts = detail.split('、').filter { part -> part.isNotBlank() }
                require(weekParts.isNotEmpty()) { "课程 $name 缺少周次" }
                weekParts.flatMap { part ->
                    val weekText = part.substringBefore('[').substringBefore('【').trim()
                    require(weekText.isNotEmpty()) { "课程 $name 的周次字段为空" }
                    val teacher = labeledValue(part, "教师").ifEmpty { defaultTeacher }
                    val room = labeledValue(part, "地点").ifEmpty { defaultRoom }
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
    }

    /**
     * 从方括号详情中读取教师或地点字段。
     *
     * 字段结束位置由下一个已知标签或右方括号确定，兼容半角和全角冒号。
     */
    private fun labeledValue(text: String, label: String): String {
        val matches = LABEL_PATTERN.findAll(text).toList()
        val targetIndex = matches.indexOfFirst { match -> match.groupValues[1] == label }
        if (targetIndex < 0) return ""
        val target = matches[targetIndex]
        val end = matches.getOrNull(targetIndex + 1)?.range?.first ?: text.length
        return text.substring(target.range.last + 1, end)
            .substringBefore(']').substringBefore('】')
            .trim(' ', ',', '，', ';', '；')
    }

    /** 周日排在第一列时，视觉列到标准星期的映射。 */
    private val SUNDAY_FIRST_DAY = intArrayOf(7, 1, 2, 3, 4, 5, 6)
    private val LABEL_PATTERN = Regex("(教师|地点)\\s*[:：]")
}
