package com.openwakeup.schedule.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.openwakeup.schedule.core.database.entity.TableEntity
import kotlinx.coroutines.flow.Flow

/**
 * 课表 DAO。
 */
@Dao
interface TableDao {

    /** 全部课表（按创建顺序） */
    @Query("SELECT * FROM tables ORDER BY tableOrder, id")
    fun tables(): Flow<List<TableEntity>>

    /** 单张课表 */
    @Query("SELECT * FROM tables WHERE id = :tableId")
    fun table(tableId: Long): Flow<TableEntity?>

    /** 单张课表（一次性） */
    @Query("SELECT * FROM tables WHERE id = :tableId")
    suspend fun tableOnce(tableId: Long): TableEntity?

    /**
     * 一次性读取全部课表，供备份目录和快照使用。
     *
     * 与可观察的 [tables] 保持完全相同的顺序，确保导出后仍能恢复课表间的相对排列。
     */
    @Query("SELECT * FROM tables ORDER BY tableOrder, id")
    suspend fun tablesOnce(): List<TableEntity>

    /** 当前最大课表顺序；没有课表时返回 -1，便于导入内容从 0 开始追加。 */
    @Query("SELECT COALESCE(MAX(tableOrder), -1) FROM tables")
    suspend fun maxTableOrder(): Int

    /** 首张课表（应用首次启动的默认表） */
    @Query("SELECT * FROM tables ORDER BY tableOrder, id LIMIT 1")
    suspend fun firstTable(): TableEntity?

    @Insert
    suspend fun insert(table: TableEntity): Long

    @Update
    suspend fun update(table: TableEntity)

    @Delete
    suspend fun delete(table: TableEntity)
}
