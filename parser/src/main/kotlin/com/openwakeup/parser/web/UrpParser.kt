package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * `urp` 对应的旧版 URP 行式 HTML 解析器。
 *
 * 支持 `displayTag`、Bootstrap 条纹表和原版 `table#tb` 三种明确表格锚点。课程名、教师等使用
 * `rowspan` 跨行时，解析器先把物理单元格展开为逻辑列，再逐行读取星期、节次和周次。
 */
object UrpParser : Parser {

    /**
     * 解析旧版 URP 课表 HTML。
     *
     * @param input `text` 为完整课表页 HTML
     * @return 非空课程预览列表
     * @throws ParserException 没有 URP 表格、表头不完整或任一课程时间字段非法
     */
    override fun parse(input: ParserInput): List<CoursePreview> {
        try {
            val document = Jsoup.parse(input.text)
            val candidates = document.select(
                "table.displayTag, table.table.table-striped.table-bordered, table#tb",
            )
            if (candidates.isEmpty()) throw ParserException.parse("页面中没有旧版 URP 课表表格")

            val result = mutableListOf<CoursePreview>()
            var matchedTableCount = 0
            candidates.forEach { table ->
                // 含完整星期表头的是网格视图，不属于旧 URP 行式契约。
                if (table.text().contains("星期一") && table.text().contains("星期二")) {
                    return@forEach
                }
                val headerCells = table.select("thead tr").lastOrNull()?.directChildren("th")
                    ?: table.select("tr")
                        .firstOrNull { row -> row.directChildren("th").isNotEmpty() }
                        ?.directChildren("th")
                    ?: return@forEach
                val headers = expandHeaderCells(headerCells)
                val columns = resolveColumns(headers) ?: return@forEach
                matchedTableCount++

                var previousName: String? = null
                var previousTeacher = ""
                expandBodyRows(
                    table,
                    headers.size,
                    columns.week
                ).forEachIndexed { rowIndex, cells ->
                    val dayText = cells[columns.day]?.text()?.trim().orEmpty()
                    val weekText = cells[columns.week]?.text()?.trim().orEmpty()
                    val nodeText = cells[columns.node]?.text()?.trim().orEmpty()
                    val rowHasSchedule =
                        dayText.isNotEmpty() || weekText.isNotEmpty() || nodeText.isNotEmpty()
                    if (!rowHasSchedule) return@forEachIndexed

                    val nameCell = cells[columns.name]
                    val name =
                        if (nameCell == null) previousName.orEmpty() else nameCell.text().trim()
                    if (name.isEmpty()) {
                        throw ParserException.parse("旧 URP 第 ${rowIndex + 1} 行缺少课程名")
                    }
                    if (nameCell != null) previousName = name
                    val day = parseDay(dayText)
                        ?: throw ParserException.parse("课程“$name”的星期无法解析：$dayText")
                    val nodeRange = parseNodeRange(nodeText)
                        ?: throw ParserException.parse("课程“$name”的节次无法解析：$nodeText")
                    val step = columns.step?.let { index ->
                        val stepText = cells[index]?.text()?.trim().orEmpty()
                        parsePositiveNumber(stepText)
                            ?: throw ParserException.parse("课程“$name”的节数无法解析：$stepText")
                    } ?: (nodeRange.last - nodeRange.first + 1)
                    if (step <= 0) throw ParserException.parse("课程“$name”的连续节数必须大于 0")

                    val weeks = try {
                        WeekUtils.parse(weekText)
                    } catch (error: IllegalArgumentException) {
                        throw ParserException.parse("课程“$name”的周次无法解析：$weekText", error)
                    }
                    val teacher = columns.teacher?.let { teacherIndex ->
                        cells[teacherIndex]?.text()?.trim()
                            ?.also { value -> previousTeacher = value }
                            ?: previousTeacher
                    }.orEmpty()
                    val room = listOfNotNull(
                        columns.building?.let { cells[it]?.text()?.trim() },
                        columns.room?.let { cells[it]?.text()?.trim() },
                    ).filter(String::isNotEmpty).distinct().joinToString("")

                    weeks.forEach { week ->
                        result += CoursePreview(
                            name = name,
                            teacher = teacher,
                            room = room,
                            day = day,
                            startNode = nodeRange.first,
                            step = step,
                            startWeek = week.startWeek,
                            endWeek = week.endWeek,
                            type = week.type,
                        )
                    }
                }
            }
            if (matchedTableCount == 0) {
                throw ParserException.parse("页面中的候选表格不符合旧版 URP 表头结构")
            }
            if (result.isEmpty()) throw ParserException.empty("旧版 URP 课表中没有课程")
            return result
        } catch (error: ParserException) {
            throw error
        } catch (error: Exception) {
            throw ParserException.parse("旧版 URP 课表解析失败", error)
        }
    }

