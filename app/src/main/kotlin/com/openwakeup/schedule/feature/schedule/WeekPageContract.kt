package com.openwakeup.schedule.feature.schedule

import com.openwakeup.schedule.core.database.entity.CourseDetailEntity
import com.openwakeup.schedule.core.database.entity.CourseEntity
import com.openwakeup.schedule.core.database.entity.ScheduleShiftEntity
import com.openwakeup.schedule.core.database.entity.TableEntity
import com.openwakeup.schedule.core.database.entity.TimeDetailEntity
import java.time.LocalDate

/**
 * 单个周页面渲染所需的不可变数据快照。
 *
 * 快照由宿主 Activity 持有，Fragment 只在视图创建或宿主主动推送时读取。这样既能让仍存活
 * 的页面复用原有 View 树，也不会通过进程级静态字段长期持有另一个 Activity 的课程数据。
 *
 * @property table 当前课表及其显示配置
 * @property times 当前课表绑定的真实作息时间
 * @property startDate 学期第一周的起始日期
 * @property source 已展开为“课程—时间段”的有效课程数据
 * @property shifts 当前课表的日期调课记录
 */
data class WeekPageSnapshot(
    val table: TableEntity,
    val times: List<TimeDetailEntity>,
    val startDate: LocalDate,
    val source: List<Pair<CourseEntity, CourseDetailEntity>>,
    val shifts: List<ScheduleShiftEntity> = emptyList(),
)

/**
 * 为 [WeekPageFragment] 提供当前宿主自己的渲染快照。
 *
 * 主课表和外观预览均会复用周页面，但两者的数据生命周期彼此独立。通过宿主接口按需取值，
 * 可以避免使用共享静态变量，也能让系统恢复的 Fragment 在重新创建 View 时取得正确数据。
 */
internal interface WeekPageSnapshotProvider {

    /**
     * 返回当前宿主最新的周页面快照。
     *
     * @return 数据尚未加载时返回 `null`；宿主后续加载完成后会主动调用页面的更新方法
     */
    fun currentWeekPageSnapshot(): WeekPageSnapshot?
}
