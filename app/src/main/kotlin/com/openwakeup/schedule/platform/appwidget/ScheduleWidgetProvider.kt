package com.openwakeup.schedule.platform.appwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.core.format.AppDateFormatter
import com.openwakeup.schedule.core.util.DateUtils
import java.time.LocalDate

/**
 * 一周课表小部件：标题区（日期/课表名/周次）+ 星期表头 tv_title0..7 +
 * lv_schedule 列表（整周一张网格位图）+ iv_settings/iv_back/iv_next 交互。
 */
class ScheduleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateOne(
            context,
            manager,
            id,
            WidgetNavigation.offset(context, id)
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PREV, ACTION_NEXT -> {
                val id = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
                val offset = if (intent.action == ACTION_NEXT) 1 else 0
                WidgetNavigation.setOffset(context, id, offset)
                updateOne(context, AppWidgetManager.getInstance(context), id, offset)
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

    /** 桌面调整周视图小部件尺寸后，按新的实例宽度重新生成高清课程网格。 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        updateOne(
            context,
            appWidgetManager,
            appWidgetId,
            WidgetNavigation.offset(context, appWidgetId)
        )
    }

    private fun updateOne(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        weekOffset: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.schedule_app_widget)
        val prefs = Prefs.get(context)
        val options = manager.getAppWidgetOptions(appWidgetId)
        // AppWidgetManager 分别报告横竖屏宽度；按当前方向选择，避免网格位图被桌面二次放大。
        val widthOption =
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
            } else {
                AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
            }
        // 个别启动器会在小部件刚加入桌面时短暂返回 0；此时使用基准宽度，避免生成 1px 级位图。
        val widgetWidthDp = options.getInt(widthOption, DEFAULT_WIDGET_WIDTH_DP)
            .takeIf { width -> width > 0 }
            ?.toFloat()
            ?: DEFAULT_WIDGET_WIDTH_DP.toFloat()
        // 与日视图一致，以 360dp 为字号基准；只影响本次 RemoteViews，不回写设置页中的字号。
        val widthScale = widgetWidthDp / DEFAULT_WIDGET_WIDTH_DP.toFloat()
        val contentWidthDp =
            (widgetWidthDp - if (prefs.widgetShowBackground) 16f else 0f).coerceAtLeast(1f)
        applyGlobalStyle(context, views, prefs, widthScale)
        val snapshot = WidgetRepository.snapshot(context, weekOffset)
        if (snapshot == null) {
            WidgetNavigation.bind(
                context,
                views,
                appWidgetId,
                R.id.lv_schedule,
                javaClass,
                ACTION_NEXT,
                ACTION_PREV,
                weekOffset
            )
            views.setViewVisibility(R.id.lv_schedule, View.GONE)
            views.setViewVisibility(android.R.id.empty, View.VISIBLE)
            views.setTextViewText(
                R.id.tv_schedule_name,
                context.getString(R.string.widget_no_table)
            )
            manager.updateAppWidget(appWidgetId, views)
            return
        }
        val today = LocalDate.now()
        views.setTextViewText(
            R.id.tv_date,
            // 翻页到下周时显示本地化相对词，否则按全局日期格式输出完整日期。
            if (weekOffset == 1) context.getString(R.string.relative_next_week)
            else AppDateFormatter.formatFull(today, prefs.dateFormat),
        )
        views.setTextViewText(R.id.tv_schedule_name, snapshot.tableName)
        views.setTextViewText(
            R.id.tv_week,
            // 与日视图小组件保持一致，用竖线明确分隔左上角的课表名与周次状态。
            HEADER_SECTION_SEPARATOR + when {
                snapshot.displayWeek <= 0 -> context.getString(R.string.semester_not_start_yet)
                snapshot.displayWeek > (snapshot.config?.maxWeek
                    ?: Int.MAX_VALUE) -> context.getString(R.string.semester_ended)

                else -> context.getString(R.string.week_num, snapshot.displayWeek)
            },
        )
        // 星期表头：tv_title0 显示月份；tv_title1..7 显示本地化星期单字和当月日号。
        // 周小部件与应用内周视图遵循同一列序：第一列始终从周一开始。
        val weekStart = DateUtils.mondayOfWeek(snapshot.startDate)
            .plusWeeks((snapshot.displayWeek - 1).toLong())
        views.setTextViewText(
            R.id.tv_title0,
            weekStart.monthValue.toString() + "\n" + context.getString(R.string.month_suffix)
        )
        val titleIds = listOf(
            R.id.tv_title1, R.id.tv_title2, R.id.tv_title3, R.id.tv_title4,
            R.id.tv_title5, R.id.tv_title6, R.id.tv_title7,
        )
        titleIds.forEachIndexed { i, tid ->
            val date = weekStart.plusDays(i.toLong())
            val visible = !(i == 5 && snapshot.config?.showSat == false) &&
                    !(i == 6 && snapshot.config?.showSun == false)
            views.setViewVisibility(tid, if (visible) View.VISIBLE else View.GONE)
            val headerColor = parseColor(prefs.widgetHeaderColor, Color.BLACK)
            views.setTextColor(
                tid, if (date == today) headerColor else
                    androidx.core.graphics.ColorUtils.setAlphaComponent(headerColor, 51)
            )
            views.setTextViewText(
                tid,
                // 周列头只显示星期单字和日号，例如“一\n13”，不再重复“周”和月份。
                AppDateFormatter.weekdayNarrow(date) + "\n" + date.dayOfMonth,
            )
        }
        views.setRemoteCollectionAdapter(
            R.id.lv_schedule,
            ScheduleWidgetService.Factory(context, weekOffset, contentWidthDp, widthScale),
        )
        views.setEmptyView(R.id.lv_schedule, android.R.id.empty)
        views.setTextViewText(
            R.id.empty_text,
            when {
                snapshot.displayWeek <= 0 -> context.getString(R.string.semester_not_start_yet)
                snapshot.displayWeek > (snapshot.config?.maxWeek
                    ?: Int.MAX_VALUE) -> context.getString(R.string.semester_ended)

                weekOffset == 1 -> context.getString(R.string.widget_empty_next_week)
                else -> context.getString(R.string.widget_empty_week)
            },
        )
        WidgetNavigation.bind(
            context, views, appWidgetId, R.id.lv_schedule,
            javaClass, ACTION_NEXT, ACTION_PREV, weekOffset
        )
        manager.updateAppWidget(appWidgetId, views)
    }

    /**
     * 将全局小部件配置应用到周视图 RemoteViews。
     *
     * @param context 用于读取资源和屏幕密度的上下文
     * @param views 待更新的小部件视图
     * @param prefs 全局偏好
     * @param widthScale 当前实例宽度相对于 360dp 基准宽度的缩放比例
     */
    private fun applyGlobalStyle(
        context: Context,
        views: RemoteViews,
        prefs: Prefs,
        widthScale: Float,
    ) {
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
                val color = parseColor(background, Color.WHITE)
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
                val color = parseColor(prefs.widgetDefaultBackground, Color.WHITE)
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
        views.setViewVisibility(R.id.iv_next, buttonVisibility)
        views.setViewVisibility(R.id.iv_back, View.GONE)

        val headerColor = parseColor(prefs.widgetHeaderColor, Color.DKGRAY)
        val headerIds = listOf(
            R.id.tv_date, R.id.tv_schedule_name, R.id.tv_week,
            R.id.tv_title0, R.id.tv_title1, R.id.tv_title2, R.id.tv_title3,
            R.id.tv_title4, R.id.tv_title5, R.id.tv_title6, R.id.tv_title7,
        )
        headerIds.forEach { id ->
            views.setTextColor(id, headerColor)
            views.setTextViewTextSize(
                id,
                TypedValue.COMPLEX_UNIT_SP,
                prefs.widgetHeaderTextSize * widthScale,
            )
        }
        // 日期先保持比其他标题大 3sp 的原有层级，再整体参与宽度缩放。
        views.setTextViewTextSize(
            R.id.tv_date,
            TypedValue.COMPLEX_UNIT_SP,
            (prefs.widgetHeaderTextSize + 3f) * widthScale,
        )
        views.setTextColor(R.id.empty_text, headerColor)
        views.setTextViewTextSize(
            R.id.empty_text,
            TypedValue.COMPLEX_UNIT_SP,
            prefs.widgetHeaderTextSize * widthScale,
        )

        // 周视图没有“当天/第二天”语义；文字模式沿用周状态文案，图片模式复用自定义空视图图片。
        WidgetEmptyViewRenderer.apply(
            context = context,
            views = views,
            prefs = prefs,
            imageViewId = R.id.empty_image,
            textViewId = R.id.empty_text,
            text = viewsEmptyWeekText(context),
        )
    }

    /** 周视图样式初始化时使用的兜底空态文字，具体周状态会在数据加载后覆盖。 */
    private fun viewsEmptyWeekText(context: Context): CharSequence =
        context.getString(R.string.widget_empty_week)

    /** 安全解析颜色字符串。 */
    private fun parseColor(value: String, fallback: Int): Int =
        runCatching { Color.parseColor(value) }.getOrDefault(fallback)

    companion object {
        /** 左上角课表名称与周次状态之间使用的统一视觉分隔符。 */
        private const val HEADER_SECTION_SEPARATOR = " | "

        /** 桌面未报告实例尺寸时采用的周视图基准宽度。 */
        private const val DEFAULT_WIDGET_WIDTH_DP = 360

        const val ACTION_PREV = "com.openwakeup.schedule.widget.PREV"
        const val ACTION_NEXT = "com.openwakeup.schedule.widget.NEXT"
    }
}
