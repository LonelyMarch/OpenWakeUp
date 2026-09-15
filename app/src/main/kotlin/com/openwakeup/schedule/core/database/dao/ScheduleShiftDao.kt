package com.openwakeup.schedule.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.openwakeup.schedule.core.database.entity.ScheduleShiftEntity
import kotlinx.coroutines.flow.Flow

/** 日期调课记录 DAO。 */
@Dao
interface ScheduleShiftDao {

    /** 持续观察一张课表的调课记录，最新记录排在最前。 */
    @Query("SELECT * FROM schedule_shifts WHERE tableId = :tableId ORDER BY createdAt DESC, id DESC")
    fun shifts(tableId: Long): Flow<List<ScheduleShiftEntity>>

    /** 一次性读取调课记录，供小部件和提醒等同步场景使用。 */
    @Query("SELECT * FROM schedule_shifts WHERE tableId = :tableId ORDER BY createdAt DESC, id DESC")
    suspend fun shiftsOnce(tableId: Long): List<ScheduleShiftEntity>

    /** 同一课表相同日期对只保留一条记录。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(shift: ScheduleShiftEntity): Long

    /** 撤销单条调课记录。 */
    @Query("DELETE FROM schedule_shifts WHERE id = :shiftId")
    suspend fun deleteById(shiftId: Long)

    /** 删除课表时同步清理其调课记录。 */
    @Query("DELETE FROM schedule_shifts WHERE tableId = :tableId")
    suspend fun deleteByTable(tableId: Long)
}
