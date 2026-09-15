package com.openwakeup.schedule.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 节次时间实体：作息表中第 node 节的起止时间。
 *
 * @property timeTableId 所属作息表
 * @property node 节次（1 起）
 * @property startTime 上课时间（HH:mm）
 * @property endTime 下课时间（HH:mm）
 */
@Entity(
    tableName = "time_details",
    indices = [Index(value = ["timeTableId", "node"], unique = true)],
)
data class TimeDetailEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeTableId: Long,
    val node: Int,
    val startTime: String,
    val endTime: String,
)
