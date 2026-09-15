package com.openwakeup.schedule.core.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 周次计算工具（全 App 日期显示的地基）。
 * 规范数据中的 startDate 是第 1 周周一；计算入口仍会防御性归一，旧数据不会改变周一首列约定。
 */
object DateUtils {

    /**
     * 将任意日期归一到其所在 ISO 周的周一。
     *
     * 周视图、周小部件和周次计算统一使用本方法，保证第一列永远代表周一；即使旧数据或
     * 外部导入把学期起始日期保存成周中日期，也不会改变七天列的固定顺序。
     *
     * @param date 任意自然日期
     * @return 与该日期属于同一周的周一
     */
    fun mondayOfWeek(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

    /**
     * 计算指定日期处于学期的第几周。
     *
     * @param startDate 学期第一周周一
     * @param today 待判定日期
     * @return 周次；开学之前返回 0；不设上限（渲染层按 maxWeek 裁剪"非本周"判断）
     */
    fun currentWeek(startDate: LocalDate, today: LocalDate = LocalDate.now()): Int {
        val normalizedStart = mondayOfWeek(startDate)
        val mondayOfToday = mondayOfWeek(today)
        val days = ChronoUnit.DAYS.between(normalizedStart, mondayOfToday)
        if (days < 0) return 0
        return (days / 7).toInt() + 1
    }

    /**
     * 某周类型下，[week] 是否上课。
     *
     * @param week 目标周次
     * @param type 周类型：0=每周，1=单周，2=双周
     * @return 该周是否上该时间段
     */
    fun weekMatchesType(week: Int, type: Int): Boolean = when (type) {
        1 -> week % 2 == 1
        2 -> week % 2 == 0
        else -> true
    }

    /**
     * 判断时间段在第 [week] 周是否生效（周次区间 + 单双周）。
     */
    fun detailCoversWeek(startWeek: Int, endWeek: Int, type: Int, week: Int): Boolean =
        week in startWeek..endWeek && weekMatchesType(week, type)
}
