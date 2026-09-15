/*
 * 本文件基于 Jared Rummler/ColorPicker 及 YZune/WakeupSchedule_Kotlin 的 Apache-2.0 代码修改。
 * 原始版权包括 Copyright (C) 2017 Jared Rummler 与 Copyright 2019 YZune。
 * OpenWakeUp 于 2026 年重构了属性、尺寸、透明度与触摸行为；完整来源见仓库 NOTICE.md。
 */
package com.openwakeup.schedule.core.designsystem.component.colorpicker

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.openwakeup.schedule.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * HSV 取色器视图：左侧为 饱和度×明度 二维渐变方块，右侧为色相竖条，可选底部透明度横条（带棋盘底纹）。
 * 布局尺寸：色相条宽 32dp、alpha 条高 16dp、区块间距 16dp、指针半径 8dp、
 * 指针条宽 4dp、描边 2dp、最小内边距 8dp。
 *
 * 基于 jaredrummler/ColorPicker 语义实现；属性读取使用生成的 [R.styleable.ColorPickerView]
 * 索引，声明顺序变化不会造成字段错配。
 *
 * @property color 当前颜色（ARGB，HSV 存储）
 * @property showAlphaSlider 是否显示透明度条（attr cpv_showAlphaSlider）
 * @property onColorChanged 颜色变化回调
 */
class ColorPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 色相条宽（px） */
    private val hueSliderWidth: Int = (32 * density).toInt()

    /** alpha 条高（px） */
    private val alphaSliderHeight: Int = (16 * density).toInt()

    /** 区块间距（px） */
    private val spacing: Int = (16 * density).toInt()

    /** 圆形指针半径（px） */
    private val pointerRadius: Int = (8 * density).toInt()

    /** 指针条半宽（px） */
    private val pointerWidth: Int = (4 * density).toInt()

    /** 边框粗细（px） */
    private val border: Int = (8 * density).toInt()

    /** 最小内边距（px） */
    private val minPadding: Int = (8 * density).toInt()

    private val satValPaint = Paint()
    private val satValCache = BitmapCache()
    private val hueCache = BitmapCache()
    private val alphaPatternDrawable = AlphaPatternDrawable((4 * density).roundToInt())

    private val pointerPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /** 滑条指针画笔（颜色取 cpv_sliderColor 或主题 textColorSecondary） */
    private val sliderPointerPaint = Paint()

    /** 透明度渐变画笔与两个指针矩形在拖动重绘期间复用。 */
    private val alphaPaint = Paint()
    private val huePointerRect = RectF()
    private val alphaPointerRect = RectF()

    /** 明度渐变（白→黑，垂直，仅随 satValRect 重建） */
    private var valueGradient: LinearGradient? = null

    /** 饱和度渐变缓存（随色相变化重建） */
    private var hueGradient: LinearGradient? = null

    private var alpha = 255
    private var hue = 360f
    private var sat = 0f
    private var value = 0f

    /** 滑条描边色（attr 默认 -4342339，未指定时回落 textColorSecondary） */
    private var sliderColor = -4342339

    var showAlphaSlider = false
        private set

    private var drawingRect = Rect()
    private var satValRect = Rect()
    private var hueRect = Rect()
    private var alphaRect: Rect? = null

    /** 手指当前落点（决定命中的滑条），抬起时置空 */
    private var currentPoint: android.graphics.Point? = null

    var onColorChanged: ((color: Int) -> Unit)? = null

    private val density get() = resources.displayMetrics.density

    init {
        val a: TypedArray = getContext().obtainStyledAttributes(attrs, R.styleable.ColorPickerView)
        // 使用生成的 styleable 索引，属性声明调整顺序时仍能读取正确字段。
        showAlphaSlider = a.getBoolean(R.styleable.ColorPickerView_cpv_showAlphaSlider, false)
        sliderColor = a.getColor(R.styleable.ColorPickerView_cpv_sliderColor, -4342339)
        a.recycle()
        val themeTypedValue = TypedValue()
        val themeA = context.obtainStyledAttributes(
            themeTypedValue.data,
            intArrayOf(android.R.attr.textColorSecondary)
        )
        if (sliderColor == -4342339) {
            sliderColor = themeA.getColor(0, -4342339)
        }
        themeA.recycle()

        sliderPointerPaint.color = sliderColor
        sliderPointerPaint.style = Paint.Style.FILL_AND_STROKE
        sliderPointerPaint.strokeWidth = (2 * density).toInt().toFloat()
        sliderPointerPaint.isAntiAlias = true

        pointerPaint.color = -0x1
        pointerPaint.isAntiAlias = true

        isFocusable = true
        isFocusableInTouchMode = true
    }

    /** 当前颜色（ARGB，Kotlin 属性入口） */
    var color: Int
        get() = currentColor()
        set(v) = setColorInternal(v, false)

    private fun currentColor(): Int = Color.HSVToColor(alpha, floatArrayOf(hue, sat, value))

    fun setOnColorChangedListener(listener: ((color: Int) -> Unit)?) {
        onColorChanged = listener
    }

    override fun getPaddingLeft(): Int = max(super.getPaddingLeft(), minPadding)

    override fun getPaddingRight(): Int = max(super.getPaddingRight(), minPadding)

    override fun getPaddingTop(): Int = max(super.getPaddingTop(), minPadding)

    override fun getPaddingBottom(): Int = max(super.getPaddingBottom(), minPadding)

    private fun setColorInternal(color: Int, notify: Boolean) {
        val newAlpha = Color.alpha(color)
        val hsv = FloatArray(3)
        Color.RGBToHSV(Color.red(color), Color.green(color), Color.blue(color), hsv)
        alpha = newAlpha
        hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
        if (notify) {
            onColorChanged?.invoke(Color.HSVToColor(alpha, floatArrayOf(hue, sat, value)))
        }
        updateAlphaGradient()
        invalidate()
    }

    private fun moveTrackersIfNeeded(event: MotionEvent): Boolean {
        val point = currentPoint ?: return false
        var handled = false
        if (hueRect.contains(point.x, point.y)) {
            val y = event.y
            val rect = hueRect
            val height = rect.height().toFloat()
            handled = true
            var offset = 0f
            if (y >= rect.top) {
                offset = if (y > rect.bottom) height else y - rect.top
            }
            hue = 360f - offset * 360f / height
        } else if (satValRect.contains(point.x, point.y)) {
            val rect = satValRect
            val width = rect.width().toFloat()
            val height = rect.height().toFloat()
            handled = true
            val x =
                if (event.x < rect.left) 0f else if (event.x > rect.right) width else event.x - rect.left
            val y =
                if (event.y < rect.top) 0f else if (event.y > rect.bottom) height else event.y - rect.top
            sat = 1f / width * x
            value = 1f - 1f / height * y
        } else {
            val rect = alphaRect
            if (rect != null && rect.contains(point.x, point.y)) {
                val x = event.x.toInt()
                val width = rect.width()
                handled = true
                val offset = when {
                    x < rect.left -> 0
                    x > rect.right -> width
                    else -> x - rect.left
                }
                alpha = 255 - offset * 255 / width
            }
        }
        if (handled) updateAlphaGradient()
        return handled
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (drawingRect.width() <= 0 || drawingRect.height() <= 0) return

        ensureValueGradient()
        val satValBitmap = satValCache.bitmap ?: return
        canvas.drawBitmap(satValBitmap, null, satValRect, null)

        // 饱和度/明度方块上的圆形指针
        val pointerX = (sat * satValRect.width() + satValRect.left).toInt()
        val pointerY = ((1f - value) * satValRect.height() + satValRect.top).toInt()
        pointerPaint.color = -0x1
        canvas.drawCircle(
            pointerX.toFloat(),
            pointerY.toFloat(),
            pointerRadius.toFloat(),
            pointerPaint
        )

        // 色相竖条
        ensureHueCache()
        val hueBitmap = hueCache.bitmap ?: return
        canvas.drawBitmap(hueBitmap, null, hueRect, null)
        val huePointerY = (hueRect.height() - hue * hueRect.height() / 360f).toInt() + hueRect.top
        huePointerRect.set(
            hueRect.left - pointerWidth.toFloat(),
            huePointerY - border / 2f,
            hueRect.right + pointerWidth.toFloat(),
            huePointerY + border / 2f,
        )
        canvas.drawRoundRect(huePointerRect, 2f, 2f, sliderPointerPaint)

        // 透明度横条
        if (showAlphaSlider) {
            val rect = alphaRect ?: return
            alphaPatternDrawable.draw(canvas)
            canvas.drawRect(rect, alphaPaint)
            val alphaPointerX = rect.width() - alpha * rect.width() / 255 + rect.left
            alphaPointerRect.set(
                alphaPointerX - border / 2f,
                rect.top - pointerWidth.toFloat(),
                alphaPointerX + border / 2f,
                rect.bottom + pointerWidth.toFloat(),
            )
            canvas.drawRoundRect(alphaPointerRect, 2f, 2f, sliderPointerPaint)
        }
    }

    private fun ensureValueGradient() {
        if (satValCache.isDirtyFor(hue)) {
            val rect = satValRect
            if (satValCache.bitmap == null) {
                satValCache.bitmap =
                    Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
                satValCache.canvas = Canvas(satValCache.bitmap!!)
            }
            val hsvColor = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            hueGradient = LinearGradient(
                rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.top.toFloat(),
                -0x1, hsvColor, Shader.TileMode.CLAMP
            )
            val composeShader =
                ComposeShader(valueGradientInternal(), hueGradient!!, PorterDuff.Mode.MULTIPLY)
            satValPaint.shader = composeShader
            satValCache.canvas?.drawRect(
                0f, 0f,
                satValCache.bitmap!!.width.toFloat(),
                satValCache.bitmap!!.height.toFloat(),
                satValPaint
            )
            satValCache.cachedHue = hue
        }
    }

    /** 在 HSV 值或透明度轨道尺寸变化时重建渐变，避免在 [onDraw] 热路径中创建对象。 */
    private fun updateAlphaGradient() {
        val rect = alphaRect ?: return
        if (rect.width() <= 0) return
        val opaque = Color.HSVToColor(floatArrayOf(hue, sat, value))
        val transparent = Color.HSVToColor(0, floatArrayOf(hue, sat, value))
        alphaPaint.shader = LinearGradient(
            rect.left.toFloat(),
            rect.top.toFloat(),
            rect.right.toFloat(),
            rect.top.toFloat(),
            opaque,
            transparent,
            Shader.TileMode.CLAMP,
        )
    }

    @Synchronized
    private fun valueGradientInternal(): LinearGradient {
        if (valueGradient == null) {
            valueGradient = LinearGradient(
                satValRect.left.toFloat(), satValRect.top.toFloat(),
                satValRect.left.toFloat(), satValRect.bottom.toFloat(),
                -0x1, -0x1000000, Shader.TileMode.CLAMP
            )
        }
        return valueGradient!!
    }

    private fun ensureHueCache() {
        if (hueCache.bitmap != null) return
        val rect = hueRect
        hueCache.bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        hueCache.canvas = Canvas(hueCache.bitmap!!)
        val height = (rect.height() + 0.5f).toInt()
        val colors = IntArray(height)
        var hueValue = 360f
        for (i in 0 until height) {
            colors[i] = Color.HSVToColor(floatArrayOf(hueValue, 1f, 1f))
            hueValue -= 360f / height
        }
        val linePaint = Paint()
        linePaint.strokeWidth = 0f
        val bitmapWidth = hueCache.bitmap!!.width.toFloat()
        for (i in 0 until height) {
            linePaint.color = colors[i]
            hueCache.canvas?.drawLine(0f, i.toFloat(), bitmapWidth, i.toFloat(), linePaint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        var width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var height = MeasureSpec.getSize(heightMeasureSpec) - paddingBottom - paddingTop
        if (widthMode != MeasureSpec.EXACTLY && heightMode != MeasureSpec.EXACTLY) {
            val idealHeight =
                height + spacing + hueSliderWidth - (if (showAlphaSlider) spacing + alphaSliderHeight else 0)
            val idealWidth =
                width - spacing - hueSliderWidth + (if (showAlphaSlider) spacing + alphaSliderHeight else 0)
            val heightFitsWidth = idealHeight <= width
            val widthFitsHeight = idealWidth <= height
            when {
                heightFitsWidth && widthFitsHeight -> height = idealWidth
                !widthFitsHeight && heightFitsWidth -> width = idealHeight
                !heightFitsWidth && widthFitsHeight -> height = idealWidth
            }
        } else if (widthMode == MeasureSpec.EXACTLY && heightMode != MeasureSpec.EXACTLY) {
            var idealHeight = width - spacing - hueSliderWidth
            if (showAlphaSlider) {
                idealHeight += spacing + alphaSliderHeight
            }
            if (idealHeight <= height) {
                height = idealHeight
            }
        } else if (widthMode != MeasureSpec.EXACTLY) {
            var idealWidth = height + spacing + hueSliderWidth
            if (showAlphaSlider) {
                idealWidth -= spacing + alphaSliderHeight
            }
            if (idealWidth <= width) {
                width = idealWidth
            }
        }
        setMeasuredDimension(
            paddingLeft + width + paddingRight,
            paddingTop + height + paddingBottom
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        drawingRect = Rect(paddingLeft, paddingTop, w - paddingRight, h - paddingBottom)
        valueGradient = null
        hueGradient = null
        satValCache.bitmap = null
        hueCache.bitmap = null
        satValRect = Rect(
            drawingRect.left, drawingRect.top,
            drawingRect.right - spacing - hueSliderWidth,
            drawingRect.bottom - (if (showAlphaSlider) spacing + alphaSliderHeight else 0)
        )
        hueRect = Rect(
            drawingRect.right - hueSliderWidth, drawingRect.top, drawingRect.right,
            drawingRect.bottom - (if (showAlphaSlider) spacing + alphaSliderHeight else 0)
        )
        if (showAlphaSlider) {
            val rect = Rect(
                drawingRect.left,
                drawingRect.bottom - alphaSliderHeight,
                drawingRect.right,
                drawingRect.bottom
            )
            alphaRect = rect
            alphaPatternDrawable.setBounds(rect.left, rect.top, rect.right, rect.bottom)
            updateAlphaGradient()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled: Boolean
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPoint = android.graphics.Point(event.x.toInt(), event.y.toInt())
                handled = moveTrackersIfNeeded(event)
            }

            MotionEvent.ACTION_MOVE -> handled = moveTrackersIfNeeded(event)
            MotionEvent.ACTION_UP -> {
                handled = moveTrackersIfNeeded(event)
                currentPoint = null
                if (handled) performClick()
            }

            else -> handled = false
        }
        if (!handled) return super.onTouchEvent(event)
        onColorChanged?.invoke(Color.HSVToColor(alpha, floatArrayOf(hue, sat, value)))
        invalidate()
        return true
    }

    /** 向无障碍服务报告一次完整点击；实际颜色更新仍由触摸坐标决定。 */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onSaveInstanceState(): Parcelable {
        val bundle = Bundle()
        bundle.putParcelable(STATE_PARENT, super.onSaveInstanceState())
        bundle.putInt(STATE_ALPHA, alpha)
        bundle.putFloat(STATE_HUE, hue)
        bundle.putFloat(STATE_SAT, sat)
        bundle.putFloat(STATE_VALUE, value)
        bundle.putBoolean(STATE_SHOW_ALPHA, showAlphaSlider)
        return bundle
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        var superState = state
        if (state is Bundle) {
            alpha = state.getInt(STATE_ALPHA)
            hue = state.getFloat(STATE_HUE)
            sat = state.getFloat(STATE_SAT)
            value = state.getFloat(STATE_VALUE)
            showAlphaSlider = state.getBoolean(STATE_SHOW_ALPHA)
            updateAlphaGradient()
            // minSdk 33，可直接使用带类型参数的新重载，避免旧泛型接口的类型擦除风险。
            superState = state.getParcelable(STATE_PARENT, Parcelable::class.java) ?: return
        }
        @Suppress("DEPRECATION")
        super.onRestoreInstanceState(superState)
    }

    /** 位图绘制缓存：记住缓存对应的色相，色相变了才重画 */
    private class BitmapCache {
        var bitmap: Bitmap? = null
        var canvas: Canvas? = null
        var cachedHue: Float = Float.NaN

        fun isDirtyFor(currentHue: Float): Boolean = cachedHue != currentHue
    }

    private companion object {
        const val STATE_PARENT = "instanceState"
        const val STATE_ALPHA = "alpha"
        const val STATE_HUE = "hue"
        const val STATE_SAT = "sat"
        const val STATE_VALUE = "val"
        const val STATE_SHOW_ALPHA = "show_alpha"
    }
}
