package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `seu_2017` 使用的东南大学 2017 版双表课表解析器。 */
object Seu2017Parser : Parser {

    /**
     * 使用第一张 `.tableline` 建立课程信息索引，再解析最后一张七日课表。
     *
     * 课程格严格按三行一组解释为课程名、时间和教室。学分仍参与源表结构校验，但
     * [CoursePreview] 没有学分字段，因此不会写入教师、教室等不相关字段。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 双表结构、课程映射或时间字段无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val tables = Jsoup.parse(input.text).select(".tableline")
        require(tables.size >= 2) { "东南大学 2017 课表缺少课程信息表或周课表" }
        // Jsoup 的 Elements.first()/last() 在 Kotlin 中带可空类型；已校验数量后直接按下标读取。
        val courseInfo = parseCourseInfo(tables[0])
        val courses = parseSchedule(tables[tables.size - 1], courseInfo)
        if (courses.isEmpty()) throw ParserException.empty("东南大学 2017 课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("东南大学 2017 课表解析失败：${error.message}", error)
    }

    /** 从课程信息表读取课程名、教师和仅用于校验的学分。 */
    private fun parseCourseInfo(table: Element): Map<String, CourseInfo> {
        val result = linkedMapOf<String, CourseInfo>()
        table.select("tr").drop(1).forEachIndexed { rowIndex, row ->
            val cells = row.select("td")
            if (cells.all { cell -> cell.text().isBlank() }) return@forEachIndexed
            require(cells.size >= 4) { "课程信息表第 ${rowIndex + 2} 行字段不足" }
            val name = cells[1].text().trim()
            val teacher = cells[2].text().trim()
            val credit = cells[3].text().trim()
            require(name.isNotEmpty()) { "课程信息表第 ${rowIndex + 2} 行课程名称为空" }
            require(credit.toFloatOrNull() != null) { "课程 $name 的学分格式无效：$credit" }
            result[name] = CourseInfo(teacher = teacher)
        }
        require(result.isNotEmpty()) { "东南大学 2017 课程信息表为空" }
        return result
    }

    /** 按每一课段行中的七个非标签单元格恢复星期。 */
    private fun parseSchedule(
        table: Element,
        courseInfo: Map<String, CourseInfo>,
    ): List<CoursePreview> {
        val courses = mutableListOf<CoursePreview>()
        table.select("tr").drop(1).forEachIndexed { rowIndex, row ->
            var day = 0
            row.select("td").forEach cellLoop@{ cell ->
                val cellText = cell.text().trim()
                if (cellText in LABELS) return@cellLoop
                day += 1
                require(day in 1..7) { "周课表第 ${rowIndex + 2} 行星期列超过 7 列" }
                if (cellText.length <= 2) return@cellLoop
                courses += parseCell(cell, day, courseInfo)
            }
        }
        return courses
    }

    /**
     * 将课程格按固定三行分组，并解析 `[起始周-结束周]起始节-结束节` 时间字段。
     */
    private fun parseCell(
        cell: Element,
        day: Int,
        courseInfo: Map<String, CourseInfo>,
    ): List<CoursePreview> {
        val lines = cell.html().split(BR_PATTERN)
            .map { fragment -> Jsoup.parse(fragment).text().trim() }
        require(lines.size % 3 == 0) { "东南大学 2017 课程格不是完整的三行分组" }

        return lines.chunked(3).map { group ->
            val name = group[0]
            val time = group[1]
            val rawRoom = group[2]
            require(name.isNotEmpty()) { "东南大学 2017 课程名称为空" }
            val info = courseInfo[name]
                ?: throw IllegalArgumentException("课程 $name 在课程信息表中不存在")
            val match = TIME_PATTERN.matchEntire(time)
                ?: throw IllegalArgumentException("课程 $name 的时间字段无效：$time")
            val startWeek = match.groupValues[1].toInt()
            val endWeek = match.groupValues[2].toInt()
            val startNode = match.groupValues[3].toInt()
            val endNode = match.groupValues[4].toInt()
            require(startWeek > 0 && endWeek >= startWeek) { "课程 $name 的周次范围无效" }
            require(startNode > 0 && endNode >= startNode) { "课程 $name 的节次范围无效" }
            require(!(rawRoom.contains("(单)") && rawRoom.contains("(双)"))) {
                "课程 $name 同时包含单周和双周标记"
            }
            val weekType = when {
                rawRoom.contains("(单)") -> 1
                rawRoom.contains("(双)") -> 2
                else -> 0
            }
            val room = rawRoom.replace("(单)", "").replace("(双)", "").trim()

            CoursePreview(
                name = name,
                teacher = info.teacher,
                room = room,
                day = day,
                startNode = startNode,
                step = endNode - startNode + 1,
                startWeek = startWeek,
                endWeek = endWeek,
                type = weekType,
            )
        }
    }

    /** 第一张表中当前输出模型仍可保存的课程属性。 */
    private data class CourseInfo(
        val teacher: String,
    )

    private val LABELS = setOf(
        "",
        "时间",
        "星期一",
        "星期二",
        "星期三",
        "星期四",
        "星期五",
        "星期六",
        "星期日",
        "早晨",
        "上午",
        "下午",
        "晚上",
    )
    private val BR_PATTERN = Regex("""(?i)<br\s*/?>""")
    private val TIME_PATTERN = Regex("""^\[\s*(\d+)\s*-\s*(\d+)\s*周]\s*(\d+)\s*-\s*(\d+)\s*节$""")
}
