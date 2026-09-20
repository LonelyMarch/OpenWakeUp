package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `changzhou` 使用的 `GVxkall/GVxkkb` 关联表格解析器。 */
object ChangzhouParser : Parser {

    /**
     * 先从 `#GVxkall` 建立“课程名 -> 教师”映射，再解析 `#GVxkkb` 周课表。
     *
     * 周课表第一列不是星期；其余列下标直接对应星期。每一行表示一个单节，单元格可使用 `/`
     * 分隔多门课程。调用方必须提供两个表格所在的最终 HTML，不存在额外网络请求。
     *
     * @param input 包含课程清单和周课表的完整 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 任一目标表格或必需课程字段缺失
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val document = Jsoup.parse(input.text)
        val teacherTable = document.getElementById("GVxkall")
            ?: throw ParserException.parse("changzhou 页面中缺少 GVxkall")
        val scheduleTable = document.getElementById("GVxkkb")
            ?: throw ParserException.parse("changzhou 页面中缺少 GVxkkb")
        val teachers = parseTeacherMap(teacherTable)
        val courses = parseSchedule(scheduleTable, teachers)
        if (courses.isEmpty()) throw ParserException.empty("changzhou 页面中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("changzhou 课表解析失败：${error.message}", error)
    }

    /** 从课程总表固定的第 2、6 列读取课程名和教师。 */
    private fun parseTeacherMap(table: Element): Map<String, String> = buildMap {
        table.getElementsByClass("dg1-item").forEachIndexed { rowIndex, row ->
            val cells = row.select("td")
            require(cells.size > 5) { "changzhou 课程总表第 ${rowIndex + 1} 行不足 6 列" }
            val name = cells[1].text().trim()
            require(name.isNotEmpty()) { "changzhou 课程总表第 ${rowIndex + 1} 行课程名为空" }
            put(name, cells[5].text().trim())
        }
    }

    /** 解析周课表；行号表示节次，第一列之后的列号表示星期。 */
    private fun parseSchedule(
        table: Element,
        teachers: Map<String, String>,
    ): List<CoursePreview> {
        val courses = mutableListOf<CoursePreview>()
        table.getElementsByClass("dg1-item").forEachIndexed { rowIndex, row ->
            val node = rowIndex + 1
            row.select("td").forEachIndexed cellLoop@{ columnIndex, cell ->
                if (columnIndex == 0 || cell.text().isBlank()) return@cellLoop
                require(columnIndex in 1..7) { "changzhou 周课表存在超过星期日的课程列" }
                cell.text().split('/').filter { fragment -> fragment.isNotBlank() }
                    .forEach { fragment ->
                        courses += parseFragment(fragment, columnIndex, node, teachers)
                    }
            }
        }
        return courses
    }

    /**
     * 解析 `课程名 [教室] [单双周] 周次` 字段。
     *
     * 原版仅在字段数为 4 时读取单双周；该约定被保留，但周次交给 [WeekUtils] 做无损校验。
     */
    private fun parseFragment(
        source: String,
        day: Int,
        node: Int,
        teachers: Map<String, String>,
    ): List<CoursePreview> {
        val fields = source.trim().split(Regex("\\s+")).filter { field -> field.isNotEmpty() }
        require(fields.size >= 2) { "changzhou 课程字段不足：$source" }
        val name = fields.first()
        val room = if (fields.size > 2) fields[1] else ""
        val parity = if (fields.size == 4) {
            when (fields[2]) {
                "单" -> "单周"
                "双" -> "双周"
                else -> ""
            }
        } else {
            ""
        }
        val weekText = fields.last()
        require(name.isNotEmpty()) { "changzhou 课程名为空" }
        return WeekUtils.parse(weekText + parity).map { week ->
            CoursePreview(
                name = name,
                teacher = teachers[name].orEmpty(),
                room = room,
                day = day,
                startNode = node,
                startWeek = week.startWeek,
                endWeek = week.endWeek,
                type = week.type,
            )
        }
    }
}
