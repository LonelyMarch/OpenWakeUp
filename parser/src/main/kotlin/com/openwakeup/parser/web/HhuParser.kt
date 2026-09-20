package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 河海大学最终 timetable HTML 解析器。 */
object HhuParser : Parser {
    /** 解析每十个 font 组成的课程；用户名、密码、Base64 登录和 Cookie 请求已删除。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("timetable")
            ?: throw ParserException.parse("河海大学响应中缺少 timetable")
        val rows = table.select("tr"); require(rows.size >= 4) { "河海大学课表行数不足" }
        val result = mutableListOf<CoursePreview>()
        rows.subList(2, rows.size - 1).forEach { row ->
            row.select("td").forEachIndexed { dayIndex, cell ->
                val fonts = cell.selectFirst(".kbcontent")?.select("font") ?: return@forEachIndexed
                require(fonts.size % 10 == 0) { "河海大学课程 font 数量不是 10 的倍数" }
                for (offset in fonts.indices step 10) {
                    val name = fonts[offset].text();
                    val time = fonts[offset + 2].text()
                    val nodeMatch = NODE_PATTERN.find(time) ?: error("课程 $name 的节次字段无效")
                    WeekUtils.parse(time.substringBefore("(周)")).forEach { week ->
                        result += CoursePreview(
                            name,
                            teacher = fonts[offset + 1].text(),
                            room = fonts[offset + 4].text(),
                            day = dayIndex + 1,
                            startNode = nodeMatch.groupValues[1].toInt(),
                            step = nodeMatch.groupValues[2].toInt() - nodeMatch.groupValues[1].toInt() + 1,
                            startWeek = week.startWeek,
                            endWeek = week.endWeek,
                            type = week.type
                        )
                    }
                }
            }
        }
        if (result.isEmpty()) throw ParserException.empty("河海大学课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("河海大学课表解析失败：${error.message}", error)
    }

    private val NODE_PATTERN = Regex("""\(周\)\[(\d+).*?(\d+)节]""")
}
