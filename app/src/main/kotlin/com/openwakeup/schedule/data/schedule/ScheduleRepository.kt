package com.openwakeup.schedule.data.schedule

import android.content.Context
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.config.AppDefaults
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.core.database.AppDatabase
import com.openwakeup.schedule.core.database.dao.CourseWithDetails
import com.openwakeup.schedule.core.database.entity.CourseDetailEntity
import com.openwakeup.schedule.core.database.entity.CourseEntity
import com.openwakeup.schedule.core.database.entity.ScheduleShiftEntity
import com.openwakeup.schedule.core.database.entity.TableEntity
import com.openwakeup.schedule.core.database.entity.TimeDetailEntity
import com.openwakeup.schedule.core.database.entity.TimeTableEntity
import com.openwakeup.schedule.core.validation.CourseRangePolicy
import com.openwakeup.schedule.platform.appwidget.RecentCourseWidgetProvider
import com.openwakeup.schedule.platform.appwidget.ScheduleWidgetProvider
import com.openwakeup.schedule.platform.appwidget.TodayCourseWidgetProvider
import com.openwakeup.schedule.platform.appwidget.TodayWidgetProvider
import com.openwakeup.schedule.platform.reminder.ReminderScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 课表仓库层：界面唯一的数据入口（协程 + Flow）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.get(context)
    private val tableDao = db.tableDao()
    private val courseDao = db.courseDao()
    private val detailDao = db.courseDetailDao()
    private val timeTableDao = db.timeTableDao()
    private val scheduleShiftDao = db.scheduleShiftDao()
    val prefs = Prefs.get(context)

    /**
     * 当前课表（未选择时回退首张表）。
     *
     * 用 flatMapLatest 挂到 [TableDao.table] 的 Room Flow 上，而不是一次性 `tableOnce`：
     * 这样改名/改外观/改周数也会重新发射，切表时也一定拿到新表实体。
     */
    val currentTable: Flow<TableEntity?> = prefs.currentTableIdFlow
        .map { id -> if (id > 0) id else tableDao.firstTable()?.id ?: 0L }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id > 0) tableDao.table(id) else flowOf(null) }

    /** 课表列表 */
    fun tables(): Flow<List<TableEntity>> = tableDao.tables()

    /** 某课表的课程（含时间段） */
    fun coursesWithDetails(tableId: Long): Flow<List<CourseWithDetails>> =
        courseDao.coursesWithDetails(tableId)

    /** 作息节次时间 */
    fun timeDetails(timeTableId: Long): Flow<List<TimeDetailEntity>> =
        timeTableDao.timeDetails(timeTableId)

    /** 持续观察单张课表的日期调课记录。 */
    fun scheduleShifts(tableId: Long): Flow<List<ScheduleShiftEntity>> =
        scheduleShiftDao.shifts(tableId)

    /** 新增一次日期调课。 */
    suspend fun addScheduleShift(tableId: Long, fromDate: String, toDate: String): Long {
        val shiftId = scheduleShiftDao.insert(
            ScheduleShiftEntity(tableId = tableId, fromDate = fromDate, toDate = toDate),
        )
        notifyCurrentTableChanged()
        return shiftId
    }

    /** 撤销一次日期调课。 */
    suspend fun undoScheduleShift(shiftId: Long) {
        scheduleShiftDao.deleteById(shiftId)
        notifyCurrentTableChanged()
    }

    suspend fun timeDetailsOnce(timeTableId: Long): List<TimeDetailEntity> =
        timeTableDao.timeDetailsOnce(timeTableId)

    /** 当前课表 id（一次性） */
    suspend fun currentTableId(): Long =
        prefs.currentTableId.takeIf { it > 0 } ?: tableDao.firstTable()?.id ?: 0L

    /** 新建课表并切换为当前 */
    /** 拖拽排序后批量写回顺序（管理页与底部浮窗共用） */
    suspend fun reorderTables(tables: List<TableEntity>) {
        tables.forEachIndexed { index, t ->
            if (t.tableOrder != index) tableDao.update(t.copy(tableOrder = index))
        }
    }

    suspend fun createTable(
        name: String,
        startDate: String,
        maxWeek: Int = AppDefaults.Table.MAX_WEEK,
    ): Long {
        val id = tableDao.insert(
            TableEntity(
                // 空名回退为本地化默认名。
                tableName = name.ifBlank { appContext.getString(R.string.unnamed_table) },
                startDate = startDate,
                maxWeek = maxWeek.coerceIn(1, AppDefaults.Table.MAX_SUPPORTED_WEEKS),
                tableOrder = tableDao.tables().first().size,
            ),
        )
        applyDefaultConfig(id)
        prefs.currentTableId = id
        return id
    }

    /**
     * 将"默认配置"源课表的外观/数据配置套用到指定课表
     * （除上课时间、课表名称、开学日期以外的全部配置一并套用，含一天节数）。
     */
    suspend fun applyDefaultConfig(targetId: Long, sourceId: Long? = null) {
        val source = sourceId?.let { tableDao.tableOnce(it) }
            ?: run {
                val prefId = contextPrefsDefaultConfigId
                if (prefId <= 0) return
                tableDao.tableOnce(prefId) ?: return
            }
        val target = tableDao.tableOnce(targetId) ?: return
        if (source.id == target.id) return
        tableDao.update(
            target.copy(
                nodes = source.nodes.coerceIn(1, AppDefaults.Table.MAX_SUPPORTED_NODES),
                maxWeek = source.maxWeek.coerceIn(1, AppDefaults.Table.MAX_SUPPORTED_WEEKS),
                background = source.background,
                textColor = source.textColor,
                courseTextColor = source.courseTextColor,
                headerTextSize = source.headerTextSize,
                showTimeBar = source.showTimeBar,
                showSat = source.showSat,
                showSun = source.showSun,
                showOtherWeekCourse = source.showOtherWeekCourse,
                showGrid = source.showGrid,
                itemHeight = source.itemHeight,
                itemRadius = source.itemRadius,
                itemAlpha = source.itemAlpha,
                itemTextSize = source.itemTextSize,
                textColorCompose = source.textColorCompose,
                strokeColor = source.strokeColor,
                strokeColorCompose = source.strokeColorCompose,
                useDottedLine = source.useDottedLine,
                otherWeekCourseAlpha = source.otherWeekCourseAlpha,
                itemCenterHorizontal = source.itemCenterHorizontal,
                itemCenterVertical = source.itemCenterVertical,
                showTime = source.showTime,
                showLocation = source.showLocation,
                showRoomPrefix = source.showRoomPrefix,
                showTeacher = source.showTeacher,
            )
        )
    }

    private val contextPrefsDefaultConfigId: Long
        get() = appContext.getSharedPreferences(
            "config", Context.MODE_PRIVATE
        ).getLong("default_config_id", 0L)

    /** 清空课表全部课程（先删各课程时间段再删课程） */
    suspend fun clearCourses(tableId: Long) {
        courseDao.coursesOnce(tableId).forEach { course ->
            detailDao.deleteDetailsOfCourse(course.id)
        }
        courseDao.deleteCoursesOfTable(tableId)
        scheduleShiftDao.deleteByTable(tableId)
    }

    /** 删除课表（级联删除课程与时间段） */
    suspend fun deleteTable(tableId: Long) {
        // 先判定是否删的是当前表（含"未选择→回退首张"的情形），否则删完就查不出来了
        val wasCurrent = currentTableId() == tableId
        courseDao.coursesOnce(tableId).forEach { course ->
            detailDao.deleteDetailsOfCourse(course.id)
        }
        courseDao.deleteCoursesOfTable(tableId)
        scheduleShiftDao.deleteByTable(tableId)
        tableDao.tableOnce(tableId)?.let { tableDao.delete(it) }
        // 显式落到剩余首张表：置 0 在"原本就是 0（回退首张）"时不会触发 StateFlow 重发
        if (wasCurrent) prefs.currentTableId = tableDao.firstTable()?.id ?: 0L
    }

    /** 切换当前课表 */
    suspend fun switchTable(tableId: Long) {
        if (prefs.currentTableId == tableId) return
        prefs.currentTableId = tableId
        // 切表后同步刷新小组件与课前提醒（两者都按 current_table_id 取数）
        notifyCurrentTableChanged()
    }

    /** 小组件 / 课前提醒重新按新的当前表取数 */
    private suspend fun notifyCurrentTableChanged() {
        listOf(
            ScheduleWidgetProvider::class.java,
            TodayCourseWidgetProvider::class.java,
            RecentCourseWidgetProvider::class.java,
            TodayWidgetProvider::class.java,
        ).forEach { provider ->
            appContext.sendBroadcast(
                android.content.Intent(appContext, provider)
                    .setAction(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE),
            )
        }
        // rearrange 内部经 WidgetRepository.snapshot 同步读库（runBlocking），不能留在主线程
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ReminderScheduler.rearrange(appContext)
        }
    }

    /** 单张课表（一次性） */
    suspend fun tableOnce(tableId: Long): TableEntity? = tableDao.tableOnce(tableId)

    /**
     * 更新课表配置（样式、起始日期和数据范围等）。
     *
     * 仓库层作为最终边界统一限制 48 周和每天 24 节，避免导入、默认配置或未来新增入口绕过
     * 设置页的输入范围。课程时间段原值不会在这里截断，越界课程由 [CourseRangePolicy] 隐藏。
     */
    suspend fun updateTable(table: TableEntity) = tableDao.update(
        table.copy(
            maxWeek = table.maxWeek.coerceIn(1, AppDefaults.Table.MAX_SUPPORTED_WEEKS),
            nodes = table.nodes.coerceIn(1, AppDefaults.Table.MAX_SUPPORTED_NODES),
        ),
    )

    /** 新建课程（1 课程 + N 时间段） */
    suspend fun addCourse(
        tableId: Long,
        name: String,
        color: String,
        teacher: String,
        room: String,
        details: List<CourseDetailEntity>,
        credit: Float = 0f,
        note: String = "",
    ): Long {
        val courseId = courseDao.insert(
            CourseEntity(
                tableId = tableId,
                courseName = name,
                color = color,
                credit = credit,
                note = note.take(300),
            ),
        )
        detailDao.insertAll(
            details.map {
                // 添加课程页允许每个时间段分别填写教师和地点；公共参数只作为空值回退。
                it.copy(
                    id = 0,
                    courseId = courseId,
                    teacher = it.teacher.ifBlank { teacher },
                    room = it.room.ifBlank { room },
                )
            },
        )
        return courseId
    }

    /** 编辑课程：整组重建时间段 */
    suspend fun updateCourse(
        courseId: Long,
        name: String,
        color: String,
        teacher: String,
        room: String,
        details: List<CourseDetailEntity>,
        credit: Float = 0f,
        note: String = "",
    ) {
        courseDao.courseWithDetailsOnce(courseId)?.let { cwd ->
            courseDao.update(
                cwd.course.copy(
                    courseName = name,
                    color = color,
                    credit = credit,
                    note = note.take(300),
                ),
            )
            detailDao.deleteDetailsOfCourse(courseId)
            detailDao.insertAll(
                details.map {
                    it.copy(
                        id = 0,
                        courseId = courseId,
                        teacher = it.teacher.ifBlank { teacher },
                        room = it.room.ifBlank { room },
                    )
                },
            )
        }
    }

    /** 单门课程（含时间段） */
    suspend fun courseWithDetailsOnce(courseId: Long) = courseDao.courseWithDetailsOnce(courseId)

    /** 单条时间段 */
    suspend fun detailOnce(detailId: Long) = detailDao.detailOnce(detailId)

    /** 复制整门课程（同名同色同时间段插入新课程） */
    suspend fun copyCourse(tableId: Long, courseId: Long) {
        courseWithDetailsOnce(courseId)?.let { cwd ->
            addCourse(
                tableId,
                cwd.course.courseName,
                cwd.course.color,
                cwd.details.firstOrNull()?.teacher ?: "",
                cwd.details.firstOrNull()?.room ?: "",
                cwd.details,
                cwd.course.credit,
                cwd.course.note,
            )
        }
    }

    /** 删除整门课程 */
    suspend fun deleteCourse(courseId: Long) {
        detailDao.deleteDetailsOfCourse(courseId)
        courseDao.courseWithDetailsOnce(courseId)?.let { courseDao.delete(it.course) }
    }

    /**
     * 删除本周单节：跨周课程拆记录。
     * - 区间 [start, end]（type：0 每周 / 1 单周 / 2 双周）中删除第 [week] 周该节；
     * - 目标周为边界：收缩边界；
     * - 目标周在中间：拆为两条（每周类型拆两段；单双周用 ±1 收缩，配合奇偶过滤与 ±2 收缩语义等价）。
     *
     * @return 拆分产生的新时间段 id（未拆分返回 null）
     */
    suspend fun deleteDetailThisWeek(detailId: Long, week: Int): Long? {
        val detail = detailDao.detailOnce(detailId) ?: return null
        val start = detail.startWeek
        val end = detail.endWeek
        return when {
            week < start || week > end -> null
            start == end -> {
                detailDao.delete(detail); null
            }

            week == start -> {
                detailDao.update(detail.copy(startWeek = start + 1)); null
            }

            week == end -> {
                detailDao.update(detail.copy(endWeek = end - 1)); null
            }

            else -> {
                detailDao.update(detail.copy(endWeek = week - 1))
                detailDao.insert(detail.copy(id = 0, startWeek = week + 1, endWeek = end))
            }
        }
    }

    /** 一次性取课表全部课程 */
    suspend fun coursesOnce(tableId: Long): List<CourseEntity> = courseDao.coursesOnce(tableId)

    /** 一次性取课表全部时间段 */
    suspend fun detailsOnce(tableId: Long): List<CourseDetailEntity> =
        courseDao.detailsOfTableOnce(tableId)

    /** 作息表列表 */
    fun timeTables(): Flow<List<TimeTableEntity>> = timeTableDao.timeTables()

    suspend fun timeTableOnce(id: Long) = timeTableDao.timeTableOnce(id)

    suspend fun updateTimeTable(table: TimeTableEntity) = timeTableDao.updateTimeTable(table)

    /**
     * 删除指定时间表，并保证数据库中始终至少保留一张时间表。
     *
     * @param id 待删除时间表的主键
     * @return 删除成功返回 true；目标不存在或当前仅剩一张时间表时返回 false
     */
    suspend fun deleteTimeTable(id: Long): Boolean {
        val existingTables = timeTableDao.timeTables().first()
        // 仓库层保留最终安全边界，避免未来新增入口绕过界面检查后把时间表删空。
        if (existingTables.size <= 1 || existingTables.none { it.id == id }) return false
        timeTableDao.deleteDetailsOfTable(id)
        timeTableDao.timeTableOnce(id)?.let { timeTableDao.deleteTimeTable(it) }
        // 解除悬空引用：绑定被删作息表的课表改绑剩余作息表中最早的一张；
        // 默认表（id=1）仍在时即落到默认表，否则落到删除后排在最前的作息表。
        val fallback = timeTableDao.timeTables().first().minOfOrNull { it.id } ?: 1L
        tableDao.tables().first()
            .filter { it.timeTableId == id }
            .forEach { tableDao.update(it.copy(timeTableId = fallback)) }
        return true
    }

    /**
     * 新建作息表，超过系统 24 节上限的时间行不会写入。
     *
     * @param name 新作息表名称
     * @param times 按节次顺序排列的起止时间
     * @return 新作息表 id
     */
    suspend fun createTimeTable(name: String, times: List<Pair<String, String>>): Long =
        timeTableDao.insertTimeTableWithDetails(
            // 新建作息沿用默认配置：固定 45 分钟；用户仍可在编辑页关闭该开关。
            TimeTableEntity(
                name = name,
                sameDuration = AppDefaults.Timetable.SAME_DURATION,
                durationMinutes = AppDefaults.Timetable.DURATION_MINUTES,
            ),
            times.take(AppDefaults.Table.MAX_SUPPORTED_NODES).mapIndexed { i, (s, e) ->
                TimeDetailEntity(timeTableId = 0, node = i + 1, startTime = s, endTime = e)
            },
        )

    /**
     * 整表重建节次时间，仓库层最终限制为系统允许的 24 节。
     *
     * @param timeTableId 待更新的作息表 id
     * @param times 按节次顺序排列的起止时间
     */
    suspend fun replaceTimeDetails(timeTableId: Long, times: List<Pair<String, String>>) =
        timeTableDao.replaceDetails(
            timeTableId,
            times.take(AppDefaults.Table.MAX_SUPPORTED_NODES).mapIndexed { i, (s, e) ->
                TimeDetailEntity(timeTableId = 0, node = i + 1, startTime = s, endTime = e)
            },
        )
}
