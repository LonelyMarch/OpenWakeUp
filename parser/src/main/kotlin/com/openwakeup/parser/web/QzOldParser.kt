package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import org.jsoup.Jsoup

/** 旧版强智自由文本 `kbtable` 解析器。 */
object QzOldParser : Parser {
    /** 以包含 `周[...]节` 的行为锚点恢复名称、教师、教室和时间。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("kbtable")
            ?: throw ParserException.parse("旧版强智页面中缺少 kbtable")
        val courses = mutableListOf<CoursePreview>()
        table.select("tr").forEach { row ->
            // 旧表每行第一个 td 是节次标题，上游以 -1 起算后跳过该格。
            var day = -1
            row.select("td").forEach { cell ->
                day++
                cell.select("div").forEach { block ->
                    val parts = block.html().split(Regex("(?i)<br\\s*/?>"))
                    parts.indices.filter { index ->
                        val text = Jsoup.parse(parts[index]).text()
                        text.contains('周') && text.contains('[') && text.contains(']') && text.contains(
                            '节'
                        )
                    }.forEach { index ->
                        require(day in 1..7) { "旧版强智课程所在星期列无效：$day" }
                        require(index >= 3 && index + 1 < parts.size) { "旧版强智课程片段字段不足" }
                        val time = Jsoup.parse(parts[index]).text().trim()
                        val match = TIME_PATTERN.find(time)
                            ?: throw IllegalArgumentException("旧版强智时间字段无法识别：$time")
                        val weeks = TextUtils.requirePositiveRange(match.groupValues[1], "周次")
                        val nodes = TextUtils.requirePositiveRange(match.groupValues[2], "节次")
                        courses += CoursePreview(
                            name = Jsoup.parse(parts[index - 3]).text().trim(),
                            teacher = Jsoup.parse(parts[index - 1]).text().trim(),
                            room = Jsoup.parse(parts[index + 1]).text().trim(),
                            day = day, startNode = nodes.first, step = nodes.last - nodes.first + 1,
                            startWeek = weeks.first, endWeek = weeks.last,
                        )
                    }
                }
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("旧版强智课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("旧版强智课表解析失败：${error.message}", error)
    }

    private val TIME_PATTERN = Regex("""(.+?)周\[(.+?)节]""")
}
