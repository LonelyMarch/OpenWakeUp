package com.openwakeup.schedule.platform.appwidget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.database.entity.CourseDetailEntity
import com.openwakeup.schedule.core.database.entity.CourseEntity
import java.time.LocalDate

/** 日视图数据源：以原生 RemoteViews 展示双行课程卡，支持实例独立查看明天。 */
class TodayWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(WidgetNavigation.EXTRA_OFFSET, 0))

    /** 每个尺寸版本单独持有显示宽度，避免多个小部件实例相互影响。 */
    class Factory(
        private val context: Context,
        private val offset: Int = 0,
        private val widthDp: Float = 360f,
    ) : RemoteViewsFactory {
        private var snapshot: WidgetSnapshot? = null
        private var rows: List<Pair<CourseEntity, CourseDetailEntity>> = emptyList()
        override fun onCreate() = Unit
        override fun onDestroy() = Unit
        override fun hasStableIds(): Boolean = false

        /** 自然日期查询覆盖周日到周一，以及学期边界和调课记录。 */
        override fun onDataSetChanged() {
            snapshot = WidgetRepository.snapshot(context)
            val date = LocalDate.now().plusDays(offset.toLong())
            rows = snapshot?.coursesOfDate(date)?.map { (course, detail, _) -> course to detail }
                .orEmpty()
        }

        override fun getCount(): Int = rows.size
        override fun getViewAt(position: Int): RemoteViews =
            snapshot?.let { snap ->
                rows.getOrNull(position)?.let { (course, detail) ->
                    WidgetCourseRowRenderer.create(context, snap, course, detail, widthDp = widthDp)
                }
            } ?: RemoteViews(context.packageName, R.layout.item_widget_course_native)

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(position: Int): Long = position.toLong()
    }
}
