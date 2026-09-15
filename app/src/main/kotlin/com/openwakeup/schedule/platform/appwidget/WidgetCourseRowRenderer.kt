package com.openwakeup.schedule.platform.appwidget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.core.database.entity.CourseDetailEntity
import com.openwakeup.schedule.core.database.entity.CourseEntity

/**
 * 日视图课程卡的原生 RemoteViews 渲染器。
 *
 * 课程文字交给 TextView 在桌面进程中按当前小部件宽度测量，避免固定 Bitmap 被二次缩放后发虚；
 * 颜色填充使用原生 shape；描边按当前显示尺寸绘制，使总宽度精确减小一个物理像素。
 */
object WidgetCourseRowRenderer {

    /** 返回连续课程的完整时间范围；自定义时间优先于作息表。 */
    fun times(snapshot: WidgetSnapshot, detail: CourseDetailEntity): Pair<String, String> =
        if (detail.ownTime) detail.startTime to detail.endTime else {
            snapshot.timeDetails.firstOrNull { it.node == detail.startNode }?.startTime.orEmpty() to
                    snapshot.timeDetails.firstOrNull { it.node == detail.startNode + detail.step - 1 }?.endTime.orEmpty()
        }

    /**
     * 创建一条可由 ListView/RemoteCollectionItems 自适应测量的课程卡。
     *
     * @param context 用于读取全局小部件样式
     * @param snapshot 当前课表快照
     * @param course 课程主体
     * @param detail 当前课程时间段
     * @param fillInIntent 集合项点击时交给 PendingIntentTemplate 的补充 Intent
     * @param widthDp 当前实例的可用宽度；仅影响显示比例，不修改用户保存的字号
     */
    fun create(
        context: Context,
        snapshot: WidgetSnapshot,
        course: CourseEntity,
        detail: CourseDetailEntity,
        fillInIntent: Intent = Intent(),
        widthDp: Float = 360f,
    ): RemoteViews {
        val prefs = Prefs.get(context)
        val courseColor = parseColor(course.color, Color.BLUE)
        val textColor = parseColor(prefs.widgetTextColor, Color.WHITE).let { configured ->
            if (prefs.widgetTextCompose) ColorUtils.compositeColors(
                configured,
                courseColor
            ) else configured
        }
        val configuredStroke = parseColor(prefs.widgetStrokeColor, Color.TRANSPARENT)
        // 辅助信息按颜色本身的 alpha 再乘用户百分比，课程名称保持原始文字颜色。
        val secondaryTextColor = ColorUtils.setAlphaComponent(
            textColor, (Color.alpha(textColor) * prefs.widgetSecondaryTextAlpha / 100f).toInt(),
        )
        val strokeColor = if (prefs.widgetStrokeCompose) {
            ColorUtils.setAlphaComponent(courseColor, Color.alpha(configuredStroke))
        } else {
            configuredStroke
        }
        val (startTime, endTime) = times(snapshot, detail)
        // 左侧节次使用基准字号，课程信息略大一级；两者共用卡片宽度比例以保持信息层级。
        // 先在设置字号上加 2sp，再应用同一比例，保持两列的视觉关系。
        val scale = widthDp.coerceAtLeast(1f) / 360f
        val leftTextSize = prefs.widgetTextSize * scale
        val courseInfoTextSize = (prefs.widgetTextSize + COURSE_INFO_TEXT_SIZE_OFFSET_SP) * scale
        val geometry = courseGeometry(context, widthDp, scale, prefs)

        return RemoteViews(context.packageName, R.layout.item_widget_course_native).apply {
            applyScaledGeometry(context, scale, geometry)
            setImageViewBitmap(
                R.id.widget_course_stroke,
                createStrokeBitmap(context, geometry),
            )
            // 使用原生布局尺寸 API，图标仍由矢量资源绘制，避免位图缩放导致模糊。
            // 地点图标的宽高均为教师图标的 98%，视觉面积会随宽高同步缩小。
            val teacherIconSizeDp = COURSE_DETAIL_ICON_SIZE_DP * scale
            val locationIconSizeDp = teacherIconSizeDp * LOCATION_ICON_SIZE_RATIO
            setViewLayoutWidth(
                R.id.widget_location_icon,
                locationIconSizeDp,
                TypedValue.COMPLEX_UNIT_DIP
            )
            setViewLayoutHeight(
                R.id.widget_location_icon,
                locationIconSizeDp,
                TypedValue.COMPLEX_UNIT_DIP
            )
            setViewLayoutWidth(
                R.id.widget_teacher_icon,
                teacherIconSizeDp,
                TypedValue.COMPLEX_UNIT_DIP
            )
            setViewLayoutHeight(
                R.id.widget_teacher_icon,
                teacherIconSizeDp,
                TypedValue.COMPLEX_UNIT_DIP
            )
            setViewLayoutWidth(R.id.widget_time_column, 60f * scale, TypedValue.COMPLEX_UNIT_DIP)
            // 教师列锚定在详情行右侧，并按当前字号预留约八个汉字；地点列只占用左侧剩余空间。
            // 字号与教师列使用同一宽度比例，因此缩放小部件后仍能保持相同的视觉位置和容量。
            val teacherColumnWidthDp = teacherIconSizeDp +
                    courseInfoTextSize * context.resources.configuration.fontScale * TEACHER_TEXT_CAPACITY
            setViewLayoutWidth(
                R.id.widget_teacher_group,
                teacherColumnWidthDp,
                TypedValue.COMPLEX_UNIT_DIP,
            )
            listOf(R.id.widget_start_node, R.id.widget_end_node).forEach { id ->
                setViewLayoutWidth(id, 20f * scale, TypedValue.COMPLEX_UNIT_DIP)
            }
            setTextViewText(R.id.widget_start_node, detail.startNode.toString())
            setTextViewText(R.id.widget_end_node, (detail.startNode + detail.step - 1).toString())
            setTextViewText(R.id.widget_start_time, startTime)
            setTextViewText(R.id.widget_end_time, endTime)
            setTextViewText(R.id.widget_course_name, course.courseName)
            setTextViewText(R.id.widget_location, detail.room)
            setTextViewText(R.id.widget_teacher, detail.teacher)

            LEFT_TEXT_IDS.forEach { id ->
                setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, leftTextSize)
                setTextColor(id, secondaryTextColor)
            }
            COURSE_INFO_TEXT_IDS.forEach { id ->
                setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, courseInfoTextSize)
                setTextColor(
                    id,
                    if (id == R.id.widget_course_name) textColor else secondaryTextColor
                )
            }
            // 某些桌面宿主会忽略颜色滤镜中的 alpha，因此将 RGB 与透明度分开设置。
            // 颜色滤镜只负责不透明 RGB，ImageView 自身 alpha 精确复用辅助文字的最终 alpha。
            val secondaryOpaqueColor = ColorUtils.setAlphaComponent(secondaryTextColor, 255)
            val secondaryAlpha = Color.alpha(secondaryTextColor)
            listOf(R.id.widget_location_icon, R.id.widget_teacher_icon).forEach { id ->
                setInt(id, "setColorFilter", secondaryOpaqueColor)
                setInt(id, "setImageAlpha", secondaryAlpha)
            }

