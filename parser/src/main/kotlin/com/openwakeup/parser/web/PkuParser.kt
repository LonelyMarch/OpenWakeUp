package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import org.jsoup.Jsoup

/** 北京大学 `datagrid` 选课结果解析器。 */
object PkuParser : Parser {
    /**
     * 解析已选课程行及每行可能包含的多个上课时间。
     *
     * @param input 完整课表 HTML
     * @return 所有已选课程时间段
     * @throws ParserException 表格或必填时间字段缺失、课程为空
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val body = Jsoup.parse(input.text).selectFirst("table.datagrid tbody")
            ?: throw ParserException.parse("北京大学页面中缺少 datagrid 课表")
        val courses = mutableListOf<CoursePreview>()
        body.select("tr").forEachIndexed { rowIndex, row ->
            val cells = row.select("td")
            if (cells.size < 11 || cells[8].text().contains('未')) return@forEachIndexed
            val name = cells[0].text().trim()
            require(name.isNotEmpty()) { "北京大学课表第 ${rowIndex + 1} 行缺少课程名称" }
            cells[7].html().split(Regex("(?i)<br\\s*/?>")).forEach { fragment ->
                val text = Jsoup.parse(fragment).text().trim()
                if (text.isEmpty()) return@forEach
                val parts = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
                require(parts.size >= 2) { "课程 $name 的上课时间字段不完整：$text" }
                val weeks = TextUtils.requirePositiveRange(parts[0], "周次")
                val day = TextUtils.requireDay(parts[1])
                val nodes = NODE_PATTERN.find(parts[1])?.groupValues?.let {
                    it[1].toInt()..it[2].toInt()
                } ?: throw IllegalArgumentException("课程 $name 缺少明确节次：${parts[1]}")
                val room = parts.getOrNull(2)?.trim().orEmpty().ifEmpty {
                    parts[1].substringAfter('(', "").substringBefore(')', "").trim()
                }
                courses += CoursePreview(
                    name = name,
                    teacher = cells[4].text().trim(),
                    room = room,
                    day = day,
                    startNode = nodes.first,
                    step = nodes.last - nodes.first + 1,
                    startWeek = weeks.first,
                    endWeek = weeks.last,
                    type = when {
                        parts[1].contains('单') -> 1
                        parts[1].contains('双') -> 2
                        else -> 0
                    },
                )
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("北京大学课表中没有已选课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("北京大学课表解析失败：${error.message}", error)
    }

    /** 匹配时间字段中的明确节次范围。 */
    private val NODE_PATTERN = Regex("""(\d+)\s*[~～\-－—]\s*(\d+)\s*节""")
}
