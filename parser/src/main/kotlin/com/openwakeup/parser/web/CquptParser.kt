package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 重庆邮电大学 stuPanel 文本网格解析器。 */
object CquptParser : Parser {
    /** 按八字段课程组解析，并严格要求表格行能映射到已知两节时段。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val panel = Jsoup.parse(input.text).getElementById("stuPanel")
            ?: throw ParserException.parse("重庆邮电大学页面中缺少 stuPanel")
        val result = mutableListOf<CoursePreview>()
        panel.select("tr[style=text-align:center]").forEachIndexed { rowIndex, row ->
            val startNode = START_NODE[rowIndex] ?: return@forEachIndexed
            row.select("td").forEachIndexed { day, cell ->
                if (day !in 1..7) return@forEachIndexed
                val fields =
                    cell.text().replace(" -", "-").split(Regex("\\s+")).filter { it.isNotEmpty() }
                require(fields.size % 8 == 0 || fields.isEmpty()) { "重邮课程格不是完整八字段分组" }
                for (offset in fields.indices step 8) {
                    val name = fields[offset + 1].substringAfter('-').trim()
                    val room = fields[offset + 2].substringAfter('：').trim()
                    val weekText = fields[offset + 3]
                    val extra =
                        Regex("""周(\d+)节连上""").find(weekText)?.groupValues?.get(1)?.toInt() ?: 2
                    WeekUtils.parse(weekText.substringBefore("节连上")).forEach { week ->
                        result += CoursePreview(
                            name = name, teacher = fields[offset + 4], room = room, day = day,
                            startNode = startNode, step = extra, startWeek = week.startWeek,
                            endWeek = week.endWeek, type = week.type
                        )
                    }
                }
            }
        }
        if (result.isEmpty()) throw ParserException.empty("重庆邮电大学课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("重庆邮电大学课表解析失败：${error.message}", error)
    }

    private val START_NODE = mapOf(0 to 1, 1 to 3, 3 to 5, 4 to 7, 6 to 9, 7 to 11)
}
