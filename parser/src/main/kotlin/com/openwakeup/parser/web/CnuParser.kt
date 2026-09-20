package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import org.jsoup.Jsoup

/** 长春师范大学全校课表页面解析器。 */
object CnuParser : Parser {

    /**
     * 解析 `curriculum-item` 课程卡片。
     *
     * @param input 完整课表 HTML
     * @return 页面内所有课程卡片
     * @throws ParserException 表体缺失、卡片字段错误或课表为空
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val body = Jsoup.parse(input.text).selectFirst("tbody")
            ?: throw ParserException.parse("长春师范大学页面中缺少课程表体")
        val courses = mutableListOf<CoursePreview>()
        body.select("tr").forEachIndexed { rowIndex, row ->
            val cells = row.select("td")
            var day = if (cells.size == 8) -1 else -2
            cells.forEach { cell ->
                day++
                if (cell.selectFirst(".curriculum-item") == null) return@forEach
                require(day in 1..7) { "第 ${rowIndex + 1} 行课程所在星期列无效：$day" }
                val name = cell.select("span").text().substringBefore(" 查看更多").trim()
                val fields = cell.select("div")
                require(name.isNotEmpty() && fields.size >= 5) { "第 ${rowIndex + 1} 行课程卡片字段不完整" }
                val time = parseTime(fields[3].text())
                courses += CoursePreview(
                    name = name,
                    teacher = fields[2].text().trim(),
                    room = fields[4].text().trim(),
                    day = day,
                    startNode = time.startNode,
                    step = time.endNode - time.startNode + 1,
                    startWeek = time.startWeek,
                    endWeek = time.endWeek,
                )
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("长春师范大学课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("长春师范大学课表解析失败：${error.message}", error)
    }

    /**
     * 解析 `1-16周(1-2节)` 形式的课程时间。
     *
     * @param text 课程卡片的时间字段
     * @return 校验后的周次和节次边界
     */
    private fun parseTime(text: String): CourseTime {
        val match = TIME_PATTERN.find(text)
            ?: throw IllegalArgumentException("课程时间格式无效：$text")
        val values = match.groupValues.drop(1).map { it.toInt() }
        val result = CourseTime(values[0], values[1], values[2], values[3])
        require(result.startWeek > 0 && result.endWeek >= result.startWeek) { "课程周次范围无效：$text" }
        require(result.startNode > 0 && result.endNode >= result.startNode) { "课程节次范围无效：$text" }
        return result
    }

    /** 课程卡片中已解析的时间边界。 */
    private data class CourseTime(
        val startWeek: Int,
        val endWeek: Int,
        val startNode: Int,
        val endNode: Int,
    )

    /** 匹配周次和节次之间允许出现的中文标记及括号。 */
    private val TIME_PATTERN = Regex("""(\d+)\s*[-－—]\s*(\d+)\s*周.*?(\d+)\s*[-－—]\s*(\d+)\s*节""")
}
