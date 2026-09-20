package com.openwakeup.parser.utils

import com.openwakeup.parser.CoursePreview
import org.jsoup.Jsoup

/** 强智多个独立 Parser 共同使用的旧版网格提取算法。 */
internal object QzGridUtils {

    /**
     * 解析强智 `kbtable` 或调用方明确声明的同型网格。
     *
     * @param source 完整课表 HTML
     * @param contentClass 课程内容元素类名
     * @param separator 连续课程之间的横线分隔符
     * @param nameBeforeBreak 课程名是否只取第一个换行前内容
     * @param explicitNodes 是否从课程属性中读取明确节次
     * @param weekBeforeZhou 是否在首个 `周` 字前截断周次属性
     * @param tableIds 按优先级尝试的强智课表元素 id
     * @param explicitNodeFallback 属性节次缺失或无效时，是否回退到行节次映射
     * @param nodeRangeProvider 将从 1 开始的课表行组映射为实际节次范围的纯函数
     * @return 全部课程预览
     */
    fun parse(
        source: String,
        contentClass: String = "kbcontent",
        separator: String = "-----",
        nameBeforeBreak: Boolean = false,
        explicitNodes: Boolean = false,
        weekBeforeZhou: Boolean = false,
        tableIds: List<String> = listOf("kbtable"),
        explicitNodeFallback: Boolean = false,
        nodeRangeProvider: (Int) -> IntRange = { nodeGroup ->
            val startNode = nodeGroup * 2 - 1
            startNode..(startNode + 1)
        },
    ): List<CoursePreview> {
        require(tableIds.isNotEmpty()) { "强智课表 id 列表不能为空" }
        val document = Jsoup.parse(source)
        val table = tableIds.firstNotNullOfOrNull { tableId -> document.getElementById(tableId) }
            ?: throw IllegalArgumentException("强智页面中缺少课表：${tableIds.joinToString()}")
        val headers = table.select("th").map { it.text() }
        val sundayFirst = headers.indexOfFirst { it.contains("星期日") } >= 0 &&
                headers.indexOfFirst { it.contains("星期日") } < headers.indexOfFirst {
            it.contains(
                "星期一"
            )
        }
        val result = mutableListOf<CoursePreview>()
        var nodeGroup = 0
        table.select("tr").forEach { row ->
            val cells = row.select("td")
            if (cells.isEmpty()) return@forEach
            nodeGroup++
            cells.forEachIndexed { columnIndex, cell ->
                val day = if (sundayFirst) {
                    if (columnIndex == 0) 7 else columnIndex
                } else {
                    columnIndex + 1
                }
                require(day in 1..7) { "强智课表星期列超过 7 列" }
                cell.select("div").forEach { wrapper ->
                    val content = wrapper.getElementsByClass(contentClass)
                    if (content.text().isBlank()) return@forEach
                    splitCourses(content.html(), separator).forEach { fragment ->
                        result += parseFragment(
                            html = fragment,
                            day = day,
                            nodeGroup = nodeGroup,
                            nameBeforeBreak = nameBeforeBreak,
                            explicitNodes = explicitNodes,
                            weekBeforeZhou = weekBeforeZhou,
                            explicitNodeFallback = explicitNodeFallback,
                            nodeRangeProvider = nodeRangeProvider,
                        )
                    }
                }
            }
        }
        return result
    }

    /** 将一个单元格按横线标记拆成多门课程，忽略分隔线后的换行。 */
    private fun splitCourses(html: String, separator: String): List<String> = html.split(separator)
        .map { it.removePrefix("<br>").trim() }
        .filter { it.isNotEmpty() }

    /** 解析一个课程片段中的名称、教师、教室、周次及可选明确节次。 */
    private fun parseFragment(
        html: String,
        day: Int,
        nodeGroup: Int,
        nameBeforeBreak: Boolean,
        explicitNodes: Boolean,
        weekBeforeZhou: Boolean,
        explicitNodeFallback: Boolean,
        nodeRangeProvider: (Int) -> IntRange,
    ): List<CoursePreview> {
        val document = Jsoup.parse(html)
        val rawName =
            if (nameBeforeBreak) html.substringBefore("<br>") else html.substringBefore("<font")
        val name = Jsoup.parse(rawName).text().trim()
        require(name.isNotEmpty()) { "强智课程片段缺少课程名称" }
        val teacher = document.getElementsByAttributeValue("title", "老师").text().trim()
            .ifEmpty { document.getElementsByAttributeValue("title", "教师").text().trim() }
        val combinedRoom = document.getElementsByAttributeValue("title", "教室").text().trim() +
                document.getElementsByAttributeValue("title", "分组").text().trim()
        val room = combinedRoom.ifEmpty {
            document.getElementsByAttributeValue("title", "上课地点").text().trim()
        }
        val combined = document.getElementsByAttributeValue("title", "周次(节次)").text().trim()
        val weekText = when {
            combined.isBlank() -> document.getElementsByAttributeValue("title", "周次").text()
                .trim()

            combined.contains(' ') -> combined.substringBefore(' ')
            weekBeforeZhou && combined.contains('周') -> combined.substringBefore('周')
            else -> combined.substringBefore("(周)").substringBefore(')')
        }
        require(weekText.isNotBlank()) { "课程 $name 缺少周次属性" }
        val rowNodeRange = nodeRangeProvider(nodeGroup)
        require(rowNodeRange.first > 0 && rowNodeRange.last >= rowNodeRange.first) {
            "强智第 $nodeGroup 行映射出的节次范围无效"
        }
        val nodeRange = if (explicitNodes) {
            if (explicitNodeFallback) {
                // 原版部分学校会在属性缺失时使用学校专属行映射，不能写死标准双节规则。
                runCatching { parseExplicitNodes(document, combined) }.getOrElse { rowNodeRange }
            } else {
                parseExplicitNodes(document, combined)
            }
        } else {
            rowNodeRange
        }
        return WeekUtils.parse(weekText).map { week ->
            CoursePreview(
                name = name, teacher = teacher, room = room, day = day,
                startNode = nodeRange.first, step = nodeRange.last - nodeRange.first + 1,
                startWeek = week.startWeek, endWeek = week.endWeek, type = week.type,
            )
        }
    }

    /** 从组合属性或独立节次属性中提取明确节次。 */
    private fun parseExplicitNodes(document: org.jsoup.nodes.Document, combined: String): IntRange {
        val text = when {
            combined.contains(' ') -> combined.substringAfter(' ')
            combined.isBlank() -> document.getElementsByAttributeValue("title", "节次").text()
                .substringAfter(')')

            else -> combined.substringAfter(')')
        }
        val values = Regex("""\d+""").findAll(text).map { it.value.toInt() }.toList()
        require(values.isNotEmpty()) { "强智课程缺少明确节次" }
        val normalized = if (values.size == 1 && values[0] > 99) {
            val compact = values[0].toString()
            require(compact.length == 4) { "紧凑节次格式无效：$text" }
            listOf(compact.take(2).toInt(), compact.takeLast(2).toInt())
        } else values
        val start = normalized.first()
        val end = normalized.last()
        require(start > 0 && end >= start) { "强智课程节次范围无效：$text" }
        return start..end
    }
}
