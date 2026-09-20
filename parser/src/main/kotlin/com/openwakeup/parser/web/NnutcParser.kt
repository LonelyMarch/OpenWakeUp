package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `nnutc` 使用的南京师范大学泰州学院图标课表解析器。 */
object NnutcParser : Parser {

    /**
     * 解析课表主体中的课程块。
     *
     * 原页面第一列为节次说明，后续列号直接对应星期一至星期日。每个课程块使用
     * `svg[data-icon]` 标识相邻 `label` 的含义：`calculator` 是课程名，`user` 是教师，
     * `environment` 是“教室[周次]”；最后一个标签提供“第 N,M 节”。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 课表主体、图标字段、周次或节次不符合原页面契约
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val body = Jsoup.parse(input.text).selectFirst("tbody")
            ?: throw ParserException.parse("南师泰州学院页面缺少课表主体")
        val courses = body.select("tr").flatMapIndexed { rowIndex, row ->
            parseRow(row, rowIndex + 1)
        }
        if (courses.isEmpty()) throw ParserException.empty("南师泰州学院课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("南师泰州学院课表解析失败：${error.message}", error)
    }

    /**
     * 按单元格列号确定星期，并展开当前行中的所有课程块。
     *
     * @param row 当前表格行
     * @param rowNumber 用于错误信息的 1 起行号
     * @return 当前行解析出的课程预览
     */
    private fun parseRow(row: Element, rowNumber: Int): List<CoursePreview> =
        row.select("td").flatMapIndexed { columnIndex, cell ->
            // 原版明确跳过首列；空单元格不代表页面错误，只表示该时段没有课程。
            if (columnIndex == 0 || cell.text().isBlank()) {
                emptyList()
            } else {
                require(columnIndex in 1..7) {
                    "南师泰州学院课表第 $rowNumber 行出现星期范围外的非空列：$columnIndex"
                }
                val blocks = cell.select("div").filter { block -> block.html().isNotBlank() }
                require(blocks.isNotEmpty()) {
                    "南师泰州学院课表第 $rowNumber 行第 $columnIndex 列缺少课程块"
                }
                blocks.flatMapIndexed { blockIndex, block ->
                    parseBlock(block, columnIndex, rowNumber, blockIndex + 1)
                }
            }
        }

    /**
     * 按图标与同序标签的对应关系解析一个课程块。
     *
     * `environment` 图标出现时，当前已经读取到的课程名和教师会与该地点/周次字段组合成课程；
     * 同一块内再次出现 `calculator` 时开始下一门课程，并按原版行为清空上一门课程的教师。
     *
     * @param block 当前课程块元素
     * @param day 星期，取自当前单元格列号
     * @param rowNumber 当前表格行号
     * @param blockNumber 当前单元格内课程块序号
     * @return 当前课程块展开出的课程预览
     */
    private fun parseBlock(
        block: Element,
        day: Int,
        rowNumber: Int,
        blockNumber: Int,
    ): List<CoursePreview> {
        val labels = block.select("label").map { label -> label.text().trim() }
        val icons = block.select("svg").map { svg -> svg.attr("data-icon").trim() }
        val context = "南师泰州学院课表第 $rowNumber 行星期 $day 的第 $blockNumber 个课程块"
        require(labels.isNotEmpty()) { "$context 缺少标签" }
        require(labels.size == icons.size) {
            "$context 的图标与标签数量不一致：${icons.size}/${labels.size}"
        }
        val nodes = parseNodes(labels.last(), context)
        var courseName = ""
        var teacher = ""
        val courses = mutableListOf<CoursePreview>()

        labels.indices.forEach { index ->
            when (icons[index]) {
                "calculator" -> {
                    courseName = labels[index]
                    require(courseName.isNotEmpty()) { "$context 的课程名称为空" }
                    // 原版遇到下一门课程时会清空教师，防止沿用上一门课程的信息。
                    teacher = ""
                }

                "user" -> teacher = labels[index]
                "environment" -> {
                    require(courseName.isNotEmpty()) { "$context 在课程名称之前出现地点字段" }
                    val location = parseLocationAndWeeks(labels[index], courseName)
                    courses += location.weeks.map { weeks ->
                        CoursePreview(
                            name = courseName,
                            teacher = teacher,
                            room = location.room,
                            day = day,
                            startNode = nodes.first,
                            step = nodes.last - nodes.first + 1,
                            startWeek = weeks.first,
                            endWeek = weeks.last,
                            type = location.type,
                        )
                    }
                }
            }
        }
        require(courses.isNotEmpty()) { "$context 缺少可用的课程地点与周次" }
        return courses
    }

    /**
     * 从课程块末尾标签中读取起止节次。
     *
     * @param text 原页面的“第 N,M 节”文本
     * @param context 当前课程块的错误上下文
     * @return 起止节次闭区间
     */
    private fun parseNodes(text: String, context: String): IntRange {
        val match = NODE_PATTERN.find(text)
            ?: throw IllegalArgumentException("$context 的节次字段无效：$text")
        val values = match.groupValues[1].split(',').map { value ->
            value.trim().toIntOrNull()
                ?: throw IllegalArgumentException("$context 的节次不是整数：$text")
        }
        require(values.isNotEmpty() && values.all { value -> value > 0 }) {
            "$context 的节次必须是正整数：$text"
        }
        val startNode = values.first()
        val endNode = values.last()
        require(endNode >= startNode) { "$context 的结束节次小于起始节次：$text" }
        return startNode..endNode
    }

    /**
     * 拆分原页面的“教室[周次]”地点标签。
     *
     * 周次允许逗号分隔多个单值或范围；`单`、`双`分别映射到统一模型的单双周类型。
     * 为避免把矛盾页面静默导入，同一地点字段同时出现单双标记时直接失败。
     *
     * @param text 地点与周次复合字段
     * @param courseName 用于错误信息的课程名称
     * @return 教室、周类型与周次范围
     */
    private fun parseLocationAndWeeks(text: String, courseName: String): LocationWeeks {
        require(text.contains('[') && text.contains(']')) {
            "课程 $courseName 的地点字段缺少方括号周次：$text"
        }
        val room = text.substringBefore('[').trim()
        val weekText = text.substringAfter('[').substringBefore(']').trim()
        require(weekText.isNotEmpty()) { "课程 $courseName 的周次为空" }
        val hasOdd = weekText.contains('单')
        val hasEven = weekText.contains('双')
        require(!(hasOdd && hasEven)) { "课程 $courseName 的周次同时包含单双标记：$weekText" }
        val type = when {
            hasOdd -> 1
            hasEven -> 2
            else -> 0
        }
        val weekParts = weekText.replace("单", "").replace("双", "")
            .split(',')
            .map { part -> part.trim() }
            .filter { part -> part.isNotEmpty() }
        require(weekParts.isNotEmpty()) { "课程 $courseName 缺少明确周次" }
        val weeks = weekParts.map { part ->
            TextUtils.requirePositiveRange(part, "课程 $courseName 的周次")
        }
        return LocationWeeks(room = room, type = type, weeks = weeks)
    }

    /** 地点复合字段拆分后的不可变结果。 */
    private data class LocationWeeks(
        val room: String,
        val type: Int,
        val weeks: List<IntRange>,
    )

    private val NODE_PATTERN = Regex("""第\s*([0-9\s,]+)\s*节""")
}
