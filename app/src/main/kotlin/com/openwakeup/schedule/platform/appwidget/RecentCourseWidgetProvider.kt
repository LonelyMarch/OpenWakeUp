package com.openwakeup.schedule.platform.appwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
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

/** “近日课程”小部件：并排显示今天与明天的课程。 */
class RecentCourseWidgetProvider : BaseScheduleWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateOne(context, manager, it) }
    }

    /** 更新单个近日课程小部件。 */
    private fun updateOne(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.today_list_app_widget)
        val snapshot = WidgetRepository.snapshot(context, 0)
        val today = LocalDate.now()
        val prefs = Prefs.get(context)
        views.setTextViewText(
            R.id.tv_schedule_name,
            snapshot?.tableName ?: context.getString(R.string.widget_no_table)
        )
        // 标题只显示月和日，但分隔符继续遵循全局日期格式设置。
        views.setTextViewText(R.id.tv_date, AppDateFormatter.formatCompact(today, prefs.dateFormat))
        views.setTextViewText(
            R.id.tv_week_count,
            snapshot?.let { context.getString(R.string.week_num, it.displayWeek) } ?: "")
        views.setTextViewText(R.id.tv_week, weekdayName(context, today.dayOfWeek.value))

        views.setRemoteCollectionAdapter(
            R.id.lv_course,
            TodayCourseWidgetService.Factory(
                context = context,
                dayOffset = 0,
                providedSnapshot = snapshot,
            ),
        )
        views.setRemoteCollectionAdapter(
            R.id.lv_course_next_day,
            TodayCourseWidgetService.Factory(
                context = context,
                dayOffset = 1,
                providedSnapshot = snapshot,
            ),
        )
        views.setEmptyView(R.id.lv_course, R.id.empty)
        views.setEmptyView(R.id.lv_course_next_day, R.id.empty_next_day)
        WidgetEmptyViewRenderer.apply(
            context = context,
            views = views,
            prefs = prefs,
            imageViewId = R.id.empty_image_today,
            textViewId = R.id.empty_text_today,
            text = prefs.widgetEmptyTodayText,
        )
        WidgetEmptyViewRenderer.apply(
            context = context,
            views = views,
            prefs = prefs,
            imageViewId = R.id.empty_image_next_day,
            textViewId = R.id.empty_text_next_day,
            text = prefs.widgetEmptyTomorrowText,
        )
        val mainIntent = PendingIntent.getActivity(
            context,
            appWidgetId,
            Intent(context, ScheduleActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setPendingIntentTemplate(R.id.lv_course, mainIntent)
        views.setPendingIntentTemplate(R.id.lv_course_next_day, mainIntent)
        views.setOnClickPendingIntent(android.R.id.background, mainIntent)
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
        listOf(
            R.id.tv_schedule_name,
            R.id.tv_date,
            R.id.tv_week_count,
            R.id.tv_week
        ).forEach { id ->
            views.setTextColor(id, headerColor)
            views.setTextViewTextSize(
                id,
                TypedValue.COMPLEX_UNIT_SP,
                prefs.widgetHeaderTextSize.toFloat()
            )
        }
        // 星期使用与全局设置分组标题相同的主题主色，其他三项继续使用小部件标题色。
        views.setTextColor(R.id.tv_week, ContextCompat.getColor(context, R.color.md_theme_primary))
        manager.updateAppWidget(appWidgetId, views)
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
    }

}
