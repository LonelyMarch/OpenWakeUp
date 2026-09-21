package com.openwakeup.schedule.platform.appwidget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.core.schedule.ScheduleNodeResolver
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 周课表结构：列表只含一张整周网格位图，节次列和星期列并排。
 * 页码由 Provider 的实例专属 Intent 传入，避免表头翻周而内容停留本周。
 */
class ScheduleWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(WidgetNavigation.EXTRA_OFFSET, 0))

    class Factory(
        private val context: Context,
        private val offset: Int = 0,
        private val widthDp: Float = 360f,
        private val widthScale: Float = 1f,
        private val providedSnapshot: WidgetSnapshot? = null,
    ) : RemoteViewsFactory {
        private var snapshot: WidgetSnapshot? = null
        private var bitmap: Bitmap? = null

        override fun onCreate() = Unit
        override fun onDestroy() {
            bitmap = null
        }

        override fun hasStableIds(): Boolean = true

        /** 工作线程装配整周，整周无课时交由 Provider 的空视图显示。 */
        override fun onDataSetChanged() {
            // Provider 的内联集合路径已经读取过完整快照；系统服务兼容入口仍按需自行读取。
            snapshot = providedSnapshot ?: WidgetRepository.snapshot(context, offset)
            val current = snapshot
            val visibleDays = (1..7)
                .filter { it != 6 || current?.config?.showSat != false }
                .filter { it != 7 || current?.config?.showSun != false }
            bitmap =
                if (current != null && visibleDays.any { current.coursesOfDay(it).isNotEmpty() }) {
                    renderWeek(current)
                } else null
        }

        override fun getCount(): Int = if (bitmap == null) 0 else 1
        override fun getViewAt(position: Int): RemoteViews =
            RemoteViews(context.packageName, R.layout.item_schedule_widget).apply {
                setImageViewBitmap(R.id.iv_schedule, bitmap)
                setOnClickFillInIntent(R.id.ll_contentPanel, Intent())
            }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(position: Int): Long = 0L

        /**
         * 根据当前小部件实例宽度，以设备原生像素密度绘制整周课程网格。
         *
         * 旧实现固定生成约 540px 宽位图，并把 sp 数值直接作为像素使用，高密度桌面需要再次
         * 放大位图，文字和边框因此发虚。当前实现把所有 dp/sp 几何统一换算成目标像素，普通
         * 尺寸不再发生放大；只有极端超长课表才按像素预算等比降采样以控制 RemoteViews 内存。
         */
        private fun renderWeek(snap: WidgetSnapshot): Bitmap {
            val prefs = Prefs.get(context)
            val config = snap.config
            // 周视图小部件与主课表使用同一节次规则，但只在本次位图渲染中补齐占位时间，
            // 不修改 WidgetSnapshot 中供提醒调度读取的真实作息列表。
            val displayTimes = ScheduleNodeResolver.resolveDisplayTimes(
                configuredNodes = config?.nodes ?: snap.timeDetails.size,
                sourceTimes = snap.timeDetails,
            )
            val days = (1..7).filter { it != 6 || config?.showSat != false }
                .filter { it != 7 || config?.showSun != false }
            val nodes = displayTimes.size.coerceAtLeast(1)
            val logicalWidthDp = widthDp.coerceAtLeast(1f)
            val rowHeightDp = (config?.itemHeight ?: 64).toFloat() + 2f
            val logicalHeightDp = nodes * rowHeightDp
            val density = context.resources.displayMetrics.density
            val fontScale = context.resources.configuration.fontScale
            // 字号比例与日视图相同；宽度仅用于本次渲染，不改变用户在设置页保存的字号。
            val textScale = widthScale.coerceAtLeast(MIN_TEXT_SCALE)
            // 以设备密度为首选；超出像素预算时才降低每 dp 像素数，避免异常配置耗尽内存。
            val budgetScale = sqrt(MAX_BITMAP_PIXELS / (logicalWidthDp * logicalHeightDp))
            val pixelsPerDp = minOf(density, budgetScale).coerceAtLeast(MIN_PIXELS_PER_DP)
            val resultWidth = (logicalWidthDp * pixelsPerDp).roundToInt().coerceAtLeast(1)
            val resultHeight = (logicalHeightDp * pixelsPerDp).roundToInt().coerceAtLeast(1)
            val result = Bitmap.createBitmap(resultWidth, resultHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            val rowHeight = rowHeightDp * pixelsPerDp
            val colWidth = resultWidth / (days.size + 0.64f)
            val nodeWidth = colWidth * 0.64f
            val titleColor = color(prefs.widgetHeaderColor, Color.BLACK)
            if (config?.showGrid == true) {
                val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = androidx.core.graphics.ColorUtils.setAlphaComponent(titleColor, 64)
                    strokeWidth = pixelsPerDp
                }
                for (row in 0..nodes) {
                    val y = row * rowHeight
                    canvas.drawLine(nodeWidth, y, resultWidth.toFloat(), y, grid)
                }
                for (column in 0..days.size) {
                    val x = nodeWidth + column * colWidth
                    canvas.drawLine(x, 0f, x, resultHeight.toFloat(), grid)
                }
            }
            val label = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                textAlign = Paint.Align.CENTER
                this.color = titleColor
                textSize = 11f * textScale * pixelsPerDp * fontScale
                typeface = Typeface.DEFAULT_BOLD
            }
            displayTimes.forEachIndexed { index, time ->
                val top = index * rowHeight
                canvas.drawText(time.node.toString(), nodeWidth / 2, top + 23f * pixelsPerDp, label)
                if (config?.showTimeBar != false) {
                    label.textSize = 8f * textScale * pixelsPerDp * fontScale
                    label.typeface = Typeface.DEFAULT
                    canvas.drawText(time.startTime, nodeWidth / 2, top + 37f * pixelsPerDp, label)
                    canvas.drawText(time.endTime, nodeWidth / 2, top + 49f * pixelsPerDp, label)
                    label.textSize = 11f * textScale * pixelsPerDp * fontScale
                    label.typeface = Typeface.DEFAULT_BOLD
                }
            }
            days.forEachIndexed { column, day ->
                snap.coursesOfDay(day).forEach { (course, detail, _) ->
                    val top = (detail.startNode - 1).coerceAtLeast(0) * rowHeight + 2f * pixelsPerDp
                    val bottom = minOf(
                        resultHeight.toFloat(),
                        top + detail.step * rowHeight - 2f * pixelsPerDp
                    )
                    if (bottom <= top) return@forEach
                    val rect = RectF(
                        nodeWidth + column * colWidth + pixelsPerDp, top,
                        nodeWidth + (column + 1) * colWidth - pixelsPerDp, bottom
                    )
                    val base = color(course.color, Color.BLUE)
                    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.color = base
                        alpha =
                            if (prefs.widgetShowColor) (prefs.widgetItemAlpha * 2.55f).roundToInt()
                                .coerceIn(0, 255) else 0
                    }
                    val radius = (config?.itemRadius ?: 4).toFloat() * pixelsPerDp
                    canvas.drawRoundRect(rect, radius, radius, fill)
                    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.color = color(prefs.widgetStrokeColor, Color.TRANSPARENT)
                        if (prefs.widgetStrokeCompose) this.color =
                            (this.color and -0x1000000) or (base and 0x00ffffff)
                        style = Paint.Style.STROKE
                        strokeWidth = pixelsPerDp
                    }
                    canvas.drawRoundRect(rect, radius, radius, stroke)
                    val text = buildString {
                        if (config?.showTime == true) {
                            append(
                                if (detail.ownTime) detail.startTime else
                                    displayTimes.getOrNull(detail.startNode - 1)?.startTime.orEmpty()
                            )
                            append('\n')
                        }
                        append(course.courseName)
                        if (config?.showLocation != false && detail.room.isNotBlank()) append("\n@").append(
                            detail.room
                        )
                        if (config?.showTeacher != false && detail.teacher.isNotBlank()) append('\n').append(
                            detail.teacher
                        )
                    }
                    val textPaint =
                        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                            this.color = color(prefs.widgetTextColor, Color.WHITE)
                            if (prefs.widgetTextCompose) this.color =
                                androidx.core.graphics.ColorUtils.compositeColors(this.color, base)
                            // 周视图列宽更窄：在通用课程字号基础上减 1sp，再应用与其他文字相同的宽度比例。
                            textSize = (prefs.widgetTextSize - COURSE_TEXT_SIZE_REDUCTION_SP)
                                .coerceAtLeast(MIN_COURSE_TEXT_SIZE_SP) * textScale * pixelsPerDp * fontScale
                            typeface = Typeface.DEFAULT_BOLD
                        }
                    val textPadding = 3f * pixelsPerDp
                    val width = (rect.width() - textPadding * 2f).roundToInt().coerceAtLeast(1)
                    val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
                        .setIncludePad(false).build()
                    canvas.save()
                    canvas.clipRect(rect)
                    canvas.translate(rect.left + textPadding, top + 4f * pixelsPerDp)
                    layout.draw(canvas)
                    canvas.restore()
                }
            }
            return result
        }

        /** 解析用户颜色，遇到非法值时采用可读的默认色。 */
        private fun color(value: String, fallback: Int): Int =
            runCatching { Color.parseColor(value) }.getOrDefault(fallback)

        private companion object {
            /** 单张周视图位图最多约六百万像素，ARGB_8888 下约占 24MB。 */
            const val MAX_BITMAP_PIXELS = 6_000_000f

            /** 极端尺寸下仍保留每 dp 至少一个像素，防止生成不可读的小图。 */
            const val MIN_PIXELS_PER_DP = 1f

            /** 防御异常启动器宽度；正常桌面最小宽度远大于该比例对应的 1dp。 */
            const val MIN_TEXT_SCALE = 1f / 360f

            /** 周视图课程列较窄，课程文字在用户设置字号基础上减小一级。 */
            const val COURSE_TEXT_SIZE_REDUCTION_SP = 1f

            /** 防止损坏的偏好值在减小一级后生成非正文字尺寸。 */
            const val MIN_COURSE_TEXT_SIZE_SP = 1f
        }
    }
}
