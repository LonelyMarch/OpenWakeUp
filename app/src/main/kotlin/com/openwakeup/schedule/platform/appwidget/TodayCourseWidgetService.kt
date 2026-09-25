package com.openwakeup.schedule.platform.appwidget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.openwakeup.schedule.R
import com.openwakeup.schedule.platform.appwidget.TodayCourseWidgetService.Companion.EXTRA_DAY_OFFSET
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * “今日课程”和“近日课程”共用的现代列表数据源。
 *
 * Provider 通过 [EXTRA_DAY_OFFSET] 区分今天和明天，并设置不同 URI，避免桌面启动器复用错误的
 * RemoteViewsFactory。
 */
class TodayCourseWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(EXTRA_DAY_OFFSET, 0))

    internal class Factory(
        private val context: Context,
        private val dayOffset: Int,
        private val providedSnapshot: WidgetSnapshot? = null,
    ) : RemoteViewsFactory {

        private data class Row(
            val courseName: String,
            val room: String,
            val teacher: String,
            val time: String,
            val color: Int,
        )

        private var rows: List<Row> = emptyList()

        override fun onCreate() = Unit
        override fun onDestroy() = Unit
        override fun hasStableIds(): Boolean = false

        override fun onDataSetChanged() {
            // 今日与明日两列共享 Provider 的同一份数据，避免一次刷新重复执行三次完整查询。
            val snapshot = providedSnapshot ?: WidgetRepository.snapshot(context, 0) ?: run {
                rows = emptyList()
                return
            }
            val date = LocalDate.now().plusDays(dayOffset.toLong())
            val now = LocalTime.now()
            rows = snapshot.coursesOfDate(date).map { (course, detail, _) ->
                val (startTime, endTime) = WidgetCourseRowRenderer.times(snapshot, detail)
                Row(
                    courseName = course.courseName,
                    room = detail.room,
                    teacher = detail.teacher,
                    time = if (startTime.isNotBlank() && endTime.isNotBlank()) {
                        "$startTime - $endTime"
                    } else {
                        context.getString(
                            R.string.widget_node_range,
                            detail.startNode,
                            detail.startNode + detail.step - 1,
                        )
                    },
                    color = runCatching { Color.parseColor(course.color) }
                        .getOrDefault(Color.parseColor("#5C6BC0")),
                ) to endTime
            }.filter { (_, endTime) ->
                dayOffset != 0 || runCatching {
                    !LocalTime.parse(endTime, TIME_FORMATTER).isBefore(now)
                }.getOrDefault(true)
            }.map { it.first }
        }

        override fun getCount(): Int = rows.size

        override fun getViewAt(position: Int): RemoteViews {
            val row = rows[position]
            return RemoteViews(context.packageName, R.layout.item_today_modern_widget).apply {
                setTextViewText(R.id.tv_course_name, row.courseName)
                setTextViewText(R.id.tv_location, row.room)
                setViewVisibility(
                    R.id.tv_location,
                    if (row.room.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
                )
                setTextViewText(R.id.tv_teacher, row.teacher)
                setViewVisibility(
                    R.id.tv_teacher,
                    if (row.teacher.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
                )
                // 地点与教师同时为空时折叠整行，避免课程名和时间之间残留空白。
                setViewVisibility(
                    R.id.ll_detail,
                    if (row.room.isBlank() && row.teacher.isBlank()) android.view.View.GONE else android.view.View.VISIBLE,
                )
                setTextViewText(R.id.tv_time, row.time)
                setInt(R.id.iv_indicator, "setColorFilter", row.color)
                setOnClickFillInIntent(
                    R.id.ll_item,
                    Intent().setData(Uri.parse("openwakeup://widget/course/$dayOffset/$position")),
                )
            }
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(position: Int): Long = position.toLong()
    }

    companion object {
        /** Intent 参数：0 表示今天，1 表示明天。 */
        const val EXTRA_DAY_OFFSET = "day_offset"

        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
