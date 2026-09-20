package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `qz_single_node` 使用的强智教室占用式单节课表解析器。 */
object QzSingleNodeParser : Parser {

    /**
     * 解析 `TableLCRoomOccupy` 第一层表体中的 `PuTongCell` 课程格。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹缺失、星期列越界或课程时间字段不完整
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("TableLCRoomOccupy")
            ?: throw ParserException.parse("强智单节页面中缺少 TableLCRoomOccupy 课表")
        if (table.childrenSize() == 0) {
            throw ParserException.parse("强智单节课表缺少表体")
        }
        val courses = mutableListOf<CoursePreview>()

        // 原版只遍历表格第一个直接子节点，避免嵌套课程块中的 tr 被重复解析。
        table.child(0).select("tr").forEach { row ->
            row.select(".PuTongCell").forEachIndexed { columnIndex, cell ->
                val day = columnIndex + 1
                require(day in 1..7) { "强智单节课表的星期列超过 7 列" }
                if (cell.text().isBlank()) return@forEachIndexed
                courses += parseCell(cell, day)
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("强智单节课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("强智单节课表解析失败：${error.message}", error)
    }

    /** 根据课程格 class 是否包含 `jieciN`，选择固定单节或内嵌节次布局。 */
    private fun parseCell(cell: Element, day: Int): List<CoursePreview> {
        val fixedNode = JIECI_PATTERN.find(cell.className())?.groupValues?.get(1)?.toIntOrNull()
        return if (fixedNode != null) {
            require(fixedNode > 0) { "强智单节课程格的 jieci 节次必须为正整数" }
            cell.select("div").filter { block -> block.text().isNotBlank() }.flatMap { block ->
                parseFixedNodeBlock(block, day, fixedNode)
            }
        } else {
            parseEmbeddedNodeBlock(cell, day)
        }
    }

    /** 解析由 `jieciN` 明确声明起止节次均为 N 的课程块。 */
    private fun parseFixedNodeBlock(block: Element, day: Int, node: Int): List<CoursePreview> {
        val lines = htmlLines(block.html())
        require(lines.size >= 5) { "强智单节 jieci 课程块字段不足" }
        val name = lines[0]
        require(name.isNotEmpty()) { "强智单节课程名称为空" }
        val weekLine =
            lines.firstOrNull { line -> line.contains("周次:") || line.contains("周次：") }
                ?: throw IllegalArgumentException("课程 $name 缺少周次")
        val weekText = weekLine.substringAfter("周次:", weekLine.substringAfter("周次：", "")).trim()
        require(weekText.isNotEmpty()) { "课程 $name 的周次字段为空" }

        return WeekUtils.parse(weekText).map { week ->
            CoursePreview(
                name = name,
                teacher = lines[1],
                room = cleanRoom(lines[2]),
                day = day,
                startNode = node,
                startWeek = week.startWeek,
                endWeek = week.endWeek,
                type = week.type,
            )
        }
    }

    /**
     * 解析第四行同时包含 `[起-止节]`、周次及独立单双周标记的课程格。
     *
     * 单双周标记会先绑定到当前课程的对应周次，再创建结果，绝不修改全局上一门课程。
     */
    private fun parseEmbeddedNodeBlock(cell: Element, day: Int): List<CoursePreview> {
        val lines = htmlLines(cell.html())
        require(lines.size >= 4) { "强智单节内嵌节次课程格字段不足" }
        val name = lines[0]
        require(name.isNotEmpty()) { "强智单节课程名称为空" }
        val scheduleText = lines.drop(3).joinToString(",")
        val nodeText = NODE_PATTERN.find(scheduleText)?.value
            ?: throw IllegalArgumentException("课程 $name 缺少明确节次")
        val nodes = TextUtils.requirePositiveRange(nodeText, "课程 $name 的节次")
        val weekMatches = WEEK_PATTERN.findAll(scheduleText).toList()
        require(weekMatches.isNotEmpty()) { "课程 $name 缺少明确周次" }

        val weeks = weekMatches.flatMapIndexed { index, match ->
            val nextStart = weekMatches.getOrNull(index + 1)?.range?.first ?: scheduleText.length
            val localSuffix = scheduleText.substring(match.range.last + 1, nextStart)
            val parity = when {
                localSuffix.contains("单周") -> "单周"
                localSuffix.contains("双周") -> "双周"
                else -> ""
            }
            WeekUtils.parse(match.value + parity)
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

    /** 将 `<br>` 分隔的 HTML 转为保持字段顺序的非空纯文本行。 */
    private fun htmlLines(source: String): List<String> = source.split(BR_PATTERN)
        .map { fragment -> Jsoup.parse(fragment).text().trim() }
        .filter { line -> line.isNotEmpty() }

    /** 删除教室标签和人数说明，仅保留真实上课地点。 */
    private fun cleanRoom(source: String): String = source
        .removePrefix("教室:").removePrefix("教室：")
        .substringBefore("(教室人数").substringBefore("（教室人数")
        .trim()

    private val BR_PATTERN = Regex("(?i)<br\\s*/?>")
    private val JIECI_PATTERN = Regex("jieci(\\d+)", RegexOption.IGNORE_CASE)
    private val NODE_PATTERN = Regex("(?:第)?\\s*\\d+\\s*[-~～至—–]\\s*\\d+\\s*节")
    private val WEEK_PATTERN = Regex("(?:第)?\\s*\\d+(?:\\s*[-~～至—–]\\s*\\d+)?\\s*周")
}
