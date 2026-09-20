package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import org.jsoup.Jsoup

/** `yzzy` 使用的永州职业技术学院单节网格解析器。 */
object YzzyParser : Parser {

    /**
     * 解析 `#timetable` 的前 13 个节次行及其中的 `.kbcontent` 课程块。
     *
     * 每个课程块以固定 `font` 属性声明名称、周次、教室和教师。相邻行中名称与周次相同的
     * 课程会沿用原版规则合并为连续节次，并保留不同教室或教师的去重组合。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹、星期列或周次字段无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("timetable")
            ?: throw ParserException.parse("永州职院页面中缺少 timetable 课表")
        val rows = table.select("tr")
        if (rows.size <= 1) throw ParserException.parse("永州职院课表中缺少课程行")
        val courses = mutableListOf<CoursePreview>()

        rows.drop(1).take(MAX_NODES_PER_DAY).forEachIndexed { rowIndex, row ->
            val startNode = rowIndex + 1
            row.select("td").forEachIndexed { columnIndex, cell ->
                val day = columnIndex + 1
                require(day in 1..7) { "永州职院课表的星期列超过 7 列" }
                cell.select(".kbcontent").forEach { block ->
                    val name =
                        block.selectFirst("font[onmouseover=kbtc(this)]")?.text()?.trim().orEmpty()
                    require(name.isNotEmpty()) { "永州职院课程名称为空" }
                    val weekText = block.selectFirst("font[title=\"周次(节次)\"]")
                        ?.text()?.trim().orEmpty()
                    val weeks = WEEK_PATTERN.find(weekText)
                        ?: throw IllegalArgumentException("课程 $name 的周次格式无效：$weekText")
                    val startWeek = weeks.groupValues[1].toInt()
                    val endWeek = weeks.groupValues[2].toInt()
                    require(startWeek > 0 && endWeek >= startWeek) { "课程 $name 的周次范围无效" }

                    courses += CoursePreview(
                        name = name,
                        teacher = block.selectFirst("font[title=\"教师\"]")?.text()?.trim()
                            .orEmpty(),
                        room = block.selectFirst("font[title=\"教室\"]")?.text()?.trim().orEmpty(),
                        day = day,
                        startNode = startNode,
                        startWeek = startWeek,
                        endWeek = endWeek,
                    )
                }
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("永州职院课表中没有课程")
        mergeAdjacentCourses(courses)
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("永州职院课表解析失败：${error.message}", error)
    }

    /**
     * 合并同一天、同课程、同周次的相邻单节记录。
     *
     * @param courses 尚未合并的单节课程
     * @return 按星期和起始节排序后的连续课程
     */
    private fun mergeAdjacentCourses(courses: List<CoursePreview>): List<CoursePreview> {
        val merged = mutableListOf<CoursePreview>()
        courses.sortedWith(compareBy(CoursePreview::day, CoursePreview::startNode))
            .forEach { current ->
                val previous = merged.lastOrNull()
                val canMerge = previous != null &&
                        previous.day == current.day &&
                        previous.name == current.name &&
                        previous.startNode + previous.step == current.startNode &&
                        previous.startWeek == current.startWeek &&
                        previous.endWeek == current.endWeek &&
                        previous.type == current.type
                if (canMerge) {
                    // canMerge 已经检查 previous；显式绑定可让后续 copy 保持稳定的非空类型。
                    val courseToMerge = checkNotNull(previous)
                    merged[merged.lastIndex] = courseToMerge.copy(
                        teacher = mergeText(courseToMerge.teacher, current.teacher),
                        room = mergeText(courseToMerge.room, current.room),
                        step = courseToMerge.step + current.step,
                    )
                } else {
                    merged += current
                }
            }
        return merged
    }

    /** 合并两个可能相同或为空的字段，避免原版生成多余的 `, `。 */
    private fun mergeText(first: String, second: String): String = listOf(first, second)
        .filter { value -> value.isNotBlank() }
        .distinct()
        .joinToString(", ")

    private const val MAX_NODES_PER_DAY = 13
    private val WEEK_PATTERN = Regex("(\\d+)\\s*-\\s*(\\d+)\\s*\\(周\\)")
}
