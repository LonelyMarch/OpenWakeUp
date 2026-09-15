package com.openwakeup.schedule.platform.appwidget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.IdRes
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.core.data.WidgetEmptyViewMode

/** 统一处理所有桌面小部件空视图的文字、图片显隐和图片解码失败回退。 */
object WidgetEmptyViewRenderer {

    /**
     * 把当前空视图偏好应用到一组图片与文字控件。
     *
     * 图片模式下只有成功读取图片才隐藏文字；文件损坏或丢失时会回退到对应文案，避免桌面
     * 出现完全空白的区域。文字模式始终隐藏占位插图，仅显示用户保存的文字。
     *
     * @param context 用于读取和解码应用私有图片的上下文
     * @param views 待更新的小部件 RemoteViews
     * @param prefs 当前全局小部件偏好
     * @param imageViewId 空视图图片控件 id
     * @param textViewId 空视图文字控件 id
     * @param text 当前日期语义对应的空视图文字
     */
    fun apply(
        context: Context,
        views: RemoteViews,
        prefs: Prefs,
        @IdRes imageViewId: Int,
        @IdRes textViewId: Int,
        text: CharSequence,
    ) {
        val bitmap = if (prefs.widgetEmptyViewMode == WidgetEmptyViewMode.IMAGE) {
            WidgetImageStore.decodeFile(
                prefs.widgetEmptyImage,
                WidgetImageStore.MAX_EMPTY_IMAGE_DIMENSION,
            )
        } else {
            null
        }
        if (bitmap != null) {
            views.setImageViewBitmap(imageViewId, bitmap)
            views.setViewVisibility(imageViewId, View.VISIBLE)
            views.setViewVisibility(textViewId, View.GONE)
        } else {
            views.setTextViewText(textViewId, text)
            views.setViewVisibility(textViewId, View.VISIBLE)
            views.setViewVisibility(imageViewId, View.GONE)
        }
    }
}
