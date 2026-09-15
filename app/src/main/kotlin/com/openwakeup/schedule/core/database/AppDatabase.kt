package com.openwakeup.schedule.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.openwakeup.schedule.core.config.AppDefaults
import com.openwakeup.schedule.core.database.AppDatabase.Companion.MIGRATION_1_2
import com.openwakeup.schedule.core.database.dao.CourseDao
import com.openwakeup.schedule.core.database.dao.CourseDetailDao
import com.openwakeup.schedule.core.database.dao.ScheduleShiftDao
import com.openwakeup.schedule.core.database.dao.TableDao
import com.openwakeup.schedule.core.database.dao.TimeTableDao
import com.openwakeup.schedule.core.database.entity.CourseDetailEntity
import com.openwakeup.schedule.core.database.entity.CourseEntity
import com.openwakeup.schedule.core.database.entity.ScheduleShiftEntity
import com.openwakeup.schedule.core.database.entity.TableEntity
import com.openwakeup.schedule.core.database.entity.TimeDetailEntity
import com.openwakeup.schedule.core.database.entity.TimeTableEntity

/**
 * Room 数据库（当前 version 10）：包含课表、课程、作息和可撤销日期调课记录。
 * 首建回调预置默认作息表（1~12 节，每节 45 分钟）。
 *
 * v2 迁移见 [MIGRATION_1_2]
 */
