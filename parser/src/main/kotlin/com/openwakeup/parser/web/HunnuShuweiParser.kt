package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils

/** 湖南师范大学树维 EAMS 最终课表脚本解析器。 */
object HunnuShuweiParser : Parser {

    /**
     * 解析教师声明、`TaskActivity` 参数和网格坐标。
     *
     * @param input `courseTable.action` 最终页面 HTML
     * @return 去除 EAMS 哨兵位并无损压缩周次后的课程
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val activities = TEACHER_BLOCK_PATTERN.findAll(input.text).flatMap { blockMatch ->
            parseTeacherBlock(blockMatch.value).asSequence()
        }.toList()
        if (activities.isEmpty()) throw ParserException.empty("湖南师大课表中没有课程")
        mergeAdjacentNodes(activities)
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("湖南师大课表解析失败：${error.message}", error)
    }

    /** 解析一个教师活动块中的课程字段及全部节次坐标。 */
    private fun parseTeacherBlock(block: String): List<CoursePreview> {
        val teacher = TEACHER_PATTERN.find(block)?.groupValues?.get(1)?.trim().orEmpty()
        val activity = ACTIVITY_PATTERN.find(block)
            ?: throw IllegalArgumentException("TaskActivity 缺少课程参数")
        val name = activity.groupValues[1].replace(COURSE_SUFFIX_PATTERN, "").trim()
        val room = activity.groupValues[2].trim()
        val mask = activity.groupValues[3]
        require(name.isNotEmpty()) { "TaskActivity 课程名为空" }
        // 该 EAMS 版本的第 0 位是哨兵位；上游以字符下标直接作为周数可证明此约定。
        require(mask.length > 1 && mask.first() == '0') { "湖南师大周位图缺少第 0 位哨兵" }
        val weeks = WeekUtils.parseBitMask(mask.drop(1))
        val coordinates = COORDINATE_PATTERN.findAll(block).map { match ->
            match.groupValues[1].toInt() + 1 to match.groupValues[2].toInt() + 1
        }.toList()
        require(coordinates.isNotEmpty()) { "课程 $name 缺少星期和节次坐标" }
        return coordinates.flatMap { (day, node) ->
            require(day in 1..7 && node > 0) { "课程 $name 的星期或节次越界" }
            weeks.map { week ->
                CoursePreview(
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
        }
    }

    /** 仅合并字段和周次完全相同、节次真正相邻的课程。 */
    private fun mergeAdjacentNodes(courses: List<CoursePreview>): List<CoursePreview> = courses
        .groupBy { course ->
            MergeKey(
                course.name,
                course.teacher,
                course.room,
                course.day,
                course.startWeek,
                course.endWeek,
                course.type,
            )
        }
        .flatMap { (key, values) ->
            val nodes = values.flatMap { course ->
                (course.startNode until course.startNode + course.step).toList()
            }.toSortedSet()
            val ranges = mutableListOf<IntRange>()
            var start = nodes.first()
            var previous = start
            nodes.drop(1).forEach { node ->
                if (node != previous + 1) {
                    ranges += start..previous
                    start = node
                }
                previous = node
            }
            ranges += start..previous
            ranges.map { range ->
                CoursePreview(
                    name = key.name,
                    teacher = key.teacher,
                    room = key.room,
                    day = key.day,
                    startNode = range.first,
                    step = range.last - range.first + 1,
                    startWeek = key.startWeek,
                    endWeek = key.endWeek,
                    type = key.type,
                )
            }
        }

    private data class MergeKey(
        val name: String,
        val teacher: String,
        val room: String,
        val day: Int,
        val startWeek: Int,
        val endWeek: Int,
        val type: Int,
    )

    private val TEACHER_BLOCK_PATTERN = Regex(
        """var\s+actTeachers\s*=\s*\[\{[\s\S]*?(?=var\s+actTeachers\s*=|$)""",
    )
    private val TEACHER_PATTERN = Regex("""name\s*:\s*"([^"]*)"""")
    private val ACTIVITY_PATTERN = Regex(
        """new\s+TaskActivity\([\s\S]+?\),[\s\S]+?\),[\s\S]+?,"(.+?)",[\s\S]*?,"(.*?)","([01]+)[^"]*"\)""",
    )
    private val COORDINATE_PATTERN =
        Regex("""index\s*=\s*(\d+)\s*\*\s*unitCount\s*\+\s*(\d+)\s*;""")
    private val COURSE_SUFFIX_PATTERN = Regex("""\([^)]*\)$""")
}
