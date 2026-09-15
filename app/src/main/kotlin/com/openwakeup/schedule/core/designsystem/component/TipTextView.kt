/*
 * 本文件基于 YZune/WakeupSchedule_Kotlin 的 Apache-2.0 代码修改。
 * 原始版权：Copyright 2019 YZune。
 * OpenWakeUp 于 2026 年重构了绘制、状态标签、透明度和按压反馈；完整来源见仓库 NOTICE.md。
 */
package com.openwakeup.schedule.core.designsystem.component

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.LazyThreadSafetyMode.NONE

/**
 * 课程卡片视图（主课表每张课程卡即本 View）：圆角底色 + 描边 + StaticLayout 主文本/详情文本 +
 * 右下角标（三角形/X 形，用于同格冲突与重叠标记）+ 按压缩放反馈。
 * 课程正文分两段绘制：主文本（课名/教室/时间等）用 [mTextPaint]，详情行（教师）用 [mDetailPaint]。
 * 非本周等状态文字由 [label] 与 [mLabelPaint] 独立绘制，可在不改变课程正文的前提下使用更小字号。
 * 画笔与文本由创建方（周页面构建逻辑）直接赋值，故均为 public。
 *
 * @property label 卡片顶部的单行状态标签，为空时保持原有两段文字布局
 * @property labelBaseTextSizePx 状态标签未经宽度压缩时的基础字号，单位为 px
 * @property text 主文本（多行，\n 分隔）
 * @property detail 详情文本（教师行），为空串时不绘制
 * @property tipVisibility 角标/状态：0=无角标；1=右下三角角标（同格重叠提示）；-1=右下 X 角标；2=仅按 alpha 重绘
 * @property textAlpha 状态标签、主文本和详情文本透明度；[strokeAlpha] 描边透明度；[bgAlpha] 背景透明度（均 0-255）
 * @property cornerRadius 圆角半径（px，创建方按 config.radius × [dot] 换算）
 * @property isCenter 内容在垂直方向居中（内容高度小于 View 高时）
 */
@SuppressLint("ViewConstructor")
class TipTextView(context: Context) : View(context) {

    var label: String = ""
    var labelBaseTextSizePx: Float = 0f
    var text: String = ""
    var detail: String = ""
    var tipVisibility: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var textAlpha: Int = 255
    var strokeAlpha: Int = 255
    var bgAlpha: Int = 255

    var alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL

    /** 1px 对应的密度值，角标尺寸与圆角换算基准 */
    val dot: Float = 1 * context.resources.displayMetrics.density

    var cornerRadius: Float = 4 * dot

    var isCenter: Boolean = false

    lateinit var mTextPaint: TextPaint
    lateinit var mDetailPaint: TextPaint
    lateinit var mLabelPaint: TextPaint
    lateinit var bgPaint: Paint
    lateinit var strokePaint: Paint
    lateinit var mPaint: Paint

    private val rect = RectF()
    private val path = Path()
    private val interpolator: DecelerateInterpolator by lazy(NONE) { DecelerateInterpolator() }

