package com.openwakeup.schedule.platform.appwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import com.openwakeup.schedule.platform.reminder.ReminderReceiver
import java.time.LocalDate
import java.time.ZoneId

/**
 * 四类桌面小部件的跨天刷新调度器。
 *
 * 系统 `DATE_CHANGED` 广播是第一层保障；次日零点后的精确闹钟是第二层保障，避免部分厂商
 * 在省电模式下延迟日期广播。每次触发后都会安排下一次刷新。
 */
object WidgetRefreshScheduler {

    /** 次日零点刷新广播动作。 */
    const val ACTION_DATE_REFRESH = "com.openwakeup.schedule.widget.DATE_REFRESH"

    private const val REQUEST_DATE_REFRESH = 0x7301

    /** 刷新所有已添加的小部件，并重新安排下一个自然日刷新。 */
    fun refreshAndSchedule(context: Context) {
        refreshAll(context)
        scheduleNextDateRefresh(context)
    }

    /** 向四个 Provider 发送显式更新广播。 */
    fun refreshAll(context: Context) {
        val appContext = context.applicationContext
        listOf(
            ScheduleWidgetProvider::class.java,
            TodayCourseWidgetProvider::class.java,
            RecentCourseWidgetProvider::class.java,
            TodayWidgetProvider::class.java,
        ).forEach { provider ->
            appContext.sendBroadcast(
                Intent(appContext, provider).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE),
            )
        }
    }

    /** 安排次日零点后两秒的兜底刷新。 */
    fun scheduleNextDateRefresh(context: Context) {
        val appContext = context.applicationContext
        val alarm = appContext.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = LocalDate.now()
            .plusDays(1)
            .atStartOfDay()
            .plusSeconds(2)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val pending = PendingIntent.getBroadcast(
            appContext,
            REQUEST_DATE_REFRESH,
            Intent(appContext, ReminderReceiver::class.java).setAction(ACTION_DATE_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // minSdk 为 33；精确闹钟未获授权时必须退化，否则调用会抛出 SecurityException。
        if (alarm.canScheduleExactAlarms()) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }
}
