package com.openwakeup.schedule.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 单张课表的一次日期调课记录。
 *
 * 记录表示把 [fromDate] 当天实际生效的课程整体移动到 [toDate]；不修改原始循环课程数据，
 * 因而删除记录即可完整撤销。
 *
 * @property tableId 所属课表 id
 * @property fromDate 原课程日期，ISO `yyyy-MM-dd`
 * @property toDate 调整后的日期，ISO `yyyy-MM-dd`
 * @property createdAt 创建时间戳，用于记录排序
 */
@Entity(
    tableName = "schedule_shifts",
    indices = [Index("tableId"), Index(value = ["tableId", "fromDate"], unique = true)],
)
data class ScheduleShiftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val tableId: Long,
    val fromDate: String,
    val toDate: String,
    val createdAt: Long = System.currentTimeMillis(),
)
