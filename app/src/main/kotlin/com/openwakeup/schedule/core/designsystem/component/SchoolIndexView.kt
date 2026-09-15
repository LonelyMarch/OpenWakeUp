package com.openwakeup.schedule.core.designsystem.component


import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt
import com.google.android.material.color.MaterialColors
import com.openwakeup.schedule.R
import kotlin.math.floor

/**
 * 学校列表右侧的快速字母索引。
 *
 * 该控件按照可用高度均匀绘制字母，并在手指按下或拖动时立即回调当前字母。与在 XML 中
 * 堆叠二十多个 TextView 相比，自绘控件不会额外参与复杂测量，也能在不同屏幕高度上保持
 * 紧凑的字母间距。
 */
class SchoolIndexView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val normalTextSizePx = spToPx(TEXT_SIZE_SP)
    private val selectedTextSizePx = spToPx(SELECTED_TEXT_SIZE_SP)

    @ColorInt
    private val normalTextColor = MaterialColors.getColor(
        this,
        if (isNightMode()) {
            // 暗色背景使用更亮的 onSurface，避免字母索引保持灰暗而难以辨认。
            com.google.android.material.R.attr.colorOnSurface
        } else {
            // 浅色背景与全局设置 leading 图标保持同一灰色层级。
            com.google.android.material.R.attr.colorOnSurfaceVariant
        },
    )

    @ColorInt
    private val selectedTextColor = MaterialColors.getColor(
        this,
        // colorPrimary 由 AppCompat 主题属性声明；Material 负责按当前主题解析实际颜色。
        androidx.appcompat.R.attr.colorPrimary,
    )

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT
    }

    private var letters: List<String> = emptyList()
    private var selectedIndex: Int = NO_SELECTION
    private var lastDispatchedIndex: Int = NO_SELECTION
    private var onLetterSelected: ((String) -> Unit)? = null

    init {
        // 索引本身只负责快速定位，保留透明背景以免遮挡学校列表内容。
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = resources.getString(R.string.school_index_description)
    }

    /**
     * 更新索引字母和点击回调。
     *
     * @param values 按列表排序顺序排列的分组键。
     * @param listener 用户触摸某个分组键时的回调。
     */
    fun setLetters(values: List<String>, listener: (String) -> Unit) {
        letters = values.distinct()
        onLetterSelected = listener
        selectedIndex = NO_SELECTION
        lastDispatchedIndex = NO_SELECTION
        invalidate()
    }

    /** 根据触摸纵坐标选择最接近的索引项，并避免在同一项内重复触发滚动。 */
    private fun selectLetterAt(y: Float) {
        if (letters.isEmpty() || height <= 0) return
        val itemHeight = height.toFloat() / letters.size
        val index = floor(y.coerceIn(0f, height.toFloat() - 1f) / itemHeight)
            .toInt()
            .coerceIn(letters.indices)
        selectedIndex = index
        if (lastDispatchedIndex != index) {
            lastDispatchedIndex = index
            onLetterSelected?.invoke(letters[index])
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (letters.isEmpty()) return

        val itemHeight = height.toFloat() / letters.size
        letters.forEachIndexed { index, letter ->
            val selected = index == selectedIndex
            textPaint.textSize = if (selected) selectedTextSizePx else normalTextSizePx
            textPaint.color = if (selected) selectedTextColor else normalTextColor

            // 使用当前字号的 FontMetrics 计算基线，确保每个字母在各自槽位内垂直居中。
            val metrics = textPaint.fontMetrics
            val centerY = itemHeight * index + itemHeight / 2f
            val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(letter, width / 2f, baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastDispatchedIndex = NO_SELECTION
                selectLetterAt(event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                selectLetterAt(event.y)
                return true
            }

            MotionEvent.ACTION_UP -> {
                selectLetterAt(event.y)
                performClick()
                clearTouchSelection()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                clearTouchSelection()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** 清除按压高亮，并把滚动手势重新交还给父容器。 */
    private fun clearTouchSelection() {
        parent?.requestDisallowInterceptTouchEvent(false)
        selectedIndex = NO_SELECTION
        lastDispatchedIndex = NO_SELECTION
        invalidate()
    }

    override fun getSuggestedMinimumWidth(): Int = (INDEX_WIDTH_DP * density).toInt()

    /** 按系统字体缩放规则把 sp 转为像素，避免直接读取已弃用的 scaledDensity。 */
    private fun spToPx(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

    /** 判断当前 View 是否使用夜间资源配置。 */
    private fun isNightMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

    private companion object {
        const val NO_SELECTION = -1
        const val INDEX_WIDTH_DP = 28f
        const val TEXT_SIZE_SP = 11f
        const val SELECTED_TEXT_SIZE_SP = 12f
    }
}
