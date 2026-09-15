package com.openwakeup.schedule.data.backup

import kotlinx.serialization.Serializable

/**
 * 当前 OpenWakeUp 唯一支持的 `.openwakebak` 文件格式标识。
 *
 * 该值写入备份归档内 `manifest.json` 的 `format` 字段。导入器会将文件中的值与本常量
 * 严格比较，从而区分 OpenWakeUp 备份与普通 ZIP 或其他应用生成的归档。
 */
internal const val BACKUP_FORMAT = "openwakeup-backup"

/** 当前应用唯一支持的备份协议版本。 */
internal const val BACKUP_FORMAT_VERSION = 1

/** 当前 Room 数据库版本；备份导入严格要求完全一致。 */
internal const val BACKUP_DATABASE_SCHEMA_VERSION = 10

/** OpenWakeUp 备份文件使用的扩展名。 */
const val BACKUP_FILE_EXTENSION = ".openwakebak"

/** OpenWakeUp 备份文件在 SAF 中使用的专用 MIME。 */
const val BACKUP_MIME_TYPE = "application/vnd.openwakeup.backup+zip"

/**
 * 用户在导入或导出页面选中的备份内容。
 *
 * ID 始终是来源侧 ID：导出时对应当前数据库，导入时对应备份文件中的 `sourceId`。
 *
 * @property scheduleSourceIds 已选择的课表 ID
 * @property timeTableSourceIds 已选择的时间表 ID
 * @property includeGlobalSettings 是否包含全局设置
 * @property includeWidgetSettings 是否包含小部件全局样式
 */
data class BackupSelection(
    val scheduleSourceIds: Set<Long> = emptySet(),
    val timeTableSourceIds: Set<Long> = emptySet(),
    val includeGlobalSettings: Boolean = false,
    val includeWidgetSettings: Boolean = false,
) {
    /** 当前选择是否完全为空。 */
    val isEmpty: Boolean
        get() = scheduleSourceIds.isEmpty() && timeTableSourceIds.isEmpty() &&
                !includeGlobalSettings && !includeWidgetSettings
}

/**
 * 课表与时间表联动后需要展示的用户提示。
 */
enum class BackupSelectionNotice {
    /** 勾选课表时补选了它引用的时间表。 */
    TIME_TABLE_AUTO_SELECTED,

    /** 取消时间表时取消了所有引用它的已选课表。 */
    SCHEDULE_AUTO_DESELECTED,
}

/**
 * 一次选择变更的完整结果。
 *
 * @property selection 联动后的合法选择
 * @property notice 本次联动需要显示的提示；没有自动变更时为 `null`
 */
data class BackupSelectionChange(
    val selection: BackupSelection,
    val notice: BackupSelectionNotice? = null,
)

/**
 * 两个备份页面共用的依赖选择规则。
 *
 * 规则只根据 [BackupCatalog] 工作，不接触 View，保证导入、导出页面不会出现不同的联动行为。
 */
object BackupSelectionRules {

    /**
     * 更新一张课表的选中状态；选中课表时自动补选其关联时间表。
     *
     * @param current 变更前的选择
     * @param catalog 当前页面展示的备份目录
     * @param scheduleId 目标课表来源 ID
     * @param checked 新的选中状态
     * @return 联动后的选择和可选提示
     */
    fun toggleSchedule(
        current: BackupSelection,
        catalog: BackupCatalog,
        scheduleId: Long,
        checked: Boolean,
    ): BackupSelectionChange {
        val schedule = catalog.schedules.firstOrNull { item -> item.sourceId == scheduleId }
            ?: return BackupSelectionChange(current)
        val schedules = current.scheduleSourceIds.toMutableSet()
        val timeTables = current.timeTableSourceIds.toMutableSet()
        var notice: BackupSelectionNotice? = null
        if (checked) {
            schedules += scheduleId
            if (timeTables.add(schedule.timeTableSourceId)) {
                notice = BackupSelectionNotice.TIME_TABLE_AUTO_SELECTED
            }
        } else {
            schedules -= scheduleId
        }
        return BackupSelectionChange(
            current.copy(scheduleSourceIds = schedules, timeTableSourceIds = timeTables),
            notice,
        )
    }

