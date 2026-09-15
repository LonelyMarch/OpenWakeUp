package com.openwakeup.schedule.feature.schedule

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.openwakeup.schedule.core.designsystem.component.TipTextView

/**
 * 课表背景网格视图：绘制横向列分隔虚线（列数-1 条）与行分隔虚线（行数-1 条）。
 * 永远使用 [16, 8] 的虚线段（构造器无条件设置，无实线开关）。
 *
 * @param row 行数（节次数），默认 20
 * @param col 列数（天数），默认 7
 * @param color 线条颜色，默认黑色
 * @param horizontalMargin 没有绑定真实列视图时使用的相邻列间距（px）
 * @param verticalMargin 没有绑定真实行视图时使用的相邻行间距（px）
 */
class GridBackgroundView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 行数（节次数） */
    var row: Int = 20

    /** 列数（天数） */
    var col: Int = 7

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DEFAULT_COLOR
        isDither = true
        style = Paint.Style.FILL
        strokeWidth = 1f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(16f, 8f), 0f)
    }

    /** 线条颜色（设置时同步到画笔） */
    var color: Int = DEFAULT_COLOR
        set(value) {
            field = value
            paint.color = value
        }

    /** 相邻列课程块水平间距（px） */
    var horizontalMargin: Int = 0

    /** 相邻行课程块垂直间距（px） */
    var verticalMargin: Int = 0

    /**
     * 与网格对应的真实课程列。
     *
     * ConstraintLayout 可能在百分比宽度和双向约束之间分配剩余像素，因此仅用平均列宽推算会
     * 出现 1～数 px 偏差。绑定后直接在相邻列真实边界的中点画线。
     */
    var columnViews: List<View> = emptyList()

    /** 与网格对应的真实节次行；绑定后在上下两行真实间隙的中点画线。 */
    var rowViews: List<View> = emptyList()

    /** 复用所有可见课程卡组成的裁剪路径，减少课表滚动和刷新时的临时对象。 */
    private val courseMaskPath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val checkpoint = canvas.save()
        clipVisibleCourseCards(canvas)
        val width = width.toFloat()
        if (columnViews.size == col) {
            columnViews.zipWithNext().forEach { (leftColumn, rightColumn) ->
                val parentX = (leftColumn.right + rightColumn.left) / 2f
                canvas.drawLine(parentX - left, 0f, parentX - left, height.toFloat(), paint)
            }
        } else {
            drawCalculatedColumns(canvas, width)
        }

        if (rowViews.size == row) {
            rowViews.zipWithNext().forEach { (upperRow, lowerRow) ->
                val parentY = (upperRow.bottom + lowerRow.top) / 2f
                canvas.drawLine(0f, parentY - top, width, parentY - top, paint)
            }
        } else {
            drawCalculatedRows(canvas, width)
        }
        canvas.restoreToCount(checkpoint)
    }

    /**
     * 从网格可绘制区域中排除所有可见课程卡。
     *
     * 课程卡背景允许半透明。如果只依赖 View 的前后顺序，位于底层的虚线仍会透过课程色块，
     * 视觉上就像虚线画在课程卡上方。这里按课程卡相对于网格的真实坐标建立圆角遮罩，使网格
     * 仅存在于空白单元格和课程间隙，同时保留课程卡自身的透明背景效果。
     */
    private fun clipVisibleCourseCards(canvas: Canvas) {
        courseMaskPath.rewind()
        var hasVisibleCourse = false
        columnViews.forEach { column ->
            val container = column as? ViewGroup ?: return@forEach
            for (index in 0 until container.childCount) {
                val course = container.getChildAt(index) as? TipTextView ?: continue
                if (course.visibility != VISIBLE || course.width <= 0 || course.height <= 0) continue
                val courseLeft = column.left + course.left - left.toFloat()
                val courseTop = column.top + course.top - top.toFloat()
                val bounds = RectF(
                    courseLeft,
                    courseTop,
                    courseLeft + course.width,
                    courseTop + course.height,
                )
                // TipTextView 的描边延伸到视图边缘，因此遮罩使用完整视图边界；
                // 圆角额外包含内部 1dp 偏移，避免抗锯齿边缘残留一圈虚线像素。
                courseMaskPath.addRoundRect(
                    bounds,
                    course.cornerRadius + course.dot,
                    course.cornerRadius + course.dot,
                    Path.Direction.CW,
                )
                hasVisibleCourse = true
            }
        }
        if (hasVisibleCourse) canvas.clipOutPath(courseMaskPath)
    }

    /** 在布局尚未绑定真实列视图时，按平均列宽绘制兼容性网格。 */
    private fun drawCalculatedColumns(canvas: Canvas, width: Float) {
        val columnWidth = (width - (col - 1) * horizontalMargin) / col
        var lastX = 0f
        for (i in 0 until col - 1) {
            val x =
                (if (i == 0) horizontalMargin / 2f + columnWidth else horizontalMargin + columnWidth) + lastX
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            lastX = x
        }
    }

    /** 在布局尚未绑定真实行视图时，按平均行高绘制兼容性网格。 */
    private fun drawCalculatedRows(canvas: Canvas, width: Float) {
        val rowHeight = height.toFloat() / row
        for (i in 1 until row) {
            val y = verticalMargin / 2f + i * rowHeight
            canvas.drawLine(0f, y, width, y, paint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
    }

    private companion object {
        const val DEFAULT_COLOR = -16777216 // Color.BLACK
    }
}
