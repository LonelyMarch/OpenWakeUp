/*
 * 本文件参考 YZune/WakeupSchedule_Kotlin 的 Apache-2.0 教务表格解析行为并进行了重写。
 * 原始版权：Copyright 2019 YZune。
 * OpenWakeUp 于 2026 年建立统一网格模型、坐标推导和边界校验；完整来源见仓库 NOTICE.md。
 */
package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * HTML 课表网格解析核心（强智/金智/青果/树维等网格家族共用的容错实现）。
 *
 * 解析策略：
 * 1. 取页面全部 `<table>`（优先命中 [preferredTableIds]）；
 * 2. 表头行（含"星期一…星期日"）→ 记录列→星期映射；
 * 3. 数据行：单元格文本行 = 课程名 / 周次段(单双周) / 教室 / 教师；星期 = 列映射，无表头时按数据列位置回退；
 * 4. 节次来自行首"第N节"标记或格内节次段。
 */
open class HtmlGridParser(
    private val preferredTableIds: List<String> = emptyList(),
) : Parser {

    override fun parse(input: ParserInput): List<CoursePreview> {
        val doc = Jsoup.parse(input.text)
        val tables = doc.select("table")
        if (tables.isEmpty()) throw ParserException.parse("页面中没有课表表格")
        val preferred =
            preferredTableIds.firstNotNullOfOrNull { id -> doc.select("#$id").firstOrNull() }
        val ordered = listOfNotNull(preferred) + tables.filter { it !== preferred }
        for (table in ordered) {
            val result = parseTable(table)
            if (result.isNotEmpty()) return result
        }
        throw ParserException.empty()
    }

    /** 解析单张表格 */
    fun parseTable(table: Element): List<CoursePreview> {
        val previews = mutableListOf<CoursePreview>()
        var columnDays: Map<Int, Int> = emptyMap() // 列索引 → 星期
        var node = 0
        for (row in table.select("tr")) {
            val cells = row.select("td")
            if (cells.isEmpty()) continue

            // 表头行：星期一…星期日 → 列映射
            val headerMap = headerColumnDays(row)
            if (headerMap.isNotEmpty()) {
                columnDays = headerMap
                continue
            }

            for ((idx, cell) in cells.withIndex()) {
                // 行首节次标记（第N节 / 纯数字）
                val text0 = cell.text().trim()
                val headerNode = parseHeaderNode(text0)
                if (headerNode != -1) {
                    node = headerNode
                    continue
                }
                if (idx == 0 && node > 0 && text0.length <= 3 && text0.toIntOrNull() != null) continue
                val day = columnDays[idx] ?: if (node > 0) idx else -1
                parseCell(cell, day, node)?.let { previews.add(it) }
            }
        }
        return previews
    }

    /** 表头行：返回 列索引→星期；非表头行返回空 */
    private fun headerColumnDays(row: Element): Map<Int, Int> {
        val map = mutableMapOf<Int, Int>()
        row.select("td, th").forEachIndexed { idx, cell ->
            val t = cell.text().trim().removePrefix("星期")
            val d = when (t) {
                "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4
                "五" -> 5; "六" -> 6; "日", "天" -> 7
                else -> -1
            }
            if (d > 0) map[idx] = d
        }
        return if (map.size >= 5) map else emptyMap()
    }

    private fun parseHeaderNode(text: String): Int =
        if (text.startsWith("第") && text.endsWith("节")) {
            text.substring(1, text.length - 1).trim().toIntOrNull() ?: -1
        } else {
            -1
        }

    /**
     * 解析单元格：文本 ≥2 行且存在周次模式行；[fallbackDay] 为列位置推算的星期。
     */
    fun parseCell(cell: Element, fallbackDay: Int, fallbackNode: Int): CoursePreview? {
        val text = cell.wholeText().replace('\u00A0', ' ')
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return null
        val weekIdx = lines.indexOfLast { WEEK_PATTERN.containsMatchIn(it) }
        if (weekIdx <= 0) return null
        val name = lines[0]
        if (name.isBlank() || name.length > 60) return null
        val (startWeek, endWeek, type) = parseWeeks(lines[weekIdx])
        val middle = lines.subList(1, weekIdx)
        val teacher = middle.lastOrNull { it.length <= 20 && !it.contains("节") } ?: ""
        val room = middle.dropLast(1).firstOrNull() ?: ""
        var startNode = fallbackNode.coerceAtLeast(1)
        var step = 1
        middle.firstOrNull { NODE_PATTERN.containsMatchIn(it) }?.let {
            val m = NODE_PATTERN.find(it)!!
            startNode = m.groupValues[1].toInt()
            step = (m.groupValues[2].toIntOrNull() ?: startNode) - startNode + 1
            if (step < 1) step = 1
        }
        val day = detectDay(cell).takeIf { it > 0 } ?: fallbackDay
        if (day !in 1..7) return null
        return CoursePreview(
            name = name, teacher = teacher, room = room,
            day = day, startNode = startNode, step = step,
            startWeek = startWeek, endWeek = endWeek, type = type,
        )
    }

    /** 从单元格属性（queue/id/class）中的 1-7 数字推断星期 */
    private fun detectDay(cell: Element): Int {
        for (attr in listOf("queue", "id", "class")) {
            val m = DAY_DIGIT.find(cell.attr(attr)) ?: continue
            val v = m.groupValues[1].toInt()
            if (v in 1..7) return v
        }
        return -1
    }

    companion object {
        /** 周次模式：1-16周 / 第1-16周 / 1-16周(单周) / 1-16(双) */
        val WEEK_PATTERN =
            Regex("""(第)?\d{1,2}\s*[-–]\s*\d{1,2}\s*周?(\s*[(（]\s*[单双]\s*周?\s*[)）])?""")
        private val NODE_PATTERN = Regex("""第?\s*(\d{1,2})\s*[-–]\s*(\d{1,2})\s*节""")
        private val DAY_DIGIT = Regex("""[^0-9]([1-7])(?:\D|$)""")

        /** 解析周次文本 → (start, end, type)；type：0 每周 / 1 单周 / 2 双周 */
        fun parseWeeks(text: String): Triple<Int, Int, Int> {
            val m = WEEK_PATTERN.find(text) ?: return Triple(1, 25, 0)
            val body = m.value
            val nums = Regex("""\d{1,2}""").findAll(body).toList().map { it.value.toInt() }
            val start = nums.getOrElse(0) { 1 }
            val end = nums.getOrElse(1) { start }
            val type = when {
                body.contains("单") -> 1
                body.contains("双") -> 2
                else -> 0
            }
            // 保留网页中的原始周次范围。应用层会自动扩展至最多 48 周，并把超过系统上限的
            // 课程标记为非法；解析器若在此截断，课程管理页将无法显示真实的待修正数值。
            return Triple(start, end, type)
        }
    }
}

/** 强智家族（qz 系列）：kbtable 主表（表头星期定位 + 列回退） */
object QzParser : HtmlGridParser(listOf("kbtable"))

/** 金智家族（jz 系列） */
object JzParser : HtmlGridParser()

/** 青果家族（kingosoft） */
object KingosoftParser : HtmlGridParser()

/** 树维家族（shuwei） */
object ShuweiParser : HtmlGridParser()

/** 未识别 type 的回落解析（ParserFactory 的默认回落分支） */
object GenericTableParser : HtmlGridParser()
