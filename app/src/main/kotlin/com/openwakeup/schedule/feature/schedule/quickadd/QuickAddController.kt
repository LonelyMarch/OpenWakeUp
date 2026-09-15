package com.openwakeup.schedule.feature.schedule.quickadd

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 快速加课的几何与节数计算（纯逻辑，不持有 View）。
 *
 * 坐标系：内容面板局部坐标（面板顶 = 第一节格顶上方 nodeGap 处）。
 * 格 n 的纵向区间 = [pitch×(n-1)+nodeGap, 该值+itemHeightPx]，
 * 相邻格之间有 nodeGap 行间距；拖动计数以"格中心线"为准：
 * nodeAt(y) = round((y - 首格中心) / pitch) + 1。
 *
 * @property rowPitch 节高 + 2dp 行间距（px）
 * @property nodeGap 行间距（px）
 * @property itemHeightPx 单节格高（px）
 * @property maxNode 可用节数上限（min(课表设置节数, 实际绘制行数)）
 */
class QuickAddController(
    private val rowPitch: Int,
    private val nodeGap: Int,
    private val itemHeightPx: Int,
    val maxNode: Int,
) {

    /** 首格中心线 y（拖动映射的基准点） */
    private val firstCellCenterY: Float = nodeGap + itemHeightPx / 2f

    /** 网格纵向总高（含首行上方 nodeGap），用于判定点击是否落在有效格内 */
    val gridBottom: Int = rowPitch * (maxNode - 1) + nodeGap + itemHeightPx

    /**
     * 把拖动/轻点位置的 y 换算成节数。
     *
     * @param byCenter true = 中心线计数（拖动拉伸；round 越线才计入）
     *                 false = 点所在格（轻点建格；floor 归属）
     */
    fun nodeAt(y: Float, byCenter: Boolean): Int {
        val n = if (byCenter) {
            ((y - firstCellCenterY) / rowPitch).roundToInt() + 1
        } else {
            ((y - nodeGap) / rowPitch).toInt() + 1
        }
        return n.coerceIn(1, maxNode)
    }

    /** y 是否落在有效格区间内（尾部留白与范围外不产生草稿） */
    fun inGrid(y: Float): Boolean = y >= nodeGap && y <= gridBottom

    /**
     * 按手指所在节次更新草稿范围（无磁吸：直接以越线后的完整格为准）。
     *
     * @return 本节数是否发生变化（用于触觉反馈与重绘判断）
     */
    fun resizeTo(draft: QuickAddDraft, node: Int): Boolean {
        val target = node.coerceIn(1, maxNode)
        val newStart = minOf(target, draft.anchorNode)
        val newStep = abs(target - draft.anchorNode) + 1
        if (newStart == draft.startNode && newStep == draft.step) return false
        draft.startNode = newStart
        draft.step = newStep
        return true
    }

    /** 草稿卡顶部 y（内容局部坐标） */
    fun cardTop(startNode: Int): Int = rowPitch * (startNode - 1) + nodeGap

    /** 草稿卡高度 */
    fun cardHeight(step: Int): Int = step * itemHeightPx + (step - 1) * nodeGap

    /** 还原为锚点处的单格草稿（RESIZING 收到 CANCEL 时回退） */
    fun resetToAnchor(draft: QuickAddDraft) {
        draft.startNode = draft.anchorNode
        draft.step = 1
    }
}
