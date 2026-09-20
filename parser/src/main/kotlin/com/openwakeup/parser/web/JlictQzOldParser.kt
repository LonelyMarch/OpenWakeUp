package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `jlict_qz_old` 使用的吉林交通职业技术学院旧强智课表解析器。 */
object JlictQzOldParser : Parser {

    /**
     * 解析 `#kb` 第一张表中课程链接的多行 `title` 属性。
     *
     * 表格首行和每行首列是标题；数据行序号映射为连续双节，数据列序号映射为星期。课程名称、
     * 教师、周次和教室分别位于原版 title 字段的第 3、4、6、7 行。
     *
     * @param input 已由调用方取得的完整旧强智 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException `#kb` 表格、title 字段、星期或周次无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val container = Jsoup.parse(input.text).getElementById("kb")
            ?: throw ParserException.parse("吉林交通职院旧强智页面缺少 kb 容器")
        val table = container.selectFirst("table")
            ?: throw ParserException.parse("吉林交通职院旧强智页面缺少课表")
        val courses = table.select("tr").drop(1).flatMapIndexed { rowIndex, row ->
            parseRow(row, rowIndex + 1)
        }
        if (courses.isEmpty()) throw ParserException.empty("吉林交通职院旧强智课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("吉林交通职院旧强智课表解析失败：${error.message}", error)
    }

    /**
     * 按数据行生成双节范围，并展开该行星期一至星期日的课程链接。
     *
     * @param row 当前课表数据行
     * @param rowNumber 去除表头后的 1 起数据行号
     * @return 当前行解析出的课程预览列表
     */
    private fun parseRow(row: Element, rowNumber: Int): List<CoursePreview> {
        val startNode = rowNumber * 2 - 1
        return row.select("td").drop(1).flatMapIndexed { dayIndex, cell ->
            val day = dayIndex + 1
            require(day in 1..7) { "吉林交通职院旧强智课表出现第 $day 个星期列" }
            cell.select("a[title]").flatMap { link ->
                parseCourse(link.attr("title"), day, startNode)
            }
        }
    }

    /**
     * 从课程链接的多行 title 中读取原版固定字段。
     *
     * @param title 浏览器已经解码实体后的 title 属性
     * @param day 当前课程所在星期
     * @param startNode 当前数据行对应的起始节次
     * @return 按周次片段展开的课程预览
     */
    private fun parseCourse(title: String, day: Int, startNode: Int): List<CoursePreview> {
        val fields = title.split(TITLE_LINE_PATTERN).map { line ->
            line.substringAfter('：').trim()
        }
        require(fields.size >= 7) { "吉林交通职院课程 title 字段不足：${fields.size}" }
        val name = fields[2]
        val teacher = fields[3]
        val weekText = fields[5]
        val room = fields[6]
        require(name.isNotEmpty()) { "吉林交通职院课程名称为空" }
        require(weekText.isNotEmpty()) { "课程 $name 的周次为空" }

        return WeekUtils.parse(weekText).map { week ->
            CoursePreview(
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
    }

    private val TITLE_LINE_PATTERN = Regex("""\r?\n""")
}
