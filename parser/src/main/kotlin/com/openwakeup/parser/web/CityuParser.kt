package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.JsonUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** `cityu` 使用的 CityU Banner 矩阵课表解析器。 */
object CityuParser : Parser {

    /**
     * 解析带显式学期周数的本地 JSON 输入封套。
     *
     * 封套格式为 `{"html":"...","weekCount":13}`。CityU 原页面只表达每周的星期和节次，
     * 不包含上课周边界；原版直接写死第 1～20 周，会产生跨学期假课程，因此本实现拒绝裸 HTML。
     * 调用方能够从可信学期元数据提供 `weekCount` 之前，该 type 必须保持未启用。
     *
     * @param input 包含 `html` 和正整数 `weekCount` 的 JSON 对象
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 封套、矩阵坐标、课程字段或 rowspan 无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val root = Json.parseToJsonElement(input.text).jsonObject
        val html = JsonUtils.requiredString(root, "html")
        val weekCount = JsonUtils.requiredPositiveInt(root, "weekCount")
        val matrix = Jsoup.parse(html).getElementsByClass("ctt-matrix").firstOrNull()
            ?: throw ParserException.parse("cityu 页面中缺少 ctt-matrix")
        val courses = parseMatrix(matrix, weekCount)
        if (courses.isEmpty()) throw ParserException.empty("cityu 矩阵中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("cityu 课表解析失败：${error.message}", error)
    }

    /** 使用七列 rowspan 占用表恢复每个课程格的星期和连续节次。 */
    private fun parseMatrix(matrix: Element, weekCount: Int): List<CoursePreview> {
        val occupiedUntilRow = IntArray(7)
        val courses = mutableListOf<CoursePreview>()
        matrix.select("tr").drop(1).forEachIndexed { dataRowIndex, row ->
            val startNode = dataRowIndex + 1
            var logicalColumn = 0
            row.children().filter { child -> child.tagName() == "td" }.forEach { cell ->
                if (cell.className().lowercase() == "ctt-matrix-td-time") return@forEach
                while (logicalColumn < occupiedUntilRow.size &&
                    occupiedUntilRow[logicalColumn] >= startNode
                ) {
                    logicalColumn++
                }
                require(logicalColumn in occupiedUntilRow.indices) {
                    "cityu 第 $startNode 节存在超过 7 个星期列"
                }
                val rowSpan = cell.attr("rowspan").takeIf { value -> value.isNotBlank() }
                    ?.toIntOrNull() ?: 1
                require(rowSpan > 0) { "cityu rowspan 必须为正整数" }
                occupiedUntilRow[logicalColumn] = startNode + rowSpan - 1
                if (cell.text().isNotBlank()) {
                    courses += parseCourseCell(
                        cell = cell,
                        day = logicalColumn + 1,
                        startNode = startNode,
                        rowSpan = rowSpan,
                        weekCount = weekCount,
                    )
                }
                logicalColumn++
            }
        }
        return courses
    }

    /** 按原版字段位置读取课程名和教室，并应用封套中的明确学期周数。 */
    private fun parseCourseCell(
        cell: Element,
        day: Int,
        startNode: Int,
        rowSpan: Int,
        weekCount: Int,
    ): CoursePreview {
        val fields = cell.html().split(BR_PATTERN)
            .map { fragment -> Jsoup.parse(fragment).text().trim() }
        require(fields.size >= 3) { "cityu 课程格字段不足" }
        val name = fields[1]
        require(name.isNotEmpty()) { "cityu 课程名为空" }
        return CoursePreview(
            name = name,
            room = fields[2],
            day = day,
            startNode = startNode,
            step = rowSpan,
            startWeek = 1,
            endWeek = weekCount,
        )
    }

    private val BR_PATTERN = Regex("(?i)<br\\s*/?>")
}