@Database(
    entities = [
        TableEntity::class,
        CourseEntity::class,
        CourseDetailEntity::class,
        TimeTableEntity::class,
        TimeDetailEntity::class,
        ScheduleShiftEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun tableDao(): TableDao
    abstract fun courseDao(): CourseDao
    abstract fun courseDetailDao(): CourseDetailDao
    abstract fun timeTableDao(): TimeTableDao
    abstract fun scheduleShiftDao(): ScheduleShiftDao

    companion object {

        /** v9 → v10：课程增加可选学分与最长 300 字的备注。 */
        private val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE courses ADD COLUMN credit REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE courses ADD COLUMN note TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v8 → v9：每张时间表保存独立的单节课时长。 */
        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE time_tables ADD COLUMN durationMinutes INTEGER NOT NULL DEFAULT 45")
            }
        }

        /** v7 → v8：时间表增加“每节课时长相同”持久化开关。 */
        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE time_tables ADD COLUMN sameDuration INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v6 → v7：增加按课表隔离、可撤销的日期调课记录表。 */
        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS schedule_shifts (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "tableId INTEGER NOT NULL, fromDate TEXT NOT NULL, " +
                            "toDate TEXT NOT NULL, createdAt INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_schedule_shifts_tableId ON schedule_shifts(tableId)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_schedule_shifts_tableId_fromDate " +
                            "ON schedule_shifts(tableId, fromDate)",
                )
            }
        }

        /**
         * v5 → v6：更新仍保持旧默认值的作息时间。
         *
         * 每一节都附带旧起止时间条件，因此用户手动修改过的节次不会被迁移覆盖。
         */
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                val oldTimes = listOf(
                    "08:00" to "08:45", "08:55" to "09:40", "10:00" to "10:45", "10:55" to "11:40",
                    "14:00" to "14:45", "14:55" to "15:40", "16:00" to "16:45", "16:55" to "17:40",
                    "19:00" to "19:45", "19:55" to "20:40", "20:50" to "21:35", "21:45" to "22:30",
                )
                oldTimes.zip(DEFAULT_TIMES).forEachIndexed { index, (oldTime, newTime) ->
                    db.execSQL(
                        "UPDATE time_details SET startTime = ?, endTime = ? " +
                                "WHERE timeTableId = 1 AND node = ? AND startTime = ? AND endTime = ?",
                        arrayOf<Any>(
                            newTime.first,
                            newTime.second,
                            index + 1,
                            oldTime.first,
                            oldTime.second
                        ),
                    )
                }
            }
        }

        /** v4 -> v5: showGrid default off (official appearance page) */
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("UPDATE tables SET showGrid = 0")
            }
        }

        /** v1 → v2：tables 表补齐课表配置字段（默认值与实体声明一致） */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf(
                    "nodes INTEGER NOT NULL DEFAULT 20",
                    "courseTextColor TEXT NOT NULL DEFAULT '#FFFFFFFF'",
                    "itemTextSize INTEGER NOT NULL DEFAULT 12",
                    "itemRadius INTEGER NOT NULL DEFAULT 10",
                    "strokeColor TEXT NOT NULL DEFAULT '#B3FFFFFF'",
                    "useDottedLine INTEGER NOT NULL DEFAULT 0",
                    "showGrid INTEGER NOT NULL DEFAULT 1",
                    "showTimeBar INTEGER NOT NULL DEFAULT 1",
                    "showLocation INTEGER NOT NULL DEFAULT 1",
                    "showRoomPrefix INTEGER NOT NULL DEFAULT 1",
                    "showOtherWeekCourse INTEGER NOT NULL DEFAULT 1",
                    "otherWeekCourseAlpha INTEGER NOT NULL DEFAULT 30",
                    "itemCenterHorizontal INTEGER NOT NULL DEFAULT 0",
                    "itemCenterVertical INTEGER NOT NULL DEFAULT 0",
                ).forEach { col ->
                    val name = col.substringBefore(' ')
                    val def = col.substringAfter("DEFAULT ")
                    // v1 的 itemHeight(64) 保留；新增列以默认值填充
                    db.execSQL(
                        "ALTER TABLE tables ADD COLUMN $name ${
                            col.substringAfter(' ').substringBefore(" DEFAULT")
                        } NOT NULL DEFAULT $def"
                    )
                }
                // 默认节高 56dp：老数据一并校正
                db.execSQL("UPDATE tables SET itemHeight = 56 WHERE itemHeight = 64")
                // v1 旧列 itemRoundRadius 更名为 itemRadius：删除遗留列（SQLite 3.35+/Android 13+）
                db.execSQL("ALTER TABLE tables DROP COLUMN itemRoundRadius")
                // v1 旧列 showOtherWeek 更名为 showOtherWeekCourse
                db.execSQL("ALTER TABLE tables DROP COLUMN showOtherWeek")
                // v2 顺带补课程时间段"自定义时间"字段（与备份格式同名字段对应）
                db.execSQL("ALTER TABLE course_details ADD COLUMN ownTime INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE course_details ADD COLUMN startTime TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE course_details ADD COLUMN endTime TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v2 → v3：新增表排序字段（多课表管理/底部浮窗拖拽排序共用） */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tables ADD COLUMN tableOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3 → v4：课表外观字段补齐 + 默认值 */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tables ADD COLUMN headerTextSize INTEGER NOT NULL DEFAULT 11")
                db.execSQL("ALTER TABLE tables ADD COLUMN textColorCompose INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tables ADD COLUMN strokeColorCompose INTEGER NOT NULL DEFAULT 0")
                // 默认值与设置页一致
                db.execSQL("UPDATE tables SET itemAlpha = 50, itemHeight = 64, itemRadius = 4, showTime = 0, otherWeekCourseAlpha = 50")
            }
        }

        /** 默认作息：1~12 节，每节固定 45 分钟。 */
        private val DEFAULT_TIMES = listOf(
            "08:30" to "09:15", "09:20" to "10:05", "10:20" to "11:05", "11:10" to "11:55",
            "14:30" to "15:15", "15:20" to "16:05", "16:20" to "17:05", "17:10" to "17:55",
            "19:30" to "20:15", "20:20" to "21:05", "21:10" to "21:55", "22:00" to "22:45",
        )

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * 获取单例数据库；首次创建时预置默认作息表。
         *
         * @param context 任意 Context
         * @return AppDatabase 单例
         */
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "openwakeup.db",
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                    )
                    .addCallback(object : Callback() {
                        // 预置默认作息表
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            super.onCreate(db)
                            val sameDuration = if (AppDefaults.Timetable.SAME_DURATION) 1 else 0
                            db.execSQL(
                                "INSERT INTO time_tables (id, name, sameDuration, durationMinutes) " +
                                        "VALUES (1, '默认作息', $sameDuration, " +
                                        "${AppDefaults.Timetable.DURATION_MINUTES})",
                            )
                            DEFAULT_TIMES.forEachIndexed { index, (start, end) ->
                                db.execSQL(
                                    "INSERT INTO time_details (timeTableId, node, startTime, endTime) VALUES (1, ${index + 1}, '$start', '$end')",
                                )
                            }
                        }
                    }).build().also { instance = it }
            }
    }
}
