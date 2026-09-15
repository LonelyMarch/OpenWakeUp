package com.openwakeup.schedule.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import com.openwakeup.schedule.core.database.entity.CourseDetailEntity
import com.openwakeup.schedule.core.database.entity.CourseEntity
import kotlinx.coroutines.flow.Flow

/**
 * 课程 + 其全部时间段的关联模型。
 */
data class CourseWithDetails(
    @Embedded val course: CourseEntity,
    @Relation(parentColumn = "id", entityColumn = "courseId")
    val details: List<CourseDetailEntity>,
)

/**
 * 课程 DAO。
 */
@Dao
interface CourseDao {

    /** 某课表全部课程（含时间段） */
    @Transaction
    @Query("SELECT * FROM courses WHERE tableId = :tableId ORDER BY id")
    fun coursesWithDetails(tableId: Long): Flow<List<CourseWithDetails>>

    /** 单门课程（含时间段） */
    @Transaction
    @Query("SELECT * FROM courses WHERE id = :courseId")
    suspend fun courseWithDetailsOnce(courseId: Long): CourseWithDetails?

    /** 单门课程的全部时间段（一次性，渲染/提醒用） */
    @Query("SELECT * FROM course_details WHERE courseId IN (SELECT id FROM courses WHERE tableId = :tableId)")
    suspend fun detailsOfTableOnce(tableId: Long): List<CourseDetailEntity>

    /** 单门课程的全部时间段 */
    @Query("SELECT * FROM course_details WHERE courseId = :courseId ORDER BY day, startNode")
    suspend fun detailsOnce(courseId: Long): List<CourseDetailEntity>

    /** 某课表全部课程（一次性，导出/小部件用） */
    @Query("SELECT * FROM courses WHERE tableId = :tableId ORDER BY id")
    suspend fun coursesOnce(tableId: Long): List<CourseEntity>

    @Insert
    suspend fun insert(course: CourseEntity): Long

    @Update
    suspend fun update(course: CourseEntity)

    @Delete
    suspend fun delete(course: CourseEntity)

    /** 按课表清空课程（删表时使用） */
    @Query("DELETE FROM courses WHERE tableId = :tableId")
    suspend fun deleteCoursesOfTable(tableId: Long)
}

/**
 * 课程时间段 DAO。
 */
@Dao
interface CourseDetailDao {

    @Insert
    suspend fun insertAll(details: List<CourseDetailEntity>): List<Long>

    @Insert
    suspend fun insert(detail: CourseDetailEntity): Long

    @Update
    suspend fun update(detail: CourseDetailEntity)

    @Delete
    suspend fun delete(detail: CourseDetailEntity)

    /** 按课程清空时间段（编辑课程时整组重建） */
    @Query("DELETE FROM course_details WHERE courseId = :courseId")
    suspend fun deleteDetailsOfCourse(courseId: Long)

    /** 单条时间段 */
    @Query("SELECT * FROM course_details WHERE id = :detailId")
    suspend fun detailOnce(detailId: Long): CourseDetailEntity?
}
