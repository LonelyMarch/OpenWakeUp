package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.JsonUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup

/** `uic` 使用的 `mytimetable` 单节课表解析器。 */
object UicParser : Parser {

    /**
     * 解析带显式学期周数的本地 JSON 输入封套。
     *
     * 封套格式为 `{"html":"...","weekCount":16}`。原页面只有星期和节次坐标，没有课程
     * 周边界；调用方能够提供可信 `weekCount` 之前，该 type 必须保持未启用。
     *
     * @param input 包含完整 HTML 和正整数 `weekCount` 的 JSON 对象
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 封套、已存在的教师映射表、课表坐标或课程字段无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val root = Json.parseToJsonElement(input.text).jsonObject
        val html = JsonUtils.requiredString(root, "html")
        val weekCount = JsonUtils.requiredPositiveInt(root, "weekCount")
        val document = Jsoup.parse(html)
        val teacherMap = parseTeacherMap(document)
        val table = document.getElementById("mytimetable")
            ?: throw ParserException.parse("UIC 页面中缺少 mytimetable 课表")
        val rows = table.select("tr")
        if (rows.size <= 1) throw ParserException.parse("UIC 课表中缺少课程行")

        val courses = mutableListOf<CoursePreview>()
        rows.drop(1).forEachIndexed { rowIndex, row ->
            val node = rowIndex + 1
            row.select("td").forEachIndexed { columnIndex, cell ->
                if (cell.text().isBlank()) return@forEachIndexed
                val day = columnIndex + 1
                require(day in 1..7) { "UIC 课表星期列超过 7 列" }
                val fields = cell.html().split(BR_PATTERN)
                    .map { fragment -> Jsoup.parse(fragment).text().trim() }
                require(fields.size >= 2) { "UIC 第 $node 节课程格字段不足" }
                val name = fields[0]
                require(name.isNotEmpty()) { "UIC 课程名称为空" }
                courses += CoursePreview(
                    name = name,
                    teacher = teacherMap[name].orEmpty(),
                    room = fields[1],
                    day = day,
                    startNode = node,
                    startWeek = 1,
                    endWeek = weekCount,
                )
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("UIC 课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("UIC 课表解析失败：${error.message}", error)
    }

    /** 从可选 `.tablestyle-2` 的第 3、4 列建立课程到教师的映射。 */
    private fun parseTeacherMap(document: org.jsoup.nodes.Document): Map<String, String> {
        val table = document.selectFirst(".tablestyle-2")
            ?: return emptyMap()
        val result = linkedMapOf<String, String>()
        table.select("tbody tr").forEach { row ->
            val cells = row.select("td")
            if (cells.isEmpty()) return@forEach
            require(cells.size >= 4) { "UIC 教师映射行字段不足" }
            val name = cells[2].text().trim()
            require(name.isNotEmpty()) { "UIC 教师映射课程名称为空" }
            result[name] = cells[3].text().trim()
        }
        return result
    }

    private val BR_PATTERN = Regex("(?i)<br\\s*/?>")
}
