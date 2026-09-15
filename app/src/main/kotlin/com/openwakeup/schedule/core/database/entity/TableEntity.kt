package com.openwakeup.schedule.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.openwakeup.schedule.core.config.AppDefaults

/**
 * 课表实体：一张课表一份配置（每表全部配置收敛为表字段，样式即渲染单一数据源）。
 * 颜色字段为 #AARRGGBB 字符串；空串表示使用渲染回退值。
 * 运行时默认值统一维护在 [AppDefaults.Table]；下方少数 [ColumnInfo.defaultValue] 仍保留历史
 * Room schema 默认值，用于兼容既有数据库迁移，不代表新建课表的产品默认值。
 *
 * @property tableName 课表名称
 * @property startDate 学期第一周周一（ISO：yyyy-MM-dd）
 * @property maxWeek 学期总周数
 * @property nodes 每天课程节数，同时决定越界校验和课表网格行数；作息不足时显示 24:00 占位
 * @property timeTableId 关联的作息时间表 id
 * @property currentWeekOverride 手动调整的当前周（0 表示未手动调整）
 * @property background 课表背景（颜色 #AARRGGBB 或图片 uri，空为默认渐变）
 * @property textColor 表头/节次/网格线颜色
 * @property courseTextColor 课程卡文字/角标颜色
 * @property itemTextSize 课程卡主文字字号（sp），详情行 -1sp
 * @property itemAlpha 课程卡背景不透明度 0-100（×2.55 → alpha）
 * @property itemHeight 单节课卡片高度（dp）
 * @property itemRadius 卡片圆角（dp）
 * @property strokeColor 卡片描边颜色（Compose 关闭时原色使用）
 * @property useDottedLine 卡片描边虚线
 * @property showGrid 是否显示背景网格（虚线）
 * @property showTimeBar 是否显示节次上下课时间（节点列 tv_start/tv_end）
 * @property showSat/showSun 是否显示周六/周日
 * @property showTime 卡片内显示上课时间
 * @property showTeacher/showLocation/showRoomPrefix 教师/教室/教室@前缀
 * @property showOtherWeekCourse 是否淡化显示非本周课程
 * @property otherWeekCourseAlpha 非本周课程文字/背景/描边透明度百分比
 * @property itemCenterHorizontal/itemCenterVertical 卡片内容水平/垂直居中
 * @property slogan 课表底部个性签名
 */
@Entity(tableName = "tables")
data class TableEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tableName: String,
    val startDate: String,
    val maxWeek: Int = AppDefaults.Table.MAX_WEEK,
    @ColumnInfo(defaultValue = "20")
    val nodes: Int = AppDefaults.Table.NODES,
    val timeTableId: Long = AppDefaults.Table.TIME_TABLE_ID,
    val currentWeekOverride: Int = AppDefaults.Table.CURRENT_WEEK_OVERRIDE,
    val background: String = AppDefaults.Table.BACKGROUND,
    val textColor: String = AppDefaults.Table.TEXT_COLOR,
    @ColumnInfo(defaultValue = "'#FFFFFFFF'")
    val courseTextColor: String = AppDefaults.Table.COURSE_TEXT_COLOR,
    @ColumnInfo(defaultValue = "12")
    val itemTextSize: Int = AppDefaults.Table.ITEM_TEXT_SIZE,
    val itemAlpha: Int = AppDefaults.Table.ITEM_ALPHA,
    val itemHeight: Int = AppDefaults.Table.ITEM_HEIGHT,
    @ColumnInfo(defaultValue = "10")
    val itemRadius: Int = AppDefaults.Table.ITEM_RADIUS,
    @ColumnInfo(defaultValue = "'#B3FFFFFF'")
    val strokeColor: String = AppDefaults.Table.STROKE_COLOR,
    @ColumnInfo(defaultValue = "0")
    val useDottedLine: Boolean = AppDefaults.Table.USE_DOTTED_LINE,
    @ColumnInfo(defaultValue = "1")
    val showGrid: Boolean = AppDefaults.Table.SHOW_GRID,
    @ColumnInfo(defaultValue = "1")
    val showTimeBar: Boolean = AppDefaults.Table.SHOW_TIME_BAR,
    val headerTextSize: Int = AppDefaults.Table.HEADER_TEXT_SIZE,
    val textColorCompose: Boolean = AppDefaults.Table.TEXT_COLOR_COMPOSE,
    val strokeColorCompose: Boolean = AppDefaults.Table.STROKE_COLOR_COMPOSE,
    val showSat: Boolean = AppDefaults.Table.SHOW_SATURDAY,
    val showSun: Boolean = AppDefaults.Table.SHOW_SUNDAY,
    val showTime: Boolean = AppDefaults.Table.SHOW_TIME,
    val showTeacher: Boolean = AppDefaults.Table.SHOW_TEACHER,
    @ColumnInfo(defaultValue = "1")
    val showLocation: Boolean = AppDefaults.Table.SHOW_LOCATION,
    @ColumnInfo(defaultValue = "1")
    val showRoomPrefix: Boolean = AppDefaults.Table.SHOW_ROOM_PREFIX,
    @ColumnInfo(defaultValue = "1")
    val showOtherWeekCourse: Boolean = AppDefaults.Table.SHOW_OTHER_WEEK_COURSE,
    @ColumnInfo(defaultValue = "30")
    val otherWeekCourseAlpha: Int = AppDefaults.Table.OTHER_WEEK_COURSE_ALPHA,
    @ColumnInfo(defaultValue = "0")
    val itemCenterHorizontal: Boolean = AppDefaults.Table.ITEM_CENTER_HORIZONTAL,
    @ColumnInfo(defaultValue = "0")
    val itemCenterVertical: Boolean = AppDefaults.Table.ITEM_CENTER_VERTICAL,
    val slogan: String = AppDefaults.Table.SLOGAN,
    @ColumnInfo(defaultValue = "0") val tableOrder: Int = AppDefaults.Table.TABLE_ORDER,
)
