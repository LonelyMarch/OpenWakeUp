package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.JsonUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** 北京航空航天大学新本研教务 JSON 课表解析器。 */
object BuaaParser : Parser {
    /**
     * 解析 `datas.arrangedList` 中已排课课程。
     *
     * @param input `getMyScheduleDetail.do` 的完整 JSON 响应
     * @return 教师与周次片段展开后的课程预览
     * @throws ParserException 响应结构、节次、星期或教师周次字段非法
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val root = Json.parseToJsonElement(input.text).jsonObject
        val datas = JsonUtils.requiredObject(root, "datas")
        val courses =
            JsonUtils.requiredArray(datas, "arrangedList").flatMapIndexed { index, element ->
                val row = element.jsonObject
                val name = JsonUtils.requiredString(row, "courseName")
                val day = JsonUtils.requiredPositiveInt(row, "dayOfWeek")
                require(day in 1..7) { "第 ${index + 1} 门课程星期不在 1～7 范围内" }
                val startNode = JsonUtils.requiredPositiveInt(row, "beginSection")
                val endNode = JsonUtils.requiredPositiveInt(row, "endSection")
                require(endNode >= startNode) { "课程 $name 的结束节次小于开始节次" }
                val details = JsonUtils.requiredArray(row, "cellDetail")
                require(details.size >= 2) { "课程 $name 缺少教师与周次详情" }
                val text = JsonUtils.requiredString(details[1].jsonObject, "text")
                val matches = TEACHER_WEEK_PATTERN.findAll(text).toList()
                require(matches.isNotEmpty()) { "课程 $name 的教师周次字段无法识别：$text" }
                matches.map { match ->
                    val startWeek = match.groupValues[2].toInt()
                    val endWeek = match.groupValues[3].toInt()
                    require(endWeek >= startWeek) { "课程 $name 的周次范围倒置" }
                    CoursePreview(
                        name = name,
                        teacher = match.groupValues[1].trim(),
                        room = JsonUtils.optionalString(row, "placeName"),
                        day = day,
                        startNode = startNode,
                        step = endNode - startNode + 1,
                        startWeek = startWeek,
                        endWeek = endWeek,
                        type = when (match.groupValues[4]) {
                            "单" -> 1; "双" -> 2; else -> 0
                        },
                    )
                }
            }
        if (courses.isEmpty()) throw ParserException.empty("北航课表响应中没有已排课课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("北航课表 JSON 解析失败：${error.message}", error)
    }

    /** 教师名允许包含空格，匹配边界由后续方括号周次确定。 */
    private val TEACHER_WEEK_PATTERN = Regex("""([^\[\]]+?)\[(\d+)-(\d+)周(?:\(([单双])\))?]""")
}
