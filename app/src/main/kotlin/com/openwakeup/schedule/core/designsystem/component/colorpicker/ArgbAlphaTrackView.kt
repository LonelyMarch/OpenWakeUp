package com.openwakeup.schedule.core.designsystem.component.colorpicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors

/**
 * Material 透明度滑条的 ARGB 背景轨道。
 *
 * 轨道先绘制用于表示透明区域的中性棋盘格，再叠加当前 RGB 从完全透明到完全不透明的
 * 横向渐变。实际数值与触摸、无障碍操作仍由上层 Material Slider 处理，本视图只负责
 * 展示 alpha 通道，避免自定义绘制破坏 Material 3 Expressive 的滑块动画和交互范围。
 */
class ArgbAlphaTrackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density: Float = resources.displayMetrics.density
    private val checkerSizePx: Float = CHECKER_SIZE_DP * density
    private val cornerRadiusPx: Float = TRACK_RADIUS_DP * density
    private val borderWidthPx: Float = BORDER_WIDTH_DP * density
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val grayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(189, 189, 189) }
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 轨道与描边矩形只随控件尺寸变化，绘制阶段直接复用。 */
    private val trackRect = RectF()
    private val borderRect = RectF()

    /** 复用圆角裁剪路径，避免每次重绘透明度轨道时创建临时对象。 */
    private val clipPath = Path()
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(
            this@ArgbAlphaTrackView,
            com.google.android.material.R.attr.colorOutlineVariant,
        )
        style = Paint.Style.STROKE
        strokeWidth = borderWidthPx
    }

    /** 当前 RGB 颜色；传入值中的 alpha 只由滑块位置表示，不影响渐变的完整范围。 */
    var color: Int = Color.WHITE
        set(value) {
            field = ColorUtils.setAlphaComponent(value, 255)
            updateGradientShader()
            invalidate()
        }

    /** 尺寸变化时更新固定几何数据和依赖宽度的透明度渐变。 */
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        trackRect.set(0f, 0f, width.toFloat(), height.toFloat())
        val borderInset = borderWidthPx / 2f
        borderRect.set(borderInset, borderInset, width - borderInset, height - borderInset)
        updateGradientShader()
    }

    /** 布局方向变化时同步反转透明度渐变，保证数值方向与 Slider 一致。 */
    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        updateGradientShader()
        invalidate()
    }

    /** 重建只在颜色、宽度或布局方向变化时失效的渐变着色器。 */
    private fun updateGradientShader() {
        if (width <= 0) return
        val transparent = ColorUtils.setAlphaComponent(color, 0)
        val opaque = ColorUtils.setAlphaComponent(color, 255)
        val startColor = if (layoutDirection == LAYOUT_DIRECTION_RTL) opaque else transparent
        val endColor = if (layoutDirection == LAYOUT_DIRECTION_RTL) transparent else opaque
        gradientPaint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            0f,
            startColor,
            endColor,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val checkpoint = canvas.save()
        // Canvas 没有接收 RectF 和两个圆角半径的 clipRoundRect 重载；
        // 使用平台稳定支持的 Path 裁剪，效果与圆角矩形裁剪一致。
        clipPath.rewind()
        clipPath.addRoundRect(trackRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        canvas.clipPath(clipPath)
        drawCheckerboard(canvas)

        canvas.drawRect(trackRect, gradientPaint)
        canvas.restoreToCount(checkpoint)

        // 细描边在浅色和深色棋盘格上都能明确轨道边界，并沿用当前 Material 主题颜色。
        canvas.drawRoundRect(
            borderRect,
            cornerRadiusPx,
            cornerRadiusPx,
            borderPaint,
        )
    }

    /** 按固定单元格尺寸绘制透明度棋盘格，奇偶行交替起色。 */
    private fun drawCheckerboard(canvas: Canvas) {
        var row = 0
        var top = 0f
        while (top < height) {
            var column = 0
            var left = 0f
            while (left < width) {
                val paint = if ((row + column) % 2 == 0) whitePaint else grayPaint
                canvas.drawRect(
                    left,
                    top,
                    minOf(left + checkerSizePx, width.toFloat()),
                    minOf(top + checkerSizePx, height.toFloat()),
                    paint,
                )
                left += checkerSizePx
                column++
            }
            top += checkerSizePx
            row++
        }
    }

    private companion object {
        const val CHECKER_SIZE_DP = 4f
        const val TRACK_RADIUS_DP = 8f
        const val BORDER_WIDTH_DP = 1f
    }
}
