package com.openwakeup.schedule.feature.schedule

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.openwakeup.schedule.R
import com.openwakeup.schedule.feature.schedule.quickadd.QuickAddController
import com.openwakeup.schedule.feature.schedule.quickadd.QuickAddDraft
import com.openwakeup.schedule.feature.schedule.quickadd.QuickAddGestureState
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 快速加课草稿层：覆盖在日列区域之上（课程卡之上、表头之下）。
 *
 * 无草稿时 [onTouchEvent] 返回 false，触摸全部穿透给下方日列；
 * 显示草稿后，按在草稿卡/竖条上即进入拉伸状态机（中心线计数、无磁吸动画），
 * 松手回调 [onFinished] 由 Fragment 打开已预填的添加课程页；
 * 按在其他空白处则事件穿透，由日列点击把草稿移动到新格。
 *
 * 视觉：草稿卡 = 粉色圆角块（圆角跟随课表格子配置）+ 居中白色加号；
 * 竖条 = 固定尺寸白色胶囊，上下各一个箭头，垂直居中于草稿卡右缘（末列时内收）。
 */
class QuickAddOverlayLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** 拉伸完成（松手）：携带最终 start/step 的草稿，由 Fragment 转发添加课程页 */
    var onFinished: ((QuickAddDraft) -> Unit)? = null

    private lateinit var controller: QuickAddController
    private var draft: QuickAddDraft? = null
    private var state = QuickAddGestureState.IDLE

    /** 草稿卡所在日列在内容面板中的横向位置与宽度（reposition 时按当前布局测量） */
    private var panelX = 0
    private var panelWidth = 0

    /** 触发起点的 y 与是否已越过 touchSlop（按下在草稿上时记录） */
    private var downY = 0f
    private var pointerId = MotionEvent.INVALID_POINTER_ID

    private val density = resources.displayMetrics.density
    private val cardView: FrameLayout
    private val handleView: FrameLayout

    /** 竖条触控目标（≥48dp 命中区，视觉宽度保持固定） */
    private val handleHit = android.graphics.RectF()

    init {
        clipChildren = false
        clipToPadding = false

        // 草稿卡：圆角跟随课表格子配置，容器色 token + 白描边
        cardView = FrameLayout(context).apply {
            visibility = GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val plus = ImageView(context).apply {
            setImageResource(R.drawable.ms_add_24)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.quick_add_draft_on_container),
            )
            layoutParams = LayoutParams(dp(28), dp(28), Gravity.CENTER)
        }
        cardView.addView(plus)

        // 竖条：白色胶囊 + 上下箭头；不参与无障碍焦点（TalkBack 读草稿卡整体描述）
        handleView = FrameLayout(context).apply {
            visibility = GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                cornerRadius = dp(99).toFloat()
                setColor(ContextCompat.getColor(context, R.color.quick_add_draft_handle))
            }
        }
        val arrows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams =
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        listOf(R.drawable.ms_arrow_upward_24, R.drawable.ms_arrow_downward_24).forEach { res ->
            arrows.addView(ImageView(context).apply {
                setImageResource(res)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.quick_add_draft_on_handle),
                )
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                    setMargins(0, dp(3), 0, dp(3))
                }
            })
        }
        handleView.addView(arrows)

        addView(cardView, LayoutParams(0, 0))
        addView(handleView, LayoutParams(0, 0))
    }

    /** 在重建后的网格上重置几何参数（节高/行距/节数上限/圆角） */
    fun configure(controller: QuickAddController, itemRadiusDp: Int) {
        this.controller = controller
        cardView.background = GradientDrawable().apply {
            cornerRadius = itemRadiusDp * density
            setColor(ContextCompat.getColor(context, R.color.quick_add_draft_container))
            setStroke(dp(2), ContextCompat.getColor(context, R.color.quick_add_draft_on_container))
        }
        cancelDraft()
    }

    /** 是否存在可见草稿（课程卡点击/数据刷新/离开页面时需要先取消） */
    fun hasActiveDraft(): Boolean = draft != null && state != QuickAddGestureState.IDLE

    /** 取消草稿并隐藏草稿层 */
    fun cancelDraft() {
        draft = null
        state = QuickAddGestureState.IDLE
        contentDescription = null
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        cardView.visibility = GONE
        handleView.visibility = GONE
    }

    /**
     * 在指定日列的节点上显示单格草稿（已存在草稿时=移动锚点并恢复一格）。
     *
     * @param panelX 日列左缘相对本层的位置（px）
     * @param panelWidth 日列宽度（px）
     * @param tableId / week / day / node 草稿业务参数
     */
    fun showDraftAt(panelX: Int, panelWidth: Int, tableId: Long, week: Int, day: Int, node: Int) {
        this.panelX = panelX
        this.panelWidth = panelWidth
        draft = QuickAddDraft(tableId, week, day, node, node, 1)
        state = QuickAddGestureState.PREVIEW_ONE_CELL
        reposition()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        cardView.visibility = VISIBLE
        handleView.visibility = VISIBLE
    }

    /** 按当前草稿 start/step 重设卡片与竖条位置（无动画，直接落位） */
    private fun reposition() {
        val d = draft ?: return
        val top = controller.cardTop(d.startNode)
        val cardH = controller.cardHeight(d.step)
        cardView.layoutParams = (cardView.layoutParams as LayoutParams).apply {
            width = panelWidth
            height = cardH
            leftMargin = panelX
            topMargin = top
        }
        // 整个草稿层负责点击动作，因此无障碍描述也挂在同一个可执行节点上。
        contentDescription = context.getString(
            R.string.quick_add_draft_desc,
            d.week,
            d.day,
            d.startNode,
            d.startNode + d.step - 1,
        )

        // 竖条：固定尺寸，垂直居中；默认贴卡片右缘，末列放不下时内收
        val handleW = dp(18)
        val handleH = dp(40).coerceAtMost(cardH)
        var handleX = panelX + panelWidth + dp(3)
        if (handleX + handleW > width) handleX = panelX + panelWidth - dp(3) - handleW
        val handleTop = top + (cardH - handleH) / 2
        handleView.layoutParams = (handleView.layoutParams as LayoutParams).apply {
            width = handleW
            height = handleH
            leftMargin = handleX
            topMargin = handleTop
        }
        handleHit.set(
            (handleX - (dp(48) - handleW) / 2).toFloat(),
            (handleTop - dp(8)).toFloat(),
            (handleX + handleW + (dp(48) - handleW) / 2).toFloat(),
            (handleTop + handleH + dp(8)).toFloat(),
        )
        requestLayout()
    }

    /** 命中测试：DOWN 落在草稿卡或竖条扩展命中区内才算拉伸开始 */
    private fun hitTest(x: Float, y: Float): Boolean {
        val d = draft ?: return false
        val inCard = x >= panelX && x <= panelX + panelWidth &&
                y >= controller.cardTop(d.startNode) &&
                y <= controller.cardTop(d.startNode) + controller.cardHeight(d.step)
        return inCard || handleHit.contains(x, y)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 无草稿：完全穿透给日列（空白格轻点由日列自行处理）
        if (!hasActiveDraft()) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hitTest(event.x, event.y)) return false
                pointerId = event.getPointerId(0)
                downY = event.y
                state = QuickAddGestureState.PRESSING_DRAFT
                // 拉伸期间禁止 ScrollView/ViewPager 抢事件
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 多点触控：固定首个 pointer，第二指不改变选择
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(pointerId)
                if (idx < 0) return true
                val y = event.getY(idx)
                if (state == QuickAddGestureState.PRESSING_DRAFT &&
                    abs(y - downY) > android.view.ViewConfiguration.get(context).scaledTouchSlop
                ) {
                    state = QuickAddGestureState.RESIZING
                }
                if (state == QuickAddGestureState.RESIZING) {
                    val d = draft ?: return true
                    val node = controller.nodeAt(y, byCenter = true)
                    if (controller.resizeTo(d, node)) {
                        // 无磁吸动画：跨过中心线后直接更新完整卡高。
                        reposition()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                when (state) {
                    QuickAddGestureState.PRESSING_DRAFT -> performClick()
                    QuickAddGestureState.RESIZING -> finishActiveDraft()
                    else -> Unit
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                val d = draft
                if (state == QuickAddGestureState.RESIZING && d != null) {
                    // 回退单格预览（锚点保持不变）
                    controller.resetToAnchor(d)
                    reposition()
                    state = QuickAddGestureState.PREVIEW_ONE_CELL
                } else {
                    cancelDraft()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 让 TalkBack 点击与手指轻点共享同一条完成草稿路径。 */
    override fun performClick(): Boolean {
        super.performClick()
        return finishActiveDraft()
    }

    /** 完成当前草稿并只回调一次；无活动草稿时返回 `false`。 */
    private fun finishActiveDraft(): Boolean {
        val completedDraft = draft ?: return false
        cancelDraft()
        onFinished?.invoke(completedDraft)
        return true
    }

    private fun dp(v: Int): Int = (v * density).roundToInt()
}
