package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `ucas` 使用的 `.mc-body` 三字段课程安排解析器。 */
object UcasParser : Parser {

    /**
     * 解析每个课程容器中的“时间、地点、显式周列表”三元组。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 容器指纹缺失、字段数不是三的倍数或时间集合不合法
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val containers = Jsoup.parse(input.text).getElementsByClass("mc-body")
        if (containers.isEmpty()) throw ParserException.parse("国科大页面中缺少 mc-body 课程容器")
        val courses = containers.flatMap(::parseContainer)
        if (courses.isEmpty()) throw ParserException.empty("国科大课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("国科大课表解析失败：${error.message}", error)
    }

    /** 解析一个课程容器内全部三字段安排。 */
    private fun parseContainer(container: Element): List<CoursePreview> {
        require(container.childrenSize() > 0) { "国科大课程容器缺少标题" }
        val name = container.child(0).text().substringAfter('：').trim()
        require(name.isNotEmpty()) { "国科大课程名称为空" }
        val cells = container.select("td")
        require(cells.isNotEmpty()) { "课程 $name 缺少安排字段" }
        require(cells.size % 3 == 0) { "课程 $name 的安排字段不是三的倍数" }

        return cells.chunked(3).flatMap { group ->
            val scheduleText = group[0].text().trim()
            val day = TextUtils.requireDay(scheduleText.substringBefore('：'))
            val nodes = parseNodes(scheduleText.substringAfter('：'), name)
            val room = group[1].text().trim()
            val weeks = group[2].text().split('、').map { token ->
                token.trim().toIntOrNull()
                    ?: throw IllegalArgumentException("课程 $name 的周次无法识别：$token")
            }.toSortedSet()
            require(weeks.isNotEmpty()) { "课程 $name 缺少明确周次" }

            WeekUtils.compact(weeks).map { week ->
                CoursePreview(
                    name = name,
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

    /** 读取“第 1、2 节”中的节次，并确认集合确实连续。 */
    private fun parseNodes(source: String, courseName: String): IntRange {
        val values = NODE_NUMBER_PATTERN.findAll(source.substringBefore("节"))
            .map { match -> match.value.toInt() }
            .toList()
        require(values.isNotEmpty()) { "课程 $courseName 缺少明确节次" }
        require(values.all { node -> node > 0 }) { "课程 $courseName 的节次必须为正整数" }
        val range = values.first()..values.last()
        require(values == range.toList()) { "课程 $courseName 的节次不连续，当前模型无法无损表达" }
        return range
    }

    private val NODE_NUMBER_PATTERN = Regex("\\d+")
}