    /**
     * 更新时间表选中状态；取消时间表时同步取消所有引用它的已选课表。
     *
     * @param current 变更前的选择
     * @param catalog 当前页面展示的备份目录
     * @param timeTableId 目标时间表来源 ID
     * @param checked 新的选中状态
     * @return 联动后的选择和可选提示
     */
    fun toggleTimeTable(
        current: BackupSelection,
        catalog: BackupCatalog,
        timeTableId: Long,
        checked: Boolean,
    ): BackupSelectionChange {
        if (catalog.timeTables.none { item -> item.sourceId == timeTableId }) {
            return BackupSelectionChange(current)
        }
        val timeTables = current.timeTableSourceIds.toMutableSet()
        val schedules = current.scheduleSourceIds.toMutableSet()
        var notice: BackupSelectionNotice? = null
        if (checked) {
            timeTables += timeTableId
        } else {
            timeTables -= timeTableId
            val removed = catalog.schedules
                .filter { item -> item.timeTableSourceId == timeTableId }
                .map { item -> item.sourceId }
                .toSet()
            if (schedules.removeAll(removed)) {
                notice = BackupSelectionNotice.SCHEDULE_AUTO_DESELECTED
            }
        }
        return BackupSelectionChange(
            current.copy(scheduleSourceIds = schedules, timeTableSourceIds = timeTables),
            notice,
        )
    }
}

/**
 * 备份中可供用户选择的业务目录。
 *
 * @property schedules 归档内课表摘要
 * @property timeTables 归档内时间表摘要
 * @property hasGlobalSettings 是否包含全局设置
 * @property hasWidgetSettings 是否包含小部件设置
 */
@Serializable
data class BackupCatalog(
    val schedules: List<BackupScheduleCatalogItem>,
    val timeTables: List<BackupTimeTableCatalogItem>,
    val hasGlobalSettings: Boolean,
    val hasWidgetSettings: Boolean,
) {
    /** 创建默认全选且满足全部课表依赖的选择。 */
    fun selectAll(): BackupSelection = BackupSelection(
        scheduleSourceIds = schedules.mapTo(linkedSetOf()) { item -> item.sourceId },
        timeTableSourceIds = timeTables.mapTo(linkedSetOf()) { item -> item.sourceId },
        includeGlobalSettings = hasGlobalSettings,
        includeWidgetSettings = hasWidgetSettings,
    )
}

/**
 * 一张课表在备份目录中的展示信息。
 *
 * @property sourceId 来源数据库中的课表 ID
 * @property name 用户可见课表名
 * @property timeTableSourceId 该课表引用的来源时间表 ID
 */
@Serializable
data class BackupScheduleCatalogItem(
    val sourceId: Long,
    val name: String,
    val timeTableSourceId: Long,
)

/**
 * 一张时间表在备份目录中的展示信息。
 *
 * @property sourceId 来源数据库中的时间表 ID
 * @property name 用户可见时间表名
 * @property nodeCount 节次数量，用于选择页摘要
 */
@Serializable
data class BackupTimeTableCatalogItem(
    val sourceId: Long,
    val name: String,
    val nodeCount: Int,
)

/** `.openwakebak` 根清单。 */
@Serializable
internal data class BackupManifest(
    val format: String,
    val formatVersion: Int,
    val databaseSchemaVersion: Int,
    val createdAt: String,
    val app: BackupAppInfo,
    val catalog: BackupCatalog,
    val entries: List<BackupManifestEntry>,
)

/** 导出应用的版本信息。 */
@Serializable
internal data class BackupAppInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
)

/**
 * ZIP 中一个有效负载文件的完整性记录。
 *
 * @property path 相对于 ZIP 根目录的规范路径
 * @property size 未压缩字节数
 * @property sha256 对未压缩字节计算的小写十六进制 SHA-256
 */
@Serializable
internal data class BackupManifestEntry(
    val path: String,
    val size: Long,
    val sha256: String,
)

/** 一张作息时间表及其全部节次。 */
@Serializable
internal data class BackupTimeTable(
    val sourceId: Long,
    val name: String,
    val sameDuration: Boolean,
    val durationMinutes: Int,
    val details: List<BackupTimeDetail>,
)

/** 时间表中的单节起止时间。 */
@Serializable
internal data class BackupTimeDetail(
    val sourceId: Long,
    val node: Int,
    val startTime: String,
    val endTime: String,
)

