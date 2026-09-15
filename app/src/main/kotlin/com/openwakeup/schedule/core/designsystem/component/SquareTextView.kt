/*
 * 本文件基于 YZune/WakeupSchedule_Kotlin 的 Apache-2.0 代码修改。
 * 原始版权：Copyright 2019 YZune。
 * OpenWakeUp 于 2026 年完成包结构及 AndroidX 适配；完整来源见仓库 NOTICE.md。
 */
package com.openwakeup.schedule.core.designsystem.component

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * 正方形 TextView：测量后强制高=宽。用于周次选择网格的数字格（item_select_week）。
 */
class SquareTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredWidth)
    }
}
