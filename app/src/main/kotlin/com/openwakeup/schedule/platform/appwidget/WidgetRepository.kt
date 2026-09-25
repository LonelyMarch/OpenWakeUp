package com.openwakeup.schedule.platform.appwidget

import android.content.Context
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.core.database.AppDatabase
import com.openwakeup.schedule.core.database.entity.CourseDetailEntity
import com.openwakeup.schedule.core.database.entity.CourseEntity
import com.openwakeup.schedule.core.database.entity.ScheduleShiftEntity
import com.openwakeup.schedule.core.database.entity.TimeDetailEntity
import com.openwakeup.schedule.core.util.DateUtils
import com.openwakeup.schedule.core.validation.CourseRangePolicy
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 小部件用的一次性课表快照（RemoteViewsFactory 无法直接访问仓库 Flow） */
data class WidgetSnapshot(
    val tableName: String,
    val displayWeek: Int,
    val details: List<CourseDetailEntity>,
    val courses: Map<Long, CourseEntity>,
    val timeDetails: List<TimeDetailEntity>,
    val startDate: LocalDate,
    val shifts: List<ScheduleShiftEntity>,
    val config: com.openwakeup.schedule.core.database.entity.TableEntity? = null,
) {
    /** 指定星期（1-7）当周的课程条目，包含日期调课结果并按起始节排序。 */
    fun coursesOfDay(day: Int): List<Triple<CourseEntity, CourseDetailEntity, TimeDetailEntity?>> {
        val targetDate = DateUtils.mondayOfWeek(startDate)
            .plusWeeks((displayWeek - 1).toLong())
            .plusDays((day - 1).toLong())
        return coursesOfDate(targetDate)
    }

    /**
     * 指定自然日期的课程条目。
     *
     * 近日课程需要处理周日到下周一的跨周场景，因此不能只依赖 [displayWeek]。
     */
    fun coursesOfDate(targetDate: LocalDate): List<Triple<CourseEntity, CourseDetailEntity, TimeDetailEntity?>> {
        val sourceDates = mutableListOf<LocalDate>()
        if (shifts.none { it.fromDate == targetDate.toString() }) {
            sourceDates += targetDate
        }
        shifts.filter { it.toDate == targetDate.toString() }
            .mapNotNullTo(sourceDates) { runCatching { LocalDate.parse(it.fromDate) }.getOrNull() }

        return sourceDates.flatMap { sourceDate ->
            val sourceWeek = DateUtils.currentWeek(startDate, sourceDate)
            details.filter {
                it.day == sourceDate.dayOfWeek.value &&
                        DateUtils.detailCoversWeek(it.startWeek, it.endWeek, it.type, sourceWeek)
            }
        }
            .sortedBy { it.startNode }
            .mapNotNull { d ->
                val c = courses[d.courseId] ?: return@mapNotNull null
                Triple(c, d, timeDetails.getOrNull(d.startNode - 1))
            }
    }
}

/**
 * 小部件数据装配。
 * RemoteViewsFactory 的 onDataSetChanged 在工作线程同步执行，故用 runBlocking 桥接 Room suspend。
 */
object WidgetRepository {

    /**
     * 计算今天下一节尚未结束课程的结束时间。
     *
     * 课程列表与剩余数量都在结束时发生变化；返回值额外后移一秒，确保 Provider 重新计算时
     * [LocalTime.now] 已经越过以分钟记录的课程结束时刻。空时间、非法时间和已经结束的课程
     * 都会被跳过。
     *
     * @param context 任意 Context
     * @return 下一结束节点的毫秒时间戳；今天没有后续节点时返回 null
     */
    fun nextCourseEndEpochMillis(context: Context): Long? {
        val snapshot = snapshot(context, 0) ?: return null
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        return snapshot.coursesOfDate(today)
            .asSequence()
            .mapNotNull { (_, detail, _) ->
                val endText = WidgetCourseRowRenderer.times(snapshot, detail).second
                val endTime = runCatching {
                    LocalTime.parse(endText, TIME_FORMATTER)
                }.getOrNull() ?: return@mapNotNull null
                today.atTime(endTime).plusSeconds(1)
            }
            .filter { endDateTime -> endDateTime.isAfter(now) }
            .minOrNull()
            ?.atZone(ZoneId.systemDefault())
            ?.toInstant()
            ?.toEpochMilli()
    }

    /**
     * 装配当前课表快照。
     *
     * @param weekOffset 相对当前周的偏移（widget 翻周按钮维护）
     * @param useWidgetTable 是否优先使用小部件固定课表；提醒调度应传 `false`
     */
    fun snapshot(
        context: Context,
        weekOffset: Int = 0,
        useWidgetTable: Boolean = true,
    ): WidgetSnapshot? = runBlocking {
        val db = AppDatabase.get(context)
        val prefs = Prefs.get(context)
        // 小部件可固定一张课表；未指定时才跟随应用当前课表。
        val fixedTable = if (useWidgetTable) {
            prefs.widgetTableId.takeIf { it > 0 }?.let { db.tableDao().tableOnce(it) }
        } else {
            null
        }
        val tableId = fixedTable?.id
            ?: prefs.currentTableId.takeIf { it > 0 }
            ?: db.tableDao().firstTable()?.id
            ?: return@runBlocking null
        val table = fixedTable ?: db.tableDao().tableOnce(tableId) ?: return@runBlocking null
        val allCourses = db.courseDao().coursesOnce(tableId)
        val allDetails = db.courseDao().detailsOfTableOnce(tableId)
        val times = db.timeTableDao().timeDetailsOnce(table.timeTableId)
        val visibleNodeLimit = CourseRangePolicy.visibleNodeLimit(table.nodes)
        val detailsByCourse = allDetails.groupBy { detail -> detail.courseId }
        val validCourseIds = allCourses.asSequence()
            .filter { course ->
                CourseRangePolicy.isCourseValid(
                    detailsByCourse[course.id].orEmpty(),
                    visibleNodeLimit,
                )
            }
            .map { course -> course.id }
            .toSet()
        // 小部件与提醒共用此快照，二者都不得消费仅供课程管理修正的非法课程。
        val courses =
            allCourses.filter { course -> course.id in validCourseIds }.associateBy { it.id }
        val details = allDetails.filter { detail -> detail.courseId in validCourseIds }
        val shifts = db.scheduleShiftDao().shiftsOnce(tableId)
        val normalizedStartDate = DateUtils.mondayOfWeek(LocalDate.parse(table.startDate))
        val currentWeek = DateUtils.currentWeek(normalizedStartDate)
        // 保留 0 和超过学期上限的周次，Provider 才能正确显示“未开学/学期结束”空态。
        val displayWeek = currentWeek + weekOffset
        WidgetSnapshot(
            table.tableName,
            displayWeek,
            details,
            courses,
            times,
            normalizedStartDate,
            shifts,
            table,
        )
    }

    /** 作息时间统一使用 24 小时制分钟精度。 */
    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}
