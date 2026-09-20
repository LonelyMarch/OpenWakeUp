package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import org.jsoup.Jsoup

/** 南阳理工学院课程卡片解析器。 */
object NyistParser : Parser {
    /**
     * 解析 `course-content` 下的课程记录。
     *
     * @param input 完整课表 HTML
     * @return 页面中全部明确时间记录
     * @throws ParserException 课程名称、星期、节次或周次缺失
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val items = Jsoup.parse(input.text).select(".course-content")
        if (items.isEmpty()) throw ParserException.parse("南阳理工学院页面中缺少 course-content")
        val courses = mutableListOf<CoursePreview>()
        items.forEach { item ->
            val name = item.select(".name").text().trim()
            require(name.isNotEmpty()) { "课程卡片缺少名称" }
            item.select(".course-item-list").forEach { record ->
                val time = record.select("div.time > p.content").text().trim()
                require(time.isNotEmpty()) { "课程 $name 缺少上课时间" }
                val dayMatch = DAY_PATTERN.find(time)
                    ?: throw IllegalArgumentException("课程 $name 缺少星期：$time")
                val nodeMatch = NODE_PATTERN.find(time)
                    ?: throw IllegalArgumentException("课程 $name 缺少节次：$time")
                val nodes = nodeMatch.groupValues[1].toInt()..nodeMatch.groupValues[2].toInt()
                val weekMatches = WEEK_PATTERN.findAll(time).toList()
                require(weekMatches.isNotEmpty()) { "课程 $name 缺少周次：$time" }
                weekMatches.forEach { weekMatch ->
                    val weeks = TextUtils.requirePositiveRange(weekMatch.groupValues[1], "周次")
                    courses += CoursePreview(
                        name = name,
                        teacher = record.select("div.teacher > p.content").text().trim(),
                        room = record.select("div.address > p.content").text().trim(),
                        day = TextUtils.requireDay(dayMatch.value),
                        startNode = nodes.first,
                        step = nodes.last - nodes.first + 1,
                        startWeek = weeks.first,
                        endWeek = weeks.last,
                        type = when {
                            time.contains('单') -> 1
                            time.contains('双') -> 2
                            else -> 0
                        },
                    )
                }
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("南阳理工学院课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("南阳理工学院课表解析失败：${error.message}", error)
    }

    private val DAY_PATTERN = Regex("""(?:星期|周)[一二三四五六日天]""")
    private val NODE_PATTERN = Regex("""(\d+)\s*[-－—]\s*(\d+)\s*节""")
    private val WEEK_PATTERN = Regex("""第\s*\[?([0-9]+(?:\s*[-－—]\s*[0-9]+)?)\]?\s*周""")
}
