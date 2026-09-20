package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.JsonUtils
import com.openwakeup.parser.utils.TextUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject

/** 吉林大学研究生教务任务 JSON 解析器。 */
object JluPostParser : Parser {
    /**
     * 解析 `datas.xsjxrwcx.rows` 中的排课时间地点字段。
     *
     * @param input 完整研究生教务 JSON 响应
     * @return 每个分号时间段及其中每个周次片段对应的课程预览
     * @throws ParserException JSON 结构或可见时间字段错误
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val root = Json.parseToJsonElement(input.text).jsonObject
        val datas = JsonUtils.requiredObject(root, "datas")
        val task = JsonUtils.requiredObject(datas, "xsjxrwcx")
        val courses = JsonUtils.requiredArray(task, "rows").flatMap { element ->
            val row = element.jsonObject
            val scheduleElement = row["PKSJDD"]
            if (scheduleElement == null || scheduleElement === JsonNull) return@flatMap emptyList()
            val name = JsonUtils.requiredString(row, "KCMC")
            val teacher = JsonUtils.optionalString(row, "RKJS")
            JsonUtils.requiredString(row, "PKSJDD").split(';').filter { it.isNotBlank() }
                .flatMap { schedule ->
                    parseSchedule(name, teacher, schedule)
                }
        }
        if (courses.isEmpty()) throw ParserException.empty("吉林大学研究生课表响应中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("吉林大学研究生课表解析失败：${error.message}", error)
    }

    /**
     * 解析一条可能包含多个周段的排课描述。
     *
     * @param name 课程名称
     * @param teacher 任课教师
     * @param schedule 例如 `3-5单周,7-12周 星期二[1-4节]教室`
     * @return 周段展开后的课程预览
     */
    private fun parseSchedule(
        name: String,
        teacher: String,
        schedule: String
    ): List<CoursePreview> {
        val dayMatch = DAY_PATTERN.find(schedule)
            ?: throw IllegalArgumentException("课程 $name 缺少星期：$schedule")
        val nodeMatch = NODE_PATTERN.find(schedule)
            ?: throw IllegalArgumentException("课程 $name 缺少节次：$schedule")
        val startNode = nodeMatch.groupValues[1].toInt()
        val endNode = nodeMatch.groupValues[2].ifEmpty { nodeMatch.groupValues[1] }.toInt()
        val weeks = WEEK_PATTERN.findAll(schedule).toList()
        require(weeks.isNotEmpty()) { "课程 $name 缺少周次：$schedule" }
        val room = LOCATION_PATTERN.find(schedule)?.groupValues?.get(1)?.trim().orEmpty()
        return weeks.map { match ->
            val startWeek = match.groupValues[1].toInt()
            val endWeek = match.groupValues[2].ifEmpty { match.groupValues[1] }.toInt()
            CoursePreview(
                name = name, teacher = teacher, room = room,
                day = TextUtils.requireDay(dayMatch.value), startNode = startNode,
                step = endNode - startNode + 1, startWeek = startWeek, endWeek = endWeek,
                type = when (match.groupValues[3]) {
                    "单" -> 1; "双" -> 2; else -> 0
                },
            )
        }
    }

    private val WEEK_PATTERN = Regex("""(\d+)(?:-(\d+))?([单双]?)周""")
    private val DAY_PATTERN = Regex("""星期[一二三四五六日七]""")
    private val NODE_PATTERN = Regex("""(\d+)(?:-(\d+))?节""")
    private val LOCATION_PATTERN = Regex("""节](.+)""")
}