    /**
     * 展开表头中的 colspan，使表头下标与数据逻辑列保持一致。
     *
     * @param cells 最后一行表头单元格
     * @return 每个逻辑列对应的规范化表头文本
     */
    private fun expandHeaderCells(cells: List<Element>): List<String> = buildList {
        cells.forEach { cell ->
            val colspan = cell.attr("colspan").toIntOrNull()?.takeIf { it > 0 } ?: 1
            repeat(colspan) { add(cell.text().trim().replace(WHITESPACE_PATTERN, "")) }
        }
    }

    /**
     * 根据中文表头建立逻辑列索引。
     *
     * @param headers 已展开的表头文本
     * @return 必需列齐全时的索引，否则返回 null 供调用方尝试下一张候选表
     */
    private fun resolveColumns(headers: List<String>): UrpColumns? {
        fun find(vararg names: String): Int? = headers.indexOfFirst { header ->
            names.any { name -> header.contains(name) }
        }.takeIf { it >= 0 }

        return UrpColumns(
            name = find("课程名", "课程名称") ?: return null,
            teacher = find("代课教师", "教师", "任课教师"),
            week = find("周次") ?: return null,
            day = find("星期") ?: return null,
            node = find("节次") ?: return null,
            step = find("节数"),
            building = find("教学楼"),
            room = find("教室", "地点"),
        )
    }

    /**
     * 将 tbody 的物理单元格按 rowspan/colspan 展开为固定宽度逻辑行。
     *
     * @param table 已通过表头识别的 URP 表格
     * @param columnCount 表头确定的逻辑列数
     * @param continuationOffset 省略课程前置列的续行应当从哪个逻辑列开始
     * @return 每行按表头下标对齐的可空单元格列表
     * @throws ParserException 数据行列数超过表头定义
     */
    private fun expandBodyRows(
        table: Element,
        columnCount: Int,
        continuationOffset: Int,
    ): List<List<Element?>> {
        val rows = table.select("tbody > tr")
        val activeSpans = mutableMapOf<Int, ActiveSpan>()
        return rows.mapIndexed { rowIndex, row ->
            val logical = MutableList<Element?>(columnCount) { null }
            val hasCarriedCells = activeSpans.isNotEmpty()

            // 先填入上一行延续的单元格，再消费其剩余行数。
            activeSpans.toMap().forEach { (column, span) ->
                logical[column] = span.cell
                span.remainingRows--
                if (span.remainingRows == 0) activeSpans.remove(column)
            }

            val physicalCells = row.directChildren("td")
            val physicalWidth = physicalCells.sumOf { cell ->
                cell.attr("colspan").toIntOrNull()?.takeIf { it > 0 } ?: 1
            }
            // 部分旧 URP 不写 rowspan，而是从周次列开始输出续行；此时显式补齐前置空列。
            var column = if (
                !hasCarriedCells && physicalWidth <= columnCount - continuationOffset
            ) {
                continuationOffset
            } else {
                0
            }
            physicalCells.forEach { cell ->
                while (column < columnCount && logical[column] != null) column++
                val colspan = cell.attr("colspan").toIntOrNull()?.takeIf { it > 0 } ?: 1
                val rowspan = cell.attr("rowspan").toIntOrNull()?.takeIf { it > 0 } ?: 1
                if (column + colspan > columnCount) {
                    throw ParserException.parse("旧 URP 第 ${rowIndex + 1} 行列数超过表头")
                }
                repeat(colspan) { offset ->
                    val targetColumn = column + offset
                    if (logical[targetColumn] != null) {
                        throw ParserException.parse("旧 URP 第 ${rowIndex + 1} 行 colspan 与 rowspan 重叠")
                    }
                    logical[targetColumn] = cell
                    if (rowspan > 1) {
                        activeSpans[targetColumn] = ActiveSpan(cell, rowspan - 1)
                    }
                }
                column += colspan
            }
            logical
        }
    }

