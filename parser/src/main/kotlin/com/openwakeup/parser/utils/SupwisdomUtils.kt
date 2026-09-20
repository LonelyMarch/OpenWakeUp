package com.openwakeup.parser.utils

import com.openwakeup.parser.CoursePreview
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Supwisdom `TaskActivity` 页面脚本的无状态解析工具。 */
internal object SupwisdomUtils {

    /**
     * 解析页面脚本中的课程、教师、周位图与网格坐标。
     *
     * @param source 包含 `table0.marshalTable` 的完整 HTML
     * @return 周位图无损拆分后的课程预览
     * @throws IllegalArgumentException 页面指纹或课程字段不完整
     */
    fun parse(source: String): List<CoursePreview> {
        val script = SCRIPT_PATTERN.find(source)?.value
            ?: throw IllegalArgumentException("页面中缺少 TaskActivity 课表脚本")
        val lines = script
            .replace(Regex("""\n\s*"""), "\n")
            .replace(Regex(""",\r?\n"""), ",")
            .replace(Regex("""\(\r?\n"""), "(")
            .lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.endsWith(';') }
            .toList()

        val courses = mutableListOf<CoursePreview>()
        var courseName = ""
        var previousName = ""
        var teacher = ""
        var room = ""
        var weekMask = ""
        lines.forEach { line ->
            when {
                line.contains("courseName += ") -> {
                    // 部分页面把课程名拆成多条字符串拼接语句，必须按原顺序累加。
                    courseName += line.substringAfter('"').substringBeforeLast('"')
                }

                line.contains("var teachers =") -> teacher = parseTeachers(line)

                line.contains("new TaskActivity(") -> {
                    val values =
                        QUOTED_PATTERN.findAll(line).map { match -> match.groupValues[1] }.toList()
                    require(values.size >= 5) { "TaskActivity 参数不足" }
                    val group = when (values.size) {
                        11 -> values[8]
                        in 7..10 -> values[6]
                        else -> ""
                    }
                    val parsedName =
                        values[1].substringBeforeLast('(').substringBeforeLast('[').trim()
                    require(parsedName.isNotEmpty()) { "TaskActivity 课程名为空" }
                    courseName = if (group.isNotBlank()) {
                        "$parsedName(${group.removeSuffix("组")}组)"
                    } else {
                        parsedName
                    }
                    room = values[3].trim()
                    weekMask = values[4].trim()
                }

                line.contains("index =") && line.contains("*unitCount+") -> {
                    val coordinates = line.substringAfter("index =").substringBefore(';')
                        .split("*unitCount+")
                        .map { value -> value.trim().toIntOrNull() }
                    require(coordinates.size == 2 && coordinates.all { value -> value != null }) {
                        "课表网格坐标无效：$line"
                    }
                    val name = courseName.ifBlank { previousName }
                    require(name.isNotBlank() && weekMask.isNotBlank()) { "课表活动缺少课程名或周次位图" }
                    val day = coordinates[0]!! + 1
                    val node = coordinates[1]!! + 1
                    require(day in 1..7 && node > 0) { "课表活动的星期或节次越界" }
                    WeekUtils.parseBitMask(weekMask).forEach { week ->
                        courses += CoursePreview(
                            name = name,
                            teacher = teacher,
                            room = room,
                            day = day,
                            startNode = node,
                            startWeek = week.startWeek,
                            endWeek = week.endWeek,
                            type = week.type,
                        )
                    }
                    previousName = name
                    courseName = ""
                }
            }
        }
        require(courses.isNotEmpty()) { "页面中没有课程" }
        return courses
    }

    /**
     * 从教师 JSON 数组脚本行读取全部教师姓名。
     *
     * @param line `var teachers = [...]` 脚本行
     * @return 用逗号连接的教师姓名
     */
    private fun parseTeachers(line: String): String = Json.parseToJsonElement(
        line.substringAfter("var teachers =").substringBeforeLast(';').trim(),
    ).jsonArray.joinToString(", ") { element ->
        element.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
    }

    private val SCRIPT_PATTERN = Regex("""var activity=null;[\w\W]*?(?=table0\.marshalTable)""")
    private val QUOTED_PATTERN = Regex(""""(.*?)"""")
}
