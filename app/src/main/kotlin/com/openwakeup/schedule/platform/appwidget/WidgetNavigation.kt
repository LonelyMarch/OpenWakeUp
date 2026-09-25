package com.openwakeup.schedule.platform.appwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.feature.schedule.ScheduleActivity
import com.openwakeup.schedule.feature.settings.widget.WidgetSettingsActivity
import java.time.LocalDate

/** 经典小部件的按钮协议：向后查看一次、返回当前、打开样式设置。 */
object WidgetNavigation {
    const val EXTRA_OFFSET = "widget_offset"

    /** 每个桌面实例独立保存翻页状态，日期切换后回到今天/本周。 */
    fun offset(context: Context, id: Int): Int {
        val prefs = context.getSharedPreferences("widget_navigation", Context.MODE_PRIVATE)
        return if (prefs.getString("date_$id", "") == LocalDate.now().toString()) {
            prefs.getInt("offset_$id", 0).coerceIn(0, 1)
        } else 0
    }

    /** 保存单个小部件是否正在显示下一天或下一周。 */
    fun setOffset(context: Context, id: Int, offset: Int) {
        context.getSharedPreferences("widget_navigation", Context.MODE_PRIVATE).edit()
            .putString("date_$id", LocalDate.now().toString())
            .putInt("offset_$id", offset.coerceIn(0, 1)).apply()
    }

    /**
     * 删除已经移出桌面的实例翻页状态，避免 SharedPreferences 长期积累无效 ID。
     *
     * @param context 任意 Context
     * @param ids 已被桌面删除的小组件实例 ID
     */
    fun removeOffsets(context: Context, ids: IntArray) {
        if (ids.isEmpty()) return
        context.getSharedPreferences("widget_navigation", Context.MODE_PRIVATE).edit().apply {
            ids.forEach { id ->
                remove("date_$id")
                remove("offset_$id")
            }
        }.apply()
    }

    /** 配置经典小部件的主区域、设置按钮和翻页按钮。 */
    fun bind(
        context: Context, views: RemoteViews, id: Int, listId: Int,
        provider: Class<*>, nextAction: String, backAction: String, offset: Int
    ) {
        val prefs = Prefs.get(context)
        val settings = activity(context, id, WidgetSettingsActivity::class.java)
        val content = if (!prefs.widgetShowHeader || !prefs.widgetShowButtons) settings
        else activity(context, id, ScheduleActivity::class.java)
        views.setOnClickPendingIntent(R.id.rl_appwidget, content)
        views.setPendingIntentTemplate(listId, content)
        views.setOnClickPendingIntent(R.id.iv_settings, settings)
        views.setOnClickPendingIntent(R.id.iv_next, broadcast(context, id, provider, nextAction))
        views.setOnClickPendingIntent(R.id.iv_back, broadcast(context, id, provider, backAction))
        val buttons = prefs.widgetShowHeader && prefs.widgetShowButtons
        views.setViewVisibility(R.id.iv_settings, if (buttons) View.VISIBLE else View.GONE)
        // 使用 INVISIBLE 保留设置按钮相对 next 按钮的定位，返回箭头占据同一位置。
        views.setViewVisibility(
            R.id.iv_next,
            if (!buttons) View.GONE else if (offset == 0) View.VISIBLE else View.INVISIBLE
        )
        views.setViewVisibility(
            R.id.iv_back,
            if (!buttons) View.GONE else if (offset == 1) View.VISIBLE else View.INVISIBLE
        )
        val color = runCatching { android.graphics.Color.parseColor(prefs.widgetHeaderColor) }
            .getOrDefault(android.graphics.Color.DKGRAY)
        listOf(R.id.iv_settings, R.id.iv_next, R.id.iv_back).forEach {
            views.setInt(it, "setColorFilter", color)
        }
    }

    /** 内容点击为显式 Activity Intent，不接收外部组件注入。 */
    private fun activity(context: Context, id: Int, target: Class<*>): PendingIntent =
        PendingIntent.getActivity(
            context, id, Intent(context, target),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** 广播带有实例 id，点击一个小部件不会翻动其他小部件。 */
    private fun broadcast(
        context: Context,
        id: Int,
        target: Class<*>,
        action: String
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context, id, Intent(context, target).setAction(action)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}
