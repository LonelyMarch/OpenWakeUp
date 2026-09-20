package com.openwakeup.parser.utils

import com.openwakeup.parser.CoursePreview
import org.jsoup.nodes.Element

/**
 * 解析树维/金智页面中具有明确行节次和课程时间文本的 MTT 网格。
 *
 * 此对象只是无状态的 DOM 解码工具，不读取学校 type、名称或 URL，也不负责选择具体 Parser。
 * 每个学校 Parser 必须先独立校验自己的页面指纹，再把已经确认属于目标网格的课程行交给这里。
 */
internal object MttGridUtils {

    /**
     * 解析标准 MTT 课表行。
     *
     * 每一行必须通过 `td.mtt_bgcolor_grey[data-unit]` 声明兜底节次；课程块必须包含课程名、
     * 教师、教室和 `.mtt_item_sksj` 时间文本。时间文本中的星期和周次始终要求显式存在，
     * 只有节次允许在时间文本缺失时使用当前行的 `data-unit`。
     *
     * @param rows 已去除表头、且已经由入口 Parser 完成页面指纹校验的课程行
     * @param sourceName 面向用户的来源名称，仅用于构造可定位的错误信息
     * @return 从全部课程块解析出的课程预览；允许返回空列表，由入口 Parser 决定空表文案
     * @throws IllegalArgumentException 页面字段缺失、数字越界或周次无法无损表达
     */
    fun parseStandardRows(
        rows: Iterable<Element>,
        sourceName: String,
    ): List<CoursePreview> {
        val courses = mutableListOf<CoursePreview>()

        rows.forEach rowLoop@{ row ->
            val unitCell = row.selectFirst("td.mtt_bgcolor_grey[data-unit]") ?: return@rowLoop
            val fallbackNode = unitCell.attr("data-unit").toIntOrNull()
            require(fallbackNode != null && fallbackNode > 0) {
                "$sourceName 课表行的 data-unit 节次无效"
            }

            row.select("td[data-role=item] .mtt_arrange_item").forEach { item ->
                courses += parseItem(
                    item = item,
                    fallbackNode = fallbackNode,
                    sourceName = sourceName,
                )
            }
        }

        return courses
    }

    /**
     * 解析一个 MTT 课程块，并按可无损表达的周次片段拆分结果。
     *
     * @param item `.mtt_arrange_item` 课程块
     * @param fallbackNode 当前表格行声明的节次
     * @param sourceName 错误消息中的来源名称
     * @return 同一课程在各周次片段上的课程预览
     */
    private fun parseItem(
        item: Element,
        fallbackNode: Int,
        sourceName: String,
    ): List<CoursePreview> {
        val rawName = item.selectFirst(".mtt_item_kcmc")?.text()?.trim().orEmpty()
        val name = normalizeCourseName(rawName)
        require(name.isNotEmpty()) { "$sourceName 课程块缺少课程名称" }

        val teacher = item.selectFirst(".mtt_item_jxbmc")?.text()?.trim()
            ?: throw IllegalArgumentException("课程 $name 缺少教师字段")
        val room = item.selectFirst(".mtt_item_room")?.text()?.trim()
            ?: throw IllegalArgumentException("课程 $name 缺少教室字段")
        val timeText = item.selectFirst(".mtt_item_sksj")?.text()?.trim().orEmpty()
        require(timeText.isNotEmpty()) { "课程 $name 缺少上课时间字段" }

        val dayText = DAY_PATTERN.find(timeText)?.value
            ?: throw IllegalArgumentException("课程 $name 缺少明确星期：$timeText")
        val day = TextUtils.requireDay(dayText)
        val nodes = parseNodes(timeText, fallbackNode, name)
        val weekText = WEEK_FRAGMENT_PATTERN.findAll(timeText)
            .map { match -> match.value }
            .toList()
            .joinToString(",")
        require(weekText.isNotEmpty()) { "课程 $name 缺少明确周次：$timeText" }

        // WeekUtils 会先展开为精确周集合，再无损压缩；离散周不会被错误扩大成连续周。
        return WeekUtils.parse(weekText).map { week ->
            CoursePreview(
                name = name,
                teacher = teacher,
                room = room,
                day = day,
                startNode = nodes.first,
                step = nodes.last - nodes.first + 1,
                startWeek = week.startWeek,
                endWeek = week.endWeek,
                type = week.type,
            )
        }
    }

    /**
     * 读取时间文本中的明确节次范围；未显示范围时才使用表格行节次。
     *
     * @param timeText `.mtt_item_sksj` 的完整文本
     * @param fallbackNode 当前行 `data-unit` 声明的正整数节次
     * @param courseName 仅用于错误定位的课程名称
     * @return 起止节次闭区间
     */
    private fun parseNodes(
        timeText: String,
        fallbackNode: Int,
        courseName: String,
    ): IntRange {
        val matchedRange = NODE_RANGE_PATTERN.find(timeText)?.value
            ?: return fallbackNode..fallbackNode
        return try {
            TextUtils.requirePositiveRange(matchedRange, "课程 $courseName 的节次")
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("课程 $courseName 的节次无效：$timeText", error)
        }
    }

    /**
     * 去除原版 MTT 页面附加在课程名两侧的课程代码和教学班后缀。
     *
     * @param rawName 页面原始课程名
     * @return 可直接写入课程预览的规范名称
     */
    private fun normalizeCourseName(rawName: String): String = rawName
        .replace(COURSE_CODE_PREFIX_PATTERN, "")
        .replace(CLASS_SUFFIX_PATTERN, "")
        .trim()

    private val NODE_RANGE_PATTERN = Regex("""\d+\s*[-~～至—–]\s*\d+\s*节""")
    private val DAY_PATTERN = Regex("""(?:星期|周)[一二三四五六日天1-7]""")
    private val WEEK_FRAGMENT_PATTERN = Regex(
        """\d+(?:\s*[-~～至—–]\s*\d+)?\s*周(?:\s*[（(]?[单双]\s*周?[）)]?)?""",
    )
    private val COURSE_CODE_PREFIX_PATTERN = Regex("""^\S+\s+""")
    private val CLASS_SUFFIX_PATTERN = Regex("""\d+班$""")
}
