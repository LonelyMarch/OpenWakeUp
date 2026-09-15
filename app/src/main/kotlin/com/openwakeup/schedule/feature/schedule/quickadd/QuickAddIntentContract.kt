package com.openwakeup.schedule.feature.schedule.quickadd

import android.content.Intent
import com.openwakeup.schedule.feature.schedule.quickadd.QuickAddIntentContract.readPrefill

/**
 * 主课表快速加课 → 添加课程页的稳定 Intent 契约。
 *
 * 参数缺失、越界或来自旧入口（＋按钮）时不附加预填，添加页继续使用默认值。
 */
object QuickAddIntentContract {

    /** 当前显示周，预填为仅该周 */
    const val EXTRA_INITIAL_WEEK = "initial_week"

    /** 点击日列对应的星期，范围 1~7 */
    const val EXTRA_INITIAL_DAY = "initial_day"

    /** 拉伸后的起始节次，范围 1~课表节数 */
    const val EXTRA_INITIAL_START_NODE = "initial_start_node"

    /** 拉伸后的连续节数 */
    const val EXTRA_INITIAL_STEP = "initial_step"

    /** 标记入口来自主课表快速加课，便于与顶部"＋"等旧入口区分 */
    const val EXTRA_SOURCE = "source"

    /** 快速加课入口标识值 */
    const val SOURCE_SCHEDULE_QUICK_ADD = "schedule_quick_add"

    /** 快速加课预填结果；解析失败返回 null（走默认建行） */
    data class Prefill(val week: Int, val day: Int, val startNode: Int, val step: Int)

    /**
     * 从 Intent 读取快速加课预填参数并按当前课表边界校验。
     *
     * @param intent 添加课程页收到的 Intent
     * @param maxWeek 课表学期周数（周次越界时钳制到 1..maxWeek）
     * @param nodes 课表一天节数（节次与跨度越界时钳制到有效范围）
     * @return 非快速加课入口或数据不完整时返回 null
     */
    fun readPrefill(intent: Intent, maxWeek: Int, nodes: Int): Prefill? {
        if (intent.getStringExtra(EXTRA_SOURCE) != SOURCE_SCHEDULE_QUICK_ADD) return null
        val week = intent.getIntExtra(EXTRA_INITIAL_WEEK, -1)
        val day = intent.getIntExtra(EXTRA_INITIAL_DAY, -1)
        val startNode = intent.getIntExtra(EXTRA_INITIAL_START_NODE, -1)
        val step = intent.getIntExtra(EXTRA_INITIAL_STEP, -1)
        if (day < 1 || day > 7) return null
        if (startNode < 1 || step < 1) return null
        return Prefill(
            week = week.coerceIn(1, maxWeek),
            day = day,
            startNode = startNode.coerceIn(1, nodes),
            step = step.coerceAtMost(nodes - startNode.coerceIn(1, nodes) + 1),
        )
    }

    /** 把草稿写入 Intent（添加页侧按 [readPrefill] 解析） */
    fun apply(intent: Intent, draft: QuickAddDraft): Intent = intent
        .putExtra(EXTRA_SOURCE, SOURCE_SCHEDULE_QUICK_ADD)
        .putExtra(EXTRA_INITIAL_WEEK, draft.week)
        .putExtra(EXTRA_INITIAL_DAY, draft.day)
        .putExtra(EXTRA_INITIAL_START_NODE, draft.startNode)
        .putExtra(EXTRA_INITIAL_STEP, draft.step)
}