/** 一张课表及其课程、时间段和调课记录。 */
@Serializable
internal data class BackupSchedule(
    val sourceId: Long,
    val tableName: String,
    val startDate: String,
    val maxWeek: Int,
    val nodes: Int,
    val timeTableSourceId: Long,
    val currentWeekOverride: Int,
    val background: BackupStoredValue,
    val textColor: String,
    val courseTextColor: String,
    val itemTextSize: Int,
    val itemAlpha: Int,
    val itemHeight: Int,
    val itemRadius: Int,
    val strokeColor: String,
    val useDottedLine: Boolean,
    val showGrid: Boolean,
    val showTimeBar: Boolean,
    val headerTextSize: Int,
    val textColorCompose: Boolean,
    val strokeColorCompose: Boolean,
    val showSat: Boolean,
    val showSun: Boolean,
    val showTime: Boolean,
    val showTeacher: Boolean,
    val showLocation: Boolean,
    val showRoomPrefix: Boolean,
    val showOtherWeekCourse: Boolean,
    val otherWeekCourseAlpha: Int,
    val itemCenterHorizontal: Boolean,
    val itemCenterVertical: Boolean,
    val slogan: String,
    val tableOrder: Int,
    val courses: List<BackupCourse>,
    val details: List<BackupCourseDetail>,
    val shifts: List<BackupScheduleShift>,
)

/** 课程基础信息。 */
@Serializable
internal data class BackupCourse(
    val sourceId: Long,
    val courseName: String,
    val color: String,
    val credit: Float,
    val note: String,
)

/** 一门课程的一段上课安排。 */
@Serializable
internal data class BackupCourseDetail(
    val sourceId: Long,
    val courseSourceId: Long,
    val day: Int,
    val startNode: Int,
    val step: Int,
    val startWeek: Int,
    val endWeek: Int,
    val type: Int,
    val teacher: String,
    val room: String,
    val ownTime: Boolean,
    val startTime: String,
    val endTime: String,
)

/** 一条日期调课记录。 */
@Serializable
internal data class BackupScheduleShift(
    val sourceId: Long,
    val fromDate: String,
    val toDate: String,
    val createdAt: Long,
)

/**
 * 可移植的颜色、空值或图片资源引用。
 *
 * @property kind `empty`、`color` 或 `asset`
 * @property value 颜色字符串或 ZIP 内资源路径；`empty` 时为空
 */
@Serializable
internal data class BackupStoredValue(
    val kind: String,
    val value: String = "",
) {
    companion object {
        const val KIND_EMPTY = "empty"
        const val KIND_COLOR = "color"
        const val KIND_ASSET = "asset"
    }
}

/** 全局设置的类型化快照。 */
@Serializable
internal data class BackupGlobalSettings(
    val reminderMinutes: Int,
    val themeMode: Int,
    val appLocale: Int,
    val dateFormat: String,
    val dynamicColors: Boolean,
    val scheduleBlankArea: Boolean,
)

/** 小部件全局样式的类型化快照；不包含小部件显示课表。 */
@Serializable
internal data class BackupWidgetSettings(
    val showBackground: Boolean,
    val background: BackupStoredValue,
    val emptyViewMode: String,
    val emptyImage: BackupStoredValue,
    val emptyTodayTextOverride: String?,
    val emptyTomorrowTextOverride: String?,
    val showHeader: Boolean,
    val showDate: Boolean,
    val showButtons: Boolean,
    val headerColor: String,
    val headerTextSize: Int,
    val showColor: Boolean,
    val itemAlpha: Int,
    val textSize: Int,
    val secondaryTextAlpha: Int,
    val textColor: String,
    val textCompose: Boolean,
    val strokeColor: String,
    val strokeCompose: Boolean,
)

/** 已读取并通过归档级完整性校验的 ZIP 内容。 */
internal data class BackupArchiveContent(
    val manifest: BackupManifest,
    val payloads: Map<String, ByteArray>,
)

/** 导入成功后的新增与覆盖摘要。 */
data class BackupImportResult(
    val scheduleCount: Int,
    val timeTableCount: Int,
    val globalSettingsOverwritten: Boolean,
    val widgetSettingsOverwritten: Boolean,
)

/**
 * 备份失败的稳定原因，界面层负责映射为本地化文案。
 */
class BackupException(
    val reason: Reason,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception("$reason${detail?.let { value -> ":$value" }.orEmpty()}", cause) {
    enum class Reason {
        OPEN_FAILED,
        INVALID_FORMAT,
        UNSUPPORTED_VERSION,
        DATABASE_VERSION_MISMATCH,
        INTEGRITY_CHECK_FAILED,
        UNSAFE_ARCHIVE,
        INVALID_SELECTION,
        INVALID_REFERENCE,
        INVALID_IMAGE,
        WRITE_FAILED,
    }
}
