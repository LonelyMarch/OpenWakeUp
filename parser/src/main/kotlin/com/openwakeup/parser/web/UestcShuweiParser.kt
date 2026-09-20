package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 电子科技大学本科旧 EAMS 课表解析器。
 *
 * 该页面不使用树维 MTT DOM，而是把 7 天课表保存在 `window.table0.activities` 中。Android
 * 层只负责把该运行时对象无损序列化成 JSON；本解析器负责校验槽位、合并连续节次，并将周次
 * 位图转换成 [CoursePreview] 可以无损表达的区间。
 */
object UestcShuweiParser : Parser {

    /**
     * 解析电子科大 `table0.activities` JSON 封套。
     *
     * `activities` 按“星期一全部节次、星期二全部节次……”顺序排列。每个槽位包含零个或多个
     * 课程对象；同一课程在连续槽位重复出现，需要在这里合并为一个连续节次区间。
     *
     * @param input WebView 从当前页面运行时提取的结构化 JSON
     * @return 合并连续节次并无损压缩周次后的课程预览
     * @throws ParserException JSON 结构、槽位数量或课程字段不符合页面契约
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val root = Json.parseToJsonElement(input.text).jsonObject
        val activities = root[ACTIVITIES_KEY]?.jsonArray
            ?: throw ParserException.parse("电子科大页面中缺少 table0.activities 数据")
        require(activities.isNotEmpty() && activities.size % DAYS_PER_WEEK == 0) {
            "电子科大课表槽位数量必须是 7 的正整数倍"
        }
        val nodeCount = activities.size / DAYS_PER_WEEK
        require(nodeCount in 1..MAX_NODE_COUNT) {
            "电子科大课表每天节数超出支持范围：$nodeCount"
        }

        val groupedCourses = linkedMapOf<CourseIdentity, MutableSet<Int>>()
        activities.forEachIndexed { slotIndex, slotElement ->
            val day = slotIndex / nodeCount + 1
            val node = slotIndex % nodeCount + 1
            parseSlot(slotElement, day).forEach { identity ->
                // 同一课程的每个槽位只记录节次，最终再按连续区间生成 CoursePreview。
                groupedCourses.getOrPut(identity) { sortedSetOf() }.add(node)
            }
        }

        val courses = groupedCourses.flatMap { (identity, nodes) ->
            splitConsecutiveNodes(nodes).flatMap { nodeRange ->
                WeekUtils.compact(identity.weeks).map { week ->
                    CoursePreview(
                        name = identity.name,
                        teacher = identity.teacher,
                        room = identity.room,
                        day = identity.day,
                        startNode = nodeRange.first,
                        step = nodeRange.last - nodeRange.first + 1,
                        startWeek = week.startWeek,
                        endWeek = week.endWeek,
                        type = week.type,
                    )
                }
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("uestc_shuwei 课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("uestc_shuwei 课表解析失败：${error.message}", error)
    }

    /**
     * 解析单个星期/节次槽位中的全部课程。
     *
     * @param slotElement 槽位 JSON，必须是课程对象数组
     * @param day 当前槽位对应的星期，范围为 1～7
     * @return 用于跨槽位合并的课程身份列表
     */
    private fun parseSlot(slotElement: JsonElement, day: Int): List<CourseIdentity> {
        val slot = slotElement as? JsonArray
            ?: throw IllegalArgumentException("电子科大课表槽位不是课程数组")
        return slot.map { courseElement ->
            val course = courseElement as? JsonObject
                ?: throw IllegalArgumentException("电子科大课表槽位中存在非对象课程")
            val rawName = course.requiredText(COURSE_NAME_KEY, "课程名称")
            val name = rawName.replace(COURSE_SERIAL_SUFFIX_PATTERN, "").trim()
            require(name.isNotEmpty()) { "电子科大课程名称为空" }
            val weeks = parseWeeks(course[VALID_WEEKS_KEY], name)
            CourseIdentity(
                name = name,
                teacher = course.optionalText(TEACHER_NAME_KEY),
                room = course.optionalText(ROOM_NAME_KEY),
                day = day,
                weeks = weeks,
            )
        }
    }

    /**
     * 把 EAMS 从下标 1 开始的周次位图转换成精确周集合。
     *
     * @param element `validWeeks` 数组；元素允许使用布尔值或 0/1 数字
     * @param courseName 仅用于错误信息定位的课程名称
     * @return 所有值为真的正整数周次
     */
    private fun parseWeeks(element: JsonElement?, courseName: String): List<Int> {
        val values = element as? JsonArray
            ?: throw IllegalArgumentException("课程 $courseName 缺少周次位图")
        val weeks = values.mapIndexedNotNull { index, value ->
            val primitive = value.jsonPrimitive
            val enabled = primitive.booleanOrNull ?: (primitive.intOrNull == 1)
            if (index >= 1 && enabled) index else null
        }
        require(weeks.isNotEmpty()) { "课程 $courseName 的周次位图没有上课周" }
        return weeks
    }

    /**
     * 把可能不连续的节次集合拆成若干连续闭区间。
     *
     * @param nodes 同一课程在同一天出现的全部节次
     * @return 按起始节升序排列的连续区间
     */
    private fun splitConsecutiveNodes(nodes: Set<Int>): List<IntRange> {
        val sortedNodes = nodes.sorted()
        require(sortedNodes.isNotEmpty()) { "课程节次集合为空" }
        val ranges = mutableListOf<IntRange>()
        var start = sortedNodes.first()
        var end = start
        sortedNodes.drop(1).forEach { node ->
            if (node == end + 1) {
                end = node
            } else {
                ranges += start..end
                start = node
                end = node
            }
        }
        ranges += start..end
        return ranges
    }

    /** 读取必填字符串字段，并统一清理页面中的连续空白。 */
    private fun JsonObject.requiredText(key: String, fieldName: String): String =
        optionalText(key).ifBlank { throw IllegalArgumentException("电子科大课程缺少$fieldName") }

    /** 读取可选字符串字段，并统一清理页面中的连续空白。 */
    private fun JsonObject.optionalText(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
            .replace(NON_BREAKING_SPACE, ' ')
            .replace(WHITESPACE_PATTERN, " ")
            .trim()

    /** 跨槽位合并时使用的稳定课程身份；周集合不同的记录不得误合并。 */
    private data class CourseIdentity(
        val name: String,
        val teacher: String,
        val room: String,
        val day: Int,
        val weeks: List<Int>,
    )

    private const val DAYS_PER_WEEK = 7
    private const val MAX_NODE_COUNT = 24
    private const val ACTIVITIES_KEY = "activities"
    private const val COURSE_NAME_KEY = "courseName"
    private const val TEACHER_NAME_KEY = "teacherName"
    private const val ROOM_NAME_KEY = "roomName"
    private const val VALID_WEEKS_KEY = "validWeeks"
    private const val NON_BREAKING_SPACE = '\u00A0'
    private val WHITESPACE_PATTERN = Regex("""\s+""")
    private val COURSE_SERIAL_SUFFIX_PATTERN =
        Regex("""\s*[（(][A-Za-z]\d{4,}(?:\.[A-Za-z0-9]+)+[)）]\s*$""")
}
