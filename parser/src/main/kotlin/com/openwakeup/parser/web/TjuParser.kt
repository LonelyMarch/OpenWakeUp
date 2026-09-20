package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** `tju` 使用的 Element UI 选课结果表格解析器。 */
object TjuParser : Parser {

    /**
     * 根据中文表头定位课程字段，兼容“上课时间”和“教学安排”两种时间列。
     *
     * @param input 已由调用方取得的完整选课结果 HTML
     * @return 具有明确星期、节次和周次的课程预览列表
     * @throws ParserException 表头、表体或课程时间字段不符合预期
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val document = Jsoup.parse(input.text)
        val header = document.selectFirst(".el-table__header")
            ?: throw ParserException.parse("天津大学页面中缺少 el-table__header")
        val body = document.selectFirst(".el-table__body")
            ?: throw ParserException.parse("天津大学页面中缺少 el-table__body")
        val headers = header.select("th").map { cell -> cell.text().trim() }
        val indexes = resolveIndexes(headers)
        val rows = body.select("tr")
        if (rows.isEmpty()) throw ParserException.parse("天津大学课程表中缺少数据行")

        val courses =
            rows.flatMap { row -> parseRow(row.select("td").map { it.text().trim() }, indexes) }
        if (courses.isEmpty()) throw ParserException.empty("天津大学课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("天津大学课表解析失败：${error.message}", error)
    }

    /** 根据表头文本冻结本页实际列号，防止列顺序调整导致静默错位。 */
    private fun resolveIndexes(headers: List<String>): ColumnIndexes {
        fun required(label: String): Int = headers.indexOfFirst { header -> header.contains(label) }
            .also { index -> require(index >= 0) { "天津大学课表缺少“$label”列" } }

        val timeIndex = headers.indexOfFirst { it.contains("上课时间") }
            .takeIf { it >= 0 }
            ?: required("教学安排")
        return ColumnIndexes(
            name = required("课程名称"),
            teacher = required("教师"),
            time = timeIndex,
            room = headers.indexOfFirst { it.contains("上课地点") },
        )
    }

    /** 解析一行课程，以及该行逗号分隔的全部上课时间段。 */
    private fun parseRow(cells: List<String>, indexes: ColumnIndexes): List<CoursePreview> {
        val highestRequiredIndex = maxOf(indexes.name, indexes.teacher, indexes.time, indexes.room)
        require(cells.size > highestRequiredIndex) { "天津大学课程行列数不足" }
        val name = cells[indexes.name]
        val scheduleText = cells[indexes.time]
        if (name.isBlank() && scheduleText.isBlank()) return emptyList()
        require(name.isNotBlank()) { "天津大学课程名称为空" }
        require(scheduleText.isNotBlank()) { "课程 $name 缺少上课时间" }
        val teacher = cells[indexes.teacher].split(PARENTHETICAL_PATTERN)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(",")

        return scheduleText.split(SCHEDULE_SEPARATOR_PATTERN).flatMap { rawSchedule ->
            parseSchedule(
                name = name,
                teacher = teacher,
                separateRoom = indexes.room.takeIf { it >= 0 }?.let { index -> cells[index] }
                    .orEmpty(),
                source = rawSchedule.trim(),
            )
        }
    }

    /** 解析一段“星期 + 节次 + [周次] + 地点”的教学安排。 */
    private fun parseSchedule(
        name: String,
        teacher: String,
        separateRoom: String,
        source: String,
    ): List<CoursePreview> {
        require(source.isNotEmpty()) { "课程 $name 存在空的上课时间段" }
        val dayText = EXPLICIT_DAY_PATTERN.find(source)?.groupValues?.get(1)
            ?: LEADING_DAY_PATTERN.find(source)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("课程 $name 缺少明确星期：$source")
        val day = TextUtils.requireDay(dayText)
        val nodeText = NODE_PATTERN.find(source)?.value
            ?: throw IllegalArgumentException("课程 $name 缺少明确节次：$source")
        val nodes = TextUtils.requirePositiveRange(nodeText, "课程 $name 的节次")
        val weekText = source.substringAfter('[', "").substringBefore(']', "").trim()
        require(weekText.isNotEmpty()) { "课程 $name 缺少方括号周次：$source" }
        val room = separateRoom.ifBlank { source.substringAfterLast(']', "").trim() }
        val weekParts = weekText.split(WEEK_SEPARATOR_PATTERN).filter { it.isNotBlank() }
        require(weekParts.isNotEmpty()) { "课程 $name 的周次字段为空" }

        return weekParts.flatMap(WeekUtils::parse).map { week ->
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

    /** 当前页面通过表头解析得到的课程字段列号。 */
    private data class ColumnIndexes(
        val name: Int,
        val teacher: Int,
        val time: Int,
        val room: Int,
    )

    private val PARENTHETICAL_PATTERN = Regex("\\(.*?\\)")
    private val SCHEDULE_SEPARATOR_PATTERN = Regex(",\\s*(?=星期)")
    private val EXPLICIT_DAY_PATTERN = Regex("星期([一二三四五六七日天1-7])")
    private val LEADING_DAY_PATTERN = Regex("^\\s*([一二三四五六七日天1-7])")
    private val NODE_PATTERN = Regex("\\d+(?:\\s*[-~～至—–]\\s*\\d+)?(?=\\s*节)")
    private val WEEK_SEPARATOR_PATTERN = Regex("[,，\\s]+")
}