    private var textLayout: StaticLayout? = null
    private var detailLayout: StaticLayout? = null
    private var labelLayout: StaticLayout? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (tipVisibility == TIP_ALPHA_REFRESH) {
            mTextPaint.alpha = textAlpha
            mPaint.alpha = textAlpha
            mDetailPaint.alpha = textAlpha
            if (label.isNotEmpty() && ::mLabelPaint.isInitialized) {
                mLabelPaint.alpha = textAlpha
            }
            strokePaint.alpha = strokeAlpha
            bgPaint.alpha = bgAlpha
        }
        val textWidth = (width - paddingRight - paddingLeft).coerceAtLeast(1)
        val currentTextLayout =
            textLayout ?: createLayout(text, mTextPaint, textWidth).also { textLayout = it }
        val currentDetailLayout = detailLayout
            ?: createLayout(detail, mDetailPaint, textWidth).also { detailLayout = it }
        val currentLabelLayout = if (label.isEmpty()) {
            null
        } else {
            labelLayout ?: createSingleLineLabelLayout(
                label,
                mLabelPaint,
                textWidth
            ).also { labelLayout = it }
        }

        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)
        canvas.clipRect(rect)
        canvas.save()
        val detailDrawHeight =
            if (detail.isEmpty()) 0 else currentDetailLayout.height - paddingTop * 2
        val labelDrawHeight = currentLabelLayout?.height ?: 0
        val contentHeight = labelDrawHeight + currentTextLayout.height + detailDrawHeight
        val freeHeight = height - contentHeight
        if (!isCenter || freeHeight <= 0) {
            canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        } else {
            canvas.translate(paddingLeft.toFloat(), freeHeight / 2f)
        }
        currentLabelLayout?.let { layout ->
            layout.draw(canvas)
            canvas.translate(0f, layout.height.toFloat())
        }
        currentTextLayout.draw(canvas)
        if (detail.isNotEmpty()) {
            canvas.translate(0f, (currentTextLayout.height - paddingTop * 2).toFloat())
            currentDetailLayout.draw(canvas)
        }
        canvas.restore()

        when (tipVisibility) {
            TIP_TRIANGLE -> {
                path.moveTo(width - 12 * dot, height - 6 * dot)
                path.lineTo(width - 6 * dot, height - 6 * dot)
                path.lineTo(width - 6 * dot, height - 12 * dot)
                path.close()
                canvas.drawPath(path, mPaint)
            }

            TIP_CROSS -> {
                canvas.drawLine(
                    width - 12 * dot,
                    height - 6 * dot,
                    width - 6 * dot,
                    height - 12 * dot,
                    mPaint
                )
                canvas.drawLine(
                    width - 6 * dot,
                    height - 6 * dot,
                    width - 12 * dot,
                    height - 12 * dot,
                    mPaint
                )
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        rect.left = dot
        rect.right = width - dot
        rect.top = dot
        rect.bottom = height - dot
        setMeasuredDimension(width, height)
    }

    /**
     * 尺寸发生变化时清除依赖旧宽度的文字布局。
     *
     * 横竖屏切换或父布局列宽调整后，状态标签需要按新宽度重新计算字号，正文和详情也需要重新换行。
     */
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width == oldWidth && height == oldHeight) return
        textLayout = null
        detailLayout = null
        labelLayout = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN ->
                animate().scaleX(0.95f).scaleY(0.95f).setDuration(300).setInterpolator(interpolator)
                    .start()

            MotionEvent.ACTION_UP -> {
                animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(interpolator)
                    .start()
                performClick()
            }

            MotionEvent.ACTION_CANCEL ->
                animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(interpolator)
                    .start()
        }
        return super.onTouchEvent(event)
    }

    private fun createLayout(source: String, textPaint: TextPaint, textWidth: Int): StaticLayout =
        StaticLayout.Builder
            .obtain(source, 0, source.length, textPaint, textWidth)
            .setIncludePad(false)
            .setAlignment(alignment)
            .build()

    /**
     * 创建不会换行的状态标签布局。
     *
     * 标签首先使用调用方指定的小字号；若文字仍宽于课程列，则按测量宽度继续等比缩小，并留出少量
     * 安全余量防止字体栅格化后的边缘像素触发意外换行。
     *
     * @param source 状态标签文字
     * @param textPaint 状态标签专用画笔
     * @param textWidth 扣除卡片左右内边距后的可用宽度
     * @return 最多一行且固定居中的 StaticLayout
     */
    private fun createSingleLineLabelLayout(
        source: String,
        textPaint: TextPaint,
        textWidth: Int,
    ): StaticLayout {
        // 每次按新宽度布局前先恢复基础字号，使课程列重新变宽时标签可以同步放大。
        if (labelBaseTextSizePx > 0f) textPaint.textSize = labelBaseTextSizePx
        val measuredWidth = textPaint.measureText(source)
        if (measuredWidth > textWidth && measuredWidth > 0f) {
            textPaint.textSize *= textWidth / measuredWidth * LABEL_WIDTH_SAFETY_FACTOR
        }
        return StaticLayout.Builder
            .obtain(source, 0, source.length, textPaint, textWidth)
            .setIncludePad(false)
            .setMaxLines(1)
            // 状态标签用于提示课程状态，始终居中；课程正文仍由 alignment 配置决定对齐方式。
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
    }

    companion object {
        /** 右下三角角标（同节重叠提示） */
        const val TIP_TRIANGLE = 1

        /** 右下 X 角标（被遮挡课程提示） */
        const val TIP_CROSS = -1

        /** 仅按各 alpha 值重绘（冲突卡片交替显隐用） */
        const val TIP_ALPHA_REFRESH = 2

        /** 状态标签宽度安全系数，避免字形抗锯齿边缘造成一行布局溢出。 */
        private const val LABEL_WIDTH_SAFETY_FACTOR = 0.96f
    }
}
