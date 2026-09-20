package com.openwakeup.parser.utils

import com.openwakeup.parser.CoursePreview

/** 旧 EAMS `TaskActivity` 脚本的共享解析工具。 */
internal object EamsScriptUtils {


    fun parse(
        source: String,
        tableVariable: String,
        nodeMapper: (Int) -> List<Int>,
        splitAfter: Set<Int> = emptySet(),
        nameTransform: (String) -> String = { name -> name },
    ): List<CoursePreview> {
        val bounds = parseTermBounds(source, tableVariable)
        val activities = ACTIVITY_SPLIT_PATTERN.split(source).drop(1).mapIndexed { index, block ->
            parseActivity(block, index + 1, bounds, nodeMapper, nameTransform)
        }
        require(activities.isNotEmpty()) { "页面中没有 TaskActivity 课程活动" }

        // 同一课程可能以多条活动分别描述不同周次或节次，先按单周聚合才能无损合并。
        val nodesByWeek = linkedMapOf<WeekKey, MutableSet<Int>>()
        activities.forEach { activity ->
            activity.weeks.forEach { week ->
                val key =
                    WeekKey(activity.name, activity.teacher, activity.room, activity.day, week)
                nodesByWeek.getOrPut(key) { sortedSetOf() }.addAll(activity.nodes)
            }
        }

        val weeksByNodes = linkedMapOf<ScheduleKey, MutableSet<Int>>()
        nodesByWeek.forEach { (key, nodes) ->
            val scheduleKey =
                ScheduleKey(key.name, key.teacher, key.room, key.day, nodes.toSortedSet())
            weeksByNodes.getOrPut(scheduleKey) { sortedSetOf() }.add(key.week)
        }

        return weeksByNodes.flatMap { (key, weeks) ->
            val nodeSegments = splitNodes(key.nodes, splitAfter)
            WeekUtils.compact(weeks).flatMap { week ->
                nodeSegments.map { nodes ->
                    CoursePreview(
                        name = key.name,
                        teacher = key.teacher,
                        room = key.room,
                        day = key.day,
                        startNode = nodes.first,
                        step = nodes.last - nodes.first + 1,
                        startWeek = week.startWeek,
                        endWeek = week.endWeek,
                        type = week.type,
                    )
                }
            }
        }.also { courses -> require(courses.isNotEmpty()) { "页面中没有可导入课程" } }
    }

    /** 读取学期周位图的偏移、首周和末周。 */
    private fun parseTermBounds(source: String, tableVariable: String): TermBounds {
        val pattern = Regex(
            """${Regex.escape(tableVariable)}\.marshalTable\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\);""",
        )
        val match = pattern.find(source)
            ?: throw IllegalArgumentException("页面中缺少 $tableVariable.marshalTable 学期边界")
        val from = match.groupValues[1].toInt()
        val start = match.groupValues[2].toInt()
        val end = match.groupValues[3].toInt()
        require(from >= 0 && start > 0 && end >= start) { "学期周边界无效" }
        return TermBounds(from, start, end)
    }

    /** 解析一条活动中的七个文本参数、周位图和全部网格坐标。 */
    private fun parseActivity(
        block: String,
        ordinal: Int,
        bounds: TermBounds,
        nodeMapper: (Int) -> List<Int>,
        nameTransform: (String) -> String,
    ): Activity {
        val fields = ACTIVITY_FIELDS_PATTERN.find(block)?.groupValues?.drop(1)
            ?: throw IllegalArgumentException("第 $ordinal 条 TaskActivity 缺少七个文本参数")
        val name = nameTransform(fields[3]).trim()
        require(name.isNotEmpty()) { "第 $ordinal 条 TaskActivity 课程名为空" }
        val teacher = fields[1].trim()
        val room = fields[5].trim()
        val weeks = decodeWeeks(fields[6], bounds)

        val coordinates = COORDINATE_PATTERN.findAll(block).map { match ->
            match.groupValues[1].toInt() to match.groupValues[2].toInt()
        }.toList()
        require(coordinates.isNotEmpty()) { "课程 $name 缺少网格坐标" }
        val rawDays = coordinates.map { coordinate -> coordinate.first }.distinct()
        require(rawDays.size == 1) { "课程 $name 的一条活动跨越多个星期，无法确定语义" }
        val day = rawDays.single() + 1
        require(day in 1..7) { "课程 $name 的星期越界：$day" }
        val nodes =
            coordinates.flatMap { coordinate -> nodeMapper(coordinate.second) }.toSortedSet()
        require(nodes.isNotEmpty() && nodes.all { node -> node > 0 }) { "课程 $name 的节次无效" }
        return Activity(name, teacher, room, day, weeks, nodes)
    }

    /**
     * 按 EAMS 的 `termFrom + week - 2` 规则读取周位图。
     *
     * 上游通过重复字符串避免跨边界访问；这里显式检查索引并保留该编码规则。
     */
    private fun decodeWeeks(mask: String, bounds: TermBounds): Set<Int> {
        require(mask.isNotEmpty() && mask.all { flag -> flag == '0' || flag == '1' }) { "周位图只能包含 0 和 1" }
        val repeated = mask.repeat(2)
        val weeks = (bounds.start..bounds.end).filter { week ->
            val index = bounds.from + week - 2
            require(index in repeated.indices) { "周位图长度不足以覆盖第 $week 周" }
            repeated[index] == '1'
        }.toSortedSet()
        require(weeks.isNotEmpty()) { "周位图中没有上课周" }
        return weeks
    }

    /** 将节次集合拆成连续区间，并在学校定义的午休边界处强制断开。 */
    private fun splitNodes(nodes: Set<Int>, splitAfter: Set<Int>): List<IntRange> {
        require(nodes.isNotEmpty()) { "节次集合为空" }
        val sorted = nodes.sorted()
        val result = mutableListOf<IntRange>()
        var start = sorted.first()
        var previous = start
        sorted.drop(1).forEach { node ->
            if (node != previous + 1 || previous in splitAfter) {
                result += start..previous
                start = node
            }
            previous = node
        }
        result += start..previous
        return result
    }

    private data class TermBounds(val from: Int, val start: Int, val end: Int)
    private data class Activity(
        val name: String,
        val teacher: String,
        val room: String,
        val day: Int,
        val weeks: Set<Int>,
        val nodes: Set<Int>,
    )

    private data class WeekKey(
        val name: String,
        val teacher: String,
        val room: String,
        val day: Int,
        val week: Int,
    )

    private data class ScheduleKey(
        val name: String,
        val teacher: String,
        val room: String,
        val day: Int,
        val nodes: Set<Int>,
    )

    private val ACTIVITY_SPLIT_PATTERN = Regex("""activity\s*=\s*new\s+TaskActivity""")
    private val ACTIVITY_FIELDS_PATTERN = Regex(
        """\("(.*?)","(.*?)","(.*?)","(.*?)","(.*?)","(.*?)","([01]+)"\);""",
    )
    private val COORDINATE_PATTERN =
        Regex("""index\s*=\s*(\d+)\s*\*\s*unitCount\s*\+\s*(\d+)\s*;""")
}
