package com.openwakeup.schedule.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.openwakeup.schedule.core.database.entity.TimeDetailEntity
import com.openwakeup.schedule.core.database.entity.TimeTableEntity
import kotlinx.coroutines.flow.Flow

/**
 * 作息时间 DAO。
 */
@Dao
interface TimeTableDao {

    @Query("SELECT * FROM time_tables ORDER BY id")
    fun timeTables(): Flow<List<TimeTableEntity>>

    /** 一次性读取全部时间表，供备份选择页构建目录。 */
    @Query("SELECT * FROM time_tables ORDER BY id")
    suspend fun timeTablesOnce(): List<TimeTableEntity>

    @Query("SELECT * FROM time_tables WHERE id = :id")
    suspend fun timeTableOnce(id: Long): TimeTableEntity?

    @Query("SELECT * FROM time_details WHERE timeTableId = :timeTableId ORDER BY node")
    fun timeDetails(timeTableId: Long): Flow<List<TimeDetailEntity>>

    @Query("SELECT * FROM time_details WHERE timeTableId = :timeTableId ORDER BY node")
    suspend fun timeDetailsOnce(timeTableId: Long): List<TimeDetailEntity>

    @Transaction
    suspend fun insertTimeTableWithDetails(
        table: TimeTableEntity,
        details: List<TimeDetailEntity>
    ): Long {
        val id = insertTimeTable(table)
        insertDetails(details.map { it.copy(timeTableId = id) })
        return id
    }

    @Insert
    suspend fun insertTimeTable(table: TimeTableEntity): Long

    @androidx.room.Update
    suspend fun updateTimeTable(table: TimeTableEntity)

    @androidx.room.Delete
    suspend fun deleteTimeTable(table: TimeTableEntity)

    @Insert
    suspend fun insertDetails(details: List<TimeDetailEntity>)

    @Query("DELETE FROM time_details WHERE timeTableId = :timeTableId")
    suspend fun deleteDetailsOfTable(timeTableId: Long)

    /** 整表重建节次时间（编辑作息时使用） */
    @Transaction
    suspend fun replaceDetails(timeTableId: Long, details: List<TimeDetailEntity>) {
        deleteDetailsOfTable(timeTableId)
        insertDetails(details.map { it.copy(id = 0, timeTableId = timeTableId) })
    }
}
