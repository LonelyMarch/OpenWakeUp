package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** `swust` 使用的 `.UICourseTable` 单节网格解析器。 */
object SwustParser : Parser {

    /**
     * 解析每行最后七个星期单元格中的 `.lecture` 课程块。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹缺失、星期列不足或周次字段无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).selectFirst(".UICourseTable")
            ?: throw ParserException.parse("西南科大页面中缺少 UICourseTable 课表")
        val body = table.selectFirst("tbody")
            ?: throw ParserException.parse("西南科大课表中缺少 tbody")
        val rows = body.select("tr")
        if (rows.isEmpty()) throw ParserException.parse("西南科大课表中缺少课程行")
        val courses = mutableListOf<CoursePreview>()

        rows.forEachIndexed { rowIndex, row ->
            val cells = row.select("td")
            require(cells.size >= 7) { "西南科大第 ${rowIndex + 1} 节星期列不足" }
            cells.takeLast(7).forEachIndexed { dayIndex, cell ->
                cell.getElementsByClass("lecture").forEach { lecture ->
                    val name = lecture.getElementsByClass("course").text().trim()
                    require(name.isNotEmpty()) { "西南科大课程名称为空" }
                    val room = lecture.getElementsByClass("place").text().trim()
                    val weekText = lecture.getElementsByClass("week").text().trim()
                    require(weekText.isNotEmpty()) { "课程 $name 缺少明确周次" }

                    WeekUtils.parse(weekText).forEach { week ->
                        courses += CoursePreview(
                            name = name,
                            // 原版把 `.course` 再读一次作为教师，属于错误字段映射；无证据时保持空值。
                            teacher = "",
                            room = room,
                            day = dayIndex + 1,
                            startNode = rowIndex + 1,
                            startWeek = week.startWeek,
                            endWeek = week.endWeek,
                            type = week.type,
                        )
                    }
                }
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("西南科大课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("西南科大课表解析失败：${error.message}", error)
    }
}
