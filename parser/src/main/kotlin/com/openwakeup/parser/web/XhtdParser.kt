package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `xhtd` 使用的 `#kbtable` 双节网格解析器。 */
object XhtdParser : Parser {

    /**
     * 解析 id 为“时段-星期”的课程格。
     *
     * 每个时段固定映射为两节：第 1 时段是第 1～2 节，第 2 时段是第 3～4 节，以此类推。
     * 单元格只在包含 `<nobr>` 时属于课程数据，每门课程由五个 `<br>` 字段组成。
     *
     * @param input 完整 `#kbtable` HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 网格坐标、五字段分组或周次无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("kbtable")
            ?: throw ParserException.parse("xhtd 页面中缺少 kbtable")
        val courses = table.select("div").flatMap { cell -> parseCell(cell) }
        if (courses.isEmpty()) throw ParserException.empty("xhtd kbtable 中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("xhtd 课表解析失败：${error.message}", error)
    }

    /** 解析一个网格课程格；非课程 div 返回空列表。 */
    private fun parseCell(cell: Element): List<CoursePreview> {
        val id = cell.id().trim()
        if (id.isEmpty() || !cell.html().contains("<nobr", ignoreCase = true)) return emptyList()
        val coordinates = id.split('-')
        require(coordinates.size >= 2) { "xhtd 课程格 id 无法识别：$id" }
        val block = coordinates[0].trim().toIntOrNull()
            ?: throw IllegalArgumentException("xhtd 时段不是整数：$id")
        val day = coordinates[1].trim().toIntOrNull()
            ?: throw IllegalArgumentException("xhtd 星期不是整数：$id")
        require(block > 0 && day in 1..7) { "xhtd 网格坐标越界：$id" }
        val startNode = (block - 1) * 2 + 1

        val rawFields = cell.html().split(BR_PATTERN)
            .map { fragment -> Jsoup.parse(fragment).text().trim() }
            .dropWhile { field -> field.isEmpty() }
            .dropLastWhile { field -> field.isEmpty() }
        require(rawFields.size % COURSE_FIELD_COUNT == 0) {
            "xhtd 课程格字段数不是 5 的整数倍：${rawFields.size}"
        }
        return rawFields.chunked(COURSE_FIELD_COUNT).flatMap { fields ->
            parseCourseFields(fields, day, startNode)
        }
    }

    /** 按原版五字段位置读取名称、教师、周次和教室。 */
    private fun parseCourseFields(
        fields: List<String>,
        day: Int,
        startNode: Int,
    ): List<CoursePreview> {
        val name = fields[0]
        if (name.isEmpty()) return emptyList()
        val teacher = fields[2]
        val weekText = fields[3]
        val room = fields[4]
        require(weekText.isNotEmpty()) { "课程 $name 缺少周次" }
        return WeekUtils.parse(weekText).map { week ->
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

    private const val COURSE_FIELD_COUNT = 5
    private val BR_PATTERN = Regex("(?i)<br\\s*/?>")
}
