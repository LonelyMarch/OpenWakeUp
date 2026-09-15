package com.openwakeup.schedule.core.validation

import com.openwakeup.schedule.core.config.AppDefaults
import com.openwakeup.schedule.core.database.entity.CourseDetailEntity
import com.openwakeup.schedule.core.schedule.ScheduleNodeResolver

/**
 * 课程时间段在应用中的统一合法性规则。
 *
 * 周次只受系统 48 周上限约束；节次由课表“一天课程节数”和系统 24 节上限约束。绑定作息
 * 不足时由显示层补齐时间文字，不再缩小合法节次范围。任何一条时间段非法时，整门课程都
 * 只能出现在课程管理页，避免普通视图显示无法完整定位的课程。
 */
object CourseRangePolicy {

    /**
     * 计算目标课表真正能够显示和排课的节次数量。
     *
     * @param configuredNodes 课表配置的一天节数
     * @return 课表配置与系统上限共同限制后的非负值
     */
    fun visibleNodeLimit(configuredNodes: Int): Int =
        ScheduleNodeResolver.visibleNodeCount(configuredNodes)

    /**
     * 判断一条课程时间段是否可以在普通课程视图中显示。
     *
     * @param detail 待校验的课程时间段
     * @param visibleNodeLimit 目标课表实际可显示的最大节次
     * @return 星期、周次和节次范围全部合法时返回 `true`
     */
    fun isDetailValid(detail: CourseDetailEntity, visibleNodeLimit: Int): Boolean {
        val endNode = detail.startNode.toLong() + detail.step.toLong() - 1L
        return detail.day in 1..7 &&
                detail.startWeek in 1..AppDefaults.Table.MAX_SUPPORTED_WEEKS &&
                detail.endWeek in detail.startWeek..AppDefaults.Table.MAX_SUPPORTED_WEEKS &&
                detail.startNode >= 1 &&
                detail.step >= 1 &&
                endNode <= visibleNodeLimit.toLong()
    }

    /**
     * 判断一门课程的全部时间段是否都可正常显示。
     *
     * @param details 一门课程的完整时间段集合
     * @param visibleNodeLimit 目标课表实际可显示的最大节次
     * @return 至少有一条时间段且所有时间段都合法时返回 `true`
     */
    fun isCourseValid(details: List<CourseDetailEntity>, visibleNodeLimit: Int): Boolean =
        details.isNotEmpty() && details.all { detail -> isDetailValid(detail, visibleNodeLimit) }
}
