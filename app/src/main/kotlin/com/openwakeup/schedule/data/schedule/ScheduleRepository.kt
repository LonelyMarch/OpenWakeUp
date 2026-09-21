package com.openwakeup.schedule.data.schedule

import android.content.Context
import androidx.room.withTransaction
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

    /** 拖拽排序后批量写回顺序（管理页与底部浮窗共用） */
    suspend fun reorderTables(tables: List<TableEntity>) {
        val changedTables = tables.mapIndexedNotNull { index, table ->
            table.takeIf { it.tableOrder != index }?.copy(tableOrder = index)
        }
        if (changedTables.isEmpty()) return
        db.withTransaction {
            tableDao.updateAll(changedTables)
        }
    }

    /**
     * 新建课表、应用默认配置，并在事务提交后切换为当前课表。
     *
     * SharedPreferences 不属于 Room 事务，必须等数据库成功提交后再更新当前课表 ID，避免插入
     * 回滚时偏好仍指向不存在的记录。
     *
     * @param name 课表名称，空值使用本地化默认名称
     * @param startDate 学期开始日期
     * @param maxWeek 学期周数
     * @return 新课表主键
     */
    suspend fun createTable(
        name: String,
        startDate: String,
        maxWeek: Int = AppDefaults.Table.MAX_WEEK,
    ): Long {
        val defaultConfigId = contextPrefsDefaultConfigId.takeIf { it > 0 }
        val id = db.withTransaction {
            val insertedId = tableDao.insert(
                TableEntity(
                    // 在数据库中直接读取最大排序值，避免为一个数字构造完整课表列表。
                    tableName = name.ifBlank { appContext.getString(R.string.unnamed_table) },
                    startDate = startDate,
                    maxWeek = maxWeek.coerceIn(1, AppDefaults.Table.MAX_SUPPORTED_WEEKS),
                    tableOrder = tableDao.maxTableOrder() + 1,
                ),
            )
            defaultConfigId?.let { sourceId ->
                applyDefaultConfigInTransaction(insertedId, sourceId)
            }
            insertedId
        }
        prefs.currentTableId = id
        return id
    }

    /**
     * 将"默认配置"源课表的外观/数据配置套用到指定课表
     * （除上课时间、课表名称、开学日期以外的全部配置一并套用，含一天节数）。
     */
    suspend fun applyDefaultConfig(targetId: Long, sourceId: Long? = null) {
        val resolvedSourceId = sourceId ?: contextPrefsDefaultConfigId.takeIf { it > 0 } ?: return
        db.withTransaction {
            applyDefaultConfigInTransaction(targetId, resolvedSourceId)
        }
    }

    /**
     * 在调用方已经建立的 Room 事务中复制默认配置。
     *
     * @param targetId 接收默认配置的课表主键
     * @param sourceId 默认配置来源课表主键
     */
    private suspend fun applyDefaultConfigInTransaction(targetId: Long, sourceId: Long) {
        val source = tableDao.tableOnce(sourceId) ?: return
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
        db.withTransaction {
            clearCoursesInTransaction(tableId)
        }
    }

    /**
     * 在调用方事务中清空课表的课程、时间段和日期调课记录。
     *
     * @param tableId 目标课表主键
     */
    private suspend fun clearCoursesInTransaction(tableId: Long) {
        detailDao.deleteDetailsOfTable(tableId)
        courseDao.deleteCoursesOfTable(tableId)
        scheduleShiftDao.deleteByTable(tableId)
    }

    /** 删除课表（级联删除课程与时间段） */
    suspend fun deleteTable(tableId: Long) {
        val selectedTableId = prefs.currentTableId
        val fallbackTableId = db.withTransaction {
            // 偏好为 0 时当前表语义是数据库首表，必须在删除前解析实际 ID。
            val effectiveCurrentId = selectedTableId.takeIf { it > 0 }
                ?: tableDao.firstTable()?.id
                ?: 0L
            val wasCurrent = effectiveCurrentId == tableId
            clearCoursesInTransaction(tableId)
            tableDao.tableOnce(tableId)?.let { tableDao.delete(it) }
            if (wasCurrent) tableDao.firstTable()?.id ?: 0L else null
        }
        // Room 提交成功后再更新进程内 StateFlow 和磁盘偏好。
        fallbackTableId?.let { prefs.currentTableId = it }
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
    ): Long = db.withTransaction {
        insertCourseInTransaction(
            tableId = tableId,
            name = name,
            color = color,
            teacher = teacher,
            room = room,
            details = details,
            credit = credit,
            note = note,
        )
    }

    /**
     * 在调用方已经建立的 Room 事务中插入一门课程及其全部时间段。
     *
     * @param tableId 所属课表主键
     * @param name 课程名称
     * @param color 课程颜色
     * @param teacher 时间段教师为空时使用的回退值
     * @param room 时间段地点为空时使用的回退值
     * @param details 待写入的时间段
     * @param credit 课程学分
     * @param note 课程备注
     * @return 新课程主键
     */
    private suspend fun insertCourseInTransaction(
        tableId: Long,
        name: String,
        color: String,
        teacher: String,
        room: String,
        details: List<CourseDetailEntity>,
        credit: Float,
        note: String,
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
        val normalizedDetails = details.map { detail ->
            // 添加课程页允许每个时间段分别填写教师和地点；公共参数只作为空值回退。
            detail.copy(
                id = 0,
                courseId = courseId,
                teacher = detail.teacher.ifBlank { teacher },
                room = detail.room.ifBlank { room },
            )
        }
        if (normalizedDetails.isNotEmpty()) {
            detailDao.insertAll(normalizedDetails)
        }
        return courseId
    }

    /** 编辑课程：整组重建时间段，并保证外部观察者只看到最终状态。 */
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
        db.withTransaction {
            val existing = courseDao.courseWithDetailsOnce(courseId) ?: return@withTransaction
            courseDao.update(
                existing.course.copy(
                    courseName = name,
                    color = color,
                    credit = credit,
                    note = note.take(300),
                ),
            )
            detailDao.deleteDetailsOfCourse(courseId)
            val normalizedDetails = details.map { detail ->
                // 添加课程页允许每个时间段分别填写教师和地点；公共参数只作为空值回退。
                detail.copy(
                    id = 0,
                    courseId = courseId,
                    teacher = detail.teacher.ifBlank { teacher },
                    room = detail.room.ifBlank { room },
                )
            }
            if (normalizedDetails.isNotEmpty()) {
                detailDao.insertAll(normalizedDetails)
            }
        }
    }

    /** 单门课程（含时间段） */
    suspend fun courseWithDetailsOnce(courseId: Long) = courseDao.courseWithDetailsOnce(courseId)

    /** 单条时间段 */
    suspend fun detailOnce(detailId: Long) = detailDao.detailOnce(detailId)

    /** 复制整门课程（同名同色同时间段插入新课程） */
    suspend fun copyCourse(tableId: Long, courseId: Long) {
        db.withTransaction {
            val source = courseDao.courseWithDetailsOnce(courseId) ?: return@withTransaction
            insertCourseInTransaction(
                tableId = tableId,
                name = source.course.courseName,
                color = source.course.color,
                teacher = source.details.firstOrNull()?.teacher ?: "",
                room = source.details.firstOrNull()?.room ?: "",
                details = source.details,
                credit = source.course.credit,
                note = source.course.note,
            )
        }
    }

    /** 删除整门课程 */
    suspend fun deleteCourse(courseId: Long) {
        db.withTransaction {
            val course = courseDao.courseWithDetailsOnce(courseId)?.course
                ?: return@withTransaction
            detailDao.deleteDetailsOfCourse(courseId)
            courseDao.delete(course)
        }
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
        return db.withTransaction {
            val detail = detailDao.detailOnce(detailId) ?: return@withTransaction null
            val start = detail.startWeek
            val end = detail.endWeek
            when {
                week < start || week > end -> null
                start == end -> {
                    detailDao.delete(detail)
                    null
                }

                week == start -> {
                    detailDao.update(detail.copy(startWeek = start + 1))
                    null
                }

                week == end -> {
                    detailDao.update(detail.copy(endWeek = end - 1))
                    null
                }

                else -> {
                    detailDao.update(detail.copy(endWeek = week - 1))
                    detailDao.insert(
                        detail.copy(id = 0, startWeek = week + 1, endWeek = end),
                    )
                }
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
        return db.withTransaction {
            val existingTables = timeTableDao.timeTablesOnce()
            val target = existingTables.firstOrNull { table -> table.id == id }
            // 仓库层保留最终安全边界，避免未来新增入口绕过界面检查后把时间表删空。
            if (existingTables.size <= 1 || target == null) return@withTransaction false

            timeTableDao.deleteDetailsOfTable(id)
            timeTableDao.deleteTimeTable(target)
            // 默认表仍在时自然得到 id=1；否则选择删除后剩余主键最小的作息表。
            val fallbackId = existingTables
                .asSequence()
                .filter { table -> table.id != id }
                .minOf { table -> table.id }
            val reboundTables = tableDao.tablesOnce()
                .filter { table -> table.timeTableId == id }
                .map { table -> table.copy(timeTableId = fallbackId) }
            if (reboundTables.isNotEmpty()) {
                tableDao.updateAll(reboundTables)
            }
            true
        }
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
