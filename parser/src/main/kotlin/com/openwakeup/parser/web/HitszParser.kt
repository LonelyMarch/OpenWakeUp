package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `hitsz` 使用的 iView 卡片式课表解析器。 */
object HitszParser : Parser {

    /**
     * 解析 `.ivu-table-tbody` 中的普通课程卡片与实验课程卡片。
     *
     * 原版使用单元格下标作为星期。页面存在行标题列时星期从第二格开始；只有七个课程列时则
     * 从第一格开始。这里根据当前行的列数显式区分两种布局，避免生成星期 0。
     *
     * @param input 完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹、课程字段、周次或节次不合法
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val body = Jsoup.parse(input.text).getElementsByClass("ivu-table-tbody").firstOrNull()
            ?: throw ParserException.parse("hitsz 页面中缺少 ivu-table-tbody")
        val courses = mutableListOf<CoursePreview>()
        body.select("tr").forEach { row ->
            val cells = row.select(":scope > td")
            val hasLeadingLabel = cells.size > 7
            cells.forEachIndexed cellLoop@{ cellIndex, cell ->
                val cards = cell.getElementsByClass("ivu-card-body")
                if (cards.isEmpty()) return@cellLoop
                val day = if (hasLeadingLabel) cellIndex else cellIndex + 1
                require(day in 1..7) { "hitsz 课程列无法映射到星期 1～7：$cellIndex" }
                cards.forEach { card -> courses += parseCard(card, day) }
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("hitsz 页面中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("hitsz 课表解析失败：${error.message}", error)
    }

    /** 按课程名称前缀选择原版普通课程或实验课程字段布局。 */
    private fun parseCard(card: Element, day: Int): List<CoursePreview> {
        val fields = card.text().split(Regex("\\s+")).filter { field -> field.isNotBlank() }
        require(fields.isNotEmpty()) { "hitsz 课程卡片为空" }
        return if (fields.first().startsWith("【实验】")) {
            parseExperimentCard(fields, day)
        } else {
            parseRegularCard(fields, day)
        }
    }

    /**
     * 解析普通课程：末三项依次提供教师、`周次[教室]` 与节次。
     *
     * @param fields 由卡片可见文本按空白切分后的字段
     * @param day 星期，范围为 1～7
     */
    private fun parseRegularCard(fields: List<String>, day: Int): List<CoursePreview> {
        require(fields.size >= 4) { "hitsz 普通课程字段不足" }
        val name = fields.first().trim()
        val teacher = bracketContent(fields[fields.lastIndex - 2], "教师")
        val weekAndRoom = fields[fields.lastIndex - 1]
        val weekText = weekAndRoom.substringBefore('[').trim()
        val room = bracketContent(weekAndRoom, "教室")
        val nodes = parseNodes(fields.last(), name)
        require(name.isNotEmpty()) { "hitsz 课程名为空" }
        return buildCourses(name, teacher, room, day, nodes, weekText)
    }

    /**
     * 解析实验课程：倒数第二项同时包含节次与方括号周次，最后一项提供教室。
     *
     * 原版实验课程没有教师字段，因此教师保持空字符串，不把其他字段误填为教师。
     */
    private fun parseExperimentCard(fields: List<String>, day: Int): List<CoursePreview> {
        require(fields.size >= 3) { "hitsz 实验课程字段不足" }
        val name = fields.first().trim()
        val nodeAndWeek = fields[fields.lastIndex - 1]
        val nodes = parseNodes(nodeAndWeek.substringBefore('['), name)
        val weekText = bracketContent(nodeAndWeek, "周次")
        val room = bracketContent(fields.last(), "教室")
        require(name.isNotEmpty()) { "hitsz 实验课程名为空" }
        return buildCourses(name, "", room, day, nodes, weekText)
    }

    /** 将明确的星期、节次和周次转换为一个或多个无损课程片段。 */
    private fun buildCourses(
        name: String,
        teacher: String,
        room: String,
        day: Int,
        nodes: IntRange,
        weekText: String,
    ): List<CoursePreview> = WeekUtils.parse(weekText).map { week ->
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

    /** 从 `第1-2节` 一类字段中读取正节次范围。 */
    private fun parseNodes(source: String, courseName: String): IntRange {
        val nodeText = source.substringAfter('第', source).substringBefore('节').trim()
        require(nodeText.isNotEmpty()) { "课程 $courseName 缺少节次" }
        return TextUtils.requirePositiveRange(nodeText, "课程 $courseName 的节次")
    }

    /** 从一对方括号中读取字段，缺失括号或内容为空时失败关闭。 */
    private fun bracketContent(source: String, fieldName: String): String {
        val value = source.substringAfter('[', "").substringBefore(']', "").trim()
        require(value.isNotEmpty()) { "hitsz $fieldName 字段为空：$source" }
        return value
    }
}
