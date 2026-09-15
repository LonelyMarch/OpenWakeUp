package com.openwakeup.schedule.core.schedule

import com.openwakeup.schedule.core.config.AppDefaults
import com.openwakeup.schedule.core.database.entity.TimeDetailEntity
import com.openwakeup.schedule.core.schedule.ScheduleNodeResolver.MISSING_TIME

/**
 * 课表网格节次与作息显示数据的统一解析器。
 *
 * “一天课程节数”决定主课表真正绘制多少行，绑定作息表只负责为这些行提供时间文字：
 * 作息较长时从前向后截取，作息较短时用 `24:00-24:00` 补齐。补齐的数据只存在于显示层，
 * 不会写回作息表，提醒等依赖真实时间计算的功能仍使用数据库原始数据。
 */
object ScheduleNodeResolver {

    /** 绑定作息缺少某一节时，在课表时间栏显示的占位时间。 */
    const val MISSING_TIME = "24:00"

    /**
     * 计算课表配置允许显示和排课的节次数。
     *
     * @param configuredNodes “课表设置”中的一天课程节数
     * @return 限制在系统 24 节上限内的非负节数
     */
    fun visibleNodeCount(configuredNodes: Int): Int = configuredNodes.coerceIn(
        0,
        AppDefaults.Table.MAX_SUPPORTED_NODES,
    )

    /**
     * 按课表节数生成连续的显示作息。
     *
     * 数据库查询结果通常已按 `node` 排序，这里仍主动排序以保证其他调用方传入无序列表时
     * 结果稳定。真实作息按顺序重新对应第 1～N 节，多余记录被截去；缺少的尾部记录使用
     * [MISSING_TIME] 补齐。
     *
     * @param configuredNodes “课表设置”中的一天课程节数
     * @param sourceTimes 绑定作息表中的真实时间记录
     * @return 行数严格等于课表节数的显示专用时间列表
     */
    fun resolveDisplayTimes(
        configuredNodes: Int,
        sourceTimes: List<TimeDetailEntity>,
    ): List<TimeDetailEntity> {
        val nodeCount = visibleNodeCount(configuredNodes)
        val orderedTimes = sourceTimes.sortedBy { detail -> detail.node }
        val timeTableId = orderedTimes.firstOrNull()?.timeTableId ?: 0L
        return List(nodeCount) { index ->
            orderedTimes.getOrNull(index)?.copy(node = index + 1)
                ?: TimeDetailEntity(
                    timeTableId = timeTableId,
                    node = index + 1,
                    startTime = MISSING_TIME,
                    endTime = MISSING_TIME,
                )
        }
    }
}
