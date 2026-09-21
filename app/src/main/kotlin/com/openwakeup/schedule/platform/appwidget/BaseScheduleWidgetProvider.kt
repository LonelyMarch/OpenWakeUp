package com.openwakeup.schedule.platform.appwidget

import android.appwidget.AppWidgetProvider
import android.content.Context

/**
 * 四类课程小组件共用的实例生命周期处理。
 *
 * 本基类只处理实例增删后的调度核对与翻页状态清理，不持有 Activity、View、Bitmap 或长期
 * 协程。具体 RemoteViews 构建仍由各 Provider 自己负责。
 */
abstract class BaseScheduleWidgetProvider : AppWidgetProvider() {

    /** 首个同类实例启用后，为现有全部小组件核对跨日调度。 */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdateCoordinator.reconcileScheduling(context)
    }

    /** 删除实例时只清理这些实例自己的翻页状态，再核对剩余组件调度。 */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetNavigation.removeOffsets(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
        WidgetUpdateCoordinator.reconcileScheduling(context)
    }

    /** 最后一个同类实例停用后，取消已经不再需要的小组件调度。 */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetUpdateCoordinator.reconcileScheduling(context)
    }
}
