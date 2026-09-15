package com.openwakeup.schedule.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 课程实体：一门课程的静态信息（名称 + 颜色）。
 * 一次"添加课程"= 1 条 Course + N 条 [CourseDetailEntity]（每周时间段）。
 *
 * @property tableId 所属课表
 * @property courseName 课程名称
 * @property color 卡片颜色（#AARRGGBB）
 * @property credit 学分，0 表示未填写
 * @property note 课程备注，最长 300 字
 */
@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tableId: Long,
    val courseName: String,
    val color: String,
    @ColumnInfo(defaultValue = "0") val credit: Float = 0f,
    @ColumnInfo(defaultValue = "''") val note: String = "",
)
