package com.openwakeup.schedule.core.designsystem.component

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * 恒定"聚焦"的 TextView：使 marquee（跑马灯）在无需真正获得焦点时也能滚动。
 * 用于课程时钟页"当前课程"文本。
 */
class MarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    override fun isFocused(): Boolean = true
}
