package com.openwakeup.schedule.platform.appwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.core.format.AppDateFormatter
import com.openwakeup.schedule.feature.schedule.ScheduleActivity
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** “今日课程”小部件：按时间顺序显示当天课程的紧凑列表。 */
class TodayCourseWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateOne(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, javaClass)).forEach {
                updateOne(context, manager, it)
            }
        }
    }

    /** 更新单个今日课程小部件。 */
    private fun updateOne(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.today_list_app_widget_compact)
        val snapshot = WidgetRepository.snapshot(context, 0)
        val today = LocalDate.now()
        val prefs = Prefs.get(context)
        views.setTextViewText(
            R.id.tv_schedule_name,
            snapshot?.tableName ?: context.getString(R.string.widget_no_table)
        )
        // 标题只显示月和日，但分隔符继续遵循全局日期格式设置。
        views.setTextViewText(R.id.tv_date, AppDateFormatter.formatCompact(today, prefs.dateFormat))
        views.setTextViewText(R.id.tv_week, weekdayName(context, today.dayOfWeek.value))

        views.setRemoteCollectionAdapter(
            R.id.lv_course,
            TodayCourseWidgetService.Factory(context, 0),
        )
        views.setEmptyView(R.id.lv_course, android.R.id.empty)
        WidgetEmptyViewRenderer.apply(
            context = context,
            views = views,
            prefs = prefs,
            imageViewId = R.id.empty_image_compact,
            textViewId = R.id.empty_text_compact,
            text = prefs.widgetEmptyTodayText,
        )
        val mainIntent = PendingIntent.getActivity(
            context,
            appWidgetId,
            Intent(context, ScheduleActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setPendingIntentTemplate(R.id.lv_course, mainIntent)
        views.setOnClickPendingIntent(android.R.id.background, mainIntent)
        views.setOnClickPendingIntent(R.id.fl_course_left_count, mainIntent)
        renderRemainingCourseCount(context, views, snapshot)
        views.setViewVisibility(
            R.id.rl_title,
            if (prefs.widgetShowHeader) View.VISIBLE else View.GONE,
        )
        views.setViewVisibility(
            R.id.tv_date,
            if (prefs.widgetShowHeader && prefs.widgetShowDate) View.VISIBLE else View.GONE,
        )
        val headerColor =
            runCatching { Color.parseColor(prefs.widgetHeaderColor) }.getOrDefault(Color.DKGRAY)
        listOf(R.id.tv_schedule_name, R.id.tv_date, R.id.tv_week).forEach { id ->
            views.setTextColor(id, headerColor)
            views.setTextViewTextSize(
                id,
                TypedValue.COMPLEX_UNIT_SP,
                prefs.widgetHeaderTextSize.toFloat()
            )
        }
        // 星期使用与全局设置分组标题相同的主题主色，浅色和暗色资源会自动切换。
        views.setTextColor(R.id.tv_week, ContextCompat.getColor(context, R.color.md_theme_primary))
        manager.updateAppWidget(appWidgetId, views)
    }

    /**
     * 设置紧凑今日小部件底部的剩余课程提示。
     *
     * @param snapshot 当前课表快照；没有课表时直接隐藏提示条
     */
    private fun renderRemainingCourseCount(
        context: Context,
        views: RemoteViews,
        snapshot: WidgetSnapshot?,
    ) {
        if (snapshot == null) {
            views.setViewVisibility(R.id.fl_course_left_count, View.GONE)
            return
        }
        val now = LocalTime.now()
        val todayRemaining = snapshot.coursesOfDate(LocalDate.now()).count { (_, detail, _) ->
            val endTime = WidgetCourseRowRenderer.times(snapshot, detail).second
            runCatching { !LocalTime.parse(endTime, TIME_FORMATTER).isBefore(now) }.getOrDefault(
                true
            )
        }
        val tomorrowCount = snapshot.coursesOfDate(LocalDate.now().plusDays(1)).size
        when {
            todayRemaining > 0 -> {
                views.setViewVisibility(R.id.fl_course_left_count, View.VISIBLE)
                views.setTextViewText(
                    R.id.tv_course_left_count,
                    context.resources.getQuantityString(
                        R.plurals.widget_today_course_left,
                        todayRemaining,
                        todayRemaining,
                    ),
                )
            }

            tomorrowCount > 0 -> {
                views.setViewVisibility(R.id.fl_course_left_count, View.VISIBLE)
                views.setTextViewText(
                    R.id.tv_course_left_count,
                    context.resources.getQuantityString(
                        R.plurals.widget_next_day_course_left,
                        tomorrowCount,
                        tomorrowCount,
                    ),
                )
            }

            else -> views.setViewVisibility(R.id.fl_course_left_count, View.GONE)
        }
    }

    /** 将 ISO 星期值转换为当前语言的星期短名。 */
    private fun weekdayName(context: Context, day: Int): String =
        context.getString(WEEKDAY_RES[day - 1])

    companion object {
        /** 星期短名资源 id（RemoteViews 进程显式按当前语言读取，避免硬编码中文）。 */
        private val WEEKDAY_RES = intArrayOf(
            R.string.widget_weekday_short_1,
            R.string.widget_weekday_short_2,
            R.string.widget_weekday_short_3,
            R.string.widget_weekday_short_4,
            R.string.widget_weekday_short_5,
            R.string.widget_weekday_short_6,
            R.string.widget_weekday_short_7,
        )

        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
