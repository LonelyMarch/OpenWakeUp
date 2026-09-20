package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** `uestc_post` 使用的研究生斜杠字段课表解析器。 */
object UestcPostParser : Parser {

    /**
     * 解析 `#tbl` 中按星期列排列的课程记录。
     *
     * 每条记录沿用原版八字段契约：课程名位于第 2 段、教室位于第 5 段、周次位于第 6 段、
     * 节次位于第 7 段、教师位于第 8 段。Parser 只消费最终 HTML，不执行额外请求。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹、星期列或课程字段无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("tbl")
            ?: throw ParserException.parse("电子科大研究生页面中缺少 tbl 课表")
        val rows = table.select("tr")
        if (rows.size <= 1) throw ParserException.parse("电子科大研究生课表中缺少课程行")

        val courses = mutableListOf<CoursePreview>()
        rows.drop(1).forEach { row ->
            row.select("td").forEachIndexed { columnIndex, cell ->
                // 第一列是行标题；原版直接以其后的单元格索引作为星期 1～7。
                if (columnIndex == 0 || cell.text().isBlank()) return@forEachIndexed
                require(columnIndex in 1..7) { "电子科大研究生课表的星期列超过 7 列" }
                cell.text().split(MULTI_COURSE_SEPARATOR_PATTERN)
                    .filter { entry -> entry.isNotBlank() }
                    .forEach { entry -> courses += parseEntry(entry, columnIndex) }
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("电子科大研究生课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("电子科大研究生课表解析失败：${error.message}", error)
    }

    /**
     * 解析一条以 `/` 分隔的课程记录。
     *
     * @param source 单条课程文本
     * @param day 当前单元格对应的星期
     * @return 课程在所有明确周次片段上的预览列表
     */
    private fun parseEntry(source: String, day: Int): List<CoursePreview> {
        val fields = source.split('/').map { field -> field.trim() }
        require(fields.size >= FIELD_COUNT) { "电子科大研究生课程记录字段不足：$source" }
        val name = fields[NAME_INDEX]
        require(name.isNotEmpty()) { "电子科大研究生课程名称为空" }
        val nodes = TextUtils.requirePositiveRange(
            fields[NODE_INDEX].substringAfter('(', fields[NODE_INDEX]).substringBefore(')'),
            "课程 $name 的节次",
        )
        val weekParts = fields[WEEK_INDEX].split(WEEK_SEPARATOR_PATTERN)
            .filter { part -> part.isNotBlank() }
        require(weekParts.isNotEmpty()) { "课程 $name 缺少明确周次" }

        return weekParts.flatMap { part ->
            WeekUtils.parse(part).map { week ->
                CoursePreview(
                    name = name,
                    teacher = fields[TEACHER_INDEX],
                    room = fields[ROOM_INDEX],
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

    private const val FIELD_COUNT = 8
    private const val NAME_INDEX = 1
    private const val ROOM_INDEX = 4
    private const val WEEK_INDEX = 5
    private const val NODE_INDEX = 6
    private const val TEACHER_INDEX = 7
    private val MULTI_COURSE_SEPARATOR_PATTERN = Regex("，\\s+")
    private val WEEK_SEPARATOR_PATTERN = Regex("[,，]")
}
