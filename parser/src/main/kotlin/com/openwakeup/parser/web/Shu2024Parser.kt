package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 上海大学 2024 版选课系统页面解析器。 */
object Shu2024Parser : Parser {

    /**
     * 优先解析移动端课程卡片；卡片不存在时解析电脑版课程表。
     *
     * @param input 完整选课结果页面 HTML
     * @return 具有明确星期、节次和周次的课程预览
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val document = Jsoup.parse(input.text)
        val cards = document.select("div.arranged-content div.el-card.arranged-course-card")
        val courses = if (cards.isNotEmpty()) {
            cards.flatMap { card ->
                val values = card.select("div.card-item.cv-clearfix").mapNotNull { item ->
                    val label = item.selectFirst("div.label.cv-pull-left")?.text()?.trim()
                        ?.removeSuffix(":")?.removeSuffix("：")
                    val value = item.selectFirst("div.value.cv-pull-left")?.text()?.trim()
                    if (label.isNullOrEmpty() || value == null) null else label to value
                }.toMap()
                parseCourse(
                    name = values["课程名"].orEmpty(),
                    teacher = values["上课教师"].orEmpty(),
                    room = values["上课地点"].orEmpty(),
                    classTime = values["上课时间"].orEmpty(),
                )
            }
        } else {
            document.select("div.arranged-content table.el-table__body tbody tr.el-table__row")
                .flatMap { row ->
                    val cells = row.select("td")
                    require(cells.size >= 8) { "上海大学电脑版课程表列数不足" }
                    parseCourse(
                        name = cells[1].text().trim(),
                        teacher = cells[5].text().trim(),
                        room = cells[7].text().trim(),
                        classTime = cells[6].text().trim(),
                    )
                }
        }
        if (courses.isEmpty()) throw ParserException.empty("上海大学 2024 课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("上海大学 2024 课表解析失败：${error.message}", error)
    }

    /** 解析一门课程可能包含的多个上课时间片段。 */
    private fun parseCourse(
        name: String,
        teacher: String,
        room: String,
        classTime: String,
    ): List<CoursePreview> {
        require(name.isNotEmpty()) { "课程名称为空" }
        require(classTime.isNotEmpty()) { "课程 $name 的上课时间为空" }
        val schedules = SCHEDULE_PATTERN.findAll(classTime).toList()
        require(schedules.isNotEmpty()) { "课程 $name 的上课时间无法识别：$classTime" }
        return schedules.flatMap { match ->
            val day = TextUtils.requireDay(match.groupValues[1])
            val startNode = match.groupValues[2].toInt()
            val endNode = match.groupValues[3].toInt()
            require(startNode > 0 && endNode >= startNode) { "课程 $name 的节次范围无效" }
            val weekText = match.groupValues[4].trim()
            require(weekText.isNotEmpty()) { "课程 $name 缺少明确周次" }
            val parity = match.groupValues[5]
            val exactWeeks = expandWeeks(WeekUtils.parse(weekText)).filter { week ->
                when (parity) {
                    "单" -> week % 2 == 1
                    "双" -> week % 2 == 0
                    else -> true
                }
            }.toSortedSet()
            require(exactWeeks.isNotEmpty()) { "课程 $name 的周次与单双周标记冲突" }
            WeekUtils.compact(exactWeeks).map { week ->
                CoursePreview(
                    name = name,
                    teacher = teacher,
                    room = room,
                    day = day,
                    startNode = startNode,
                    step = endNode - startNode + 1,
                    startWeek = week.startWeek,
                    endWeek = week.endWeek,
                    type = week.type,
                )
            }
        }
    }

    /** 将周次片段还原为精确周集合，供外部单双周后缀再次过滤。 */
    private fun expandWeeks(segments: List<com.openwakeup.parser.utils.WeekSegment>): Set<Int> =
        segments
            .flatMapTo(sortedSetOf()) { segment ->
                when (segment.type) {
                    0 -> (segment.startWeek..segment.endWeek).toList()
                    1, 2 -> (segment.startWeek..segment.endWeek).filter { week -> week % 2 == segment.type % 2 }
                    else -> throw IllegalArgumentException("未知周类型：${segment.type}")
                }
            }

    private val SCHEDULE_PATTERN = Regex(
        """([一二三四五六日天])\s*(\d+)\s*[-—–]\s*(\d+)\s*[（(]([^）)]+)[）)]\s*([单双]?)""",
    )
}
