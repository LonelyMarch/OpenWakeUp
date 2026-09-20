package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** `bfa` 使用的北京电影学院本科明细表解析器。 */
object BfaParser : Parser {

    /**
     * 解析 `#studentRecordTable` 第一段表体中的课程明细。
     *
     * 每行前六列依次为课程名、教师、周次、星期、节次和教室。周次及节次都允许由逗号分成
     * 多段，两者按笛卡尔积生成课程安排；教室中的单个 `-` 按原版语义表示空值。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 表格、字段数量、星期、周次或节次无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("studentRecordTable")
            ?: throw ParserException.parse("北京电影学院本科页面缺少 studentRecordTable")
        val body = table.selectFirst("tbody")
            ?: throw ParserException.parse("北京电影学院本科课表缺少表体")
        val courses = body.select("tr").flatMapIndexed { rowIndex, row ->
            val cells = row.select("td")
            require(cells.size >= 6) { "北京电影学院本科课表第 ${rowIndex + 1} 行字段不足" }
            val name = cells[0].text().trim()
            val teacher = cells[1].text().trim()
            val weekText = cells[2].text().trim()
            val dayText = cells[3].text().trim()
            val day = dayText.toIntOrNull()
                ?: throw IllegalArgumentException("课程 $name 的星期不是整数：$dayText")
            val nodeText = cells[4].text().trim()
            val room = cells[5].text().trim().takeUnless { value -> value == "-" }.orEmpty()
            require(name.isNotEmpty()) { "北京电影学院本科课表第 ${rowIndex + 1} 行课程名称为空" }
            require(day in 1..7) { "课程 $name 的星期无效：$dayText" }

            val nodeRanges = nodeText.split(',').map { part -> part.trim() }
                .filter { part -> part.isNotEmpty() }
                .map { part -> TextUtils.requirePositiveRange(part, "课程 $name 的节次") }
            require(nodeRanges.isNotEmpty()) { "课程 $name 缺少节次" }
            val weeks = WeekUtils.parse(weekText)

            nodeRanges.flatMap { nodes ->
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
        if (courses.isEmpty()) throw ParserException.empty("北京电影学院本科课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("北京电影学院本科课表解析失败：${error.message}", error)
    }
}
