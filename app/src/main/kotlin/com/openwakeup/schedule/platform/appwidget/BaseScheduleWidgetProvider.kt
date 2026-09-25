package com.openwakeup.schedule.platform.appwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 四类课程小组件共用的实例生命周期处理。
 *
 * 本基类统一处理系统小组件事件后的调度自恢复和翻页状态清理，不持有 Activity、View、
 * Bitmap 或长期协程。具体 RemoteViews 构建仍由各 Provider 自己负责。
 */
abstract class BaseScheduleWidgetProvider : AppWidgetProvider() {

    /**
     * 在系统完成小组件刷新或实例生命周期分发后异步恢复所需闹钟。
     *
     * 先调用父类，使 [AppWidgetProvider] 将标准广播分派到 [onUpdate]、[onDeleted] 等回调；
     * 随后根据最新实例集合核对调度。使用 [goAsync] 保持广播进程存活，并把 Room 快照读取和
     * 闹钟计算放到 IO 线程，避免阻塞广播主线程。
     */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            AppWidgetManager.ACTION_APPWIDGET_ENABLED,
            AppWidgetManager.ACTION_APPWIDGET_DELETED,
            AppWidgetManager.ACTION_APPWIDGET_DISABLED,
            AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED,
                -> reconcileSchedulingAsync(context)
        }
    }

    /** 删除实例时只清理这些实例自己的翻页状态，剩余实例调度由 [onReceive] 统一核对。 */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetNavigation.removeOffsets(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }

    /** 使用当前广播的异步令牌，在调度核对完成后及时释放系统资源。 */
    private fun reconcileSchedulingAsync(context: Context) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                WidgetUpdateCoordinator.reconcileScheduling(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
