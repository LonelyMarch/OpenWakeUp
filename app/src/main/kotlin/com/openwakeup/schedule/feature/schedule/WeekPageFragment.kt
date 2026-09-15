package com.openwakeup.schedule.feature.schedule

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.core.database.entity.CourseDetailEntity
import com.openwakeup.schedule.core.database.entity.CourseEntity
import com.openwakeup.schedule.core.database.entity.ScheduleShiftEntity
import com.openwakeup.schedule.core.database.entity.TableEntity
import com.openwakeup.schedule.core.database.entity.TimeDetailEntity
import com.openwakeup.schedule.core.designsystem.component.TipTextView
import com.openwakeup.schedule.core.schedule.ScheduleNodeResolver
import com.openwakeup.schedule.core.util.DateUtils
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 一周课表页面（主界面 ViewPager2 的每一页）：
 * 页根 ConstraintLayout = 星期表头（title0 月份 + title1..N "周X\n日"）+ ScrollView
 * （内容面板：节次列（64% 列宽）+ GridBackgroundView 虚线网格 + 每日课程列 + 空态 + 尾部留白）。
 * 课程显示先按每个节次解析：本周课程优先，无本周课程时选取查看周之后最近的非本周课程，
 * 然后把相邻且内容相同的节次合并为 [TipTextView]。同一周内多门课程重合时改为错误色冲突卡，
 * 点击后使用 Material 3 Expressive 居中弹窗展示所有冲突课程的信息。
 * 节次/面板/表头视图通过 [nodeId]/[panelId]/[titleId] 显式 id 映射定位。
 */
class WeekPageFragment : Fragment() {

