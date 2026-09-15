package com.openwakeup.schedule.platform.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.core.util.DateUtils
import com.openwakeup.schedule.platform.appwidget.WidgetRefreshScheduler
import com.openwakeup.schedule.platform.appwidget.WidgetRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 课前提醒调度：
 * - 每天 0 点后重排当日课程闹钟（setExactAndAllowWhileIdle）；
 * - 开机/时间变更广播重排；
 * - 闹钟触发 → 发布 reminder 渠道通知；
 * - 提前量来自设置（默认 10 分钟，0=关闭）。
 */
object ReminderScheduler {

    const val CHANNEL_ID = "reminder"
    const val ACTION_REMIND = "com.openwakeup.schedule.reminder.REMIND"
    const val ACTION_REARRANGE = "com.openwakeup.schedule.reminder.REARRANGE"
    private const val REQUEST_BASE = 0x5100
    private const val MAX_DAILY_REMINDERS = 128

    /**
     * 重排当日提醒：清除旧闹钟，为当天每节"有开始时间"的课程设置一个精确闹钟。
     */
    fun rearrange(context: Context) {
        val appContext = context.applicationContext
        val alarm = appContext.getSystemService<AlarmManager>() ?: return
        val prefs = Prefs.get(appContext)
        val leadMinutes = prefs.reminderMinutes
        // 先清除可能由旧提前量创建的提醒，关闭功能时不会遗留已排定通知。
        repeat(MAX_DAILY_REMINDERS) { offset ->
            val pending = PendingIntent.getBroadcast(
                appContext,
                REQUEST_BASE + offset,
                Intent(appContext, ReminderReceiver::class.java).setAction(ACTION_REMIND),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
            )
            if (pending != null) alarm.cancel(pending)
        }
        if (leadMinutes <= 0) return

        // 提醒始终跟随应用当前课表，不受“小部件固定显示课表”影响。
        val snapshot = WidgetRepository.snapshot(appContext, 0, useWidgetTable = false) ?: return
        val today = LocalDate.now()
        val day = today.dayOfWeek.value
        val todayCourses = snapshot.details
            .filter {
                it.day == day && DateUtils.detailCoversWeek(
                    it.startWeek,
                    it.endWeek,
                    it.type,
                    snapshot.displayWeek
                )
            }
            .sortedBy { it.startNode }
        var reqCode = REQUEST_BASE
        val now = LocalDateTime.now()
        for (detail in todayCourses) {
            val time = snapshot.timeDetails.getOrNull(detail.startNode - 1) ?: continue
            val start = LocalTime.parse(time.startTime, DateTimeFormatter.ofPattern("HH:mm"))
            val remindAt = start.minusMinutes(leadMinutes.toLong())
            val trigger = today.atTime(remindAt)
            if (!trigger.isAfter(now)) continue
            val course = snapshot.courses[detail.courseId] ?: continue
            val intent = Intent(appContext, ReminderReceiver::class.java).setAction(ACTION_REMIND)
                .putExtra("courseName", course.courseName)
                .putExtra("room", detail.room)
                .putExtra("startNode", detail.startNode)
                .putExtra("startTime", time.startTime)
            val pending = PendingIntent.getBroadcast(
                appContext, reqCode++, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            alarm.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                trigger.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                pending,
            )
        }
        // 明天 0 点再次重排
        val nextMidnight = today.plusDays(1).atStartOfDay()
        val rearrange = PendingIntent.getBroadcast(
            appContext, 0x7FFF,
            Intent(appContext, ReminderReceiver::class.java).setAction(ACTION_REARRANGE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarm.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextMidnight.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            rearrange,
        )
    }

    /**
     * 发布课前提醒通知。
     */
    fun notifyCourse(
        context: Context,
        courseName: String,
        room: String,
        startNode: Int,
        startTime: String
    ) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_reminder_name),
                NotificationManager.IMPORTANCE_HIGH
            ),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(
                context.getString(
                    R.string.reminder_text,
                    courseName,
                    startNode,
                    startTime,
                    room
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(0x5101, notification)
    }
}

/**
 * 闹钟/重排广播接收器。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReminderScheduler.ACTION_REMIND -> {
                ReminderScheduler.notifyCourse(
                    context,
                    intent.getStringExtra("courseName").orEmpty(),
                    intent.getStringExtra("room").orEmpty(),
                    intent.getIntExtra("startNode", 0),
                    intent.getStringExtra("startTime").orEmpty(),
                )
            }

            ReminderScheduler.ACTION_REARRANGE, Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
            WidgetRefreshScheduler.ACTION_DATE_REFRESH,
                -> {
                WidgetRefreshScheduler.refreshAndSchedule(context)
                ReminderScheduler.rearrange(context)
            }

            else -> {}
        }
    }
}
