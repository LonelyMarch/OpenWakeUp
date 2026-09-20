package com.openwakeup.parser.utils

import com.openwakeup.parser.CoursePreview
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** HFUInfo 结构排课响应的严格解码工具。 */
internal object HfuScheduleUtils {

    /**
     * 关联 `lessonList` 与 `scheduleList`，并把同一课程时段的离散周压缩为无损周次片段。
     *
     * 原版按星期、课程、时间、教师和周次排序后合并。本实现直接以最终课程字段分组，再调用
     * [WeekUtils.compact] 压缩周集合；结果等价，同时避免依赖响应原始顺序。未知课程、非法星期、
     * 非法时间或空课表均立即失败，不能生成占位课程。
     *
     * @param source 完整 HFUInfo JSON 响应
     * @param sourceName 用于错误信息的教务来源名称
     * @return 可直接交给导入预览层的课程列表
     */
    fun parse(source: String, sourceName: String): List<CoursePreview> {
        val root = Json.parseToJsonElement(source).jsonObject
        val result = JsonUtils.requiredObject(root, "result")
        val lessons = JsonUtils.requiredArray(result, "lessonList").associate { element ->
            val lesson = element.jsonObject
            JsonUtils.requiredString(lesson, "id") to JsonUtils.requiredString(lesson, "courseName")
        }

        val schedules =
            JsonUtils.requiredArray(result, "scheduleList").mapIndexed { index, element ->
                val row = element.jsonObject
                decodeSchedule(row, lessons, index)
            }
        require(schedules.isNotEmpty()) { "$sourceName 排课响应中没有课程" }

        return schedules.groupBy { schedule -> schedule.slot }.flatMap { (slot, groupedSchedules) ->
            WeekUtils.compact(groupedSchedules.map { schedule -> schedule.week }).map { week ->
                CoursePreview(
                    name = slot.name,
                    teacher = slot.teacher,
                    room = slot.room,
                    day = slot.day,
                    startNode = slot.startNode,
                    step = slot.step,
                    startWeek = week.startWeek,
                    endWeek = week.endWeek,
                    type = week.type,
                )
            }
        }
    }

    /**
     * 解码一条排课日程，并计算其节次位置。
     *
     * @param row 当前 `scheduleList` 对象
     * @param lessons 课程 ID 到课程名的映射
     * @param index 当前日程下标，仅用于生成可定位的错误信息
     * @return 尚未合并周次的排课记录
     */
    private fun decodeSchedule(
        row: JsonObject,
        lessons: Map<String, String>,
        index: Int,
    ): ScheduleAtWeek {
        val lessonId = JsonUtils.requiredString(row, "lessonId")
        val name = lessons[lessonId]
            ?: throw IllegalArgumentException("第 ${index + 1} 条日程关联不到课程 $lessonId")
        val startTime = JsonUtils.requiredPositiveInt(row, "startTime")
        val endTime = JsonUtils.requiredPositiveInt(row, "endTime")
        requireValidClock(startTime, "课程 $name 的开始时间")
        requireValidClock(endTime, "课程 $name 的结束时间")
        require(endTime > startTime) { "课程 $name 的结束时间不晚于开始时间" }

        val day = JsonUtils.requiredPositiveInt(row, "weekday")
        require(day in 1..7) { "课程 $name 的星期不在 1～7 范围内" }
        val week = JsonUtils.requiredPositiveInt(row, "weekIndex")
        val room = row["room"] as? JsonObject

        return ScheduleAtWeek(
            slot = CourseSlot(
                name = name,
                teacher = JsonUtils.optionalString(row, "personName"),
                room = room?.let { roomObject -> JsonUtils.optionalString(roomObject, "nameZh") }
                    .orEmpty(),
                day = day,
                startNode = startNode(startTime),
                step = step(endTime - startTime),
            ),
            week = week,
        )
    }

    /**
     * 按原版三段式公式将 HHmm 开始时间转换为起始节次。
     *
     * @param time 已校验的 HHmm 整数
     * @return 从 1 开始的节次
     */
    private fun startNode(time: Int): Int {
        val node = when {
            time < 1230 -> (time - 800) / 100 + 1
            time < 1800 -> (time - 1400) / 100 + 5
            else -> (time - 1900) / 100 + 9
        }
        require(node > 0) { "开始时间 $time 无法换算为正节次" }
        return node
    }

    /**
     * 按原版时间差区间计算连续节数。
     *
     * @param difference 结束 HHmm 减开始 HHmm 的正差值
     * @return 连续节数，范围为 1～4
     */
    private fun step(difference: Int): Int = when {
        difference in 50 until 100 -> 1
        difference <= 200 -> 2
        difference in 210..340 -> 3
        else -> 4
    }

    /** 校验整数确实表示 24 小时制 HHmm 时间。 */
    private fun requireValidClock(time: Int, fieldName: String) {
        val hour = time / 100
        val minute = time % 100
        require(hour in 0..23 && minute in 0..59) { "${fieldName}不是合法 HHmm：$time" }
    }

    /** 一门课程在课表中的固定时段字段。 */
    private data class CourseSlot(
        val name: String,
        val teacher: String,
        val room: String,
        val day: Int,
        val startNode: Int,
        val step: Int,
    )

    /** 固定课程时段在某一周的排课记录。 */
    private data class ScheduleAtWeek(
        val slot: CourseSlot,
        val week: Int,
    )
}
