package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `zju_post` 使用的多页面七日网格解析器。 */
object ZjuPostParser : Parser {

    /**
     * 解析输入中的全部 `kcbForm` 页面。
     *
     * D1 起页面由 App 按周次逐个取得，并以 [ParserInput.allTexts] 保留原始 HTML 顺序。
     * Parser 不再依赖 App 构造的 JSON 字符串数组封套，也不执行网络请求。
     *
     * @param input 一个或多个原始课表 HTML 页面
     * @return 所有页面中带明确周次边界的课程
     * @throws ParserException 网格结构或周次字段无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val pages = input.allTexts
        require(pages.isNotEmpty()) { "zju_post 页面列表为空" }
        val courses = pages.flatMapIndexed { index, page ->
            require(page.isNotBlank()) { "zju_post 第 ${index + 1} 个 HTML 页面为空" }
            parsePage(page)
        }
        if (courses.isEmpty()) throw ParserException.empty("zju_post 页面中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("zju_post 课表解析失败：${error.message}", error)
    }

    /**
     * 解析一个页面中 `kcbForm` 的最后一张表格。
     *
     * 页面数组允许包含无课表的响应，因此指纹缺失时返回空列表；命中指纹后的结构错误则抛出。
     */
    private fun parsePage(source: String): List<CoursePreview> {
        val form = Jsoup.parse(source).getElementById("kcbForm") ?: return emptyList()
        val table = form.select("table").lastOrNull()
            ?: throw IllegalArgumentException("zju_post 的 kcbForm 中缺少课表")
        val occupiedUntilNode = IntArray(7) { -1 }
        val courses = mutableListOf<CoursePreview>()

        table.select("tr").forEachIndexed { currentNode, row ->
            var columnCursor = 0
            row.select("td").forEach { cell ->
                while (columnCursor < 7 && occupiedUntilNode[columnCursor] >= currentNode) {
                    columnCursor += 1
                }
                require(columnCursor in 0..6) { "zju_post 第 $currentNode 节的星期列超过 7 列" }
                val rowSpan = cell.attr("rowspan").toIntOrNull() ?: 1
                require(rowSpan > 0) { "zju_post rowspan 必须为正整数" }
                occupiedUntilNode[columnCursor] = currentNode + rowSpan - 1

                if (cell.text().isNotBlank()) {
                    require(currentNode > 0) { "zju_post 表头行中出现课程" }
                    cell.select("a").forEach { anchor ->
                        courses += parseAnchor(anchor, columnCursor + 1, currentNode, rowSpan)
                    }
                }
                columnCursor += 1
            }
        }
        return courses
    }

    /**
     * 解析课程链接内的名称、周次、教师和教室。
     *
     * @param anchor 单门课程链接
     * @param day 根据七列网格恢复的星期
     * @param startNode 当前行对应的起始节次
     * @param step rowspan 对应的连续节数
     */
    private fun parseAnchor(
        anchor: Element,
        day: Int,
        startNode: Int,
        step: Int
    ): List<CoursePreview> {
        val name = anchor.selectFirst("strong")?.text()?.trim().orEmpty()
        require(name.isNotEmpty()) { "zju_post 课程链接缺少名称" }
        val primaryHtml = anchor.html().split(DOUBLE_BR_PATTERN, limit = 2).first()
        val lines = primaryHtml.split(BR_PATTERN)
            .map { fragment -> Jsoup.parse(fragment).text().trim() }
            .filter { line -> line.isNotEmpty() }
        require(lines.size >= 4) { "课程 $name 的字段不足" }

        val teacherIndex = if (lines.size == 4) 2 else 3
        val roomIndex = if (lines.size == 4) 3 else 4
        require(lines.size > roomIndex) { "课程 $name 缺少教师或教室" }
        val weekExpression = WEEK_EXPRESSION_PATTERN.find(lines[1])?.groupValues?.get(1)?.trim()
            ?: throw IllegalArgumentException("课程 $name 缺少 ||(...)周 周次字段")
        val weeks = parseWeeks(weekExpression, name)

        return weeks.map { week ->
            CoursePreview(
                name = name,
                teacher = lines[teacherIndex],
                room = lines[roomIndex],
                day = day,
                startNode = startNode,
                step = step,
                startWeek = week.startWeek,
                endWeek = week.endWeek,
                type = week.type,
            )
        }
    }

    /**
     * 解析括号内用逗号分隔的周次表达式。
     *
     * `前X周` 可推导出明确上限；`每周` 没有学期总周数，必须失败关闭，禁止恢复原版固定 20 周。
     */
    private fun parseWeeks(source: String, courseName: String) = source
        .replace('，', ',')
        .split(',')
        .filter { part -> part.isNotBlank() }
        .flatMap { rawPart ->
            val part = rawPart.trim()
            require(!part.contains('每')) { "课程 $courseName 的“每周”缺少学期结束周" }
            if (part.startsWith('前')) {
                val endWeek = NUMBER_PATTERN.find(part)?.value?.toIntOrNull()
                    ?: throw IllegalArgumentException("课程 $courseName 的前 X 周无法识别：$part")
                require(endWeek > 0) { "课程 $courseName 的结束周必须为正整数" }
                WeekUtils.parse("1-$endWeek 周")
            } else {
                WeekUtils.parse(part)
            }
        }
        .also { weeks -> require(weeks.isNotEmpty()) { "课程 $courseName 没有可用周次" } }

    private val BR_PATTERN = Regex("(?i)<br\\s*/?>")
    private val DOUBLE_BR_PATTERN = Regex("(?i)<br\\s*/?>\\s*<br\\s*/?>")
    private val WEEK_EXPRESSION_PATTERN = Regex("\\|\\|\\s*[（(]([^）)]*)周[）)]")
    private val NUMBER_PATTERN = Regex("\\d+")
}
