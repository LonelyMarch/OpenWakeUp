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
 * 小组件日期和课程进度属于用户直接可见状态，即使应用进程已经退出，也需要由系统通过显式
 * [PendingIntent] 拉起 [WidgetRefreshReceiver] 完成刷新。精确闹钟权限可用时采用精确唤醒；
 * 权限不可用或在调用期间被撤销时退化为非精确唤醒，避免因普通非唤醒闹钟长期停留在旧日期。
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

    /** 安排次日零点后两秒的进程外唤醒刷新。 */
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
        scheduleWakeupAlarm(
            alarm,
            triggerAt,
            pendingIntent(appContext, REQUEST_DATE_REFRESH, ACTION_DATE_REFRESH),
        )
    }

    /** 根据固定/当前小组件课表安排今天下一节课程结束后的进程外唤醒刷新。 */
    private fun scheduleNextCourseProgressRefresh(context: Context) {
        val appContext = context.applicationContext
        val alarm = appContext.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = WidgetRepository.nextCourseEndEpochMillis(appContext)
        if (triggerAt == null) {
            cancelCourseProgressRefresh(appContext)
            return
        }
        scheduleWakeupAlarm(
            alarm,
            triggerAt,
            pendingIntent(
                appContext,
                REQUEST_COURSE_PROGRESS_REFRESH,
                ACTION_COURSE_PROGRESS_REFRESH,
            ),
        )
    }

    /**
     * 登记允许在设备休眠期间执行的一次性唤醒闹钟。
     *
     * [AlarmManager.canScheduleExactAlarms] 的结果可能在检查后立即因用户或系统撤权而失效，
     * 因此精确调用仍需捕获 [SecurityException]，并退化到不依赖精确闹钟权限的
     * [AlarmManager.setAndAllowWhileIdle]。两种路径都使用 [AlarmManager.RTC_WAKEUP]，确保
     * 应用进程不存在时系统仍可发送显式广播。
     *
     * @param alarm 系统闹钟服务
     * @param triggerAt 按系统墙上时钟计算的触发时间戳
     * @param operation 指向应用内部刷新接收器的显式广播 PendingIntent
     */
    private fun scheduleWakeupAlarm(
        alarm: AlarmManager,
        triggerAt: Long,
        operation: PendingIntent,
    ) {
        if (alarm.canScheduleExactAlarms()) {
            try {
                alarm.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    operation,
                )
                return
            } catch (_: SecurityException) {
                // 权限可能在检查与登记之间被撤销；继续使用无需精确权限的休眠唤醒路径。
            }
        }
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
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
