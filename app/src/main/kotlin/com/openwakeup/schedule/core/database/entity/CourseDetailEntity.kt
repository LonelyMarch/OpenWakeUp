package com.openwakeup.schedule.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 课程时间段实体：一门课程在周内的一段时间安排。
 * 字段语义：起止周 + 单双周类型 + 起始节 + 步长（连续节数）。
 *
 * @property courseId 所属课程
 * @property day 星期（1=周一 … 7=周日）
 * @property startNode 起始节（1 起）
 * @property step 连续节数（含起始节）
 * @property startWeek 起始周
 * @property endWeek 结束周
 * @property type 周类型：0=每周，1=单周，2=双周
 * @property teacher 教师名
 * @property room 教室
 */
@Entity(
    tableName = "course_details",
    indices = [Index("courseId"), Index("courseId", "day", "startNode")],
)
data class CourseDetailEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val day: Int,
    val startNode: Int,
    val step: Int = 1,
    val startWeek: Int = 1,
    val endWeek: Int = 25,
    val type: Int = TYPE_ALL,
    val teacher: String = "",
    val room: String = "",
    @ColumnInfo(defaultValue = "0") val ownTime: Boolean = false,
    @ColumnInfo(defaultValue = "''") val startTime: String = "",
    @ColumnInfo(defaultValue = "''") val endTime: String = "",
) {
    companion object {
        /** 每周上 */
        const val TYPE_ALL = 0

        /** 单周上 */
        const val TYPE_ODD = 1

        /** 双周上 */
        const val TYPE_EVEN = 2
    }
}