    private var table: TableEntity? = null
    private var times: List<TimeDetailEntity> = emptyList()
    private var week = 1
    private var currentWeek = 0
    private var startDate: LocalDate = LocalDate.now()
    private var source: List<Pair<CourseEntity, CourseDetailEntity>> = emptyList()
    private var shifts: List<ScheduleShiftEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        week = arguments?.getInt("week") ?: 1
    }

    override fun onResume() {
        super.onResume()
        // 数据由 ScheduleActivity 推送到共享快照，页面就绪后自行拉取（Fragment 创建早于首次数据推送）
        latest?.let { s ->
            update(s.table, s.times, week, s.currentWeek, s.startDate, s.source, s.shifts)
        }
    }

    override fun onPause() {
        super.onPause()
        // 换周/离开页面/返回桌面时取消未提交草稿（屏幕旋转与进程回收同样不恢复草稿）
        quickAdd?.cancelDraft()
    }

    /** 课程卡点击（跳课程详情弹窗） */
    var onCourseClick: ((detailId: Long, week: Int) -> Unit)? = null

    private var pageRoot: ConstraintLayout? = null
    private var container: FrameLayout? = null
    private val panelViews = mutableListOf<FrameLayout>()

    /** 快速加课草稿层与完成回调（ScheduleActivity 借此打开已预填的添加课程页） */
    private var quickAdd: QuickAddOverlayLayout? = null

    /** 外观预览等只读场景设为 false，主课表页面保持默认开启。 */
    var isQuickAddEnabled: Boolean = true
        set(value) {
            field = value
            // 页面已构建后切换为只读时，同时清除可能存在的草稿，避免留下不可操作浮层。
            if (!value) quickAdd?.cancelDraft()
        }
    private var overlayItemId = View.generateViewId()
    var onQuickAddRequest: ((com.openwakeup.schedule.feature.schedule.quickadd.QuickAddDraft) -> Unit)? =
        null
    private var monthHeaderView: TextView? = null
    private val dayHeaderViews = mutableListOf<DayHeaderView>()
    private var nodeStartViews = mutableListOf<TextView>()
    private var nodeEndViews = mutableListOf<TextView>()
    private var emptyView: LinearLayout? = null
    private var blankView: View? = null
    private var gridId = 0
    private var blankId = View.generateViewId()

    /** 节次列右缘 guideline（表头与网格各一条，同一 percent，保证两处列宽一致） */
    private val nodeGuideId = View.generateViewId()
    private val headerGuideId = View.generateViewId()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val host = FrameLayout(requireContext())
        this.container = host
        val t = table
        if (pageRoot == null && t != null) {
            build(t, times)
            refresh()
        }
        pageRoot?.let { host.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        return host
    }

    /** 由 ScheduleActivity 在数据就绪后调用；首次触发构建 */
    fun update(
        table: TableEntity,
        times: List<TimeDetailEntity>,
        week: Int,
        currentWeek: Int,
        startDate: LocalDate,
        source: List<Pair<CourseEntity, CourseDetailEntity>>,
        shifts: List<ScheduleShiftEntity> = emptyList(),
    ) {
        // 周页面只消费显示专用作息：行数由课表配置决定，真实作息不足的尾部统一补为
        // 24:00-24:00；该列表不会回写数据库，也不会进入提醒时间计算。
        val displayTimes = ScheduleNodeResolver.resolveDisplayTimes(table.nodes, times)
        val rebuild = this.table != table || this.times != displayTimes || pageRoot == null
        this.table = table
        this.times = displayTimes
        this.week = week
        this.currentWeek = currentWeek
        this.startDate = startDate
        this.source = source
        this.shifts = shifts
        if (rebuild) {
            build(table, displayTimes)
            container?.let { host ->
                host.removeAllViews()
                pageRoot?.let { root ->
                    host.addView(root, FrameLayout.LayoutParams(-1, -1))
                }
            }
        }
        refresh()
    }

    // ================= id 映射 =================

    private val nodeIdCache = mutableMapOf<Int, Int>()

    /** 节次行 id：1~9 复用历史资源；10 及以上生成并缓存临时 id，保证同一节次稳定。 */
    private fun nodeId(n: Int): Int = nodeIdCache.getOrPut(n) {
        if (n in 1..9) {
            listOf(
                R.id.anko_tv_node1, R.id.anko_tv_node2, R.id.anko_tv_node3, R.id.anko_tv_node4,
                R.id.anko_tv_node5, R.id.anko_tv_node6, R.id.anko_tv_node7, R.id.anko_tv_node8,
                R.id.anko_tv_node9,
            )[n - 1]
        } else {
            // ids.xml 只声明到第 9 节，继续按名称反射必然失败，直接生成即可。
            View.generateViewId()
        }
    }

    private fun panelId(i: Int): Int = listOf(
        R.id.anko_ll_week_panel_0, R.id.anko_ll_week_panel_1, R.id.anko_ll_week_panel_2,
        R.id.anko_ll_week_panel_3, R.id.anko_ll_week_panel_4, R.id.anko_ll_week_panel_5,
        R.id.anko_ll_week_panel_6, R.id.anko_ll_week_panel_7,
    )[i]

    private fun titleId(i: Int): Int = listOf(
        R.id.anko_tv_title0, R.id.anko_tv_title1, R.id.anko_tv_title2, R.id.anko_tv_title3,
        R.id.anko_tv_title4, R.id.anko_tv_title5, R.id.anko_tv_title6, R.id.anko_tv_title7,
    )[i]

    // ================= 构建 =================

    private fun build(t: TableEntity, times: List<TimeDetailEntity>) {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density).roundToInt()

        val textColor = ScheduleThemeColors.tableTextColor(ctx, t.textColor)
        val headerSubColor =
            withAlpha(textColor, ((textColor ushr 24) * 0.32f).roundToInt().coerceIn(0, 255))
        val columnCount = 6 + (if (t.showSat) 1 else 0) + (if (t.showSun) 1 else 0)
        val nodeTextSp = when (columnCount) {
            // 时间文字统一缩小 1sp，避免与加粗后的节次序号争抢视觉层级。
            8 -> 9f
            7 -> 10f
            else -> 11f
        }
        val itemHeightPx = (t.itemHeight * density).roundToInt()
        val nodeGap = dp(2f)
        val rowCount = times.size.coerceAtLeast(1)
        val lastNodeId = nodeId(rowCount)

        val content = ConstraintLayout(ctx).apply {
            id = R.id.anko_cl_content_panel
            isMotionEventSplittingEnabled = false
        }

        // ---- 节次列（每行：序号 + 可选 tv_start/tv_end） ----
        nodeStartViews = mutableListOf()
        nodeEndViews = mutableListOf()
        for (n in 1..rowCount) {
            val row = LinearLayout(ctx).apply {
                id = nodeId(n)
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            row.addView(
                TextView(ctx).apply {
                    setTextColor(textColor)
                    text = n.toString()
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    // 节次序号是左侧时间轴的主信息，使用粗体提高逐行定位效率。
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setSingleLine()
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            if (t.showTimeBar) {
                val start = TextView(ctx).apply {
                    id = R.id.tv_start
                    // 行头时间属于辅助信息，与未高亮的星期和日期使用相同灰色层级。
                    setTextColor(headerSubColor)
                    setSingleLine()
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, nodeTextSp)
                }
                val end = TextView(ctx).apply {
                    id = R.id.tv_end
                    setTextColor(headerSubColor)
                    setSingleLine()
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, nodeTextSp)
                }
                row.addView(
                    start, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                row.addView(
                    end, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                nodeStartViews.add(start)
                nodeEndViews.add(end)
            }
            content.addView(row, ConstraintLayout.LayoutParams(0, itemHeightPx).apply {
                topMargin = nodeGap
            })
        }

        // ---- 空态（本周无课程） ----
        val empty = LinearLayout(ctx).apply {
            id = R.id.anko_empty_view
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            // 固定为上一轮 128dp 的 150%，避免 drawable 无密度原图按 439px 不确定缩放。
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_schedule_empty)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(192f), dp(192f)))
            addView(
                TextView(ctx).apply {
                    text = getString(R.string.tips_this_week_is_empty)
                    setTextColor(textColor)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8f) })
        }
        content.addView(empty)
        emptyView = empty

        // ---- 尾部留白（全局设置控制，关闭时高度为 0） ----
        val blank = View(ctx).apply { id = blankId }
        blankView = blank
        content.addView(
            blank, ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (Prefs.get(ctx).scheduleBlankArea) itemHeightPx * 4 else 0,
            )
        )

        // ---- 网格（showGrid） ----
        gridId = View.NO_ID
        val grid = if (t.showGrid) {
            val grid = GridBackgroundView(ctx).apply { id = View.generateViewId() }
            gridId = grid.id
            grid.verticalMargin = nodeGap
            grid.horizontalMargin = dp(2f)
            grid.col = columnCount - 1
            grid.row = rowCount
            grid.color = textColor
            // 固定插入到内容层最底部，行头、课程列和快速添加层都绘制在辅助线之上。
            content.addView(grid, 0, ConstraintLayout.LayoutParams(0, 0))
            grid
        } else {
            null
        }

        // ---- 每日课程列 ----
        panelViews.clear()
        val touchSlop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        for (i in 0 until columnCount - 1) {
            val panel = FrameLayout(ctx).apply {
                id = panelId(i)
                isMotionEventSplittingEnabled = false
            }
            // 空白格轻点命中：课程卡作为可点击子视图优先消费事件，
            // 空白区域事件落到面板 → 上抛视为一次轻点（移动超 touchSlop 或被
            // ScrollView/ViewPager 拦截取消时不算，保持滚动/换周手势不变）。
            var downY = 0f
            panel.setOnTouchListener { v, event ->
                if (!isQuickAddEnabled) return@setOnTouchListener false
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downY = event.y
                        true
                    }

                    android.view.MotionEvent.ACTION_UP -> {
                        if (abs(event.y - downY) <= touchSlop) {
                            // 报告标准点击事件后再按纵坐标定位空白节次，兼顾 TalkBack 事件语义。
                            v.performClick()
                            onEmptyCellTap(v, event.y)
                        }
                        true
                    }

                    else -> false
                }
            }
            content.addView(panel, ConstraintLayout.LayoutParams(0, 0))
            panelViews.add(panel)
        }
        // 网格直接读取约束求解后的真实课程列和节次行边界，避免平均值推算产生像素偏移。
        grid?.columnViews = panelViews.toList()
        grid?.rowViews = (1..rowCount).map { index -> content.findViewById<View>(nodeId(index)) }

        // ---- 快速加课草稿层（课程卡之上、表头之下；无草稿时触摸穿透） ----
        val overlay = QuickAddOverlayLayout(ctx).apply {
            id = View.generateViewId()
            clipChildren = false
        }
        // 几何参数随网格重建绑定（节高/行距/可拉伸节数上限/圆角跟随课表配置）
        overlay.configure(
            com.openwakeup.schedule.feature.schedule.quickadd.QuickAddController(
                itemHeightPx + nodeGap, nodeGap, itemHeightPx, minOf(t.nodes, rowCount),
            ),
            t.itemRadius,
        )
        content.addView(overlay, ConstraintLayout.LayoutParams(0, 0))
        quickAdd = overlay
        overlayItemId = overlay.id

        // ---- 页根：表头 + 滚动区 ----
        val root = ConstraintLayout(ctx)
        monthHeaderView = null
        dayHeaderViews.clear()
        for (i in 0 until columnCount) {
            val title = if (i == 0) {
                TextView(ctx).apply {
                    id = titleId(i)
                    setPadding(0, dp(8f), 0, dp(8f))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, t.headerTextSize.toFloat())
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(textColor)
                    monthHeaderView = this
                }
            } else {
                val weekdayView = TextView(ctx).apply {
                    gravity = Gravity.CENTER
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, t.headerTextSize.toFloat())
                }
                val dateView = TextView(ctx).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    minWidth = dp(TODAY_DATE_SIZE_DP)
                    minHeight = dp(TODAY_DATE_SIZE_DP)
                    // 两位日号只保留少量横向留白，使背景仍接近目标图中的紧凑正方形。
                    setPadding(dp(2f), 0, dp(2f), 0)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, t.headerTextSize.toFloat())
                }
                LinearLayout(ctx).apply {
                    id = titleId(i)
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(0, dp(5f), 0, dp(5f))
                    addView(
                        weekdayView,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                    addView(
                        dateView,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            dp(TODAY_DATE_SIZE_DP),
                        ).apply { topMargin = dp(2f) },
                    )
                    dayHeaderViews += DayHeaderView(weekdayView, dateView)
                }
            }
            root.addView(
                title,
                ConstraintLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        val scroll = ScrollView(ctx).apply {
            id = R.id.anko_sv_schedule
            isVerticalScrollBarEnabled = false
            addView(
                content, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(scroll, ConstraintLayout.LayoutParams(0, 0))
        pageRoot = root

        // ---- 约束（ConstraintSet） ----
        applyConstraints(
            root,
            content,
            rowCount,
            lastNodeId,
            columnCount,
            itemHeightPx,
            nodeGap
        ) { v -> dp(v) }
    }

    /**
     * 布局约束：节次列:日列 = 0.64:1.0（共 columnCount 列），
     * 以父宽百分比确定性求解（ConstraintLayout 联立百分比求解在独立构建时不可复现，比例结果等价）。
     */
    private fun applyConstraints(
        root: ConstraintLayout,
        content: ConstraintLayout,
        rowCount: Int,
        lastNodeId: Int,
        columnCount: Int,
        itemHeightPx: Int,
        nodeGap: Int,
        dp: (Float) -> Int
    ) {
        val unit = 0.64f + (columnCount - 1)
        // 留 3% 余量给列间 1dp 与末列 8dp 边距，防止末列被压缩
        val nodePct = 0.64f / unit * 0.97f
        val panelPct = 1f / unit * 0.97f
        val cs = ConstraintSet()
        cs.clone(content)

        // 节次列右缘用一条竖向 guideline 定位：
        // 原先各行靠 constrainPercentWidth 各自求解，第 1 行因被 grid/panel0/空态 引用 END
        // 解出的宽度与其余行不同（62px vs 88px），行号与时间就对不齐。
        cs.create(nodeGuideId, ConstraintSet.VERTICAL_GUIDELINE)
        cs.setGuidelinePercent(nodeGuideId, nodePct)

        // 节次行：贴左，右缘到 guideline，纵向链 + 2dp 间距
        for (n in 1..rowCount) {
            val id = nodeId(n)
            cs.constrainHeight(id, itemHeightPx)
            cs.constrainWidth(id, ConstraintSet.MATCH_CONSTRAINT)
            cs.connect(id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
            cs.connect(id, ConstraintSet.END, nodeGuideId, ConstraintSet.START, 0)
            if (n == 1) {
                cs.connect(
                    id,
                    ConstraintSet.TOP,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.TOP,
                    nodeGap
                )
            } else {
                cs.connect(id, ConstraintSet.TOP, nodeId(n - 1), ConstraintSet.BOTTOM, nodeGap)
            }
            // 末行不再 BOTTOM→parent：那会让它在"最后一屏剩余高度"里被居中拉开，
            // 与其余行占位高度不一致（第 12 行比第 11 行低 845px 而非一行高）。
        }
        // 空态：相对整屏水平居中，并略高于网格垂直中心，避免文案视觉重心偏下。
        cs.constrainHeight(R.id.anko_empty_view, ConstraintSet.WRAP_CONTENT)
        cs.constrainWidth(R.id.anko_empty_view, ConstraintSet.MATCH_CONSTRAINT)
        cs.connect(
            R.id.anko_empty_view,
            ConstraintSet.START,
            ConstraintSet.PARENT_ID,
            ConstraintSet.START,
            0
        )
        cs.connect(
            R.id.anko_empty_view,
            ConstraintSet.END,
            ConstraintSet.PARENT_ID,
            ConstraintSet.END,
            0
        )
        cs.connect(
            R.id.anko_empty_view,
            ConstraintSet.TOP,
            ConstraintSet.PARENT_ID,
            ConstraintSet.TOP,
            0
        )
        cs.connect(R.id.anko_empty_view, ConstraintSet.BOTTOM, lastNodeId, ConstraintSet.BOTTOM, 0)
        cs.setVerticalBias(R.id.anko_empty_view, 0.44f)
        // 留白：紧随最后一节（决定 content 的 WRAP_CONTENT 高度）
        cs.connect(blankId, ConstraintSet.TOP, lastNodeId, ConstraintSet.BOTTOM, 0)
        cs.connect(blankId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
        cs.connect(blankId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)
        // 网格：节次列右缘 → 父右缘；关闭网格时不存在对应视图，不向 ConstraintSet 写入无效 id。
        if (gridId != View.NO_ID) {
            cs.connect(gridId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 0)
            cs.connect(gridId, ConstraintSet.BOTTOM, lastNodeId, ConstraintSet.BOTTOM, 0)
            cs.connect(gridId, ConstraintSet.START, nodeGuideId, ConstraintSet.START, 0)
            cs.connect(
                gridId,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END,
                if (columnCount < 8) dp(8f) else dp(4f)
            )
            cs.constrainWidth(gridId, ConstraintSet.MATCH_CONSTRAINT)
            cs.constrainHeight(gridId, ConstraintSet.MATCH_CONSTRAINT)
        }
        // 每日列：百分比宽 + 1dp 间距链
        for (i in 0 until columnCount - 1) {
            val pid = panelId(i)
            cs.constrainWidth(pid, ConstraintSet.MATCH_CONSTRAINT)
            cs.constrainHeight(pid, ConstraintSet.MATCH_CONSTRAINT)
            cs.connect(pid, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 0)
            cs.connect(pid, ConstraintSet.BOTTOM, lastNodeId, ConstraintSet.BOTTOM, 0)
            if (i == 0) {
                cs.connect(pid, ConstraintSet.START, nodeGuideId, ConstraintSet.START, 0)
            } else {
                cs.connect(pid, ConstraintSet.START, panelId(i - 1), ConstraintSet.END, dp(1f))
            }
            if (i == columnCount - 2) {
                cs.connect(
                    pid,
                    ConstraintSet.END,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.END,
                    if (columnCount < 8) dp(8f) else dp(4f)
                )
            } else {
                cs.connect(pid, ConstraintSet.END, panelId(i + 1), ConstraintSet.START, dp(1f))
            }
            cs.constrainPercentWidth(pid, panelPct)
        }
        // 快速加课草稿层：与日列区域同界（首列左缘 → 末列右缘），绘制顺序在日列之上
        cs.constrainWidth(overlayItemId, ConstraintSet.MATCH_CONSTRAINT)
        cs.constrainHeight(overlayItemId, ConstraintSet.MATCH_CONSTRAINT)
        cs.connect(overlayItemId, ConstraintSet.START, nodeGuideId, ConstraintSet.START, 0)
        cs.connect(
            overlayItemId,
            ConstraintSet.END,
            ConstraintSet.PARENT_ID,
            ConstraintSet.END,
            if (columnCount < 8) dp(8f) else dp(4f)
        )
        cs.connect(overlayItemId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 0)
        cs.connect(overlayItemId, ConstraintSet.BOTTOM, lastNodeId, ConstraintSet.BOTTOM, 0)
        cs.applyTo(content)

        // 页根约束：表头同比例 + 滚动区
        val rs = ConstraintSet()
        rs.clone(root)
        // 表头首列（"8月"）右缘同样贴 guideline，与下方节次列同宽（原先百分比各自求解得 73px vs 88px）
        rs.create(headerGuideId, ConstraintSet.VERTICAL_GUIDELINE)
        rs.setGuidelinePercent(headerGuideId, nodePct)
        for (i in 0 until columnCount) {
            val tid = titleId(i)
            rs.constrainWidth(tid, ConstraintSet.MATCH_CONSTRAINT)
            rs.constrainHeight(tid, ConstraintSet.WRAP_CONTENT)
            rs.connect(tid, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 0)
            if (i == 0) {
                rs.connect(
                    tid,
                    ConstraintSet.START,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.START,
                    0
                )
                rs.connect(tid, ConstraintSet.END, headerGuideId, ConstraintSet.START, 0)
            } else {
                if (i == 1) {
                    rs.connect(tid, ConstraintSet.START, headerGuideId, ConstraintSet.START, 0)
                } else {
                    rs.connect(tid, ConstraintSet.START, titleId(i - 1), ConstraintSet.END, dp(1f))
                }
                if (i == columnCount - 1) {
                    rs.connect(
                        tid,
                        ConstraintSet.END,
                        ConstraintSet.PARENT_ID,
                        ConstraintSet.END,
                        if (columnCount < 8) dp(8f) else dp(4f)
                    )
                } else {
                    rs.connect(tid, ConstraintSet.END, titleId(i + 1), ConstraintSet.START, dp(1f))
                }
                rs.constrainPercentWidth(tid, panelPct)
            }
        }
        val scrollId = R.id.anko_sv_schedule
        rs.constrainWidth(scrollId, ConstraintSet.MATCH_CONSTRAINT)
        rs.constrainHeight(scrollId, ConstraintSet.MATCH_CONSTRAINT)
        rs.connect(scrollId, ConstraintSet.TOP, titleId(1), ConstraintSet.BOTTOM, 0)
        rs.connect(scrollId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
        rs.connect(scrollId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)
        rs.connect(scrollId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 0)
        rs.applyTo(root)
    }

    // ================= 快速加课（空白格命中/草稿回调） =================

    /**
     * 空白格轻点：把草稿放到点击的日列与节次上（已存在草稿时=移动锚点并恢复一格）。
     * 隐藏列、尾部留白与范围外不产生草稿；节点以"点击点所在格"归属。
     */
    private fun onEmptyCellTap(panel: View, y: Float) {
        if (!isQuickAddEnabled) return
        val t = table ?: return
        val qa = quickAdd ?: return
        val index = panelViews.indexOf(panel)
        if (index < 0) return
        val day = weekDays(t).getOrNull(index)?.dayOfWeek?.value ?: return
        val density = resources.displayMetrics.density
        val nodeGap = (2f * density).roundToInt()
        val pitch = (t.itemHeight * density).roundToInt() + nodeGap
        val maxNode = minOf(t.nodes, times.size.coerceAtLeast(1))
        val controller = com.openwakeup.schedule.feature.schedule.quickadd.QuickAddController(
            pitch, nodeGap, (t.itemHeight * density).roundToInt(), maxNode,
        )
        if (!controller.inGrid(y)) return
        val node = controller.nodeAt(y, byCenter = false)
        // 命中的格必须完整存在于网格内（最后一格按实际格底判断）
        if (y > controller.gridBottom) return

        fun place() {
            qa.showDraftAt(
                (panel.x - qa.x).roundToInt(),
                panel.width,
                t.id,
                week,
                day,
                node,
            )
        }
        if (qa.isLaidOut) place() else qa.post { place() }
        qa.onFinished = { draft ->
            onQuickAddRequest?.invoke(draft)
        }
        qa.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // ================= 刷新（节次时间/表头/课程卡） =================

    private fun refresh() {
        val t = table ?: return
        // 课程数据刷新前先取消草稿，避免坐标基线变化后草稿悬空
        quickAdd?.cancelDraft()
        val root = pageRoot ?: return
        val density = resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density).roundToInt()

        // 全局留白开关可能在页面存活期间变化，因此每次刷新都同步高度。
        blankView?.layoutParams?.let { params ->
            params.height = if (Prefs.get(requireContext()).scheduleBlankArea) {
                (t.itemHeight * density).roundToInt() * 4
            } else {
                0
            }
            blankView?.layoutParams = params
        }

        // 节次时间
        nodeStartViews.forEachIndexed { i, tv ->
            times.getOrNull(i)?.let { tv.text = it.startTime }
        }
        nodeEndViews.forEachIndexed { i, tv ->
            times.getOrNull(i)?.let { tv.text = it.endTime }
        }

        // 表头：日列只显示日号，今天的日号使用高对比圆角色块突出。
        val textColor = ScheduleThemeColors.tableTextColor(requireContext(), t.textColor)
        val subColor =
            withAlpha(textColor, ((textColor ushr 24) * 0.32f).roundToInt().coerceIn(0, 255))
        val days = weekDays(t)
        val weekStart = DateUtils.mondayOfWeek(startDate).plusWeeks((week - 1).toLong())
        monthHeaderView?.text = getString(R.string.month_header, weekStart.monthValue)
        dayHeaderViews.forEachIndexed { index, header ->
            val day = days.getOrNull(index)
            if (day == null) {
                header.weekday.text = ""
                header.date.text = ""
                header.date.background = null
                return@forEachIndexed
            }
            header.weekday.text = weekdayShort(day.dayOfWeek.value)
            header.date.text = day.dayOfMonth.toString()
            val isToday = day == LocalDate.now()
            header.weekday.setTypeface(
                Typeface.DEFAULT,
                if (isToday) Typeface.BOLD else Typeface.NORMAL
            )
            header.weekday.setTextColor(if (isToday) textColor else subColor)
            header.date.setTypeface(
                Typeface.DEFAULT,
                if (isToday) Typeface.BOLD else Typeface.NORMAL
            )
            if (isToday) {
                val opaqueHighlight = ColorUtils.setAlphaComponent(textColor, 255)
                header.date.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = resources.displayMetrics.density * TODAY_DATE_CORNER_RADIUS_DP
                    setColor(opaqueHighlight)
                }
                header.date.setTextColor(
                    if (ColorUtils.calculateLuminance(opaqueHighlight) > 0.5) Color.BLACK else Color.WHITE,
                )
            } else {
                header.date.background = null
                header.date.setTextColor(subColor)
            }
        }

        // 课程卡
        panelViews.forEach { it.removeAllViews() }
        days.forEachIndexed { panelIndex, date ->
            if (date == null) return@forEachIndexed
            val panel = panelViews.getOrNull(panelIndex) ?: return@forEachIndexed
            val hasOutgoingShift = shifts.any { it.fromDate == date.toString() }

            val regularCourses = if (hasOutgoingShift) {
                emptyList()
            } else {
                source.filter { (_, detail) -> detail.day == date.dayOfWeek.value }
            }
            val shiftedCourses = shifts.filter { it.toDate == date.toString() }.flatMap { shift ->
                val originalDate = runCatching { LocalDate.parse(shift.fromDate) }.getOrNull()
                    ?: return@flatMap emptyList()
                source.filter { (_, detail) ->
                    detail.day == originalDate.dayOfWeek.value && detailActiveOnDate(
                        detail,
                        originalDate
                    )
                }
            }
            val currentWeekCourses = regularCourses
                .filter { (_, detail) -> detailActiveOnDate(detail, date) } + shiftedCourses
            val otherWeekCourses = regularCourses
                .filterNot { (_, detail) -> detailActiveOnDate(detail, date) }

            // 逐节解析后再合并连续片段，保证课程只在真正重合的节次让位，前后未重合部分仍可见。
            WeekCourseDisplayResolver.resolve(
                currentWeekCourses = currentWeekCourses,
                otherWeekCourses = otherWeekCourses,
                viewedWeek = DateUtils.currentWeek(startDate, date),
                nodeCount = times.size,
                showOtherWeekCourse = t.showOtherWeekCourse,
            ).forEach { segment ->
                if (segment.selection.courses.size > 1) {
                    createConflictCard(t, panel, segment, date, { value -> dp(value) })
                } else {
                    val occurrence = segment.selection.courses.single()
                    createCourseCard(
                        t = t,
                        course = occurrence.course,
                        detail = occurrence.detail,
                        panel = panel,
                        dp = { value -> dp(value) },
                        inWeek = segment.selection.inWeek,
                        effectiveWeek = segment.selection.sourceWeek,
                        segmentStartNode = segment.startNode,
                        segmentStep = segment.step,
                    )
                }
            }
        }
        emptyView?.visibility =
            if (panelViews.all { it.childCount == 0 }) View.VISIBLE else View.GONE
        // 课程卡列表变化后重新绘制网格，使辅助线遮罩立即使用最新的课程位置。
        if (gridId != View.NO_ID) root.findViewById<GridBackgroundView>(gridId)?.invalidate()
    }

    /** 判断某条循环课程在指定自然日期是否实际生效。 */
    private fun detailActiveOnDate(detail: CourseDetailEntity, date: LocalDate): Boolean {
        val dateWeek = DateUtils.currentWeek(startDate, date)
        return date.dayOfWeek.value == detail.day &&
                dateWeek in detail.startWeek..detail.endWeek &&
                (detail.type == CourseDetailEntity.TYPE_ALL ||
                        (detail.type == CourseDetailEntity.TYPE_ODD && dateWeek % 2 == 1) ||
                        (detail.type == CourseDetailEntity.TYPE_EVEN && dateWeek % 2 == 0))
    }

    /**
     * 创建单张课程片段卡。
     *
     * [segmentStartNode] 与 [segmentStep] 是逐节冲突解析后的可见范围，不必等于数据库时间段的完整范围；
     * 课程教师、地点和点击详情仍取自原始 [detail]，从而只裁剪重合部分而不丢失课程信息。
     *
     * @param t 当前课表的外观配置
     * @param course 课程基础信息
     * @param detail 课程原始时间段
     * @param panel 当天课程列容器
     * @param dp dp 到 px 的页面局部转换函数
     * @param inWeek 是否为当前查看周课程
     * @param effectiveWeek 当前课程片段实际生效的周次；非本周课程为距离查看周最近的未来周
     * @param segmentStartNode 当前可见片段的起始节次
     * @param segmentStep 当前可见片段包含的连续节数
     */
    private fun createCourseCard(
        t: TableEntity,
        course: CourseEntity,
        detail: CourseDetailEntity,
        panel: FrameLayout,
        dp: (Float) -> Int,
        inWeek: Boolean,
        effectiveWeek: Int,
        segmentStartNode: Int,
        segmentStep: Int,
    ) {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density
        val itemHeightPx = (t.itemHeight * density).roundToInt()
        val nodeGap = dp(2f)
        var step = segmentStep
        var startNode = segmentStartNode
        if (step <= 0) step = 1
        if (startNode > times.size) startNode = times.size
        if (startNode + step - 1 > times.size) step = times.size - startNode + 1
        if (startNode <= 0 || step <= 0) return

        val cardColor = parseColor(course.color, ContextCompat.getColor(ctx, R.color.blue))
        val text = StringBuilder()
        val detailText = StringBuilder()
        if (t.showTeacher && detail.teacher.isNotBlank()) {
            detailText.append("\n").append(detail.teacher)
        }
        if (t.showTime) {
            if (detail.ownTime) {
                text.append(detail.startTime).append("\n")
            } else {
                times.getOrNull(startNode - 1)?.let { text.append(it.startTime).append("\n") }
            }
        }
        text.append(course.courseName)
        if (t.showLocation && detail.room.isNotBlank()) {
            text.append("\n").append(if (t.showRoomPrefix) "@" else "").append(detail.room)
        }
        if (!t.showTeacher) {
            text.append(detailText)
        }
        if (!inWeek) {
            if (detail.type == 1) detailText.append("\n")
                .append(getString(R.string.week_type_odd_short))
            if (detail.type == 2) detailText.append("\n")
                .append(getString(R.string.week_type_even_short))
        } else if (detail.startWeek != detail.endWeek || detail.type != 0) {
            if (detail.type == 1) detailText.append("\n")
                .append(getString(R.string.week_type_odd_short))
            if (detail.type == 2) detailText.append("\n")
                .append(getString(R.string.week_type_even_short))
        }

        val card = TipTextView(ctx)
        card.setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        card.label = if (inWeek) "" else getString(R.string.not_this_week)
        card.text = text.toString()
        card.detail = detailText.toString()
        card.cornerRadius = t.itemRadius * density

        val courseTextColor = parseColor(t.courseTextColor, Color.WHITE)

        // textColorCompose：把课程文字色（可为半透明黑）按 alpha 混合到课程色上
        fun composeText(base: Int): Int {
            if (!t.textColorCompose) return base
            val fa = Color.alpha(base) / 255f
            val r = (Color.red(base) * fa + Color.red(cardColor) * (1 - fa)).roundToInt()
            val g = (Color.green(base) * fa + Color.green(cardColor) * (1 - fa)).roundToInt()
            val b = (Color.blue(base) * fa + Color.blue(cardColor) * (1 - fa)).roundToInt()
            return Color.argb(255, r, g, b)
        }
        card.mTextPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = t.itemTextSize * density
            typeface = Typeface.DEFAULT_BOLD
            color = composeText(courseTextColor)
        }
        card.mDetailPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (t.itemTextSize - 1) * density
            color = composeText(courseTextColor)
        }
        val nonWeekLabelTextSizePx = (t.itemTextSize - NON_WEEK_LABEL_SIZE_OFFSET_SP)
            .coerceAtLeast(NON_WEEK_LABEL_MIN_SIZE_SP) * density
        card.labelBaseTextSizePx = nonWeekLabelTextSizePx
        card.mLabelPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            // 状态标签先比课程正文小 3sp；TipTextView 会在窄列中继续按实际宽度缩小，保证只占一行。
            textSize = nonWeekLabelTextSizePx
            typeface = Typeface.DEFAULT_BOLD
            color = composeText(courseTextColor)
        }
        card.mPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = composeText(courseTextColor)
            isDither = true
            style = android.graphics.Paint.Style.FILL_AND_STROKE
            strokeWidth = 2 * density
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        card.bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = cardColor
            isDither = true
            style = android.graphics.Paint.Style.FILL
            alpha = (t.itemAlpha * 2.55f).roundToInt().coerceIn(0, 255)
        }
        card.strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = if (t.strokeColorCompose) {
                withAlpha(cardColor, Color.alpha(parseColor(t.strokeColor, 179)))
            } else {
                parseColor(t.strokeColor, withAlpha(Color.WHITE, 179))
            }
            isDither = true
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2 * density
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            if (t.useDottedLine) {
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 8f), 0f)
            }
        }
        val otherAlpha = if (inWeek) 1f else t.otherWeekCourseAlpha / 100f
        card.textAlpha = (card.mTextPaint.alpha * otherAlpha).roundToInt()
        card.bgAlpha = (card.bgPaint.alpha * otherAlpha).roundToInt()
        card.strokeAlpha = (card.strokePaint.alpha * otherAlpha).roundToInt()

        card.isCenter = t.itemCenterVertical
        if (t.itemCenterHorizontal) {
            card.alignment = android.text.Layout.Alignment.ALIGN_CENTER
        }

        if (!inWeek) {
            card.tipVisibility = TipTextView.TIP_ALPHA_REFRESH
        }
        card.setOnClickListener {
            // 非本周卡必须传递其实际生效周，否则详情页会按当前页面周次错误判断单双周冲突。
            quickAdd?.cancelDraft()
            onCourseClick?.invoke(detail.id, effectiveWeek)
        }

        // 放置：top = (节高+2dp)×(startNode-1)+2dp；高 = (step-1)×2dp + step×节高
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            (step - 1) * nodeGap + step * itemHeightPx
        )
        lp.gravity = Gravity.TOP
        lp.topMargin = (itemHeightPx + nodeGap) * (startNode - 1) + nodeGap
        panel.addView(card, lp)
    }

    /**
     * 创建课程冲突片段卡。
     *
     * 卡片使用 Material 主题的 errorContainer/onErrorContainer 配色和 Material Symbols 感叹号图标；
     * 非本周冲突仍服从课表的非本周透明度。点击卡片会取消快速加课草稿并显示居中冲突详情弹窗。
     *
     * @param t 当前课表的外观配置
     * @param panel 当天课程列容器
     * @param segment 冲突课程片段
     * @param date 当前列代表的自然日期
     * @param dp dp 到 px 的页面局部转换函数
     */
    private fun createConflictCard(
        t: TableEntity,
        panel: FrameLayout,
        segment: WeekCourseDisplaySegment,
        date: LocalDate,
        dp: (Float) -> Int,
    ) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val itemHeightPx = (t.itemHeight * density).roundToInt()
        val nodeGap = dp(2f)
        val containerColor = MaterialColors.getColor(
            ctx,
            com.google.android.material.R.attr.colorErrorContainer,
            Color.RED,
        )
        val contentColor = MaterialColors.getColor(
            ctx,
            com.google.android.material.R.attr.colorOnErrorContainer,
            Color.WHITE,
        )
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2f), dp(3f), dp(2f), dp(3f))
            if (!segment.selection.inWeek) {
                addView(
                    MaterialTextView(ctx).apply {
                        text = getString(R.string.not_this_week)
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        maxLines = 1
                        setTextColor(contentColor)
                        // Material TextView 的原生自动字号根据冲突格实测宽度缩放，禁止标签折成两行。
                        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                            this,
                            NON_WEEK_LABEL_AUTO_SIZE_MIN_SP,
                            (t.itemTextSize - NON_WEEK_LABEL_SIZE_OFFSET_SP)
                                .coerceAtLeast(NON_WEEK_LABEL_AUTO_SIZE_MIN_SP + 1),
                            1,
                            android.util.TypedValue.COMPLEX_UNIT_SP,
                        )
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(1f) })
            }
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ms_error_24)
                imageTintList = ColorStateList.valueOf(contentColor)
                contentDescription = getString(R.string.course_conflict)
            }, LinearLayout.LayoutParams(dp(18f), dp(18f)))
            addView(
                createConflictCardTitle(t, contentColor), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(1f) })
        }
        val card = MaterialCardView(ctx).apply {
            radius = t.itemRadius * density
            cardElevation = 0f
            setCardBackgroundColor(containerColor)
            strokeColor = ColorUtils.setAlphaComponent(contentColor, CONFLICT_CARD_STROKE_ALPHA)
            strokeWidth = dp(1f)
            alpha = if (segment.selection.inWeek) {
                1f
            } else {
                t.otherWeekCourseAlpha.coerceIn(0, 100) / 100f
            }
            isClickable = true
            isFocusable = true
            addView(
                content, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            )
            setOnClickListener {
                quickAdd?.cancelDraft()
                showCourseConflictDialog(segment, date)
            }
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            (segment.step - 1) * nodeGap + segment.step * itemHeightPx,
        ).apply {
            gravity = Gravity.TOP
            topMargin = (itemHeightPx + nodeGap) * (segment.startNode - 1) + nodeGap
        }
        panel.addView(card, lp)
    }

    /**
     * 创建冲突格中的“课程重合”标题。
     *
     * 标题优先保持单行；只有完整文字宽于课程格可用宽度时，才切换为本地化资源中预定义的两行形式。
     * 中文资源固定在“课程”和“重合”之间换行，避免系统在“课/程”或“重/合”内部任意断行。
     *
     * @param t 当前课表外观配置，用于继承课程格字号
     * @param textColor 冲突格内容色
     * @return 可直接加入冲突格内容布局的 MaterialTextView
     */
    private fun createConflictCardTitle(t: TableEntity, textColor: Int): MaterialTextView {
        val singleLineText = getString(R.string.course_conflict)
        val wrappedText = getString(R.string.course_conflict_wrapped)
        return object : MaterialTextView(requireContext()) {
            /** 根据课程格实际宽度选择单行或指定断点的两行标题。 */
            override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
                super.onSizeChanged(width, height, oldWidth, oldHeight)
                val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
                if (availableWidth == 0) return
                val targetText = if (paint.measureText(singleLineText) <= availableWidth) {
                    singleLineText
                } else {
                    wrappedText
                }
                if (text.toString() != targetText) text = targetText
            }
        }.apply {
            text = singleLineText
            gravity = Gravity.CENTER
            maxLines = 2
            setTextColor(textColor)
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                (t.itemTextSize - 1).coerceAtLeast(9).toFloat(),
            )
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    /**
     * 在屏幕中央显示冲突课程详情。
     *
     * 弹窗本体由应用的 Material 3 Expressive `materialAlertDialogTheme` 提供形状和动画；每门课程使用
     * 独立的低层级 Material 卡片，展示周次、完整节次、教师、地点、学分和备注中的有效字段。
     *
     * @param segment 用户点击的冲突片段
     * @param date 冲突发生的自然日期
     */
    private fun showCourseConflictDialog(segment: WeekCourseDisplaySegment, date: LocalDate) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density).roundToInt()

        val onSurfaceColor = MaterialColors.getColor(
            ctx,
            com.google.android.material.R.attr.colorOnSurface,
            Color.BLACK,
        )
        val onSurfaceVariantColor = MaterialColors.getColor(
            ctx,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.DKGRAY,
        )
        val surfaceContainerColor = MaterialColors.getColor(
            ctx,
            com.google.android.material.R.attr.colorSurfaceContainerLow,
            Color.WHITE,
        )
        val dialogContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(4f), dp(24f), dp(8f))
            addView(
                MaterialTextView(ctx).apply {
                    text = getString(
                        R.string.course_conflict_occurrence,
                        date.monthValue,
                        date.dayOfMonth,
                        weekdayShort(date.dayOfWeek.value),
                        segment.startNode,
                        segment.endNode,
                    )
                    setTextColor(onSurfaceVariantColor)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(12f) })

            segment.selection.courses.forEachIndexed { index, occurrence ->
                addView(
                    MaterialCardView(ctx).apply {
                        radius = dp(CONFLICT_DIALOG_CARD_RADIUS_DP).toFloat()
                        cardElevation = 0f
                        setCardBackgroundColor(surfaceContainerColor)
                        addView(
                            createConflictCourseContent(
                                occurrence = occurrence,
                                titleColor = onSurfaceColor,
                                bodyColor = onSurfaceVariantColor,
                                dp = ::dp,
                            )
                        )
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (index > 0) topMargin = dp(8f)
                    })
            }
        }
        val scrollView = ScrollView(ctx).apply {
            isFillViewport = true
            addView(
                dialogContent, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            )
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.course_conflict)
            .setView(scrollView)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /**
     * 创建冲突弹窗中单门课程的信息视图。
     *
     * @param occurrence 课程及其时间段
     * @param titleColor 课程名称文字颜色
     * @param bodyColor 辅助信息文字颜色
     * @param dp dp 到 px 的转换函数
     * @return 可直接放入 MaterialCardView 的纵向内容布局
     */
    private fun createConflictCourseContent(
        occurrence: WeekCourseOccurrence,
        titleColor: Int,
        bodyColor: Int,
        dp: (Float) -> Int,
    ): View {
        val course = occurrence.course
        val detail = occurrence.detail
        val weekText = getString(
            R.string.week_bean_to_string,
            detail.startWeek,
            detail.endWeek,
            when (detail.type) {
                CourseDetailEntity.TYPE_ODD -> getString(R.string.week_type_odd)
                CourseDetailEntity.TYPE_EVEN -> getString(R.string.week_type_even)
                else -> ""
            },
        )
        val timeText = getString(
            R.string.course_time_bean_to_string,
            detail.startNode,
            detail.startNode + detail.step.coerceAtLeast(1) - 1,
            getString(
                com.openwakeup.schedule.feature.courseedit.AddCourseActivity.DAY_NAME_RES
                    .getOrElse(detail.day - 1) { R.string.weekday_short_1 },
            ),
        )
        val information = buildList {
            add(getString(R.string.course_conflict_weeks, weekText))
            add(getString(R.string.course_conflict_time, timeText))
            if (detail.ownTime && detail.startTime.isNotBlank() && detail.endTime.isNotBlank()) {
                add(
                    getString(
                        R.string.course_conflict_custom_time,
                        detail.startTime,
                        detail.endTime
                    )
                )
            }
            if (detail.teacher.isNotBlank()) add(
                getString(
                    R.string.course_conflict_teacher,
                    detail.teacher
                )
            )
            if (detail.room.isNotBlank()) add(getString(R.string.course_conflict_room, detail.room))
            if (course.credit > 0f) add(
                getString(
                    R.string.course_conflict_credit,
                    formatCredit(course.credit)
                )
            )
            if (course.note.isNotBlank()) add(getString(R.string.course_conflict_note, course.note))
        }.joinToString("\n")

        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            addView(
                MaterialTextView(context).apply {
                    text = course.courseName
                    setTextColor(titleColor)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            )
            addView(
                MaterialTextView(context).apply {
                    text = information
                    setTextColor(bodyColor)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setLineSpacing(dp(2f).toFloat(), 1f)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6f) })
        }
    }

    /** 学分展示去除无意义的小数尾零。 */
    private fun formatCredit(value: Float): String = when {
        value % 1f == 0f -> value.toInt().toString()
        else -> value.toString().trimEnd('0').trimEnd('.')
    }

    /** 本周实际显示的日期，隐藏的周六/周日不保留占位。 */
    private fun weekDays(t: TableEntity): List<LocalDate?> {
        // 无论导入或旧配置中的起始日期是星期几，七天列都固定从该周周一开始。
        val weekStart = DateUtils.mondayOfWeek(startDate).plusWeeks((week - 1).toLong())
        return (0..6).mapNotNull { offset ->
            val day = weekStart.plusDays(offset.toLong())
            val dow = day.dayOfWeek.value
            when {
                dow == 6 && !t.showSat -> null
                dow == 7 && !t.showSun -> null
                else -> day
            }
        }
    }

    /** 表头星期短名（中文单字“一二三…”，英文 Mon/Tue…），随应用语言切换。 */
    private fun weekdayShort(dayOfWeek: Int): String =
        getString(WEEKDAY_RES[if (dayOfWeek == 7) 6 else dayOfWeek - 1])

    /** 日列表头中独立的星期文字与日号文字，便于只给当日日号加背景。 */
    private data class DayHeaderView(
        val weekday: TextView,
        val date: TextView,
    )

    private fun parseColor(color: String, fallback: Int): Int {
        if (color.isBlank()) return fallback
        return try {
            Color.parseColor(color)
        } catch (_: Exception) {
            fallback
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (alpha shl 24) or (color and 0x00FFFFFF)

    /** 全周页共享的数据快照（ScheduleActivity 推送、各页 onResume 拉取） */
    data class Snapshot(
        val table: TableEntity,
        val times: List<TimeDetailEntity>,
        val currentWeek: Int,
        val startDate: LocalDate,
        val source: List<Pair<CourseEntity, CourseDetailEntity>>,
        val shifts: List<ScheduleShiftEntity> = emptyList(),
    )

    companion object {
        /** 当日日号高亮色块的固定视觉尺寸。 */
        private const val TODAY_DATE_SIZE_DP = 20f

        /** 当日日号高亮色块的 Material 圆角半径。 */
        private const val TODAY_DATE_CORNER_RADIUS_DP = 4f

        /** 冲突卡边框透明度，避免错误容器边缘过重。 */
        private const val CONFLICT_CARD_STROKE_ALPHA = 72

        /** 冲突详情中单门课程卡的圆角半径。 */
        private const val CONFLICT_DIALOG_CARD_RADIUS_DP = 8f

        /** 普通课程卡中“非本周”标签相对课程正文字号的缩小值。 */
        private const val NON_WEEK_LABEL_SIZE_OFFSET_SP = 3

        /** 普通课程卡中“非本周”标签的初始最小字号，实际过窄时仍会继续自适应缩小。 */
        private const val NON_WEEK_LABEL_MIN_SIZE_SP = 7

        /** 冲突格中“非本周”标签使用原生自动字号时允许的最小字号。 */
        private const val NON_WEEK_LABEL_AUTO_SIZE_MIN_SP = 5

        private val WEEKDAY_RES = intArrayOf(
            R.string.weekday_char_1, R.string.weekday_char_2, R.string.weekday_char_3,
            R.string.weekday_char_4, R.string.weekday_char_5, R.string.weekday_char_6,
            R.string.weekday_char_7,
        )

        var latest: Snapshot? = null

        fun newInstance(week: Int): WeekPageFragment = WeekPageFragment().apply {
            arguments = Bundle().apply { putInt("week", week) }
        }
    }
}
