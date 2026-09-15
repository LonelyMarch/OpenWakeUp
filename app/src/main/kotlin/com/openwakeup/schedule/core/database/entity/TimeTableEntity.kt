package com.openwakeup.schedule.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.openwakeup.schedule.core.config.AppDefaults

/**
 * 作息时间表：一套节次→时间映射，可被多张课表共享。
 * 运行时默认值统一维护在 [AppDefaults.Timetable]；ColumnInfo 默认值是历史 schema 值，不能随意改动。
 *
 * @property name 作息表名称
 * @property sameDuration 是否统一按 [durationMinutes] 计算各节结束时间
 * @property durationMinutes 相同时长模式下单节课的分钟数
 */
@Entity(tableName = "time_tables")
data class TimeTableEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "默认作息",
    @ColumnInfo(defaultValue = "0")
    val sameDuration: Boolean = AppDefaults.Timetable.SAME_DURATION,
    @ColumnInfo(defaultValue = "45")
    val durationMinutes: Int = AppDefaults.Timetable.DURATION_MINUTES,
)
