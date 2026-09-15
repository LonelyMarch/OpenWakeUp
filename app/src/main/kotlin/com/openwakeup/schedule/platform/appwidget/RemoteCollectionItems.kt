package com.openwakeup.schedule.platform.appwidget

import android.widget.RemoteViews
import android.widget.RemoteViewsService

/**
 * 把既有 [RemoteViewsService.RemoteViewsFactory] 数据源转换为内联集合。
 *
 * 项目最低版本为 Android 13，而 [RemoteViews.RemoteCollectionItems] 从 Android 12 即可使用。
 * 每次 Provider 更新时直接提交完整集合，桌面启动器无需再绑定已弃用的远程适配器服务，也
 * 不再需要额外调用 notifyAppWidgetViewDataChanged 通知刷新。
 *
 * @param viewId RemoteViews 中承载集合的 ListView 或 StackView id
 * @param factory 负责加载数据并创建每个列表项 RemoteViews 的现有工厂
 */
internal fun RemoteViews.setRemoteCollectionAdapter(
    viewId: Int,
    factory: RemoteViewsService.RemoteViewsFactory,
) {
    factory.onCreate()
    try {
        factory.onDataSetChanged()
        val itemCount = factory.count
        val items = RemoteViews.RemoteCollectionItems.Builder()
            .setHasStableIds(factory.hasStableIds())
            .setViewTypeCount(factory.viewTypeCount)
            .apply {
                repeat(itemCount) { position ->
                    addItem(factory.getItemId(position), factory.getViewAt(position))
                }
            }
            .build()
        setRemoteAdapter(viewId, items)
    } finally {
        // 工厂仅服务于本次快照；提交完成后立即释放其位图或临时列表引用。
        factory.onDestroy()
    }
}