            // 有教师但无地点时使用 INVISIBLE 保留地点列宽，避免教师图标移动到地点图标的位置。
            // 两项均为空时再移除地点列，防止无辅助信息的课程卡保留无意义的空白区域。
            val locationVisibility = when {
                detail.room.isNotBlank() -> View.VISIBLE
                detail.teacher.isNotBlank() -> View.INVISIBLE
                else -> View.GONE
            }
            setViewVisibility(R.id.widget_location_group, locationVisibility)
            setViewVisibility(
                R.id.widget_teacher_group,
                if (detail.teacher.isBlank()) View.GONE else View.VISIBLE,
            )
            setInt(
                R.id.widget_course_fill,
                "setColorFilter",
                ColorUtils.setAlphaComponent(courseColor, 255),
            )
            setInt(
                R.id.widget_course_fill,
                "setImageAlpha",
                if (prefs.widgetShowColor) {
                    (prefs.widgetItemAlpha * 2.55f).toInt().coerceIn(0, 255)
                } else {
                    0
                },
            )
            setInt(
                R.id.widget_course_stroke,
                "setColorFilter",
                ColorUtils.setAlphaComponent(strokeColor, 255),
            )
            setInt(R.id.widget_course_stroke, "setImageAlpha", Color.alpha(strokeColor))
            setOnClickFillInIntent(R.id.widget_course_root, fillInIntent)
        }
    }

    /**
     * 同步缩放课程卡高度、双行行高与留白，避免缩窄后留下固定 64dp 高的空旷卡片。
     *
     * 卡片高度与间隔根据实际课程卡宽度计算；较大字号时保留文字所需的最低高度。
     * 行高在文字所需高度上额外加入少量基线间距，并继续随宽度缩放；所有尺寸仅用于本次
     * RemoteViews 渲染。
     */
    private fun RemoteViews.applyScaledGeometry(
        context: Context,
        scale: Float,
        geometry: CourseGeometry
    ) {
        val density = context.resources.displayMetrics.density
        setViewLayoutHeight(
            R.id.widget_course_content,
            geometry.cardHeightDp + geometry.gapDp,
            TypedValue.COMPLEX_UNIT_DIP
        )
        listOf(
            R.id.widget_start_row, R.id.widget_end_row,
            R.id.widget_course_name, R.id.widget_course_detail,
        ).forEach { id ->
            setViewLayoutHeight(id, geometry.rowHeightDp, TypedValue.COMPLEX_UNIT_DIP)
        }
        val horizontalPadding = (10f * scale * density).toInt()
        setViewPadding(R.id.widget_course_content, horizontalPadding, 0, horizontalPadding, 0)
        setViewLayoutMargin(
            R.id.widget_teacher_group,
            RemoteViews.MARGIN_START,
            8f * scale,
            TypedValue.COMPLEX_UNIT_DIP
        )
        // 填充与描边的上下留白一起缩放，描边中心仍与填充边缘重合。
        listOf(RemoteViews.MARGIN_TOP, RemoteViews.MARGIN_BOTTOM).forEach { edge ->
            setViewLayoutMargin(
                R.id.widget_course_fill,
                edge,
                geometry.gapDp / 2f + 1f,
                TypedValue.COMPLEX_UNIT_DIP
            )
            setViewLayoutMargin(
                R.id.widget_course_stroke,
                edge,
                geometry.gapDp / 2f,
                TypedValue.COMPLEX_UNIT_DIP
            )
        }
    }

    /** 返回未乘宽度比例的行高，与描边画布尺寸共享计算，避免边框被二次拉伸。 */
    private fun baseRowHeight(context: Context, textSizeSp: Float): Float =
        maxOf(
            24f,
            (textSizeSp + COURSE_INFO_TEXT_SIZE_OFFSET_SP) * context.resources.configuration.fontScale * 1.4f
        )

    /** 同时供原生布局和描边画布使用的尺寸，避免分别计算导致边框拉伸。 */
    private data class CourseGeometry(
        val widthPx: Int,
        val cardHeightDp: Float,
        val gapDp: Float,
        val rowHeightDp: Float,
    )

    /**
     * 参考目标图：课程卡约宽 1032px、高 184px，两卡之间约留 19px。
     * 用扣除背景内边距后的课程卡宽度换算比例，满宽和缩窄状态都保持相同的视觉关系。
     * 全宽实测原高度约为 172px，增加 184/172 的高度校准系数；实测间距已为 19px，保持不变。
     */
    private fun courseGeometry(
        context: Context,
        widthDp: Float,
        scale: Float,
        prefs: Prefs
    ): CourseGeometry {
        val density = context.resources.displayMetrics.density
        val paddingPx = if (prefs.widgetShowBackground) (8f * density).toInt() * 2 else 0
        val widthPx = ((widthDp * density).toInt() - paddingPx).coerceAtLeast(1)
        val cardWidthDp = widthPx / density
        // 目标图的两行文字基线比当前实测稍疏；满宽基准增加 2dp，同时保持卡片整体高度不变。
        val rowHeightDp = (baseRowHeight(context, prefs.widgetTextSize.toFloat()) +
                COURSE_ROW_LINE_SPACING_DP) * scale
        return CourseGeometry(
            widthPx = widthPx,
            cardHeightDp = maxOf(
                cardWidthDp * 184f / 1032f * FULL_WIDTH_HEIGHT_CORRECTION,
                rowHeightDp * 2f + 8f * scale,
            ),
            gapDp = cardWidthDp * 19f / 1032f,
            rowHeightDp = rowHeightDp,
        )
    }

    /**
     * 返回指定宽度下一条完整课程卡占用的纵向空间，包含与下一张课程卡之间的间距。
     *
     * 设置页使用同一套几何计算安排预览窗口高度，避免预览容器与桌面小部件采用不同常量后
     * 把第二行课程信息裁掉。此方法只读取设置，不会修改用户保存的课程字号。
     *
     * @param context 用于读取屏幕密度、字体缩放和小部件设置
     * @param widthDp 全宽小部件实例的宽度，单位为 dp
     */
    fun courseRowHeightDp(context: Context, widthDp: Float): Float {
        val prefs = Prefs.get(context)
        val scale = widthDp.coerceAtLeast(1f) / 360f
        val geometry = courseGeometry(context, widthDp, scale, prefs)
        return geometry.cardHeightDp + geometry.gapDp
    }

    /**
     * 仅生成装饰描边，不栅格化课程文字和图标。
     *
     * RemoteViews 无法直接修改 GradientDrawable 的 stroke，因此按实例的实际像素尺寸绘制。
     * 总线宽从 2dp 对应的像素数减去 1px，中心线保持不动，内外两侧各减少 0.5px。
     */
    private fun createStrokeBitmap(context: Context, geometry: CourseGeometry): Bitmap {
        val density = context.resources.displayMetrics.density
        val widthPx = geometry.widthPx
        val heightPx = (geometry.cardHeightDp * density).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).apply {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = (2f * density - 1f).coerceAtLeast(0f)
            }
            // 沿用原 2dp 描边的中心线及圆角，保证填充色块的位置不发生偏移。
            val inset = density
            if (widthPx > inset * 2 && heightPx > inset * 2 && paint.strokeWidth > 0f) {
                Canvas(this).drawRoundRect(
                    RectF(inset, inset, widthPx - inset, heightPx - inset),
                    5f * density, 5f * density, paint,
                )
            }
        }
    }

    /** 安全解析 ARGB/RGB 字符串。 */
    private fun parseColor(value: String, fallback: Int): Int =
        runCatching { Color.parseColor(value) }.getOrDefault(fallback)

    private const val COURSE_INFO_TEXT_SIZE_OFFSET_SP = 2f

    /** 两行课程信息之间额外增加的满宽基线距离，会随小部件宽度同比缩放。 */
    private const val COURSE_ROW_LINE_SPACING_DP = 2f

    /** 地点和教师信息图标的满宽基准尺寸。 */
    private const val COURSE_DETAIL_ICON_SIZE_DP = 18f

    /** 教师文字列在任意字号下预留的汉字数量；教师图标据此从详情行右侧向左定位。 */
    private const val TEACHER_TEXT_CAPACITY = 8f

    /** 地点图标相对教师图标的线性尺寸比例，即宽高分别缩小 2%。 */
    private const val LOCATION_ICON_SIZE_RATIO = 0.98f

    /** 根据当前与目标全宽实测图校准卡高；不改变文字、图标或卡片间距的缩放比例。 */
    private const val FULL_WIDTH_HEIGHT_CORRECTION = 184f / 172f

    private val LEFT_TEXT_IDS = intArrayOf(
        R.id.widget_start_node,
        R.id.widget_end_node,
        R.id.widget_start_time,
        R.id.widget_end_time,
    )

    private val COURSE_INFO_TEXT_IDS = intArrayOf(
        R.id.widget_course_name,
        R.id.widget_location,
        R.id.widget_teacher,
    )
}
