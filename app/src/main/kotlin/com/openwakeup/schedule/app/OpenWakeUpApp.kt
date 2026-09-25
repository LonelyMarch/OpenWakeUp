package com.openwakeup.schedule.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.content.getSystemService
import com.google.android.material.color.DynamicColors
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.core.designsystem.theme.AppThemeController
import com.openwakeup.schedule.core.format.AppLocaleResolver
import com.openwakeup.schedule.platform.appwidget.WidgetUpdateCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application 入口：初始化应用主题、通知渠道和全局协程域。
 *
 * @property appScope 应用级协程域（SupervisorJob，单个子任务失败不传染）；由各仓库层使用
 */
class OpenWakeUpApp : Application() {

    lateinit var appScope: CoroutineScope
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 在创建任何 Activity 前应用持久化主题；当前版本会按需求统一渲染为浅色。
        val prefs = Prefs.get(this)
        AppThemeController.apply(prefs.themeMode)
        // 恢复持久化的应用语言（跟随系统/中文/English）。
        AppLocaleResolver.apply(prefs.appLocale)
        if (prefs.dynamicColors) {
            // Android 12+ 使用系统壁纸动态色；不支持的设备由 Material 自动忽略。
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
        // 全局协程域
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // 厂商后台策略可能在应用进程退出后清除已登记闹钟。任意组件重新创建进程时仅根据
        // 真实桌面实例恢复调度，不主动重绘 RemoteViews；无实例时协调器会取消遗留任务。
        appScope.launch(Dispatchers.IO) {
            WidgetUpdateCoordinator.reconcileScheduling(this@OpenWakeUpApp)
        }

        // 通知渠道：课前提醒（IMPORTANCE_HIGH）
        val manager = getSystemService<NotificationManager>()
        val channel = NotificationChannel(
            CHANNEL_REMINDER,
            getString(R.string.channel_reminder_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.channel_reminder_desc)
        }
        manager?.createNotificationChannel(channel)

    }

    companion object {
        /** 课前提醒通知渠道 id */
        const val CHANNEL_REMINDER = "reminder"

        /** 全局单例（本应用规模下不引入 DI，直接持有） */
        lateinit var instance: OpenWakeUpApp
            private set
    }
}
