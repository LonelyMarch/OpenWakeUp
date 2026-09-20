package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `lz` 使用的教室占用式七日课表解析器。 */
object LzParser : Parser {

    /**
     * 解析 `TableLCRoomOccupy` 下的 `PuTongCell` 课程格。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹缺失、星期列越界或课程时间字段不完整
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("TableLCRoomOccupy")
            ?: throw ParserException.parse("页面中缺少 lz 教室占用课表")
        val courses = mutableListOf<CoursePreview>()
        table.select("tr").forEach { row ->
            row.select(".PuTongCell").forEachIndexed { columnIndex, cell ->
                val day = columnIndex + 1
                require(day in 1..7) { "lz 课表的星期列超过 7 列" }
                if (cell.text().isBlank()) return@forEachIndexed
                courses += parseCell(cell, day)
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("lz 教室占用课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("lz 课表解析失败：${error.message}", error)
    }

    /** 根据 class 中是否带 `jieciN` 选择对应的两种原版课程格布局。 */
    private fun parseCell(cell: Element, day: Int): List<CoursePreview> {
        val classNode = JIECI_PATTERN.find(cell.className())?.groupValues?.get(1)?.toIntOrNull()
        return if (classNode != null) {
            require(classNode > 0) { "lz 课程格的 jieci 节次必须为正整数" }
            cell.select("div").filter { block -> block.text().isNotBlank() }.flatMap { block ->
                parseFixedNodeBlock(block, day, classNode)
            }
        } else {
            parseEmbeddedNodeBlock(cell, day)
        }
    }

    /**
     * 解析 class 已明确节次的课程块。
     *
     * @param block 一个课程 `div`
     * @param day 当前视觉列对应的星期
     * @param node class 中的单节节次
     */
    private fun parseFixedNodeBlock(block: Element, day: Int, node: Int): List<CoursePreview> {
        val lines = htmlLines(block.html())
        require(lines.size >= 4) { "lz jieci 课程块字段不足" }
        val name = lines[0]
        require(name.isNotEmpty()) { "lz 课程名为空" }
        val teacher = lines[1]
        val room = cleanRoom(lines[2])
        val weekLine =
            lines.firstOrNull { line -> line.contains("周次:") || line.contains("周次：") }
                ?: throw IllegalArgumentException("课程 $name 缺少周次")
        val weekText = weekLine.substringAfter("周次:", weekLine.substringAfter("周次：", "")).trim()
        require(weekText.isNotEmpty()) { "课程 $name 的周次字段为空" }

        return WeekUtils.parse(weekText).map { week ->
            CoursePreview(
                name = name,
                teacher = teacher,
                room = room,
                day = day,
                startNode = node,
                startWeek = week.startWeek,
                endWeek = week.endWeek,
                type = week.type,
            )
        }
    }

    /**
     * 解析节次和周次都写在第四行及后续片段中的课程格。
     *
     * 单双周标记先与明确周次合并，再一次性构造课程，避免原版修改全局上一条课程。
     */
    private fun parseEmbeddedNodeBlock(cell: Element, day: Int): List<CoursePreview> {
        val lines = htmlLines(cell.html())
        require(lines.size >= 4) { "lz 内嵌节次课程格字段不足" }
        val name = lines[0]
        require(name.isNotEmpty()) { "lz 课程名为空" }
        val nodes = TextUtils.requirePositiveRange(
            NODE_PATTERN.find(lines.drop(3).joinToString(" "))?.value.orEmpty(),
            "课程 $name 的节次",
        )
        val scheduleText = lines.drop(3).joinToString(",")
        val weekMatches = WEEK_PATTERN.findAll(scheduleText).toList()
        require(weekMatches.isNotEmpty()) { "课程 $name 缺少明确周次" }
        val globalType = when {
            scheduleText.contains("单周") && !scheduleText.contains("双周") -> "单周"
            scheduleText.contains("双周") && !scheduleText.contains("单周") -> "双周"
            else -> ""
        }
        val weeks = weekMatches.flatMapIndexed { index, match ->
            val nextStart = weekMatches.getOrNull(index + 1)?.range?.first ?: scheduleText.length
            val localSuffix = scheduleText.substring(match.range.last + 1, nextStart)
            val typeSuffix = when {
                localSuffix.contains("单周") -> "单周"
                localSuffix.contains("双周") -> "双周"
                weekMatches.size == 1 -> globalType
                else -> ""
            }
            WeekUtils.parse(match.value + typeSuffix)
        }

        return weeks.map { week ->
            CoursePreview(
                name = name,
                teacher = lines[1].removePrefix("教师:").removePrefix("教师：").trim(),
                room = cleanRoom(lines[2]),
                day = day,
                startNode = nodes.first,
                step = nodes.last - nodes.first + 1,
                startWeek = week.startWeek,
                endWeek = week.endWeek,
                type = week.type,
            )
        }
    }

    /** 将 `<br>` 分隔的 HTML 转成保留字段顺序的纯文本行。 */
    private fun htmlLines(source: String): List<String> = source.split(BR_PATTERN)
        .map { fragment -> Jsoup.parse(fragment).text().trim() }
        .filter { line -> line.isNotEmpty() }

    /** 去除教室标签和人数说明，但保留真实地点文本。 */
    private fun cleanRoom(source: String): String = source
        .removePrefix("教室:").removePrefix("教室：")
        .substringBefore("(教室人数").substringBefore("（教室人数")
        .trim()

    private val BR_PATTERN = Regex("(?i)<br\\s*/?>")
    private val JIECI_PATTERN = Regex("jieci(\\d+)", RegexOption.IGNORE_CASE)
    private val NODE_PATTERN = Regex("(?:第)?\\s*\\d+\\s*[-~～至—–]\\s*\\d+\\s*节")
    private val WEEK_PATTERN = Regex("(?:第)?\\s*\\d+(?:\\s*[-~～至—–]\\s*\\d+)?\\s*周")
}
