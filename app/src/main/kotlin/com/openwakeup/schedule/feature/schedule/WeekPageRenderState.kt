package com.openwakeup.schedule.feature.schedule

import com.openwakeup.schedule.core.database.entity.CourseDetailEntity
import com.openwakeup.schedule.core.database.entity.CourseEntity
import com.openwakeup.schedule.core.database.entity.ScheduleShiftEntity
import com.openwakeup.schedule.core.database.entity.TableEntity
import com.openwakeup.schedule.core.database.entity.TimeDetailEntity
import java.time.LocalDate

/**
 * 决定周页面是否必须重新创建骨架的最小结构键。
 *
 * 这里只保存会改变 View 数量、约束关系，或在构建阶段写入且内容刷新无法完整覆盖的字段。
 * 课程卡外观由 [WeekPageContentKey] 管理，修改课程颜色或文字时不会重建表头和节次列。
 */
internal data class WeekPageLayoutKey(
    val tableId: Long,
    val nodeCount: Int,
    val showSaturday: Boolean,
    val showSunday: Boolean,
    val showTimeBar: Boolean,
    val showGrid: Boolean,
    val itemHeight: Int,
    val itemRadius: Int,
    val headerTextSize: Int,
    val textColor: String,
) {
    companion object {
        /**
         * 从课表配置与已归一化作息生成稳定结构键。
         *
         * @param table 当前课表及其显示配置
         * @param nodeCount 页面实际绘制的节次数
         * @return 可直接使用结构相等比较的布局键
         */
        fun from(table: TableEntity, nodeCount: Int): WeekPageLayoutKey = WeekPageLayoutKey(
            tableId = table.id,
            nodeCount = nodeCount,
            showSaturday = table.showSat,
            showSunday = table.showSun,
            showTimeBar = table.showTimeBar,
            showGrid = table.showGrid,
            itemHeight = table.itemHeight,
            itemRadius = table.itemRadius,
            headerTextSize = table.headerTextSize,
            textColor = table.textColor,
        )
    }
}

/** 仅影响课程卡内容或样式、不要求重建页面骨架的配置快照。 */
internal data class WeekPageCourseStyleKey(
    val courseTextColor: String,
    val itemTextSize: Int,
    val itemAlpha: Int,
    val strokeColor: String,
    val useDottedLine: Boolean,
    val textColorCompose: Boolean,
    val strokeColorCompose: Boolean,
    val showTime: Boolean,
    val showTeacher: Boolean,
    val showLocation: Boolean,
    val showRoomPrefix: Boolean,
    val showOtherWeekCourse: Boolean,
    val otherWeekCourseAlpha: Int,
    val itemCenterHorizontal: Boolean,
    val itemCenterVertical: Boolean,
)

/**
 * 周页面内容渲染键。
 *
 * Kotlin 数据类与 List 使用结构相等比较，因此 Room 重发内容完全相同的新列表时也能跳过
 * `removeAllViews()` 和课程卡创建，不依赖存在碰撞风险的手写哈希值。
 */
internal data class WeekPageContentKey(
    val week: Int,
    val startDate: LocalDate,
    val times: List<TimeDetailEntity>,
    val source: List<Pair<CourseEntity, CourseDetailEntity>>,
    val shifts: List<ScheduleShiftEntity>,
    val style: WeekPageCourseStyleKey,
    val scheduleBlankArea: Boolean,
) {
    companion object {
        /**
         * 从当前页面实际消费的数据生成内容键。
         *
         * @param table 当前课表显示配置
         * @param times 已按页面节数归一化的作息
         * @param week 当前 Fragment 对应的周次
         * @param startDate 学期起始日期
         * @param source 有效课程与时间段的扁平快照
         * @param shifts 当前课表的日期调课记录
         * @param scheduleBlankArea 是否显示课表尾部留白
         * @return 可用于跳过等价重绘的内容键
         */
        fun from(
            table: TableEntity,
            times: List<TimeDetailEntity>,
            week: Int,
            startDate: LocalDate,
            source: List<Pair<CourseEntity, CourseDetailEntity>>,
            shifts: List<ScheduleShiftEntity>,
            scheduleBlankArea: Boolean,
        ): WeekPageContentKey = WeekPageContentKey(
            week = week,
            startDate = startDate,
            times = times,
            source = source,
            shifts = shifts,
            style = WeekPageCourseStyleKey(
                courseTextColor = table.courseTextColor,
                itemTextSize = table.itemTextSize,
                itemAlpha = table.itemAlpha,
                strokeColor = table.strokeColor,
                useDottedLine = table.useDottedLine,
                textColorCompose = table.textColorCompose,
                strokeColorCompose = table.strokeColorCompose,
                showTime = table.showTime,
                showTeacher = table.showTeacher,
                showLocation = table.showLocation,
                showRoomPrefix = table.showRoomPrefix,
                showOtherWeekCourse = table.showOtherWeekCourse,
                otherWeekCourseAlpha = table.otherWeekCourseAlpha,
                itemCenterHorizontal = table.itemCenterHorizontal,
                itemCenterVertical = table.itemCenterVertical,
            ),
            scheduleBlankArea = scheduleBlankArea,
        )
    }
}
