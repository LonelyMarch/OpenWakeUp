package com.openwakeup.schedule.feature.schedule.quickadd

/**
 * 快速加课手势状态机。
 *
 * IDLE →（空白格轻点）→ PREVIEW_ONE_CELL →（按住草稿）→ PRESSING_DRAFT
 * →（越过 touchSlop）→ RESIZING →（松手）→ LAUNCH_EDITOR → IDLE。
 * 任何阶段收到 ACTION_CANCEL：RESIZING 回退单格预览，其余直接取消。
 */
enum class QuickAddGestureState {
    /** 未显示草稿；触摸全部穿透给日列 */
    IDLE,

    /** 单格草稿预览中；再次轻点其他空白格移动锚点 */
    PREVIEW_ONE_CELL,

    /** 手指按在草稿/竖条上，尚未越过 touchSlop */
    PRESSING_DRAFT,

    /** 纵向拖动调整节数中（中心线计数，无磁吸动画） */
    RESIZING,
}
