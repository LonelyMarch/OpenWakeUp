package com.openwakeup.schedule.platform.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.core.database.AppDatabase
import com.openwakeup.schedule.core.database.entity.CourseDetailEntity
import com.openwakeup.schedule.core.database.entity.CourseEntity
import com.openwakeup.schedule.core.util.DateUtils
import com.openwakeup.schedule.core.validation.CourseRangePolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

/**
 * 课表 ContentProvider：供桌面小部件等外部组件跨进程读取。
 * URI 与 OpenWakeUp 课程表的内容读取协议一致（show_table_id / course_list / next_course_list / table_list / has_init），
 * authority 为 com.openwakeup.schedule.provider。白名单机制由系统权限承担（简化）。
 */
class ScheduleContentProvider : ContentProvider() {

    private lateinit var db: AppDatabase

    override fun onCreate(): Boolean {
        db = AppDatabase.get(context!!)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = runBlocking { queryInternal(uri) }

    private suspend fun queryInternal(uri: Uri): Cursor {
        val matcher = MATCHER.match(uri)
        val cursor = MatrixCursor(arrayOf("key", "value"))
        when (matcher) {
            CODE_SHOW_TABLE_ID -> {
                val id = Prefs.get(context!!).currentTableId.takeIf { it > 0 }
                    ?: db.tableDao().firstTable()?.id ?: 0L
                cursor.addRow(arrayOf("show_table_id", id.toString()))
            }

            CODE_TABLE_LIST -> {
                // Room Flow 常驻不完结，取首帧即返回，避免 collect 挂起 query
                db.tableDao().tables().first().forEach { t ->
                    cursor.addRow(
                        arrayOf(
                            "table_${t.id}",
                            "${t.tableName}|${t.startDate}|${t.maxWeek}"
                        )
                    )
                }
            }

            CODE_COURSE_LIST -> {
                val tableId = currentTableId()
                val (courses, details) = visibleCourseData(tableId)
                courses.forEach { c ->
                    val d = details.filter { it.courseId == c.id }
                        .joinToString(";") { "${it.day},${it.startNode},${it.step},${it.startWeek},${it.endWeek},${it.type},${it.room},${it.teacher}" }
                    cursor.addRow(arrayOf("course_${c.id}", "${c.courseName}#${c.color}#$d"))
                }
            }

            CODE_NEXT_COURSE_LIST -> {
                val tableId = currentTableId()
                val table = db.tableDao().tableOnce(tableId)
                val week = table?.let {
                    DateUtils.currentWeek(LocalDate.parse(it.startDate)).coerceIn(1, it.maxWeek)
                } ?: 0
                val today = LocalDate.now().dayOfWeek.value
                val now = java.time.LocalTime.now()
                val (visibleCourses, visibleDetails) = visibleCourseData(tableId)
                val courses = visibleCourses.associateBy { it.id }
                val next = visibleDetails
                    .filter {
                        it.day == today && DateUtils.detailCoversWeek(
                            it.startWeek,
                            it.endWeek,
                            it.type,
                            week
                        ) &&
                                it.startNode >= nextNodeIndex(now)
                    }
                    .sortedBy { it.startNode }
                    .take(3)
                next.forEach { d ->
                    val c = courses[d.courseId] ?: return@forEach
                    val time = db.timeTableDao().timeDetailsOnce(table?.timeTableId ?: 1)
                        .getOrNull(d.startNode - 1)
                    cursor.addRow(
                        arrayOf(
                            "next_${d.id}",
                            "${c.courseName}|${time?.startTime ?: ""}|${d.room}|${d.teacher}|第${d.startNode}-${d.startNode + d.step - 1}节",
                        ),
                    )
                }
            }

            CODE_HAS_INIT -> cursor.addRow(
                arrayOf(
                    "has_init",
                    db.tableDao().firstTable()?.let { "true" } ?: "false"))

            else -> throw IllegalArgumentException("未知 URI: $uri")
        }
        return cursor
    }

    private suspend fun currentTableId(): Long =
        Prefs.get(context!!).currentTableId.takeIf { it > 0 } ?: db.tableDao().firstTable()?.id
        ?: 0L

    /**
     * 查询普通课程接口允许公开的合法课程及时间段。
     *
     * @param tableId 目标课表 id
     * @return 已排除非法课程的课程列表与时间段列表
     */
    private suspend fun visibleCourseData(
        tableId: Long,
    ): Pair<List<CourseEntity>, List<CourseDetailEntity>> {
        val table =
            db.tableDao().tableOnce(tableId) ?: return emptyList<CourseEntity>() to emptyList()
        val courses = db.courseDao().coursesOnce(tableId)
        val details = db.courseDao().detailsOfTableOnce(tableId)
        val visibleNodeLimit = CourseRangePolicy.visibleNodeLimit(table.nodes)
        val detailsByCourse = details.groupBy { detail -> detail.courseId }
        val validCourseIds = courses.asSequence()
            .filter { course ->
                CourseRangePolicy.isCourseValid(
                    detailsByCourse[course.id].orEmpty(),
                    visibleNodeLimit,
                )
            }
            .map { course -> course.id }
            .toSet()
        return courses.filter { course -> course.id in validCourseIds } to
                details.filter { detail -> detail.courseId in validCourseIds }
    }

    /** 当前时间所处节次序号（粗粒度：按时间表找 now 所在节，未命中取下一节序号 1） */
    private suspend fun nextNodeIndex(now: java.time.LocalTime): Int {
        // queryInternal 已位于协程上下文，直接挂起读取，避免在 runBlocking 内再次嵌套阻塞。
        val times = db.timeTableDao().timeDetailsOnce(1).sortedBy { it.node }
        val current = times.firstOrNull {
            !now.isBefore(java.time.LocalTime.parse(it.startTime)) && !now.isAfter(
                java.time.LocalTime.parse(
                    it.endTime
                )
            )
        }
        val next = times.firstOrNull { java.time.LocalTime.parse(it.startTime).isAfter(now) }
        return current?.node ?: next?.node ?: 1
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.$AUTHORITY"

    private companion object {
        const val AUTHORITY = "com.openwakeup.schedule.provider"
        const val CODE_SHOW_TABLE_ID = 1
        const val CODE_COURSE_LIST = 2
        const val CODE_NEXT_COURSE_LIST = 3
        const val CODE_TABLE_LIST = 4
        const val CODE_HAS_INIT = 5

        val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "show_table_id", CODE_SHOW_TABLE_ID)
            addURI(AUTHORITY, "course_list", CODE_COURSE_LIST)
            addURI(AUTHORITY, "next_course_list", CODE_NEXT_COURSE_LIST)
            addURI(AUTHORITY, "table_list", CODE_TABLE_LIST)
            addURI(AUTHORITY, "has_init", CODE_HAS_INIT)
        }
    }
}
