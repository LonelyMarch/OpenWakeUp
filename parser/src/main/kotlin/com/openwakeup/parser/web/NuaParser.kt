package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `nua` 使用的南京艺术学院课程明细解析器。 */
object NuaParser : Parser {

    /**
     * 解析页面最后一张表中的课程名称、教师、时间和教室。
     *
     * 时间字段格式为 `起始周-结束周周 周一1,2 周三3,4`。页面在第 5 节前插入“中午”节次，
     * 因而数字 5 及之后的页面节次统一后移一位；文字“中午”本身映射为第 5 节。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 表格、课程列、周次、星期或节次无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val tables = Jsoup.parse(input.text).select("table")
        require(tables.isNotEmpty()) { "南京艺术学院页面缺少课表" }
        // Jsoup 的 Elements.last() 带可空类型；已确认非空后按下标读取最后一张表。
        val table = tables[tables.size - 1]
        val courses = table.select("tr").drop(1).flatMapIndexed { rowIndex, row ->
            parseRow(row, rowIndex + 2)
        }
        if (courses.isEmpty()) throw ParserException.empty("南京艺术学院课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("南京艺术学院课表解析失败：${error.message}", error)
    }

    /** 把一行课程中的多个星期/节次安排展开为独立课程预览。 */
    private fun parseRow(row: Element, rowNumber: Int): List<CoursePreview> {
        val cells = row.select("td")
        require(cells.size >= 5) { "南京艺术学院课表第 $rowNumber 行字段不足" }
        val name = cells[0].text().trim()
        val teacher = cells[1].text().trim()
        val scheduleText = cells[3].text().trim()
        val room = cells[4].text().trim()
        require(name.isNotEmpty()) { "南京艺术学院课表第 $rowNumber 行课程名称为空" }
        val tokens = scheduleText.split(WHITESPACE_PATTERN).filter { token -> token.isNotEmpty() }
        require(tokens.size >= 2) { "课程 $name 的时间字段不足：$scheduleText" }
        require(tokens.first().firstOrNull()?.isDigit() == true) {
            "课程 $name 缺少明确周次，拒绝使用原版默认第 1～20 周"
        }
        val weeks = TextUtils.requirePositiveRange(
            tokens.first().substringBefore('周'),
            "课程 $name 的周次"
        )

        return tokens.drop(1).map { arrangement ->
            require(arrangement.length >= 2) { "课程 $name 的星期节次字段过短：$arrangement" }
            val dayCharacter = arrangement[1]
            val day = TextUtils.requireDay(dayCharacter.toString())
            val nodeText = arrangement.substringAfter(dayCharacter)
            val nodeParts = nodeText.split(',').map { part -> part.trim() }
                .filter { part -> part.isNotEmpty() }
            require(nodeParts.size in 1..2) { "课程 $name 的节次字段无效：$arrangement" }
            val startNode = mapNode(nodeParts.first(), name)
            val endNode = mapNode(nodeParts.last(), name)
            require(endNode >= startNode) { "课程 $name 的结束节次小于起始节次" }

            CoursePreview(
                name = name,
                teacher = teacher,
                room = room,
                day = day,
                startNode = startNode,
                step = endNode - startNode + 1,
                startWeek = weeks.first,
                endWeek = weeks.last,
            )
        }
    }

    /** 将南京艺术学院页面节次转换为包含午间节次的统一序号。 */
    private fun mapNode(source: String, courseName: String): Int {
        if (source == "中午") return 5
        val rawNode = source.toIntOrNull()
            ?: throw IllegalArgumentException("课程 $courseName 的节次不是数字或中午：$source")
        require(rawNode > 0) { "课程 $courseName 的节次必须为正整数" }
        return if (rawNode >= 5) rawNode + 1 else rawNode
    }

    private val WHITESPACE_PATTERN = Regex("""\s+""")
}
