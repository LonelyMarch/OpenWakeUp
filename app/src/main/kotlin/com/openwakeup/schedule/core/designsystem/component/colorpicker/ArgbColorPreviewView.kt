package com.openwakeup.schedule.core.designsystem.component.colorpicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

/**
 * 在棋盘格上展示实际 ARGB 效果的圆角颜色预览框。
 *
 * 控件先绘制与透明度滑条一致的中性棋盘格，再叠加包含 alpha 通道的当前颜色，最后使用
 * 当前主题的 `colorOnSurface` 绘制 50% 不透明度边框。浅色主题因此得到半透明深色边框，
 * 暗色主题则得到半透明浅色边框，两种主题下都能与底部面板背景保持清晰分界。
 */
class ArgbColorPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val cornerRadiusPx = CORNER_RADIUS_DP * density
    private val borderWidthPx = BORDER_WIDTH_DP * density
    private val clipPath = Path()
    private val previewRect = RectF()

    /** 描边矩形只随尺寸变化，避免预览重绘时反复分配。 */
    private val borderRect = RectF()
    private val colorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val alphaPattern =
        AlphaPatternDrawable((CHECKER_SIZE_DP * density).roundToInt().coerceAtLeast(1))
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(
            MaterialColors.getColor(
                this@ArgbColorPreviewView,
                com.google.android.material.R.attr.colorOnSurface,
            ),
            BORDER_ALPHA,
        )
        style = Paint.Style.STROKE
        strokeWidth = borderWidthPx
    }

    /** 当前预览的完整 ARGB 颜色；修改后立即重绘棋盘格上的实际混合效果。 */
    var color: Int = Color.TRANSPARENT
        set(value) {
            field = value
            colorPaint.color = value
            invalidate()
        }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        alphaPattern.setBounds(0, 0, width, height)
        previewRect.set(0f, 0f, width.toFloat(), height.toFloat())
        val borderInset = borderWidthPx / 2f
        borderRect.set(borderInset, borderInset, width - borderInset, height - borderInset)
    }

    /** 绘制圆角棋盘格、ARGB 色层以及适配当前明暗主题的半透明边框。 */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        clipPath.rewind()
        clipPath.addRoundRect(previewRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        val checkpoint = canvas.save()
        canvas.clipPath(clipPath)
        alphaPattern.draw(canvas)
        // 保留当前颜色的 alpha，让棋盘格准确展示保存后的 ARGB 视觉效果。
        canvas.drawRect(previewRect, colorPaint)
        canvas.restoreToCount(checkpoint)

        // 描边中心线向内收半个线宽，避免边框像素被控件边界裁掉。
        canvas.drawRoundRect(
            borderRect,
            cornerRadiusPx,
            cornerRadiusPx,
            borderPaint,
        )
    }

    private companion object {
        const val CHECKER_SIZE_DP = 4f
        const val CORNER_RADIUS_DP = 8f
        const val BORDER_WIDTH_DP = 1f

        /** 50% 不透明度换算到 Android 的 0..255 alpha 通道。 */
        const val BORDER_ALPHA = 128
    }
}
