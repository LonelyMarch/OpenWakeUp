package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import org.jsoup.Jsoup

/** 广州南方学院行式课表解析器。 */
object NfuParser : Parser {
    /**
     * 解析 `tr.ui-widget-content` 中周一至周日七列的时间描述。
     *
     * @param input 完整课表 HTML
     * @return 每个斜杠分段时间对应的课程预览
     * @throws ParserException 表格字段、周次、节次或星期不完整
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val rows = Jsoup.parse(input.text).select("tr.ui-widget-content")
        if (rows.isEmpty()) throw ParserException.parse("广州南方学院页面中缺少课程行")
        val courses = mutableListOf<CoursePreview>()
        rows.forEachIndexed { rowIndex, row ->
            val spans = row.select("span")
            require(spans.size >= 10) { "第 ${rowIndex + 1} 个课程行字段不足" }
            val name = spans.first()?.text()?.trim().orEmpty()
            require(name.isNotEmpty()) { "第 ${rowIndex + 1} 个课程行缺少名称" }
            for (column in 3..9) {
                val value = spans[column].text().trim()
                if (value.isEmpty()) continue
                value.replace("/ ", "/").split('/').forEach { record ->
                    courses += parseRecord(name, record.trim(), column - 2)
                }
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("广州南方学院课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("广州南方学院课表解析失败：${error.message}", error)
    }

    /**
     * 解析单条 `周次 节次 教师 [地点]` 文本。
     *
     * @param name 所属课程名称
     * @param record 单条上课记录
     * @param day 所在星期列
     * @return 课程预览
     */
    private fun parseRecord(name: String, record: String, day: Int): CoursePreview {
        val normalized = SINGLE_WEEK_PATTERN.replace(record) { match ->
            val week = match.groupValues[1]
            "$week-${week}周"
        }
        val fields = normalized.replace(" 单周", "").replace(" 双周", "")
            .split(Regex("\\s+")).filter { it.isNotEmpty() }
        require(fields.size >= 3) { "课程 $name 的时间记录字段不足：$record" }
        val weeks = TextUtils.requirePositiveRange(fields[0], "周次")
        val nodes = TextUtils.requirePositiveRange(fields[1], "节次")
        return CoursePreview(
            name = name,
            teacher = fields[2],
            room = fields.getOrNull(3).orEmpty(),
            day = day,
            startNode = nodes.first,
            step = nodes.last - nodes.first + 1,
            startWeek = weeks.first,
            endWeek = weeks.last,
            type = when {
                record.contains('单') -> 1
                record.contains('双') -> 2
                else -> 0
            },
        )
    }

    private val SINGLE_WEEK_PATTERN = Regex("""第\s*(\d+)\s*周""")
}
