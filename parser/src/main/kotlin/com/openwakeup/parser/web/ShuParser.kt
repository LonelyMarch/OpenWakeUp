package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** `shu` 旧版选课结果表格解析器；它与 `shu_2024` 是两个独立输入契约。 */
object ShuParser : Parser {

    /**
     * 解析所有 `name=rowclass` 课程行及其中可能存在的多个上课时间段。
     *
     * @param input 已由调用方取得的完整选课结果 HTML
     * @return 具有明确星期、节次和周次的课程预览列表
     * @throws ParserException 页面指纹缺失、列数不足或周次不明确
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val rows = Jsoup.parse(input.text).getElementsByAttributeValue("name", "rowclass")
        if (rows.isEmpty()) throw ParserException.parse("上海大学旧版页面中缺少 rowclass 课程行")

        val courses = rows.flatMap { row ->
            val cells = row.children()
            require(cells.size >= 8) { "上海大学旧版课程行列数不足" }
            parseSchedules(
                name = cells[2].text().trim(),
                teacher = cells[5].text().trim(),
                room = cells[7].text().trim(),
                timeText = cells[6].text().trim(),
            )
        }
        if (courses.isEmpty()) throw ParserException.empty("上海大学旧版课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("上海大学旧版课表解析失败：${error.message}", error)
    }

    /**
     * 按“星期字符 + 起止节次”坐标切分一门课程的多个时间段。
     *
     * @param name 课程名称
     * @param teacher 教师名称
     * @param room 上课地点
     * @param timeText 包含坐标与周次说明的完整时间文本
     */
    private fun parseSchedules(
        name: String,
        teacher: String,
        room: String,
        timeText: String,
    ): List<CoursePreview> {
        require(name.isNotEmpty()) { "上海大学旧版课程名称为空" }
        val coordinates = COORDINATE_PATTERN.findAll(timeText).toList()
        require(coordinates.isNotEmpty()) { "课程 $name 缺少星期和节次坐标：$timeText" }

        return coordinates.flatMapIndexed { index, coordinate ->
            val suffixStart = coordinate.range.last + 1
            val suffixEnd = coordinates.getOrNull(index + 1)?.range?.first ?: timeText.length
            val scheduleSuffix = timeText.substring(suffixStart, suffixEnd).trim()
            val weekBlock = WEEK_BLOCK_PATTERN.find(scheduleSuffix)?.groupValues?.get(1)?.trim()
                ?: throw IllegalArgumentException("课程 $name 缺少明确周次：$scheduleSuffix")
            val parity = when {
                scheduleSuffix.contains('单') && !scheduleSuffix.contains('双') -> "单"
                scheduleSuffix.contains('双') && !scheduleSuffix.contains('单') -> "双"
                else -> ""
            }
            val weeks = WeekUtils.parse(appendParityToWeekParts(weekBlock, parity))
            val day = TextUtils.requireDay(coordinate.value.take(1))
            val nodes = TextUtils.requirePositiveRange(
                coordinate.value.drop(1),
                "课程 $name 的节次",
            )

            weeks.map { week ->
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
    }

    /** 将坐标后声明的单双周属性应用到逗号分隔的每个周次片段。 */
    private fun appendParityToWeekParts(weekText: String, parity: String): String {
        if (parity.isEmpty()) return weekText
        return weekText.split(',', '，', '、').joinToString(",") { part ->
            if (part.contains('单') || part.contains('双')) part else "$part$parity"
        }
    }

    private val COORDINATE_PATTERN = Regex("[一二三四五六七日]\\d+\\s*[-~～至—–]\\s*\\d+")
    private val WEEK_BLOCK_PATTERN = Regex("[（(]([^）)]*周)[）)]")
}
