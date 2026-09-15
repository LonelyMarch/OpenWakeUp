package com.openwakeup.schedule.feature.schedule.quickadd

/**
 * 主课表快速加课的瞬时草稿。
 *
 * 草稿仅存在于页面内存中，不得提前写入 Room；真正保存仍由添加课程页完成。
 *
 * @property tableId 草稿所属课表（用于添加页加载同一张课表）
 * @property week 草稿所在周（用户点击时主课表正在显示的周，非系统当前周）
 * @property day 真实星期值（ISO 1~7；隐藏的周六/周日不产生草稿）
 * @property anchorNode 用户最初点击的节次；向下拉伸时为范围起点，向上拉伸时为范围终点
 * @property startNode 当前草稿范围的起始节次
 * @property step 当前草稿的连续节数
 */
data class QuickAddDraft(
    val tableId: Long,
    val week: Int,
    val day: Int,
    val anchorNode: Int,
    var startNode: Int,
    var step: Int,
)
