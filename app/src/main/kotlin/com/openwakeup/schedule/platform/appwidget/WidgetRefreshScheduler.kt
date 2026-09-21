package com.openwakeup.schedule.platform.appwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.openwakeup.schedule.platform.reminder.ReminderReceiver
import java.time.LocalDate
import java.time.ZoneId

/**
 * 桌面小组件一次性刷新闹钟调度器。
 *
 * 小组件只需要在跨日与今天课程结束时更新，不应使用会唤醒设备的精确闹钟。这里统一使用
 * [AlarmManager.RTC] 和非精确窗口；系统处于休眠状态时允许延迟到下次唤醒，再由日期广播提供
 * 额外保障。课前提醒仍由独立的 ReminderScheduler 使用精确唤醒闹钟。
 */
object WidgetRefreshScheduler {

    /** 次日日期刷新广播动作。 */
    const val ACTION_DATE_REFRESH = "com.openwakeup.schedule.widget.DATE_REFRESH"

    /** 今天下一节课程结束后的进度刷新动作。 */
    const val ACTION_COURSE_PROGRESS_REFRESH =
        "com.openwakeup.schedule.widget.COURSE_PROGRESS_REFRESH"

    private const val REQUEST_DATE_REFRESH = 0x7301
    private const val REQUEST_COURSE_PROGRESS_REFRESH = 0x7302
    private const val DATE_DELAY_SECONDS = 2L
    private const val DATE_WINDOW_MILLIS = 5 * 60 * 1_000L
    private const val COURSE_WINDOW_MILLIS = 60 * 1_000L

    /**
     * 根据真实实例重新建立所需闹钟。
     *
     * @param context 任意 Context
     * @param snapshot 已安装小组件的实例快照
     */
    fun reconcileScheduling(
        context: Context,
        snapshot: WidgetInstanceRegistry.Snapshot = WidgetInstanceRegistry.snapshot(context),
    ) {
        if (!snapshot.hasAny) {
            cancelAll(context)
            return
        }
        scheduleNextDateRefresh(context)
        if (snapshot.hasCourseProgressWidgets) {
            scheduleNextCourseProgressRefresh(context)
        } else {
            cancelCourseProgressRefresh(context)
        }
    }

    /** 安排次日零点后两秒的非唤醒兜底刷新。 */
    fun scheduleNextDateRefresh(context: Context) {
        val appContext = context.applicationContext
        val alarm = appContext.getSystemService(AlarmManager::class.java) ?: return
        // 新 Receiver 与旧版本 PendingIntent 的组件不同，先取消旧闹钟避免升级后保留两条链路。
        cancelLegacyDateRefresh(appContext, alarm)
        val triggerAt = LocalDate.now()
            .plusDays(1)
            .atStartOfDay()
            .plusSeconds(DATE_DELAY_SECONDS)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        alarm.setWindow(
            AlarmManager.RTC,
            triggerAt,
            DATE_WINDOW_MILLIS,
            pendingIntent(appContext, REQUEST_DATE_REFRESH, ACTION_DATE_REFRESH),
        )
    }

    /** 根据固定/当前小组件课表安排今天下一节课程结束后的非唤醒刷新。 */
    private fun scheduleNextCourseProgressRefresh(context: Context) {
        val appContext = context.applicationContext
        val alarm = appContext.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = WidgetRepository.nextCourseEndEpochMillis(appContext)
        if (triggerAt == null) {
            cancelCourseProgressRefresh(appContext)
            return
        }
        alarm.setWindow(
            AlarmManager.RTC,
            triggerAt,
            COURSE_WINDOW_MILLIS,
            pendingIntent(
                appContext,
                REQUEST_COURSE_PROGRESS_REFRESH,
                ACTION_COURSE_PROGRESS_REFRESH,
            ),
        )
    }

    /** 没有任何桌面实例时取消跨日闹钟，包括旧版本遗留的 PendingIntent。 */
    fun cancelDateRefresh(context: Context) {
        val appContext = context.applicationContext
        val alarm = appContext.getSystemService(AlarmManager::class.java) ?: return
        cancelExisting(
            appContext,
            alarm,
            REQUEST_DATE_REFRESH,
            ACTION_DATE_REFRESH,
        )
        cancelLegacyDateRefresh(appContext, alarm)
    }

    /** 取消课程结束节点刷新。 */
    private fun cancelCourseProgressRefresh(context: Context) {
        val appContext = context.applicationContext
        val alarm = appContext.getSystemService(AlarmManager::class.java) ?: return
        cancelExisting(
            appContext,
            alarm,
            REQUEST_COURSE_PROGRESS_REFRESH,
            ACTION_COURSE_PROGRESS_REFRESH,
        )
    }

    /** 取消全部小组件闹钟。 */
    private fun cancelAll(context: Context) {
        cancelDateRefresh(context)
        cancelCourseProgressRefresh(context)
    }

    /** 构造由应用内部 Receiver 接收的稳定 PendingIntent。 */
    private fun pendingIntent(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, WidgetRefreshReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** 只在 PendingIntent 已存在时取消，避免取消流程反向创建无意义对象。 */
    private fun cancelExisting(
        context: Context,
        alarm: AlarmManager,
        requestCode: Int,
        action: String,
    ) {
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, WidgetRefreshReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) ?: return
        alarm.cancel(pending)
        pending.cancel()
    }

    /** 取消升级前指向 ReminderReceiver 的同请求码跨日闹钟。 */
    private fun cancelLegacyDateRefresh(context: Context, alarm: AlarmManager) {
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_DATE_REFRESH,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_DATE_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) ?: return
        alarm.cancel(pending)
        pending.cancel()
    }
}
