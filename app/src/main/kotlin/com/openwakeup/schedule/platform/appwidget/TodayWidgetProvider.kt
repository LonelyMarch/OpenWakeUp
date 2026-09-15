package com.openwakeup.schedule.platform.appwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.core.format.AppDateFormatter
import com.openwakeup.schedule.core.util.DateUtils
import com.openwakeup.schedule.feature.schedule.ScheduleActivity
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 日视图小部件：标题区 + 当天完整课程色块列表 + 点击进入主界面。
 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateOne(context, manager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_NEXT_DAY, ACTION_BACK_TODAY -> {
                val id = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
                WidgetNavigation.setOffset(
                    context,
                    id,
                    if (intent.action == ACTION_NEXT_DAY) 1 else 0
                )
                updateOne(context, AppWidgetManager.getInstance(context), id)
            }

            AppWidgetManager.ACTION_APPWIDGET_UPDATE -> {
                val manager = AppWidgetManager.getInstance(context)
                onUpdate(
                    context,
                    manager,
                    manager.getAppWidgetIds(ComponentName(context, javaClass))
                )
            }

            else -> super.onReceive(context, intent)
        }
    }

    /** 桌面调整小部件大小后重建尺寸版本，使课程文字和图标立即采用新的缩放比例。 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        updateOne(context, appWidgetManager, appWidgetId)
    }

    /**
     * 为桌面报告的每个尺寸建立原生布局，横竖屏切换由宿主选择匹配版本。
     * 老式桌面未提供尺寸列表时，使用其最小/最大宽高推导横竖屏尺寸。
     */
    private fun updateOne(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        val options = manager.getAppWidgetOptions(appWidgetId)
        val reportedSizes = options.getParcelableArrayList(
            AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java,
        ).orEmpty().filter { it.width > 0f && it.height > 0f }
        val sizes = reportedSizes.ifEmpty {
            listOf(
                SizeF(
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 360)
                        .coerceAtLeast(1).toFloat(),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 240)
                        .coerceAtLeast(1).toFloat(),
                ),
                SizeF(
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 360)
                        .coerceAtLeast(1).toFloat(),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 240)
                        .coerceAtLeast(1).toFloat(),
                ),
            )
        }.distinct().take(16) // RemoteViews 的尺寸映射最多接受 16 个版本。
        val layouts = sizes.associateWith { size -> createViews(context, appWidgetId, size.width) }
        manager.updateAppWidget(appWidgetId, RemoteViews(layouts))
    }

    /** 按指定实例宽度创建完整小部件；缩放参数只传给课程卡，不写入设置。 */
    private fun createViews(context: Context, appWidgetId: Int, widthDp: Float): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.today_course_app_widget)
        val prefs = Prefs.get(context)
        val offset = WidgetNavigation.offset(context, appWidgetId)
        applyGlobalStyle(context, views, prefs)
        WidgetEmptyViewRenderer.apply(
            context = context,
            views = views,
            prefs = prefs,
            imageViewId = R.id.empty_image,
            textViewId = R.id.empty_text,
            text = if (offset == 1) prefs.widgetEmptyTomorrowText else prefs.widgetEmptyTodayText,
        )
        val snapshot = WidgetRepository.snapshot(context, 0) ?: run {
            views.setTextViewText(
                R.id.tv_schedule_name,
                context.getString(R.string.widget_no_table)
            )
            WidgetNavigation.bind(
                context,
                views,
                appWidgetId,
                R.id.lv_course,
                javaClass,
                ACTION_NEXT_DAY,
                ACTION_BACK_TODAY,
                offset
            )
            views.setViewVisibility(R.id.lv_course, View.GONE)
            views.setViewVisibility(android.R.id.empty, View.VISIBLE)
            return views
        }
        val today = LocalDate.now().plusDays(offset.toLong())
        views.setTextViewText(
            R.id.tv_date,
            // 切到明天时显示本地化相对词，否则按全局日期格式输出完整日期。
            if (offset == 1) context.getString(R.string.relative_tomorrow)
            else AppDateFormatter.formatFull(today, prefs.dateFormat),
        )
        views.setTextViewText(R.id.tv_schedule_name, snapshot.tableName)
        views.setTextViewText(
            R.id.tv_week,
            " | " + context.getString(
                R.string.week_num,
                DateUtils.currentWeek(snapshot.startDate, today)
            ) +
                    "    " + context.getString(WEEKDAY_RES[today.dayOfWeek.value - 1]),
        )
        renderRemainingCourseCount(context, views, snapshot)
        views.setRemoteCollectionAdapter(
            R.id.lv_course,
            TodayWidgetService.Factory(context, offset, widthDp),
        )
        views.setEmptyView(R.id.lv_course, android.R.id.empty)
        WidgetNavigation.bind(
            context, views, appWidgetId, R.id.lv_course,
            javaClass, ACTION_NEXT_DAY, ACTION_BACK_TODAY, offset
        )
        views.setOnClickPendingIntent(
            R.id.fl_course_left_count,
            PendingIntent.getActivity(
                context,
                appWidgetId,
                Intent(context, ScheduleActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return views
    }

    /**
     * 日视图底部的课程计数按钮。
     *
     * 今天尚未结束的课程优先显示；今天已经没有课程时，改为提示明日课程数。两天都无课时隐藏，
     * 以免在空视图下方留下无意义的占位条。
     */
    private fun renderRemainingCourseCount(
        context: Context,
        views: RemoteViews,
        snapshot: WidgetSnapshot,
    ) {
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

    /** 将全局背景与标题样式应用到日视图小部件。 */
    private fun applyGlobalStyle(context: Context, views: RemoteViews, prefs: Prefs) {
        val background = prefs.widgetBackground
        val backgroundBitmap = background.takeUnless { value -> value.startsWith("#") }
            ?.let { path ->
                WidgetImageStore.decodeFile(
                    path,
                    WidgetImageStore.MAX_BACKGROUND_DIMENSION
                )
            }
        val padding = (8 * context.resources.displayMetrics.density).toInt()
        when {
            !prefs.widgetShowBackground -> {
                views.setViewVisibility(R.id.iv_appwidget, View.GONE)
                views.setViewVisibility(R.id.iv_appwidget_pic_bg, View.GONE)
                views.setViewPadding(R.id.rl_appwidget, 0, 0, 0, 0)
            }

            background.startsWith("#") -> {
                val color = runCatching { Color.parseColor(background) }.getOrDefault(Color.WHITE)
                views.setViewVisibility(R.id.iv_appwidget, View.VISIBLE)
                views.setViewVisibility(R.id.iv_appwidget_pic_bg, View.GONE)
                views.setInt(R.id.iv_appwidget, "setImageAlpha", Color.alpha(color))
                views.setInt(
                    R.id.iv_appwidget,
                    "setColorFilter",
                    androidx.core.graphics.ColorUtils.setAlphaComponent(color, 255)
                )
                views.setViewPadding(R.id.rl_appwidget, padding, padding * 2, padding, 0)
            }

            backgroundBitmap != null -> {
                views.setViewVisibility(R.id.iv_appwidget, View.GONE)
                views.setViewVisibility(R.id.iv_appwidget_pic_bg, View.VISIBLE)
                views.setImageViewBitmap(R.id.iv_appwidget_pic_bg, backgroundBitmap)
                views.setViewPadding(R.id.rl_appwidget, padding, padding * 2, padding, 0)
            }

            else -> {
                val color = runCatching { Color.parseColor(prefs.widgetDefaultBackground) }
                    .getOrDefault(Color.WHITE)
                views.setViewVisibility(R.id.iv_appwidget, View.VISIBLE)
                views.setViewVisibility(R.id.iv_appwidget_pic_bg, View.GONE)
                views.setInt(R.id.iv_appwidget, "setImageAlpha", Color.alpha(color))
                views.setInt(
                    R.id.iv_appwidget,
                    "setColorFilter",
                    androidx.core.graphics.ColorUtils.setAlphaComponent(color, 255),
                )
                views.setViewPadding(R.id.rl_appwidget, padding, padding * 2, padding, 0)
            }
        }
        views.setViewVisibility(
            R.id.rl_title,
            if (prefs.widgetShowHeader) View.VISIBLE else View.GONE
        )
        views.setViewVisibility(
            R.id.tv_date,
            if (prefs.widgetShowHeader && prefs.widgetShowDate) View.VISIBLE else View.GONE,
        )
        val buttonVisibility =
            if (prefs.widgetShowHeader && prefs.widgetShowButtons) View.VISIBLE else View.GONE
        views.setViewVisibility(R.id.iv_settings, buttonVisibility)
        views.setViewVisibility(R.id.iv_back, buttonVisibility)
        views.setViewVisibility(R.id.iv_next, buttonVisibility)
        val headerColor =
            runCatching { Color.parseColor(prefs.widgetHeaderColor) }.getOrDefault(Color.DKGRAY)
        listOf(R.id.tv_date, R.id.tv_schedule_name, R.id.tv_week).forEach { id ->
            views.setTextColor(id, headerColor)
            views.setTextViewTextSize(
                id,
                TypedValue.COMPLEX_UNIT_SP,
                prefs.widgetHeaderTextSize.toFloat()
            )
        }
        views.setTextViewTextSize(
            R.id.tv_date,
            TypedValue.COMPLEX_UNIT_SP,
            prefs.widgetHeaderTextSize + 3f
        )
        views.setTextColor(R.id.empty_text, headerColor)
    }

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

        const val ACTION_NEXT_DAY = "com.openwakeup.schedule.widget.NEXT_DAY"
        const val ACTION_BACK_TODAY = "com.openwakeup.schedule.widget.BACK_TODAY"

        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
