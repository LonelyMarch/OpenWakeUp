package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `seig` 使用的显式周列表双节网格解析器。 */
object SeigParser : Parser {

    /**
     * 找出同时包含星期一、二、三的课表，并按行列坐标恢复双节与星期。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹缺失或课程括号字段不完整
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val tables = Jsoup.parse(input.text).select("table").filter { table ->
            val html = table.html()
            html.contains("星期一") && html.contains("星期二") && html.contains("星期三")
        }
        if (tables.isEmpty()) throw ParserException.parse("SEIG 页面中缺少星期课表")
        val courses = tables.flatMap(::parseTable)
        if (courses.isEmpty()) throw ParserException.empty("SEIG 课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("SEIG 课表解析失败：${error.message}", error)
    }

    /** 将表头后的每行映射为连续双节，并跳过每行第一个节次标题格。 */
    private fun parseTable(table: Element): List<CoursePreview> {
        val courses = mutableListOf<CoursePreview>()
        table.select("tr").drop(1).forEachIndexed { rowIndex, row ->
            val startNode = rowIndex * 2 + 1
            row.select("td").forEachIndexed { cellIndex, cell ->
                if (cellIndex == 0 || cell.text().isBlank()) return@forEachIndexed
                require(cellIndex in 1..7) { "SEIG 课表星期列超过 7 列" }
                courses += parseCell(cell.text().trim(), cellIndex, startNode)
            }
        }
        return courses
    }

    /** 解析“课程名(附加字段 教师 显式周列表 [教室])”课程格。 */
    private fun parseCell(source: String, day: Int, startNode: Int): List<CoursePreview> {
        require(source.contains('(') && source.contains(')')) { "SEIG 课程格缺少括号字段：$source" }
        val name = source.substringBefore('(').trim()
        require(name.isNotEmpty()) { "SEIG 课程名称为空" }
        val details = source.substringAfter('(').substringBeforeLast(')').trim()
            .split(WHITESPACE_PATTERN)
            .filter { it.isNotBlank() }
        require(details.size >= 4) { "课程 $name 的括号字段不足" }
        val teacher = details[1]
        val roomToken = details.last()
        require(roomToken.contains('[') && roomToken.contains(']')) { "课程 $name 缺少教室字段" }
        val room = roomToken.substringAfter('[').substringBefore(']').trim()
        val weeks = details.subList(2, details.lastIndex).map { token ->
            token.substringBefore("周").trim().toIntOrNull()
                ?: throw IllegalArgumentException("课程 $name 的周次无法识别：$token")
        }.toSortedSet()
        require(weeks.isNotEmpty()) { "课程 $name 缺少明确周次" }

        return WeekUtils.compact(weeks).map { week ->
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

    private val WHITESPACE_PATTERN = Regex("\\s+")
}
