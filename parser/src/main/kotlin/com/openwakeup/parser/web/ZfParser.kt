/*
 * 本文件基于 YZune/WakeupSchedule_Kotlin 的 Apache-2.0 正方教务解析实现修改。
 * 原始版权：Copyright 2019 YZune。
 * OpenWakeUp 于 2026 年统一了多版本页面解析与结果校验；完整来源见仓库 NOTICE.md。
 */
package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import org.jsoup.Jsoup

/**
 * 正方家族解析器。
 *
 * 页面语义：
 * - 主表 `<table id="Table1">`；行内 "第N节" 单元格标记当前节次；
 * - 列计数（跳过表头与空格）即星期（第 1 数据列=周一）；
 * - 单元格 html 按 `<br><br>`（异常页 `<br><br><br>`）拆分多门课；
 * - 每门课剥 `<a>` 后按 `<br>` 拆行：[名称, (属性如"必修课"), 时间段{第a-b周(单周)}, 教师?, 教室?]；
 * - 时间段 "周X" 前缀可覆盖列推算的星期；{...} 内解析起止周与单双周。
 */
object ZfParser : Parser {
    override fun parse(input: ParserInput): List<CoursePreview> {
        val doc = Jsoup.parse(input.text)
        val table = doc.getElementById("Table1")
            ?: doc.select("table#table1").firstOrNull()
            ?: doc.select("table").firstOrNull()
            ?: throw ParserException.parse("页面中没有课表表格（未找到 Table1）")

        data class Bean(
            var cDay: Int, var startNode: Int, var name: String,
            var timeInfo: String, var room: String, var teacher: String,
        )

        val beans = mutableListOf<Bean>()
        var node = -1
        for (tr in table.select("tr")) {
            val tds = tr.select("td")
            var countFlag = false
            var countDay = 0
            for (td in tds) {
                val text = td.text().trim()
                if (text.length <= 1) {
                    if (countFlag) countDay++
                    continue
                }
                if (text in OTHER_HEADER) continue
                val headerNode = parseHeaderNode(text)
                if (headerNode != -1) {
                    node = headerNode
                    countFlag = true
                    continue
                }
                countDay++
                // 单元格 html 拆多门课（按 <br><br> 拆分，异常页 <br><br><br>）
                // jsoup html() 会在 <br> 后插入换行，先去除以保证 <br><br> 连续匹配
                val html =
                    td.html().replace("\n", "").replace("\r", "").substringBeforeLast("</td>")
                val abnormal = html.contains("<br><br><br>")
                val courses = html.split(if (abnormal) "<br><br><br>" else "<br><br>")
                for (courseStr in courses) {
                    val inner = if (courseStr.contains("\">")) {
                        courseStr.substringAfter("\">").substringBeforeLast("</a>")
                    } else {
                        courseStr
                    }
                    val split = inner.split("<br>").map { it.trim() }.filter { it.isNotEmpty() }
                    if (split.size < 3) continue
                    val bean = when {
                        split[1] in COURSE_PROPERTY && split.size >= 5 ->
                            Bean(
                                countDay,
                                node,
                                split[0],
                                split[2],
                                split.getOrElse(4) { "" },
                                split[3]
                            )

                        split[1] in COURSE_PROPERTY && split.size == 4 ->
                            Bean(countDay, node, split[0], split[2], split[3], "")

                        !abnormal && split.size == 3 ->
                            Bean(countDay, node, split[0], split[1], split[2], "")

                        abnormal && split.size == 3 ->
                            Bean(countDay, node, split[0], split[1], "", split[2])

                        else ->
                            // ≥4 元：[名称, 时间段, 教师, 教室]
                            Bean(countDay, node, split[0], split[1], split[3], split[2])
                    }
                    beans.add(bean)
                }
            }
        }
        if (beans.isEmpty()) throw ParserException.empty()

        return beans.mapNotNull { b ->
            // day：timeInfo "周X" 前缀优先，否则列位置
            val day = if (b.timeInfo.startsWith("周") && b.timeInfo.length >= 2) {
                chineseWeek(b.timeInfo.substring(0, 2))
            } else {
                b.cDay
            }
            if (day !in 1..7) return@mapNotNull null
            val brace = Regex("""[{][^}]*[}]""").find(b.timeInfo)?.value.orEmpty()
            val nums = Regex("""\d{1,2}""").findAll(brace).toList().map { it.value.toInt() }
            // 保留教务网页给出的原始周次，超过应用 48 周上限的课程由统一导入策略入库、
            // 提示并隐藏，避免旧版 60 周截断掩盖真实的非法数据。
            val startWeek = nums.getOrElse(0) { 1 }
            val endWeek = nums.getOrElse(1) { startWeek }
            val type = when {
                b.timeInfo.contains("单周") -> 1
                b.timeInfo.contains("双周") -> 2
                else -> 0
            }
            // 节次区间 {第a-b节} 可覆盖行标 node
            val nodeRange = Regex("""第\s*(\d{1,2})\s*[-–]\s*(\d{1,2})\s*节""").find(brace)
            val startNode = nodeRange?.groupValues?.get(1)?.toIntOrNull() ?: b.startNode
            val step =
                nodeRange?.let { (it.groupValues[2].toInt() - startNode + 1).coerceAtLeast(1) } ?: 1
            CoursePreview(
                name = b.name, teacher = b.teacher, room = b.room,
                day = day, startNode = startNode, step = step,
                startWeek = startWeek, endWeek = endWeek, type = type,
            )
        }
    }

    private fun parseHeaderNode(text: String): Int =
        if (text.startsWith("第") && text.endsWith("节")) {
            text.substring(1, text.length - 1).trim().toIntOrNull() ?: -1
        } else {
            -1
        }

    private fun chineseWeek(text: String): Int =
        when (text) {
            "周一" -> 1; "周二" -> 2; "周三" -> 3; "周四" -> 4
            "周五" -> 5; "周六" -> 6; "周日" -> 7
            else -> -1
        }

    private val OTHER_HEADER = setOf(
        "时间", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日",
        "早晨", "上午", "下午", "晚上", "节次",
    )
    private val COURSE_PROPERTY = setOf(
        "任选", "限选", "实践选修", "必修课", "选修课", "公共必修", "专业必修", "专业选修",
    )
}
