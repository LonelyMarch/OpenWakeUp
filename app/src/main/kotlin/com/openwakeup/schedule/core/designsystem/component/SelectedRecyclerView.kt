/*
 * 本文件基于 YZune/WakeupSchedule_Kotlin 的 Apache-2.0 代码修改。
 * 原始版权：Copyright 2019 YZune。
 * OpenWakeUp 于 2026 年完成包结构、触摸回调及 AndroidX 适配；完整来源见仓库 NOTICE.md。
 */
package com.openwakeup.schedule.core.designsystem.component

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager

/**
 * 周次选择网格 RecyclerView（fragment_select_week 的 rv_week）：
 * 把触摸坐标换算成瀑布流网格里的格子序号，按下/移动/抬起时回调 [positionChangedListener]。
 *
 * @property positionChangedListener (position, isPressed) -> Unit；isPressed=true 表示按下，false 表示移动/抬起
 */
class SelectedRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    var positionChangedListener: (position: Int, pressed: Boolean) -> Unit = { _, _ -> }

    /** 网格列数（取自 StaggeredGridLayoutManager 的 spanCount） */
    private var spanCount = 1

    override fun setLayoutManager(layoutManager: LayoutManager?) {
        super.setLayoutManager(layoutManager)
        if (layoutManager is StaggeredGridLayoutManager) {
            spanCount = layoutManager.spanCount
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.x <= width && e.x >= 0f && e.y <= height && e.y >= 0f) {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    val column = (e.x.toInt()) / (width / spanCount)
                    positionChangedListener(
                        (e.y.toInt()) / (height / spanCount) * spanCount + column,
                        true
                    )
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val column = (e.x.toInt()) / (width / spanCount)
                    positionChangedListener(
                        (e.y.toInt()) / (height / spanCount) * spanCount + column,
                        false
                    )
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    val column = (e.x.toInt()) / (width / spanCount)
                    positionChangedListener(
                        (e.y.toInt()) / (height / spanCount) * spanCount + column,
                        false
                    )
                    performClick()
                    return true
                }
            }
        }
        return true
    }

    /** 向无障碍服务报告周次网格的一次完整点击。 */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