    /** 将数字或中文星期转换为 1～7。 */
    private fun parseDay(text: String): Int? {
        val normalized = text.trim().removePrefix("星期").removePrefix("周")
        return normalized.toIntOrNull()?.takeIf { it in 1..7 } ?: when (normalized) {
            "一" -> 1
            "二" -> 2
            "三" -> 3
            "四" -> 4
            "五" -> 5
            "六" -> 6
            "日", "天" -> 7
            else -> null
        }
    }

    /** 返回指定标签的直接子元素，避免把嵌套表格中的单元格混入当前逻辑行。 */
    private fun Element.directChildren(tagName: String): List<Element> =
        children().filter { child -> child.tagName().equals(tagName, ignoreCase = true) }

    /** 解析单节或连续节次区间。 */
    private fun parseNodeRange(text: String): IntRange? {
        NODE_RANGE_PATTERN.find(text)?.let { match ->
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].toInt()
            return if (start > 0 && end >= start) start..end else null
        }
        val singleText = NODE_SINGLE_PATTERN.find(text)?.groupValues?.get(1) ?: text.trim()
        val node = parsePositiveNumber(singleText) ?: return null
        return node..node
    }

    /** 解析正整数或一至二十的中文数字。 */
    private fun parsePositiveNumber(text: String): Int? {
        text.trim().toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        val normalized = text.trim().removeSuffix("节")
        normalized.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        val value = when {
            normalized == "十" -> 10
            normalized.startsWith("十") -> 10 + CHINESE_DIGITS[normalized.last()].orZero()
            normalized.endsWith("十") -> CHINESE_DIGITS[normalized.first()].orZero() * 10
            normalized.contains("十") -> {
                val parts = normalized.split("十", limit = 2)
                CHINESE_DIGITS[parts[0].singleOrNull()].orZero() * 10 +
                        CHINESE_DIGITS[parts[1].singleOrNull()].orZero()
            }

            else -> CHINESE_DIGITS[normalized.singleOrNull()].orZero()
        }
        return value.takeIf { it > 0 }
    }

    /** 将未命中的中文数字安全映射为 0，最终由正数校验拒绝。 */
    private fun Int?.orZero(): Int = this ?: 0

    private data class UrpColumns(
        val name: Int,
        val teacher: Int?,
        val week: Int,
        val day: Int,
        val node: Int,
        val step: Int?,
        val building: Int?,
        val room: Int?,
    )

    private data class ActiveSpan(
        val cell: Element,
        var remainingRows: Int,
    )

    private val WHITESPACE_PATTERN = Regex("""\s+""")
    private val NODE_RANGE_PATTERN = Regex("""(\d{1,2})\s*[-~～—–]\s*(\d{1,2})""")
    private val NODE_SINGLE_PATTERN = Regex("""第\s*([一二三四五六七八九十\d]{1,3})\s*[大小]?节""")
    private val CHINESE_DIGITS = mapOf(
        '一' to 1,
        '二' to 2,
        '两' to 2,
        '三' to 3,
        '四' to 4,
        '五' to 5,
        '六' to 6,
        '七' to 7,
        '八' to 8,
        '九' to 9,
    )
}
