/*
 * 本文件基于 YZune/WakeupSchedule_Kotlin 的 Apache-2.0 CSV 解析实现修改。
 * 原始版权：Copyright 2019 YZune。
 * OpenWakeUp 于 2026 年重构了解析规则、编码输入和校验行为；完整来源见仓库 NOTICE.md。
 */
package com.openwakeup.parser.csv

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput

/**
 * CSV 模板解析器。
 *
 * 模板规则：
 * - 首行表头必须含"课程名称"与"开始节数"（分隔符支持 , ; \t，按表头自动探测）；
 * - 列：课程名称 / 星期 / 开始节数 / 结束节数 / 老师 / 地点 / 周数（旧表头“查询周”仍兼容）；
 * - 查询周支持 "1-8周"、"1-8周(单周)"、"5周"、顿号/逗号多段；空 = 全学期；
 * - 数据行错误逐行提示（"第 N 行数据不足"等）。
 */
class CsvParser : Parser {
    override fun parse(input: ParserInput): List<CoursePreview> {
        val lines = input.text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) throw ParserException.empty("文件为空")
        val sep = detectSeparator(lines.first())
        val header = splitLine(lines.first(), sep).map { it.trim() }
        if (!header.contains(HEADER_NAME) || !header.contains(HEADER_START)) {
            throw ParserException.parse("请使用模板填写导入！并不是任何的 Excel 文件都能够导入的！")
        }
        fun col(vararg names: String): Int =
            names.firstNotNullOfOrNull { n -> header.indexOfFirst { it == n }.takeIf { it >= 0 } }
                ?: -1

        val iName = col(HEADER_NAME)
        val iDay = col("星期")
        val iStart = col(HEADER_START)
        val iEnd = col("结束节数")
        val iTeacher = col("老师", "教师")
        val iRoom = col("地点", "教室")
        // 新模板使用更直观的“周数”，同时保留旧版“查询周”兼容。
        val iWeeks = col("周数", "查询周")

        val errors = mutableListOf<String>()
        val previews = mutableListOf<CoursePreview>()
        for (i in 1 until lines.size) {
            val cells = splitLine(lines[i], sep)
            val lineNo = i + 1
            val need = maxOf(iName, iDay, iStart, iEnd, iWeeks) + 1
            if (cells.size < need) {
                errors.add("第 $lineNo 行数据不足")
                continue
            }
            val name = cells[iName].trim()
            if (name.isEmpty()) continue
            val day = parseDay(cells[iDay]) ?: run {
                errors.add("第 $lineNo 行：星期“${cells[iDay]}”无法识别")
                0
            }
            val startNode = cells.getOrNull(iStart)?.trim()?.toIntOrNull() ?: 1
            val endNode = cells.getOrNull(iEnd)?.trim()?.toIntOrNull() ?: startNode
            // 解析层只判断节次是否为正向的正整数，不再使用旧版 60 节上限截断数据。
            // 具体课表能够显示多少节由应用层结合课表配置和绑定作息统一判断；越界课程仍需
            // 进入数据库和课程管理页，不能在解析阶段被静默丢弃。
            if (day in 1..7 && startNode >= 1 && endNode >= startNode) {
                val teacher = cells.getOrNull(iTeacher)?.trim().orEmpty()
                val room = cells.getOrNull(iRoom)?.trim().orEmpty()
                val weeksText = if (iWeeks >= 0) cells[iWeeks] else ""
                previews.addAll(parseWeeks(weeksText).map { (s, e, t) ->
                    CoursePreview(
                        name = name, teacher = teacher, room = room,
                        day = day, startNode = startNode, step = endNode - startNode + 1,
                        startWeek = s, endWeek = e, type = t,
                    )
                })
            }
        }
        if (errors.isNotEmpty()) throw ParserException.parse(errors.joinToString("\n"))
        if (previews.isEmpty()) throw ParserException.empty("模板中没有可导入的课程")
        return previews
    }

    /** 分隔符探测（, ; \t 三种） */
    private fun detectSeparator(header: String): Char = when {
        header.contains("课程名称;星期") -> ';'
        header.contains("课程名称\t星期") -> '\t'
        else -> ','
    }

    private fun splitLine(line: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuote = false
        for (c in line) {
            when {
                c == '"' -> inQuote = !inQuote
                c == sep && !inQuote -> {
                    out.add(cur.toString()); cur.clear()
                }

                else -> cur.append(c)
            }
        }
        out.add(cur.toString())
        return out
    }

    private fun parseDay(text: String): Int? {
        val t = text.trim().removePrefix("星期").removePrefix("周").trim()
        val names = listOf("一", "二", "三", "四", "五", "六", "日")
        names.forEachIndexed { i, n -> if (t == n) return i + 1 }
        return t.toIntOrNull()?.takeIf { it in 1..7 }
    }

    companion object {
        const val HEADER_NAME = "课程名称"
        const val HEADER_START = "开始节数"

        /** 查询周多段拆分（顿号/逗号），每段 "a-b周(单周)" / "a周" / "a-b" */
        fun parseWeeks(text: String): List<Triple<Int, Int, Int>> {
            val cleaned = text.trim()
            if (cleaned.isBlank()) return listOf(Triple(1, 25, 0))
            return cleaned.split('、', ',').map { it.trim() }.filter { it.isNotEmpty() }.map { seg ->
                val t = when {
                    seg.contains("单") -> 1
                    seg.contains("双") -> 2
                    else -> 0
                }
                val nums = Regex("""\d{1,2}""").findAll(seg).toList().map { it.value.toInt() }
                when {
                    nums.isEmpty() -> Triple(1, 25, t)
                    nums.size == 1 -> Triple(nums[0], nums[0], t)
                    else -> Triple(nums[0], nums[1], t)
                }
            }.ifEmpty { listOf(Triple(1, 25, 0)) }
        }
    }
}
