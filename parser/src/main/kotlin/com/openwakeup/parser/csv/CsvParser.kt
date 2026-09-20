package com.openwakeup.parser.csv

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils

/**
 * CSV 模板解析器。
 *
 * 模板规则：
 * - 首行表头必须含"课程名称"与"开始节数"（分隔符支持 , ; \t，按表头自动探测）；
 * - 列：课程名称 / 星期 / 开始节数 / 结束节数 / 老师 / 地点 / 周数（旧表头“查询周”仍兼容）；
 * - 查询周支持 "1-8周"、"1-8周(单周)"、"5周"、顿号/逗号多段；空周次视为错误；
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
            val day = parseDay(cells[iDay])
            if (day == null) {
                errors.add("第 $lineNo 行：星期“${cells[iDay]}”无法识别")
                continue
            }
            val startNode = cells.getOrNull(iStart)?.trim()?.toIntOrNull()
            val endNode = cells.getOrNull(iEnd)?.trim()?.toIntOrNull()
            if (startNode == null || startNode < 1) {
                errors.add("第 $lineNo 行：开始节数必须是正整数")
                continue
            }
            if (endNode == null || endNode < startNode) {
                errors.add("第 $lineNo 行：结束节数必须不小于开始节数")
                continue
            }
            // 解析层只判断节次是否为正向的正整数，不再使用旧版 60 节上限截断数据。
            // 具体课表能够显示多少节由应用层结合课表配置和绑定作息统一判断；越界课程仍需
            // 进入数据库和课程管理页，不能在解析阶段被静默丢弃。
            val teacher = cells.getOrNull(iTeacher)?.trim().orEmpty()
            val room = cells.getOrNull(iRoom)?.trim().orEmpty()
            val weeksText = if (iWeeks >= 0) cells[iWeeks] else ""
            val weeks = try {
                WeekUtils.parse(weeksText)
            } catch (error: IllegalArgumentException) {
                errors.add("第 $lineNo 行：${error.message}")
                continue
            }
            previews.addAll(weeks.map { week ->
                CoursePreview(
                    name = name, teacher = teacher, room = room,
                    day = day, startNode = startNode, step = endNode - startNode + 1,
                    startWeek = week.startWeek,
                    endWeek = week.endWeek,
                    type = week.type,
                )
            })
        }
        if (errors.isNotEmpty()) throw ParserException.parse(errors.joinToString("\n"))
        if (previews.isEmpty()) throw ParserException.empty("模板中没有可导入的课程")
        return previews
    }

    /**
     * 根据模板表头探测字段分隔符。
     *
     * @param header CSV 文件的首行表头
     * @return 当前模板使用的逗号、分号或制表符
     */
    private fun detectSeparator(header: String): Char = when {
        header.contains("课程名称;星期") -> ';'
        header.contains("课程名称\t星期") -> '\t'
        else -> ','
    }

    /**
     * 按指定分隔符拆分一行，并保留双引号包围字段中的分隔符。
     *
     * 该方法只实现 WakeUp CSV 模板需要的引号边界，不负责处理跨行字段。
     *
     * @param line 待拆分的数据行
     * @param sep 当前模板使用的分隔符
     * @return 去除外层双引号语义后的字段列表
     */
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

    /**
     * 将中文或数字星期转换为统一的 1～7 表示。
     *
     * @param text 原始星期字段
     * @return 1 表示周一、7 表示周日；无法识别时返回 `null`
     */
    private fun parseDay(text: String): Int? {
        val t = text.trim().removePrefix("星期").removePrefix("周").trim()
        val names = listOf("一", "二", "三", "四", "五", "六", "日")
        names.forEachIndexed { i, n -> if (t == n) return i + 1 }
        return t.toIntOrNull()?.takeIf { it in 1..7 }
    }

    companion object {
        const val HEADER_NAME = "课程名称"
        const val HEADER_START = "开始节数"
    }
}
