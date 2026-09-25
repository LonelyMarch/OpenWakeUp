package com.openwakeup.schedule.data.schedule

import com.openwakeup.schedule.core.database.entity.CourseDetailEntity

/**
 * Repository 写入一门课程所需的非持久化模型。
 *
 * 该模型位于数据层但不是 Room Entity，用于隔离解析器输出与数据库实体。CSV、HTML、ICS 和
 * Web 导入先在功能层完成分组与校验，再把稳定的课程写入模型交给 Repository 批量保存。
 *
 * @property name 课程名称
 * @property color 课程卡片颜色
 * @property teacher 时间段教师为空时使用的公共回退值
 * @property room 时间段地点为空时使用的公共回退值
 * @property details 课程包含的全部时间段
 * @property credit 课程学分
 * @property note 课程备注
 */
internal data class CourseWriteModel(
    val name: String,
    val color: String,
    val teacher: String,
    val room: String,
    val details: List<CourseDetailEntity>,
    val credit: Float = 0f,
    val note: String = "",
)

/**
 * 一次课程导入的完整数据库写入请求。
 *
 * 覆盖、扩周、ICS 学期日期调整以及课程和时间段插入由 Repository 在同一 Room 事务中完成。
 * 请求不包含解析器类型，避免 `data` 层反向依赖 `feature` 或独立 Parser 模块。
 *
 * @property tableId 导入目标课表主键
 * @property overwriteExisting 是否在写入前清空目标课表现有课程和调课记录
 * @property targetMaxWeek 导入后课表至少应具有的周数
 * @property courses 已完成分组、去重和颜色选择的课程
 * @property semesterStartDate ICS 导入指定的新学期开始日期；其他导入为 `null`
 * @property resetCurrentWeekOverride 是否把手动当前周恢复为自动计算
 */
internal data class CourseImportWriteRequest(
    val tableId: Long,
    val overwriteExisting: Boolean,
    val targetMaxWeek: Int,
    val courses: List<CourseWriteModel>,
    val semesterStartDate: String? = null,
    val resetCurrentWeekOverride: Boolean = false,
)
