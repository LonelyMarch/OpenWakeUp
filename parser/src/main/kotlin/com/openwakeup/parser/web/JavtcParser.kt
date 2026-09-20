package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** JAVTC ASP.NET 网格课表解析器。 */
object JavtcParser : Parser {

    /**
     * 解析 `table[border=1][width=98%]` 中课程链接的 title 元数据。
     *
     * @param input 调用方已经取得的课表 HTML
     * @return 每个课程链接对应的全部周次区间
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).selectFirst("table[border=1][width=98%]")
            ?: throw ParserException.parse("JAVTC 页面中缺少课表网格")
        val courses = mutableListOf<CoursePreview>()
        table.select("tr").drop(1).forEach { row ->
            val cells = row.select("td")
            if (cells.size < 8) return@forEach
            val nodes = parseNodes(cells[0].text()) ?: return@forEach
            (1..7).forEach { day ->
                cells[day].select("a[title]").forEach { link ->
                    val info = parseCourseInfo(link.attr("title"))
                    WeekUtils.parse(info.weeks).forEach { week ->
                        courses += CoursePreview(
                            name = info.name,
                            teacher = info.teacher,
                            room = info.room,
                            day = day,
                            startNode = nodes.first,
                            step = nodes.last - nodes.first + 1,
                            startWeek = week.startWeek,
                            endWeek = week.endWeek,
                            type = week.type,
                        )
                    }
                }
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("JAVTC 课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("JAVTC 课表解析失败：${error.message}", error)
    }

    /** 把页面的中文时段名称转换为明确节次。 */
    private fun parseNodes(text: String): IntRange? = when {
        text.contains("第一二节") -> 1..2
        text.contains("第三四节") -> 3..4
        text.contains("第五六节") -> 5..6
        text.contains("第七八节") -> 7..8
        else -> null
    }

    /**
     * 按字段标签读取 title，避免依赖固定行号。
     *
     * @param title 课程链接的完整 title 属性
     * @return 已校验的课程元数据
     */
    private fun parseCourseInfo(title: String): CourseInfo {
        val fields =
            title.lineSequence().map { line -> line.trim() }.filter { line -> line.isNotEmpty() }
                .mapNotNull { line ->
                    val separator = line.indexOfAny(charArrayOf('：', ':'))
                    if (separator < 0) null else line.substring(0, separator)
                        .trim() to line.substring(separator + 1).trim()
                }.toMap()
        val name = fields["课程名称"].orEmpty()
        val weeks = fields["上课周次"].orEmpty()
        require(name.isNotEmpty() && weeks.isNotEmpty()) { "课程链接缺少课程名称或上课周次" }
        return CourseInfo(
            name = name,
            teacher = fields["授课教师"].orEmpty(),
            weeks = weeks,
            room = fields["开课地点"].orEmpty(),
        )
    }

    private data class CourseInfo(
        val name: String,
        val teacher: String,
        val weeks: String,
        val room: String,
    )
}
