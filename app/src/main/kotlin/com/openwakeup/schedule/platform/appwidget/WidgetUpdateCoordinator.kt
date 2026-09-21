package com.openwakeup.schedule.platform.appwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent

/**
 * 桌面小组件统一刷新入口。
 *
 * 调用方只表达“全部内容变化”或“课程进度变化”，本对象负责查询真实实例并把准确的实例 ID
 * 交给对应 Provider。没有实例时不会发送广播，也不会间接触发 Room 查询、位图解码和
 * RemoteViews 构建。
 */
object WidgetUpdateCoordinator {

    /** 刷新当前已经添加到桌面的全部小组件。 */
    fun refreshAllInstalled(context: Context) {
        val snapshot = WidgetInstanceRegistry.snapshot(context)
        sendUpdates(context, snapshot, snapshot.installedKinds())
    }

    /** 只刷新内容会随今天课程结束而变化的小组件。 */
    fun refreshCourseProgressWidgets(context: Context) {
        val snapshot = WidgetInstanceRegistry.snapshot(context)
        sendUpdates(context, snapshot, snapshot.courseProgressKinds())
    }

    /**
     * 刷新全部真实实例，并核对当前阶段所需的跨日调度。
     *
     * 刷新完成后根据真实实例和最新课程数据重新安排跨日与课程结束节点闹钟。
     */
    fun refreshAndReconcileScheduling(context: Context) {
        val snapshot = WidgetInstanceRegistry.snapshot(context)
        sendUpdates(context, snapshot, snapshot.installedKinds())
        WidgetRefreshScheduler.reconcileScheduling(context, snapshot)
    }

    /** 仅重新核对小组件生命周期对应的跨日和课程节点调度，不触发界面刷新。 */
    fun reconcileScheduling(context: Context) {
        WidgetRefreshScheduler.reconcileScheduling(
            context,
            WidgetInstanceRegistry.snapshot(context),
        )
    }

    /** 向指定类型发送携带准确实例 ID 的标准更新广播。 */
    private fun sendUpdates(
        context: Context,
        snapshot: WidgetInstanceRegistry.Snapshot,
        kinds: List<WidgetInstanceRegistry.Kind>,
    ) {
        val appContext = context.applicationContext
        kinds.forEach { kind ->
            val ids = snapshot.ids(kind)
            if (ids.isEmpty()) return@forEach
            appContext.sendBroadcast(
                Intent(appContext, kind.providerClass)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }
    }
}
