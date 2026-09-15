/*
 * 本文件基于 YZune/WakeupSchedule_Kotlin 的 Apache-2.0 URP 解析实现修改。
 * 原始版权：Copyright 2019 YZune。
 * OpenWakeUp 于 2026 年重构了 HTML 选择、字段规范化和异常处理；完整来源见仓库 NOTICE.md。
 */
package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import org.jsoup.Jsoup

/**
 * URP 家族行式解析器。
 * 老版 URP 课表为"每行一门课"的表格：class=displayTag（回退 table-striped），
 * 表头 th 文本映射列：课程名/教师/周次/星期/节次/地点；未识别 type 的默认回落语义保留。
 */
object UrpParser : Parser {
    override fun parse(input: ParserInput): List<CoursePreview> {
        val doc = Jsoup.parse(input.text)
        var tables = doc.getElementsByAttributeValue("class", "displayTag")
        if (tables.isEmpty()) {
            tables = doc.getElementsByAttributeValue("class", "table table-striped table-bordered")
        }
        if (tables.isEmpty()) throw ParserException.parse("页面中没有 URP 课表表格")

        val previews = mutableListOf<CoursePreview>()
        tables.forEach { table ->
            // 含"星期一"的表是网格视图，跳过（行式表不显示星期文本）
            if (table.text().contains("星期一")) return@forEach
            val head = table.select("thead th")
            if (head.isEmpty()) return@forEach
            var nameIndex = -1
            var teacherIndex = -1
            var weekIndex = -1
            var dayIndex = -1
            var nodeIndex = -1
            var roomIndex = -1
            head.eachText().forEachIndexed { index, s ->
                when (s.trim()) {
                    "课程名" -> nameIndex = index
                    "教师" -> teacherIndex = index
                    "周次" -> weekIndex = index
                    "星期" -> dayIndex = index
                    "节次" -> nodeIndex = index
                    "地点", "教室" -> roomIndex = index
                }
            }
            if (nameIndex < 0 || dayIndex < 0) return@forEach

            table.select("tbody tr").forEach { tr ->
                val tds = tr.select("td")
                if (tds.size <= maxOf(nameIndex, dayIndex)) return@forEach
                val name = tds[nameIndex].text().trim()
                if (name.isEmpty()) return@forEach
                val dayText = tds[dayIndex].text().trim()
                val day = parseDay(dayText)
                if (day !in 1..7) return@forEach
                val (startWeek, endWeek, type) = HtmlGridParser.parseWeeks(
                    tds.getOrNull(weekIndex)?.text().orEmpty()
                )
                val nodeText = tds.getOrNull(nodeIndex)?.text().orEmpty()
                var startNode = 1
                var step = 1
                Regex("""(\d{1,2})\s*[-–]\s*(\d{1,2})""").find(nodeText)?.let { m ->
                    startNode = m.groupValues[1].toInt()
                    step = (m.groupValues[2].toInt() - startNode + 1).coerceAtLeast(1)
                } ?: nodeText.trim().toIntOrNull()?.let { startNode = it }
                previews.add(
                    CoursePreview(
                        name = name,
                        teacher = tds.getOrNull(teacherIndex)?.text()?.trim().orEmpty(),
                        room = tds.getOrNull(roomIndex)?.text()?.trim().orEmpty(),
                        day = day, startNode = startNode, step = step,
                        startWeek = startWeek, endWeek = endWeek, type = type,
                    ),
                )
            }
        }
        if (previews.isEmpty()) throw ParserException.empty()
        return previews
    }

    private fun parseDay(text: String): Int {
        val t = text.trim().removePrefix("星期").removePrefix("周")
        val names = listOf("一", "二", "三", "四", "五", "六", "日")
        names.forEachIndexed { i, n -> if (t == n) return i + 1 }
        return t.toIntOrNull()?.takeIf { it in 1..7 } ?: -1
    }
}
