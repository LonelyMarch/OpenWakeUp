package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `qz_ustb` 使用的北京科技大学专用强智解析器。 */
object QzUstbParser : Parser {

    /**
     * 解析 `kbtable/timetable` 中的北科大 `kbcontent` 课程格。
     *
     * 该页面不使用标准强智周次属性：`title=教室` 的 HTML 由不换行空格分隔为教室和周次，
     * `title=节次` 仅作为一门课程结束标记，实际节次仍由网格行组确定。因此本 Parser 独立
     * 解码课程行，不能由 `QzParser` 或其他学校变体代替。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹、课程名称、教室周次复合字段或网格坐标无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val fragments = input.text.split(HEAD_BOUNDARY_PATTERN)
            .filter { fragment -> fragment.isNotBlank() }
            .ifEmpty { listOf(input.text) }
        val courses = fragments.asSequence().mapNotNull { fragment ->
            val document = Jsoup.parse(fragment)
            val table = document.getElementById("kbtable")
                ?: document.getElementById("timetable")
                ?: return@mapNotNull null
            parseTable(table).takeIf { parsed -> parsed.isNotEmpty() }
        }.firstOrNull().orEmpty()

        if (courses.isEmpty()) throw ParserException.empty("北京科技大学强智课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("北京科技大学强智课表解析失败：${error.message}", error)
    }

    /** 按表头确定星期顺序，并按非空课程行累计双节组。 */
    private fun parseTable(table: Element): List<CoursePreview> {
        val headers = table.select("th").map { header -> header.text() }
        val sundayIndex = headers.indexOfFirst { header -> header.contains("星期日") }
        val mondayIndex = headers.indexOfFirst { header -> header.contains("星期一") }
        val sundayFirst = sundayIndex >= 0 && mondayIndex >= 0 && sundayIndex < mondayIndex
        val courses = mutableListOf<CoursePreview>()
        var nodeGroup = 0

        table.select("tr").forEach { row ->
            val cells = row.select("td")
            if (cells.isEmpty()) return@forEach
            nodeGroup += 1
            cells.take(7).forEachIndexed { columnIndex, cell ->
                val day = if (sundayFirst) {
                    if (columnIndex == 0) 7 else columnIndex
                } else {
                    columnIndex + 1
                }
                require(day in 1..7) { "北京科技大学强智课表星期列无效：$day" }
                cell.select(".kbcontent").forEach { content ->
                    if (content.text().isNotBlank()) {
                        courses += parseContent(content, day, nodeGroup)
                    }
                }
            }
        }
        return courses
    }

    /**
     * 按 `<br>` 行依次读取课程名、教师、教室/周次和课程结束标记。
     *
     * 同一课程格可以连续保存多门课程；每遇到一个 `title=节次` 元素即完成当前课程并重置状态。
     */
    private fun parseContent(
        content: Element,
        day: Int,
        nodeGroup: Int,
    ): List<CoursePreview> {
        val courses = mutableListOf<CoursePreview>()
        val nameParts = mutableListOf<String>()
        var teacher = ""
        var room = ""
        var weekText = ""

        content.html().split(BR_PATTERN).forEach { htmlLine ->
            val lineDocument = Jsoup.parseBodyFragment(htmlLine)
            val lineBody = lineDocument.body()
            val teacherElements = lineBody.select("[title=老师]")
            if (teacherElements.isNotEmpty()) teacher = teacherElements.text().trim()

            lineBody.select("[title=上课地点]").forEach { locationElement ->
                val location = locationElement.text().trim()
                require(location.contains('[') && location.contains(']')) {
                    "北京科技大学上课地点缺少方括号：$location"
                }
                room = location.substringAfter('[').substringBefore(']').trim()
            }
            lineBody.select("[title=教室]").forEach { roomElement ->
                val parts = roomElement.html().split(NON_BREAKING_SPACE_PATTERN)
                require(parts.size >= 2) { "北京科技大学教室字段没有携带周次：${roomElement.text()}" }
                room += Jsoup.parse(parts[0]).text().trim()
                weekText = Jsoup.parse(parts[1]).text().trim()
            }

            // 移除已识别的元数据元素，剩余文本才可能是课程名或横线分隔符。
            val nameBody = lineBody.clone()
            nameBody.select("[title=老师], [title=教师], [title=教室], [title=节次], [title=上课地点]")
                .remove()
            val namePart = nameBody.text().replace(DASH_PATTERN, "").trim()
            if (namePart.isNotEmpty()) nameParts += namePart

            val nodeMarkers = lineBody.select("[title=节次]")
            require(nodeMarkers.size <= 1) { "北京科技大学同一字段行包含多个节次标记" }
            if (nodeMarkers.isNotEmpty()) {
                val name = nameParts.joinToString("").trim()
                require(name.isNotEmpty()) { "北京科技大学强智课程名称为空" }
                require(weekText.isNotEmpty()) { "课程 $name 缺少教室字段中的周次" }
                val startNode = nodeGroup * 2 - 1
                WeekUtils.parse(weekText).forEach { week ->
                    courses += CoursePreview(
                        name = name,
                        teacher = teacher,
                        room = room,
                        day = day,
                        startNode = startNode,
                        step = 2,
                        startWeek = week.startWeek,
                        endWeek = week.endWeek,
                        type = week.type,
                    )
                }
                nameParts.clear()
                teacher = ""
                room = ""
                weekText = ""
            }
        }
        require(teacher.isEmpty() && room.isEmpty() && weekText.isEmpty()) {
            "北京科技大学课程格末尾存在未闭合课程字段"
        }
        return courses
    }

    private val HEAD_BOUNDARY_PATTERN = Regex("""(?i)</?head(?:\s[^>]*)?>""")
    private val BR_PATTERN = Regex("""(?i)<br\s*/?>""")
    private val NON_BREAKING_SPACE_PATTERN =
        Regex("""(?:&nbsp;|\u00A0)+""", RegexOption.IGNORE_CASE)
    private val DASH_PATTERN = Regex("-{3,}")
}
