package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `nuaajc` 使用的南京航空航天大学金城学院打印课表解析器。 */
object NuaajcParser : Parser {

    /**
     * 按 `#GrwCourseTablePrint` 的英文键表头定位课程字段。
     *
     * 必需表头为 `kcm/jsm/roomid/week/unit/lsjs/weekly`，分别表示课程名、教师、教室、星期、
     * 起始节次、连续节数和周次。字段位置由表头决定，不依赖固定列号。
     *
     * @param input 已由调用方取得的完整打印课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 表格、表头、数据列或时间范围无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("GrwCourseTablePrint")
            ?: throw ParserException.parse("南航金城学院页面缺少 GrwCourseTablePrint")
        val headers = table.select("th").map { header -> header.text().trim() }
        val indexes = ColumnIndexes(
            name = requireHeader(headers, "kcm"),
            teacher = requireHeader(headers, "jsm"),
            room = requireHeader(headers, "roomid"),
            day = requireHeader(headers, "week"),
            startNode = requireHeader(headers, "unit"),
            step = requireHeader(headers, "lsjs"),
            weeks = requireHeader(headers, "weekly"),
        )
        val courses = table.select("tr").drop(1).flatMapIndexed { rowIndex, row ->
            parseRow(row, indexes, rowIndex + 2)
        }
        if (courses.isEmpty()) throw ParserException.empty("南航金城学院课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("南航金城学院课表解析失败：${error.message}", error)
    }

    /** 返回精确匹配的表头列号；缺失时立即失败。 */
    private fun requireHeader(headers: List<String>, key: String): Int =
        headers.indexOfFirst { header -> header == key }
            .also { index -> require(index >= 0) { "南航金城学院课表缺少 $key 表头" } }

    /** 将一行动态列课程转换为一个或多个明确周次区间。 */
    private fun parseRow(
        row: Element,
        indexes: ColumnIndexes,
        rowNumber: Int,
    ): List<CoursePreview> {
        val cells = row.select("td")
        val highestIndex = indexes.asList().max()
        require(cells.size > highestIndex) { "南航金城学院课表第 $rowNumber 行列数不足" }
        val name = cells[indexes.name].text().trim()
        require(name.isNotEmpty()) { "南航金城学院课表第 $rowNumber 行课程名称为空" }
        val day = requirePositiveInt(cells[indexes.day].text(), "课程 $name 的星期")
        require(day in 1..7) { "课程 $name 的星期超出 1～7：$day" }
        val startNode = requirePositiveInt(cells[indexes.startNode].text(), "课程 $name 的起始节次")
        val step = requirePositiveInt(cells[indexes.step].text(), "课程 $name 的连续节数")
        val weekParts = cells[indexes.weeks].text().split(',')
            .map { part -> part.trim() }
            .filter { part -> part.isNotEmpty() }
        require(weekParts.isNotEmpty()) { "课程 $name 缺少周次" }

        return weekParts.map { part ->
            val weeks = TextUtils.requirePositiveRange(part, "课程 $name 的周次")
            CoursePreview(
                name = name,
                teacher = cells[indexes.teacher].text().trim(),
                room = cells[indexes.room].text().trim(),
                day = day,
                startNode = startNode,
                step = step,
                startWeek = weeks.first,
                endWeek = weeks.last,
            )
        }
    }

    /** 把必填数字单元格转换为正整数。 */
    private fun requirePositiveInt(source: String, fieldName: String): Int {
        val value = source.trim().toIntOrNull()
        require(value != null && value > 0) { "$fieldName 必须是正整数：$source" }
        return value
    }

    /** 动态表头解析出的七个必需列号。 */
    private data class ColumnIndexes(
        val name: Int,
        val teacher: Int,
        val room: Int,
        val day: Int,
        val startNode: Int,
        val step: Int,
        val weeks: Int,
    ) {
        /** 返回全部列号，用于统一检查当前数据行宽度。 */
        fun asList(): List<Int> = listOf(name, teacher, room, day, startNode, step, weeks)
    }
}
