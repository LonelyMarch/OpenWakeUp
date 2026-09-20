package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** 新版青果教学安排/班级课表解析器，规范 type 为 `kingo_new`。 */
object KingoNewParser : Parser {

    /**
     * 按原版固定顺序尝试三种青果新版页面布局。
     *
     * 三个分支都属于 `kingo_new` 的历史页面兼容，不读取 [ParserInput.type] 做二次路由。
     * 某个页面指纹未命中时返回空列表；指纹命中但字段损坏时记录原因，全部分支失败后统一关闭。
     *
     * @param input 已由调用方取得的教学安排或班级课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面不属于任何已知布局、课程为空或字段解析失败
     */
    override fun parse(input: ParserInput): List<CoursePreview> {
        val errors = mutableListOf<String>()
        val attempts = listOf<(String) -> List<CoursePreview>>(
            ::parseMyTableGrid,
            ::parseFormatTwoTables,
            ::parseKbDivGrid,
        )

        attempts.forEach { attempt ->
            try {
                val courses = attempt(input.text)
                if (courses.isNotEmpty()) return courses
            } catch (error: Exception) {
                errors += error.message ?: error::class.simpleName.orEmpty()
            }
        }

        if (errors.isNotEmpty()) {
            throw ParserException.parse(
                "新版青果课表解析失败：${
                    errors.distinct().joinToString("；")
                }"
            )
        }
        throw ParserException.parse("页面中缺少新版青果课表指纹")
    }

    /**
     * 解析 `#mytable` 星期列网格。
     *
     * 原版会处理抓取脚本拼接的多个 HTML 文档，因此这里先按 head 边界拆成独立片段。每个课程块
     * 支持普通四行文本和 `xkinfo + &nbsp;` 三行文本两种字段排列。
     *
     * @param source 当前页及同源 frame 拼接后的完整 HTML
     * @return 此布局中解析出的课程；未命中 `#mytable` 时返回空列表
     */
    private fun parseMyTableGrid(source: String): List<CoursePreview> {
        val courses = mutableListOf<CoursePreview>()
        htmlFragments(source).forEach fragmentLoop@{ document ->
            val table = document.getElementById("mytable") ?: return@fragmentLoop
            table.select("tr").filterNot { row -> row.hasClass("H") }.forEach { row ->
                row.select("td").forEachIndexed cellLoop@{ columnIndex, cell ->
                    val day = columnIndex + 1
                    if (day !in 1..7) return@cellLoop
                    parseMyTableCell(cell, day).forEach { course -> courses += course }
                }
            }
        }
        return courses
    }

    /**
     * 解析 `#mytable` 中一个星期单元格内的全部课程块。
     *
     * @param cell 当前星期的表格单元格
     * @param day 由列位置换算出的星期，范围为 1～7
     * @return 单元格内全部课程及其周次片段
     */
    private fun parseMyTableCell(cell: Element, day: Int): List<CoursePreview> {
        val courses = mutableListOf<CoursePreview>()
        var xkInfoLayout = false

        cell.select("div").forEach blockLoop@{ block ->
            if (block.hasClass("div1")) {
                val rawHtml = block.html()
                xkInfoLayout = rawHtml.contains("class=\"xkinfo\"") && rawHtml.contains("&nbsp;")
                return@blockLoop
            }
            if (block.text().isBlank()) return@blockLoop

            val normalizedHtml = if (xkInfoLayout) {
                block.html().replace(Regex("(?:&nbsp;)+", RegexOption.IGNORE_CASE), "<br>")
            } else {
                block.html()
            }
            val lines = normalizedHtml.split(BR_PATTERN)
                .map { part -> Jsoup.parse(part).text().trim() }
                .filter { line -> line.isNotEmpty() }
            val fields = if (xkInfoLayout) {
                require(lines.size >= 3) { "新版青果 xkinfo 课程块字段不足" }
                val detail = lines[1]
                MyTableFields(
                    name = lines[0],
                    teacher = detail.substringBefore(' ').trim(),
                    room = lines[2],
                    weekText = detail.substringAfter(' ', "").substringBefore('[')
                        .substringBefore('周').trim(),
                    nodeText = detail.substringAfter('[', "").substringBefore(']').trim(),
                )
            } else {
                require(lines.size >= 4) { "新版青果课程块字段不足" }
                val detail = lines[2]
                MyTableFields(
                    name = lines[0],
                    teacher = lines[1],
                    room = lines[3],
                    weekText = detail.substringBefore('[').substringBefore('周').trim(),
                    nodeText = detail.substringAfter('[', "").substringBefore(']').trim(),
                )
            }

            require(fields.name.isNotEmpty()) { "新版青果课程名为空" }
            require(fields.weekText.isNotEmpty()) { "课程 ${fields.name} 缺少周次" }
            require(fields.nodeText.isNotEmpty()) { "课程 ${fields.name} 缺少节次" }
            val nodes =
                TextUtils.requirePositiveRange(fields.nodeText, "课程 ${fields.name} 的节次")
            WeekUtils.parse(fields.weekText).forEach { week ->
                courses += CoursePreview(
                    name = fields.name,
                    teacher = fields.teacher,
                    room = fields.room,
                    day = day,
                    startNode = nodes.first,
                    step = nodes.last - nodes.first + 1,
                    startWeek = week.startWeek,
                    endWeek = week.endWeek,
                    type = week.type,
                )
            }
        }

        return courses
    }

    /**
     * 解析带有“课程”和“节次”表头的青果班级课表格式二。
     *
     * @param source 当前页及同源 frame 拼接后的完整 HTML
     * @return 格式二表格中的全部课程；没有匹配表头时返回空列表
     */
    private fun parseFormatTwoTables(source: String): List<CoursePreview> {
        val courses = mutableListOf<CoursePreview>()
        htmlFragments(source).forEach { document ->
            document.select("table").forEach tableLoop@{ table ->
                val headerCells =
                    table.selectFirst("thead tr")?.select("td, th") ?: return@tableLoop
                val headers = headerCells.map { cell -> cell.text().trim() }
                val courseIndex = headers.indexOfFirst { header -> header == "课程" }
                val nodeIndex = headers.indexOfFirst { header -> header.contains("节次") }
                if (courseIndex < 0 || nodeIndex < 0) return@tableLoop

                val teacherIndex = headers.indexOfFirst { header -> header.contains("教师") }
                val roomIndex = headers.indexOfFirst { header -> header.contains("地点") }
                val weekIndex = headers.indexOfFirst { header -> header.contains("周次") }
                val weekTypeIndex = headers.indexOfFirst { header -> header.contains("单双周") }
                table.select("tbody tr").forEach rowLoop@{ row ->
                    val cells = row.children()
                    val requiredMaxIndex = listOf(courseIndex, nodeIndex).max()
                    require(cells.size > requiredMaxIndex) { "新版青果格式二课程行字段不足" }

                    val name = cells[courseIndex].text().substringAfter(']').trim()
                    if (name.isEmpty()) return@rowLoop
                    val nodeText = cells[nodeIndex].text().trim()
                    val dayText = nodeText.substringBefore('[').trim()
                    val bracketText = nodeText.substringAfter('[', "").substringBefore(']').trim()
                    require(dayText.isNotEmpty() && bracketText.isNotEmpty()) {
                        "课程 $name 的星期或节次字段无效：$nodeText"
                    }
                    val day = TextUtils.requireDay(dayText)
                    val nodes = TextUtils.requirePositiveRange(
                        bracketText.substringBefore('节'),
                        "课程 $name 的节次"
                    )
                    val weekText = if (weekIndex >= 0) {
                        require(cells.size > weekIndex) { "课程 $name 缺少周次列" }
                        cells[weekIndex].text().trim()
                    } else {
                        nodeText.substringAfter('(', "").substringBefore('周').trim()
                    }
                    require(weekText.isNotEmpty()) { "课程 $name 缺少周次" }
                    val weekTypeText = if (weekTypeIndex >= 0 && cells.size > weekTypeIndex) {
                        cells[weekTypeIndex].text().trim()
                    } else {
                        ""
                    }

                    parseTypedWeeks(weekText, weekTypeText).forEach { week ->
                        courses += CoursePreview(
                            name = name,
                            teacher = cellTextAfterBracket(cells, teacherIndex),
                            room = cellText(cells, roomIndex),
                            day = day,
                            startNode = nodes.first,
                            step = nodes.last - nodes.first + 1,
                            startWeek = week.startWeek,
                            endWeek = week.endWeek,
                            type = week.type,
                        )
                    }
                }
            }
        }
        return courses
    }

    /**
     * 解析 `#kbDiv` 中按行表示节次、末七列表示星期的简化网格。
     *
     * @param source 当前页及同源 frame 拼接后的完整 HTML
     * @return 简化网格中的全部课程；未命中 `#kbDiv` 时返回空列表
     */
    private fun parseKbDivGrid(source: String): List<CoursePreview> {
        val courses = mutableListOf<CoursePreview>()
        htmlFragments(source).forEach fragmentLoop@{ document ->
            val body = document.getElementById("kbDiv")?.select("tbody")?.lastOrNull()
                ?: return@fragmentLoop
            body.select("tr").forEachIndexed { rowIndex, row ->
                val allCells = row.select("td")
                val dayCells = if (allCells.size > 7) allCells.takeLast(7) else allCells
                dayCells.forEachIndexed cellLoop@{ dayIndex, cell ->
                    val text = cell.text().trim()
                    if (text.isEmpty()) return@cellLoop

                    val name = text.substringBefore('(').trim()
                    val detail = text.substringAfter('(', "").substringBeforeLast(')').trim()
                    val parts = detail.split(Regex("""\s+""")).filter { part -> part.isNotEmpty() }
                    require(name.isNotEmpty() && parts.isNotEmpty()) { "新版青果简化网格课程字段无效：$text" }
                    val room = if (parts.size > 1) parts.last() else ""
                    WeekUtils.parse(parts.first()).forEach { week ->
                        courses += CoursePreview(
                            name = name,
                            room = room,
                            day = dayIndex + 1,
                            startNode = rowIndex + 1,
                            startWeek = week.startWeek,
                            endWeek = week.endWeek,
                            type = week.type,
                        )
                    }
                }
            }
        }
        return courses
    }

    /**
     * 将抓取脚本可能拼接的多个 HTML 文档转换为可独立查询的 DOM。
     *
     * @param source 完整抓取文本
     * @return 至少包含一个文档的列表
     */
    private fun htmlFragments(source: String): List<Document> {
        val fragments =
            source.split(HEAD_BOUNDARY_PATTERN).filter { fragment -> fragment.isNotBlank() }
        return (fragments.ifEmpty { listOf(source) }).map(Jsoup::parse)
    }

    /**
     * 将独立的单双周列合并到每个周次片段，避免只给最后一段添加单双周语义。
     *
     * @param weekText 可由逗号分隔的周次范围
     * @param weekTypeText 独立的单双周列文本
     * @return 可由课程模型无损表达的周次片段
     */
    private fun parseTypedWeeks(weekText: String, weekTypeText: String) = WeekUtils.parse(
        weekText.replace('，', ',').split(',').joinToString(",") { part ->
            val trimmed = part.trim()
            when {
                trimmed.contains('单') || trimmed.contains('双') -> trimmed
                weekTypeText.contains('单') -> "$trimmed(单周)"
                weekTypeText.contains('双') -> "$trimmed(双周)"
                else -> trimmed
            }
        },
    )

    /** 返回可选列文本；列不存在时返回空字符串。 */
    private fun cellText(cells: List<Element>, index: Int): String =
        if (index >= 0 && cells.size > index) cells[index].text().trim() else ""

    /** 返回可选列中右方括号后的正文；列不存在时返回空字符串。 */
    private fun cellTextAfterBracket(cells: List<Element>, index: Int): String =
        cellText(cells, index).substringAfter(']').trim()

    /** `#mytable` 课程块中已经完成布局归一化的字段。 */
    private data class MyTableFields(
        val name: String,
        val teacher: String,
        val room: String,
        val weekText: String,
        val nodeText: String,
    )

    private val HEAD_BOUNDARY_PATTERN = Regex("""(?i)</?head(?:\s[^>]*)?>""")
    private val BR_PATTERN = Regex("""(?i)<br\s*/?>""")
}
