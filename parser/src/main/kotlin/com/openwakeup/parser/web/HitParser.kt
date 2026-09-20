package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `hit` 使用的 `xfyq_con/xszp` 压缩课程文本解析器。 */
object HitParser : Parser {

    /**
     * 解析哈尔滨工业大学旧版课表。
     *
     * 页面可能由多个 HTML 片段拼接；按原版顺序寻找 `.xfyq_con`，找不到时再使用 `#xszp`。
     * 每行第一列提供默认节次，其余八列中的第 2～8 列分别对应周一到周日。
     *
     * @param input 完整页面或由多个页面片段拼接的 HTML
     * @return 首个包含有效课程的页面片段所产生的课程预览
     * @throws ParserException 页面指纹、节次、周次或课程块边界不合法
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val candidates = input.text.split(HEAD_SEPARATOR_PATTERN)
            .filter { fragment -> fragment.isNotBlank() }
            .ifEmpty { listOf(input.text) }
        candidates.forEach { source ->
            val document = Jsoup.parse(source)
            val container = document.getElementsByClass("xfyq_con").firstOrNull()
                ?: document.getElementById("xszp")
            if (container != null) {
                val courses = parseContainer(container)
                if (courses.isNotEmpty()) return courses
            }
        }
        throw ParserException.empty("hit 页面中没有课程")
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("hit 课表解析失败：${error.message}", error)
    }

    /** 解析目标容器内的九列课程行；八列页面会在逻辑上补齐缺失的首列。 */
    private fun parseContainer(container: Element): List<CoursePreview> {
        val courses = mutableListOf<CoursePreview>()
        container.select("tr").forEach rowLoop@{ row ->
            val sourceCells = row.select(":scope > td")
            val cells =
                if (sourceCells.size == 8) listOf<Element?>(null) + sourceCells else sourceCells
            if (cells.size != 9) return@rowLoop
            val nodeText = cells[1]?.html().orEmpty()
                .substringAfter('第', "").substringBefore('节', "").trim()
            val defaultNodes = TextUtils.requirePositiveRange(nodeText, "hit 行节次")
            (2..8).forEach cellLoop@{ cellIndex ->
                val cell = cells[cellIndex] ?: return@cellLoop
                if (cell.text().isNotBlank()) {
                    courses += parseCell(cell, cellIndex - 1, defaultNodes)
                }
            }
        }
        return courses
    }

    /**
     * 依据方括号周次字段定位一个单元格内的多个课程块。
     *
     * 原版先按 `<br>`/`◇` 切分后倒序读取；下一块课程名位于下一条周次字段之前，因此当前
     * 块的结束位置必须排除该名称。连续课程省略名称时沿用本单元格上一块的课程名。
     */
    private fun parseCell(cell: Element, day: Int, defaultNodes: IntRange): List<CoursePreview> {
        val fields = cell.html().split(CELL_SEPARATOR_PATTERN)
            .map { fragment -> Jsoup.parse(fragment).text().trim() }
            .filter { field -> field.isNotEmpty() }
            .asReversed()
            .toMutableList()
        mergeCombinedWeekLines(fields)
        val boundaries = fields.indices.filter { index -> isWeekBoundary(fields[index]) }
        require(boundaries.isNotEmpty()) { "hit 星期 $day 的课程格缺少方括号周次" }

        val courses = mutableListOf<CoursePreview>()
        var previousName = ""
        boundaries.forEachIndexed { blockIndex, boundary ->
            require(boundary > 0) { "hit 星期 $day 的课程块缺少课程名" }
            val nextBoundary = boundaries.getOrNull(blockIndex + 1) ?: fields.size
            val metadataEnd =
                if (blockIndex + 1 < boundaries.size) nextBoundary - 1 else nextBoundary
            require(metadataEnd > boundary) { "hit 星期 $day 的课程块字段不足" }
            val candidateName = fields[boundary - 1].trim()
            val name = if (isWeekBoundary(candidateName)) previousName else candidateName
            require(name.isNotEmpty()) { "hit 星期 $day 的课程名为空" }
            previousName = name
            val metadata = fields.subList(boundary, metadataEnd).joinToString("")
            courses += parseCourseBlock(name, metadata, day, defaultNodes)
        }
        return courses
    }

    /**
     * 还原原版对“上一课程尾部，下一课程周次”同处一行的修正。
     *
     * 当第二个周次边界在左方括号之后仍含中文逗号时，逗号前属于上一课程，逗号后才是当前
     * 课程。必须先拆开再计算课程块边界，否则两门课程的教师、教室和周次会被拼到一起。
     */
    private fun mergeCombinedWeekLines(fields: MutableList<String>) {
        var previousBoundary: Int? = null
        fields.indices.forEach { index ->
            val value = fields[index]
            if (!isWeekBoundary(value)) return@forEach
            val previous = previousBoundary
            if (previous != null && value.substringAfter('[', "").contains('，')) {
                fields[previous] += value.substringBefore('，')
                fields[index] = value.substringAfter('，').trim()
            }
            previousBoundary = index
        }
    }

    /** 从一个压缩课程块中读取教师、教室、节次和一个或多个周次片段。 */
    private fun parseCourseBlock(
        name: String,
        metadata: String,
        day: Int,
        defaultNodes: IntRange,
    ): List<CoursePreview> {
        val weekMatches = WEEK_PATTERN.findAll(metadata).toList()
        require(weekMatches.isNotEmpty()) { "课程 $name 缺少可解析周次" }
        val rooms = ROOM_PATTERN.findAll(metadata).map { result ->
            result.value.substringAfter('周').substringBefore('第').trim().trim('[', ']')
        }.toList()
        val teachers = TEACHER_PATTERN.findAll(metadata).map { result ->
            result.value.substringBefore('[').substringAfterLast('，').trim()
        }.toList()
        val nodes = NODE_PATTERN.find(metadata)?.value?.let { value ->
            val nodeText = value.substringAfter('第').substringBefore('节').trim()
            TextUtils.requirePositiveRange(nodeText, "课程 $name 的节次")
        } ?: defaultNodes

        return weekMatches.flatMapIndexed { index, match ->
            val weekText = (match.groupValues[1].ifBlank { match.groupValues[2] })
                .removeSuffix("周").trim()
            WeekUtils.parse(weekText).map { week ->
                CoursePreview(
                    name = name,
                    teacher = teachers.getOrElse(index) { teachers.lastOrNull().orEmpty() },
                    room = rooms.getOrElse(index) { rooms.lastOrNull().orEmpty() },
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

    /** 判断字段是否同时包含完整方括号和“周”标记。 */
    private fun isWeekBoundary(source: String): Boolean =
        source.contains('[') && source.contains(']') && source.contains('周')

    private val HEAD_SEPARATOR_PATTERN = Regex("(?i)</?head>")
    private val CELL_SEPARATOR_PATTERN = Regex("(?i)<br\\s*/?>|◇")
    private val WEEK_PATTERN = Regex("""\[([^\]]+)](?:[^\[]*?)周|\[([^\]]*?周)]""")
    private val ROOM_PATTERN = Regex("""周([^，\n]*?)第|周([^，\n]*?)$|周][^，\n]*?\[""")
    private val TEACHER_PATTERN = Regex("""^(.*?)\[\d+|，([^\]]+?[^，])\[\d+""")
    private val NODE_PATTERN = Regex("""第\s*\d+\s*[,，\-~～至—–]\s*\d+\s*节""")
}
