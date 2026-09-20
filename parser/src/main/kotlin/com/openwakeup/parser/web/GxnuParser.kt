package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** `gxnu` 使用的 `xkjbgzszGridIdGrid` jqGrid 课表解析器。 */
object GxnuParser : Parser {

    /**
     * 按 `aria-describedby` 字段名读取课程，并解析分号分隔的多个时间地点安排。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException jqGrid 指纹缺失、关键字段缺失或时间格式无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("xkjbgzszGridIdGrid")
            ?: throw ParserException.parse("广西师大页面中缺少 xkjbgzszGridIdGrid 课表")
        val rows = table.select("tbody tr")
        if (rows.isEmpty()) throw ParserException.parse("广西师大 jqGrid 中缺少课程行")

        val courses = rows.flatMap { row ->
            val fields = row.select("td[aria-describedby]").associate { cell ->
                cell.attr("aria-describedby") to cell.text().trim()
            }
            val name = fields[COURSE_NAME_FIELD].orEmpty()
            val scheduleText = fields[SCHEDULE_FIELD].orEmpty()
            if (name.isBlank() && scheduleText.isBlank()) {
                emptyList()
            } else {
                require(name.isNotBlank()) { "广西师大课程行缺少课程名称" }
                require(scheduleText.isNotBlank()) { "课程 $name 缺少上课时间地点" }
                val teacher = fields[TEACHER_FIELD].orEmpty()
                scheduleText.split(';').filter { part -> part.isNotBlank() }.flatMap { part ->
                    parseSchedule(name, teacher, part.trim())
                }
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("广西师大课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("广西师大课表解析失败：${error.message}", error)
    }

    /**
     * 解析一段形如“星期一 1-2节 第1-16周【教室】”的安排。
     *
     * @param name 课程名称
     * @param teacher 教师名称
     * @param source 单个时间地点片段
     */
    private fun parseSchedule(
        name: String,
        teacher: String,
        source: String,
    ): List<CoursePreview> {
        val roomMatch = ROOM_PATTERN.find(source)
            ?: throw IllegalArgumentException("课程 $name 缺少【教室】字段：$source")
        val room = roomMatch.groupValues[1].trim()
        val schedule = source.substring(0, roomMatch.range.first).trim()
        val dayText = DAY_PATTERN.find(schedule)?.value
            ?: throw IllegalArgumentException("课程 $name 缺少明确星期：$source")
        val day = TextUtils.requireDay(dayText)
        val nodeText = NODE_PATTERN.find(schedule)?.value
            ?: throw IllegalArgumentException("课程 $name 缺少明确节次：$source")
        val nodes = TextUtils.requirePositiveRange(nodeText, "课程 $name 的节次")
        val weekBlock = WEEK_BLOCK_PATTERN.find(schedule)?.groupValues?.get(1)?.trim()
            ?: throw IllegalArgumentException("课程 $name 缺少明确周次：$source")
        val parity = when {
            schedule.contains('单') && !schedule.contains('双') -> "单"
            schedule.contains('双') && !schedule.contains('单') -> "双"
            else -> ""
        }
        val normalizedWeeks = weekBlock.split(',', '，', '、').joinToString(",") { part ->
            if (parity.isEmpty() || part.contains('单') || part.contains('双')) part else "$part$parity"
        }

        return WeekUtils.parse(normalizedWeeks).map { week ->
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

    private const val COURSE_NAME_FIELD = "xkjbgzszGridIdGrid_kcmc"
    private const val TEACHER_FIELD = "xkjbgzszGridIdGrid_rkjs"
    private const val SCHEDULE_FIELD = "xkjbgzszGridIdGrid_sksjdd"
    private val ROOM_PATTERN = Regex("【([^】]*)】")
    private val DAY_PATTERN = Regex("星期[一二三四五六七日天1-7]")
    private val NODE_PATTERN = Regex("\\d+\\s*[-~～至—–]\\s*\\d+\\s*节")
    private val WEEK_BLOCK_PATTERN = Regex("第(.+)周")
}
