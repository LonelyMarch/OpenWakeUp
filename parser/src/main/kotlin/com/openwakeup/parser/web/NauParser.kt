package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** `nau` 使用的中文动态表头课程明细解析器。 */
object NauParser : Parser {

    /**
     * 解析 `#content` 表格中串联的“上课地点：…上课时间：…”课程安排。
     *
     * @param input 已由调用方取得的完整课程明细 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 表头缺失、数据列不足或安排字段无法识别
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val content = Jsoup.parse(input.text).body().getElementById("content")
            ?: throw ParserException.parse("南航页面中缺少 content 课程表")
        val rows = content.select("tr")
        if (rows.isEmpty()) throw ParserException.parse("南航课程表中缺少表头")
        val headers = rows[0].select("th").map { cell -> cell.text().trim() }
        val indexes = ColumnIndexes(
            name = requireHeader(headers, "课程名称"),
            schedule = requireHeader(headers, "上课时间及地点"),
            teacher = requireHeader(headers, "任课教师"),
        )

        val courses = rows.drop(1).flatMap { row ->
            parseRow(row.select("td").map { cell -> cell.text().trim() }, indexes)
        }
        if (courses.isEmpty()) throw ParserException.empty("南航课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("南航课表解析失败：${error.message}", error)
    }

    /** 返回首次出现的目标表头列号；缺失时立即失败，禁止沿用原版的第 0 列默认值。 */
    private fun requireHeader(headers: List<String>, label: String): Int =
        headers.indexOfFirst { header -> header.contains(label) }
            .also { index -> require(index >= 0) { "南航课程表缺少“$label”列" } }

    /** 解析一行课程中一个或多个地点和时间组合。 */
    private fun parseRow(cells: List<String>, indexes: ColumnIndexes): List<CoursePreview> {
        val highestRequiredIndex = maxOf(indexes.name, indexes.schedule, indexes.teacher)
        require(cells.size > highestRequiredIndex) { "南航课程行列数不足" }
        val name = cells[indexes.name]
        val scheduleText = cells[indexes.schedule]
        if (name.isBlank() && scheduleText.isBlank()) return emptyList()
        require(name.isNotBlank()) { "南航课程名称为空" }
        require(scheduleText.contains(ROOM_LABEL)) { "课程 $name 缺少上课地点字段" }
        val teacher = cells[indexes.teacher]

        return scheduleText.substringAfter(ROOM_LABEL).split(ROOM_LABEL).flatMap { arrangement ->
            val fields = arrangement.split(TIME_LABEL, limit = 2)
            require(fields.size == 2) { "课程 $name 的地点和时间无法配对" }
            parseArrangement(
                name = name,
                teacher = teacher,
                room = fields[0].trim(),
                timeText = fields[1].trim(),
            )
        }
    }

    /** 按原版固定令牌位置读取周次、数字星期和节次范围。 */
    private fun parseArrangement(
        name: String,
        teacher: String,
        room: String,
        timeText: String,
    ): List<CoursePreview> {
        val tokens = timeText.split(WHITESPACE_PATTERN).filter { it.isNotBlank() }
        require(tokens.size >= 5) { "课程 $name 的上课时间字段不足：$timeText" }
        val day = tokens[2].toIntOrNull()
            ?: throw IllegalArgumentException("课程 $name 的星期不是数字：${tokens[2]}")
        require(day in 1..7) { "课程 $name 的星期超出 1～7：$day" }
        val nodes =
            TextUtils.requirePositiveRange(tokens[4].substringBefore("节"), "课程 $name 的节次")

        return WeekUtils.parse(tokens[0]).map { week ->
            CoursePreview(
                name = name,
                teacher = teacher,
                room = room,
                day = day,
                startNode = nodes.first,
                step = nodes.last - nodes.first + 1,
                startWeek = week.startWeek,
                endWeek = week.endWeek,
                type = week.type,
            )
        }
    }

    /** 当前页面通过表头解析得到的必要字段列号。 */
    private data class ColumnIndexes(
        val name: Int,
        val schedule: Int,
        val teacher: Int,
    )

    private const val ROOM_LABEL = "上课地点："
    private const val TIME_LABEL = "上课时间："
    private val WHITESPACE_PATTERN = Regex("\\s+")
}
