package com.openwakeup.schedule.platform.appwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 小组件一次性闹钟接收器。
 *
 * 该 Receiver 只处理应用内部的跨日与课程结束节点动作，不承担课前提醒通知。每次触发后都会
 * 依据最新实例和课程数据重新核对下一次调度。
 */
class WidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            WidgetRefreshScheduler.ACTION_DATE_REFRESH -> runAsync {
                WidgetUpdateCoordinator.refreshAndReconcileScheduling(appContext)
            }

            WidgetRefreshScheduler.ACTION_COURSE_PROGRESS_REFRESH -> runAsync {
                WidgetUpdateCoordinator.refreshCourseProgressWidgets(appContext)
                WidgetUpdateCoordinator.reconcileScheduling(appContext)
            }
        }
    }

    /** 使用 goAsync 保证 Room 快照与调度计算不会阻塞广播主线程。 */
    private fun runAsync(block: () -> Unit) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                block()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
