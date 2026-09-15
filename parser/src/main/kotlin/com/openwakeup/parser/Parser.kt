/*
 * 本文件基于 YZune/WakeupSchedule_Kotlin 的 Apache-2.0 解析接口修改。
 * 原始版权：Copyright 2019 YZune。
 * OpenWakeUp 于 2026 年重构了数据契约与错误处理；完整来源见仓库 NOTICE.md。
 */
package com.openwakeup.parser

/**
 * 解析产物：一门课程的一个时间段（预览态，导入时由界面/仓库层落库）。
 *
 * @property name 课程名称
 * @property color 卡片颜色（#AARRGGBB，可空=由界面分配色板）
 * @property teacher 教师
 * @property room 教室
 * @property day 星期（1=周一 … 7=周日）
 * @property startNode 起始节（1 起）
 * @property step 连续节数
 * @property startWeek 起始周
 * @property endWeek 结束周
 * @property type 周类型：0=每周，1=单周，2=双周
 */
data class CoursePreview(
    val name: String,
    val color: String? = null,
    val teacher: String = "",
    val room: String = "",
    val day: Int,
    val startNode: Int,
    val step: Int = 1,
    val startWeek: Int = 1,
    val endWeek: Int = 25,
    val type: Int = 0,
)

/** 解析输入：文本（HTML/CSV/JSON 均按文本进入解析器） */
data class ParserInput(
    val text: String,
    val type: String,
)

/**
 * 课表解析器接口：全部家族解析器的统一抽象。
 */
interface Parser {
    /**
     * 解析输入并给出课程预览列表。
     *
     * @param input 解析输入（文本 + 学校 type）
     * @return 课程预览列表；空课表返回空列表
     * @throws ParserException 空课表或页面结构不符合预期
     */
    fun parse(input: ParserInput): List<CoursePreview>
}
