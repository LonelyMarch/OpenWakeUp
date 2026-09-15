package com.openwakeup.schedule.data.backup

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.room.withTransaction
import com.openwakeup.schedule.core.config.AppDefaults
import com.openwakeup.schedule.core.data.AppLocale
import com.openwakeup.schedule.core.data.AppThemeMode
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.core.data.WidgetEmptyViewMode
import com.openwakeup.schedule.core.database.AppDatabase
import com.openwakeup.schedule.core.database.entity.CourseDetailEntity
import com.openwakeup.schedule.core.database.entity.CourseEntity
import com.openwakeup.schedule.core.database.entity.ScheduleShiftEntity
import com.openwakeup.schedule.core.database.entity.TableEntity
import com.openwakeup.schedule.core.database.entity.TimeDetailEntity
import com.openwakeup.schedule.core.database.entity.TimeTableEntity
import com.openwakeup.schedule.core.format.AppDatePattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * `.openwakebak` 备份的业务入口。
 *
 * 本仓库负责把 Room 实体和 [Prefs] 转换为可移植快照，并在导入时执行严格校验、
 * 新 ID 重映射与增量写入。ZIP 细节集中在 [BackupArchive]，界面无需理解归档内部结构。
 *
 * @param context 用于访问 Room、SharedPreferences、SAF 和应用私有图片
 */
class BackupRepository(context: Context) {

    private val appContext = context.applicationContext
    private val database = AppDatabase.get(appContext)
    private val tableDao = database.tableDao()
    private val courseDao = database.courseDao()
    private val detailDao = database.courseDetailDao()
    private val timeTableDao = database.timeTableDao()
    private val shiftDao = database.scheduleShiftDao()
    private val prefs = Prefs.get(appContext)

    /**
     * 读取本机所有可供导出的课表与时间表。
     *
     * @return 保持数据库展示顺序的目录；两类设置在本机始终可导出
     */
    suspend fun exportCatalog(): BackupCatalog = withContext(Dispatchers.IO) {
        val tables = tableDao.tablesOnce()
        val timeTables = timeTableDao.timeTablesOnce()
        BackupCatalog(
            schedules = tables.map { table ->
                BackupScheduleCatalogItem(
                    sourceId = table.id,
                    name = table.tableName,
                    timeTableSourceId = table.timeTableId,
                )
            },
            timeTables = timeTables.map { timeTable ->
                BackupTimeTableCatalogItem(
                    sourceId = timeTable.id,
                    name = timeTable.name,
                    nodeCount = timeTableDao.timeDetailsOnce(timeTable.id).size,
                )
            },
            hasGlobalSettings = true,
            hasWidgetSettings = true,
        )
    }

    /**
     * 检查备份文件并返回导入页面所需目录。
     *
     * 检查会完整读取文件并验证每个 `entries` 摘要；返回目录不代表稍后导入时跳过复检，
     * 因为用户授予的 URI 内容可能在两次操作之间发生变化。
     *
     * @param uri 用户选择的 `.openwakebak` 文档
     * @return 文件中可选择的课表、时间表和设置类别
     */
    suspend fun inspect(uri: Uri): BackupCatalog = withContext(Dispatchers.IO) {
        decodeAndValidateArchive(readArchive(uri)).archive.manifest.catalog
    }

    /**
     * 按用户选择导出 `.openwakebak`。
     *
     * @param uri SAF 创建的目标文档
     * @param selection 要写入的具体课表、时间表和设置类别
     */
    suspend fun export(uri: Uri, selection: BackupSelection) = withContext(Dispatchers.IO) {
        val fullCatalog = exportCatalog()
        validateSelection(fullCatalog, selection)
        val payloads = linkedMapOf<String, ByteArray>()
        val selectedTimeTables = fullCatalog.timeTables
            .filter { item -> item.sourceId in selection.timeTableSourceIds }
        val selectedSchedules = fullCatalog.schedules
            .filter { item -> item.sourceId in selection.scheduleSourceIds }

        val timeTableSnapshots = selectedTimeTables.map { item ->
            val entity = timeTableDao.timeTableOnce(item.sourceId)
                ?: throw BackupException(
                    BackupException.Reason.INVALID_REFERENCE,
                    "timetable:${item.sourceId}"
                )
            val snapshot = entity.toBackup(timeTableDao.timeDetailsOnce(entity.id))
            payloads[BackupArchive.timeTablePath(item.sourceId)] = encode(
                BackupTimeTable.serializer(),
                snapshot,
            )
            snapshot
        }
        val scheduleSnapshots = selectedSchedules.map { item ->
            val entity = tableDao.tableOnce(item.sourceId)
                ?: throw BackupException(
                    BackupException.Reason.INVALID_REFERENCE,
                    "schedule:${item.sourceId}"
                )
            val backgroundPath = "assets/schedules/${entity.id}/background"
            val snapshot = entity.toBackup(
                courses = courseDao.coursesOnce(entity.id),
                details = courseDao.detailsOfTableOnce(entity.id),
                shifts = shiftDao.shiftsOnce(entity.id),
                background = exportStoredValue(entity.background, backgroundPath, payloads),
            )
            payloads[BackupArchive.schedulePath(item.sourceId)] = encode(
                BackupSchedule.serializer(),
                snapshot,
            )
            snapshot
        }
        val globalSettings = if (selection.includeGlobalSettings) {
            val snapshot = createGlobalSettingsSnapshot()
            payloads[BackupArchive.GLOBAL_SETTINGS_PATH] = encode(
                BackupGlobalSettings.serializer(),
                snapshot,
            )
            snapshot
        } else {
            null
        }
        val widgetSettings = if (selection.includeWidgetSettings) {
            val snapshot = createWidgetSettingsSnapshot(payloads)
            payloads[BackupArchive.WIDGET_SETTINGS_PATH] = encode(
                BackupWidgetSettings.serializer(),
                snapshot,
            )
            snapshot
        } else {
            null
        }

        val catalog = BackupCatalog(
            schedules = selectedSchedules,
            timeTables = selectedTimeTables,
            hasGlobalSettings = selection.includeGlobalSettings,
            hasWidgetSettings = selection.includeWidgetSettings,
        )
        // 所有来源读取完成后再做一次整体校验，防止并发编辑产生目录与快照不一致的文件。
        timeTableSnapshots.zip(selectedTimeTables).forEach { (snapshot, item) ->
            if (snapshot.name != item.name || snapshot.details.size != item.nodeCount) {
                throw BackupException(
                    BackupException.Reason.INVALID_REFERENCE,
                    "timetable-changed:${item.sourceId}"
                )
            }
        }
        scheduleSnapshots.zip(selectedSchedules).forEach { (snapshot, item) ->
            if (snapshot.tableName != item.name || snapshot.timeTableSourceId != item.timeTableSourceId) {
                throw BackupException(
                    BackupException.Reason.INVALID_REFERENCE,
                    "schedule-changed:${item.sourceId}"
                )
            }
        }
        validateSnapshots(
            timeTables = timeTableSnapshots,
            schedules = scheduleSnapshots,
            globalSettings = globalSettings,
            widgetSettings = widgetSettings,
            payloads = payloads,
        )
        val output = try {
            appContext.contentResolver.openOutputStream(uri, "w")
        } catch (error: Exception) {
            throw BackupException(BackupException.Reason.OPEN_FAILED, cause = error)
        } ?: throw BackupException(BackupException.Reason.OPEN_FAILED)
        BackupArchive.write(output, appInfo(), catalog, payloads)
    }

    /**
     * 把用户选择的内容增量恢复到当前应用。
     *
     * 课表和时间表永远新增并重映射主键；全局设置、小部件设置仅在被选择时覆盖。
     * 所有 JSON、引用与图片会在数据库写入前完成解析和校验。
     *
     * @param uri 已选择的 `.openwakebak` 文档
     * @param selection 要恢复的归档来源 ID 和设置类别
     * @return 新增数量与设置覆盖摘要
     */
    suspend fun restore(uri: Uri, selection: BackupSelection): BackupImportResult =
        withContext(Dispatchers.IO) {
            val decodedArchive = decodeAndValidateArchive(readArchive(uri))
            val archive = decodedArchive.archive
            val catalog = archive.manifest.catalog
            validateSelection(catalog, selection)

            // 始终按 manifest 中的稳定顺序取出已经严格校验的快照，不能依赖 Set 的具体实现顺序。
            val timeTables = catalog.timeTables
                .filter { item -> item.sourceId in selection.timeTableSourceIds }
                .map { item -> decodedArchive.timeTables.getValue(item.sourceId) }
            val schedules = catalog.schedules
                .filter { item -> item.sourceId in selection.scheduleSourceIds }
                .map { item -> decodedArchive.schedules.getValue(item.sourceId) }
            val globalSettings = decodedArchive.globalSettings.takeIf {
                selection.includeGlobalSettings
            }
            val widgetSettings = decodedArchive.widgetSettings.takeIf {
                selection.includeWidgetSettings
            }
            val createdFiles = mutableListOf<File>()
            val preparedWidgetSettings = try {
                // 小部件资源不依赖数据库新 ID，先完成实际写入；磁盘失败时数据库尚未变化。
                val prepared = widgetSettings?.let { snapshot ->
                    prepareWidgetSettings(snapshot, archive.payloads, createdFiles)
                }
                importDatabase(timeTables, schedules, archive.payloads, createdFiles)
                prepared
            } catch (error: Exception) {
                // 数据库阶段抛错时 Room 会回滚；文件系统不参与事务，需删除本次创建的全部资源。
                createdFiles.forEach { file -> runCatching { file.delete() } }
                if (error is BackupException) throw error
                throw BackupException(BackupException.Reason.WRITE_FAILED, cause = error)
            }
            try {
                globalSettings?.let(::applyGlobalSettings)
                if (widgetSettings != null && preparedWidgetSettings != null) {
                    applyWidgetSettings(widgetSettings, preparedWidgetSettings)
                }
            } catch (error: Exception) {
                // 此时 Room 事务已经提交，绝不能删除新课表正在引用的背景资源。
                if (error is BackupException) throw error
                throw BackupException(BackupException.Reason.WRITE_FAILED, cause = error)
            }
            BackupImportResult(
                scheduleCount = schedules.size,
                timeTableCount = timeTables.size,
                globalSettingsOverwritten = globalSettings != null,
                widgetSettingsOverwritten = widgetSettings != null,
            )
        }

    /** 使用当前应用设置创建全局设置快照。 */
    private fun createGlobalSettingsSnapshot(): BackupGlobalSettings = BackupGlobalSettings(
        reminderMinutes = prefs.reminderMinutes,
        themeMode = prefs.themeMode.storedValue,
        appLocale = prefs.appLocale.storedValue,
        dateFormat = prefs.dateFormat.id,
        dynamicColors = prefs.dynamicColors,
        scheduleBlankArea = prefs.scheduleBlankArea,
    )

    /** 使用当前小部件全局样式创建快照，并自动收集两类图片。 */
    private fun createWidgetSettingsSnapshot(
        payloads: MutableMap<String, ByteArray>,
    ): BackupWidgetSettings = BackupWidgetSettings(
        showBackground = prefs.widgetShowBackground,
        background = exportStoredValue(
            prefs.widgetBackground,
            "assets/widget/background",
            payloads,
        ),
        emptyViewMode = prefs.widgetEmptyViewMode.storedValue,
        emptyImage = exportStoredValue(
            prefs.widgetEmptyImage,
            "assets/widget/empty",
            payloads,
        ),
        emptyTodayTextOverride = prefs.widgetEmptyTodayTextOverride,
        emptyTomorrowTextOverride = prefs.widgetEmptyTomorrowTextOverride,
        showHeader = prefs.widgetShowHeader,
        showDate = prefs.widgetShowDate,
        showButtons = prefs.widgetShowButtons,
        headerColor = prefs.widgetHeaderColor,
        headerTextSize = prefs.widgetHeaderTextSize,
        showColor = prefs.widgetShowColor,
        itemAlpha = prefs.widgetItemAlpha,
        textSize = prefs.widgetTextSize,
        secondaryTextAlpha = prefs.widgetSecondaryTextAlpha,
        textColor = prefs.widgetTextColor,
        textCompose = prefs.widgetTextCompose,
        strokeColor = prefs.widgetStrokeColor,
        strokeCompose = prefs.widgetStrokeCompose,
    )

    /**
     * 把颜色、空值或私有图片转换为可移植值；私有图片同时写入有效负载。
     */
    private fun exportStoredValue(
        rawValue: String,
        pathWithoutExtension: String,
        payloads: MutableMap<String, ByteArray>,
    ): BackupStoredValue {
        if (rawValue.isBlank()) return BackupStoredValue(BackupStoredValue.KIND_EMPTY)
        if (rawValue.startsWith('#')) {
            validateColor(rawValue)
            return BackupStoredValue(BackupStoredValue.KIND_COLOR, rawValue)
        }
        val file = File(rawValue)
        val appFilesRoot = appContext.filesDir.canonicalFile
        val canonicalFile = runCatching { file.canonicalFile }.getOrNull()
            ?: throw BackupException(BackupException.Reason.INVALID_IMAGE, rawValue)
        if (!canonicalFile.isFile || canonicalFile.parentFile == null ||
            !canonicalFile.path.startsWith(appFilesRoot.path + File.separator)
        ) {
            throw BackupException(BackupException.Reason.INVALID_IMAGE, rawValue)
        }
        if (canonicalFile.length() !in 1..MAX_IMAGE_BYTES.toLong()) {
            throw BackupException(BackupException.Reason.INVALID_IMAGE, canonicalFile.name)
        }
        val extension = safeExtension(canonicalFile.name)
        val bytes = canonicalFile.readBytes()
        validateImage(bytes, canonicalFile.name)
        val assetPath = "$pathWithoutExtension.$extension"
        payloads[assetPath] = bytes
        return BackupStoredValue(BackupStoredValue.KIND_ASSET, assetPath)
    }

    /** 在一个 Room 事务中新增所选时间表、课表和全部关联实体。 */
    private suspend fun importDatabase(
        timeTables: List<BackupTimeTable>,
        schedules: List<BackupSchedule>,
        payloads: Map<String, ByteArray>,
        createdFiles: MutableList<File>,
    ) {
        database.withTransaction {
            val timeTableIdMap = linkedMapOf<Long, Long>()
            timeTables.forEach { snapshot ->
                val newId = timeTableDao.insertTimeTable(
                    TimeTableEntity(
                        id = 0,
                        name = snapshot.name,
                        sameDuration = snapshot.sameDuration,
                        durationMinutes = snapshot.durationMinutes,
                    ),
                )
                timeTableDao.insertDetails(
                    snapshot.details.map { detail ->
                        TimeDetailEntity(
                            id = 0,
                            timeTableId = newId,
                            node = detail.node,
                            startTime = detail.startTime,
                            endTime = detail.endTime,
                        )
                    },
                )
                timeTableIdMap[snapshot.sourceId] = newId
            }

            val firstNewOrder = tableDao.maxTableOrder() + 1
            schedules.forEachIndexed { index, snapshot ->
                val mappedTimeTableId = timeTableIdMap[snapshot.timeTableSourceId]
                    ?: throw BackupException(
                        BackupException.Reason.INVALID_REFERENCE,
                        "schedule-timetable:${snapshot.sourceId}",
                    )
                val initialBackground = when (snapshot.background.kind) {
                    BackupStoredValue.KIND_EMPTY -> ""
                    BackupStoredValue.KIND_COLOR -> snapshot.background.value
                    BackupStoredValue.KIND_ASSET -> ""
                    else -> throw BackupException(BackupException.Reason.INVALID_IMAGE)
                }
                val provisional = snapshot.toEntity(
                    timeTableId = mappedTimeTableId,
                    tableOrder = firstNewOrder + index,
                    background = initialBackground,
                )
                val newTableId = tableDao.insert(provisional)
                val restoredBackground =
                    if (snapshot.background.kind == BackupStoredValue.KIND_ASSET) {
                        restoreAsset(
                            stored = snapshot.background,
                            payloads = payloads,
                            directory = appContext.filesDir,
                            baseName = "table_bg_$newTableId",
                            createdFiles = createdFiles,
                        )
                    } else {
                        initialBackground
                    }
                if (restoredBackground != provisional.background) {
                    tableDao.update(
                        provisional.copy(
                            id = newTableId,
                            background = restoredBackground
                        )
                    )
                }

                val courseIdMap = linkedMapOf<Long, Long>()
                snapshot.courses.forEach { course ->
                    val newCourseId = courseDao.insert(
                        CourseEntity(
                            id = 0,
                            tableId = newTableId,
                            courseName = course.courseName,
                            color = course.color,
                            credit = course.credit,
                            note = course.note,
                        ),
                    )
                    courseIdMap[course.sourceId] = newCourseId
                }
                detailDao.insertAll(
                    snapshot.details.map { detail ->
                        CourseDetailEntity(
                            id = 0,
                            courseId = courseIdMap[detail.courseSourceId]
                                ?: throw BackupException(
                                    BackupException.Reason.INVALID_REFERENCE,
                                    "course-detail:${detail.sourceId}",
                                ),
                            day = detail.day,
                            startNode = detail.startNode,
                            step = detail.step,
                            startWeek = detail.startWeek,
                            endWeek = detail.endWeek,
                            type = detail.type,
                            teacher = detail.teacher,
                            room = detail.room,
                            ownTime = detail.ownTime,
                            startTime = detail.startTime,
                            endTime = detail.endTime,
                        )
                    },
                )
                snapshot.shifts.forEach { shift ->
                    shiftDao.insert(
                        ScheduleShiftEntity(
                            id = 0,
                            tableId = newTableId,
                            fromDate = shift.fromDate,
                            toDate = shift.toDate,
                            createdAt = shift.createdAt,
                        ),
                    )
                }
            }
        }
    }

    /** 覆盖备份中明确包含的全部全局设置字段。 */
    private fun applyGlobalSettings(snapshot: BackupGlobalSettings) {
        prefs.reminderMinutes = snapshot.reminderMinutes
        prefs.themeMode = AppThemeMode.fromStoredValue(snapshot.themeMode)
        prefs.appLocale = AppLocale.fromStoredValue(snapshot.appLocale)
        prefs.dateFormat = AppDatePattern.fromId(snapshot.dateFormat)
        prefs.dynamicColors = snapshot.dynamicColors
        prefs.scheduleBlankArea = snapshot.scheduleBlankArea
    }

    /**
     * 在数据库变化前准备小部件图片，并记录成功后需要替换的旧路径。
     */
    private fun prepareWidgetSettings(
        snapshot: BackupWidgetSettings,
        payloads: Map<String, ByteArray>,
        createdFiles: MutableList<File>,
    ): PreparedWidgetSettings {
        val directory = File(appContext.filesDir, WIDGET_IMAGE_DIRECTORY)
        val restoredBackground = restoreWidgetStoredValue(
            snapshot.background,
            payloads,
            directory,
            "backup_background_${UUID.randomUUID()}",
            createdFiles,
        )
        val restoredEmptyImage = restoreWidgetStoredValue(
            snapshot.emptyImage,
            payloads,
            directory,
            "backup_empty_${UUID.randomUUID()}",
            createdFiles,
        )
        return PreparedWidgetSettings(
            background = restoredBackground,
            emptyImage = restoredEmptyImage,
            previousBackground = prefs.widgetBackground,
            previousEmptyImage = prefs.widgetEmptyImage,
        )
    }

    /**
     * 覆盖全部小部件样式，同时保留目标设备的小部件显示课表。
     *
     * 图片已由 [prepareWidgetSettings] 写入，本函数只切换设置引用，避免磁盘失败发生在
     * 数据库提交之后。
     */
    private fun applyWidgetSettings(
        snapshot: BackupWidgetSettings,
        prepared: PreparedWidgetSettings,
    ) {
        val directory = File(appContext.filesDir, WIDGET_IMAGE_DIRECTORY)

        prefs.widgetShowBackground = snapshot.showBackground
        prefs.widgetBackground = prepared.background
        prefs.widgetEmptyViewMode = WidgetEmptyViewMode.fromStoredValue(snapshot.emptyViewMode)
        prefs.widgetEmptyImage = prepared.emptyImage
        prefs.widgetEmptyTodayTextOverride = snapshot.emptyTodayTextOverride
        prefs.widgetEmptyTomorrowTextOverride = snapshot.emptyTomorrowTextOverride
        prefs.widgetShowHeader = snapshot.showHeader
        prefs.widgetShowDate = snapshot.showDate
        prefs.widgetShowButtons = snapshot.showButtons
        prefs.widgetHeaderColor = snapshot.headerColor
        prefs.widgetHeaderTextSize = snapshot.headerTextSize
        prefs.widgetShowColor = snapshot.showColor
        prefs.widgetItemAlpha = snapshot.itemAlpha
        prefs.widgetTextSize = snapshot.textSize
        prefs.widgetSecondaryTextAlpha = snapshot.secondaryTextAlpha
        prefs.widgetTextColor = snapshot.textColor
        prefs.widgetTextCompose = snapshot.textCompose
        prefs.widgetStrokeColor = snapshot.strokeColor
        prefs.widgetStrokeCompose = snapshot.strokeCompose

        // 新设置已经落盘后才删除旧的受管图片；外部路径和仍在使用的新路径绝不删除。
        deleteReplacedManagedImage(prepared.previousBackground, prepared.background, directory)
        deleteReplacedManagedImage(prepared.previousEmptyImage, prepared.emptyImage, directory)
    }

    /** 将小部件的颜色、空值或图片引用恢复为目标端实际偏好值。 */
    private fun restoreWidgetStoredValue(
        stored: BackupStoredValue,
        payloads: Map<String, ByteArray>,
        directory: File,
        baseName: String,
        createdFiles: MutableList<File>,
    ): String = when (stored.kind) {
        BackupStoredValue.KIND_EMPTY -> ""
        BackupStoredValue.KIND_COLOR -> stored.value
        BackupStoredValue.KIND_ASSET -> restoreAsset(
            stored,
            payloads,
            directory,
            baseName,
            createdFiles,
        )

        else -> throw BackupException(BackupException.Reason.INVALID_IMAGE, stored.kind)
    }

    /** 把已校验的归档图片原样写入应用私有目录，并返回绝对路径。 */
    private fun restoreAsset(
        stored: BackupStoredValue,
        payloads: Map<String, ByteArray>,
        directory: File,
        baseName: String,
        createdFiles: MutableList<File>,
    ): String {
        val bytes = payloads[stored.value]
            ?: throw BackupException(BackupException.Reason.INVALID_IMAGE, stored.value)
        val extension = safeExtension(stored.value)
        if (!directory.exists() && !directory.mkdirs()) {
            throw BackupException(BackupException.Reason.WRITE_FAILED, directory.path)
        }
        val target = File(directory, "$baseName.$extension")
        val temporary = File.createTempFile("openwakeup_restore_", ".$extension", directory)
        try {
            temporary.outputStream().use { output -> output.write(bytes) }
            if (!temporary.renameTo(target)) {
                throw BackupException(BackupException.Reason.WRITE_FAILED, target.path)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        createdFiles += target
        return target.absolutePath
    }

    /** 仅删除小部件私有目录中已被新设置替换的旧图片。 */
    private fun deleteReplacedManagedImage(oldPath: String, newPath: String, directory: File) {
        if (oldPath.isBlank() || oldPath == newPath) return
        val oldFile = runCatching { File(oldPath).canonicalFile }.getOrNull() ?: return
        val root = runCatching { directory.canonicalFile }.getOrNull() ?: return
        if (oldFile.path.startsWith(root.path + File.separator)) runCatching { oldFile.delete() }
    }

    /** 从 SAF 重新打开并严格读取归档。 */
    private fun readArchive(uri: Uri): BackupArchiveContent {
        val input = try {
            appContext.contentResolver.openInputStream(uri)
        } catch (error: Exception) {
            throw BackupException(BackupException.Reason.OPEN_FAILED, cause = error)
        } ?: throw BackupException(BackupException.Reason.OPEN_FAILED)
        return input.use(BackupArchive::read)
    }

    /** 校验选择属于目录并满足课表到时间表的强依赖。 */
    private fun validateSelection(catalog: BackupCatalog, selection: BackupSelection) {
        if (selection.isEmpty ||
            !catalog.schedules.map { it.sourceId }.containsAll(selection.scheduleSourceIds) ||
            !catalog.timeTables.map { it.sourceId }.containsAll(selection.timeTableSourceIds) ||
            (selection.includeGlobalSettings && !catalog.hasGlobalSettings) ||
            (selection.includeWidgetSettings && !catalog.hasWidgetSettings)
        ) {
            throw BackupException(BackupException.Reason.INVALID_SELECTION)
        }
        val dependencies = catalog.schedules
            .filter { item -> item.sourceId in selection.scheduleSourceIds }
            .map { item -> item.timeTableSourceId }
        if (!selection.timeTableSourceIds.containsAll(dependencies)) {
            throw BackupException(BackupException.Reason.INVALID_SELECTION, "missing-timetable")
        }
    }

    /**
     * 解码并校验归档中的全部业务内容。
     *
     * 导入页面首次选择文件时和最终恢复前都调用本函数，因此未被用户选中的条目也不能夹带
     * 损坏 JSON、断裂引用或未声明资源。返回值缓存已解码快照，恢复阶段无需再解析一次。
     *
     * @param archive 已通过 ZIP 路径、大小和摘要校验的归档
     * @return 可按来源 ID 稳定取值的完整快照
     */
    private fun decodeAndValidateArchive(archive: BackupArchiveContent): DecodedBackupArchive {
        val catalog = archive.manifest.catalog
        val timeTables = catalog.timeTables.associate { item ->
            item.sourceId to decodeTimeTable(archive, item)
        }
        val schedules = catalog.schedules.associate { item ->
            item.sourceId to decodeSchedule(archive, item)
        }
        val globalSettings = if (catalog.hasGlobalSettings) decodeGlobalSettings(archive) else null
        val widgetSettings = if (catalog.hasWidgetSettings) decodeWidgetSettings(archive) else null
        validateSnapshots(
            timeTables = timeTables.values.toList(),
            schedules = schedules.values.toList(),
            globalSettings = globalSettings,
            widgetSettings = widgetSettings,
            payloads = archive.payloads,
        )
        return DecodedBackupArchive(
            archive = archive,
            timeTables = timeTables,
            schedules = schedules,
            globalSettings = globalSettings,
            widgetSettings = widgetSettings,
        )
    }

    /** 解码并核对一张时间表文件与 manifest 目录项。 */
    private fun decodeTimeTable(
        archive: BackupArchiveContent,
        item: BackupTimeTableCatalogItem,
    ): BackupTimeTable {
        val snapshot = decode(
            BackupTimeTable.serializer(),
            archive.payloads[BackupArchive.timeTablePath(item.sourceId)],
        )
        if (snapshot.sourceId != item.sourceId || snapshot.name != item.name ||
            snapshot.details.size != item.nodeCount
        ) {
            throw BackupException(
                BackupException.Reason.INTEGRITY_CHECK_FAILED,
                "timetable-catalog"
            )
        }
        return snapshot
    }

    /** 解码并核对一张课表文件与 manifest 目录项。 */
    private fun decodeSchedule(
        archive: BackupArchiveContent,
        item: BackupScheduleCatalogItem,
    ): BackupSchedule {
        val snapshot = decode(
            BackupSchedule.serializer(),
            archive.payloads[BackupArchive.schedulePath(item.sourceId)],
        )
        if (snapshot.sourceId != item.sourceId || snapshot.tableName != item.name ||
            snapshot.timeTableSourceId != item.timeTableSourceId
        ) {
            throw BackupException(BackupException.Reason.INTEGRITY_CHECK_FAILED, "schedule-catalog")
        }
        return snapshot
    }

    /** 解码全局设置快照。 */
    private fun decodeGlobalSettings(archive: BackupArchiveContent): BackupGlobalSettings = decode(
        BackupGlobalSettings.serializer(),
        archive.payloads[BackupArchive.GLOBAL_SETTINGS_PATH],
    )

    /** 解码小部件设置快照。 */
    private fun decodeWidgetSettings(archive: BackupArchiveContent): BackupWidgetSettings = decode(
        BackupWidgetSettings.serializer(),
        archive.payloads[BackupArchive.WIDGET_SETTINGS_PATH],
    )

    /** 统一把序列化错误转换为稳定备份错误。 */
    private fun <T> decode(
        serializer: kotlinx.serialization.KSerializer<T>,
        bytes: ByteArray?,
    ): T {
        if (bytes == null) throw BackupException(BackupException.Reason.INTEGRITY_CHECK_FAILED)
        return try {
            BACKUP_JSON.decodeFromString(serializer, bytes.toString(Charsets.UTF_8))
        } catch (error: SerializationException) {
            throw BackupException(BackupException.Reason.INVALID_FORMAT, "payload-json", error)
        }
    }

    /** 把类型化快照编码为 UTF-8 JSON。 */
    private fun <T> encode(
        serializer: kotlinx.serialization.KSerializer<T>,
        value: T,
    ): ByteArray = BACKUP_JSON.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)

    /** 对归档中的全部业务快照执行主键、引用和资源检查。 */
    private fun validateSnapshots(
        timeTables: List<BackupTimeTable>,
        schedules: List<BackupSchedule>,
        globalSettings: BackupGlobalSettings?,
        widgetSettings: BackupWidgetSettings?,
        payloads: Map<String, ByteArray>,
    ) {
        val timeTableIds = timeTables.map { item -> item.sourceId }
        if (timeTableIds.size != timeTableIds.toSet().size) invalidReference("timetable-id")
        val allTimeDetailIds = timeTables.flatMap { timeTable ->
            timeTable.details.map { detail -> detail.sourceId }
        }
        if (allTimeDetailIds.size != allTimeDetailIds.toSet().size) {
            invalidReference("time-detail-id")
        }
        timeTables.forEach { timeTable ->
            val detailIds = timeTable.details.map { item -> item.sourceId }
            val nodes = timeTable.details.map { item -> item.node }
            if (timeTable.sourceId <= 0L || detailIds.any { it <= 0L } ||
                detailIds.size != detailIds.toSet().size || nodes.any { it <= 0 } ||
                nodes.size != nodes.toSet().size ||
                nodes.size !in 1..AppDefaults.Table.MAX_SUPPORTED_NODES ||
                timeTable.durationMinutes !in MIN_DURATION_MINUTES..MAX_DURATION_MINUTES ||
                timeTable.details.any { detail ->
                    !TIME_PATTERN.matches(detail.startTime) || !TIME_PATTERN.matches(detail.endTime)
                }
            ) invalidReference("timetable:${timeTable.sourceId}")
        }
        val allCourseIds =
            schedules.flatMap { schedule -> schedule.courses.map { course -> course.sourceId } }
        val allCourseDetailIds = schedules.flatMap { schedule ->
            schedule.details.map { detail -> detail.sourceId }
        }
        val allShiftIds =
            schedules.flatMap { schedule -> schedule.shifts.map { shift -> shift.sourceId } }
        if (allCourseIds.size != allCourseIds.toSet().size ||
            allCourseDetailIds.size != allCourseDetailIds.toSet().size ||
            allShiftIds.size != allShiftIds.toSet().size
        ) {
            invalidReference("schedule-child-id")
        }
        schedules.forEach { schedule ->
            if (schedule.sourceId <= 0L || schedule.timeTableSourceId !in timeTableIds ||
                schedule.maxWeek !in 1..AppDefaults.Table.MAX_SUPPORTED_WEEKS ||
                schedule.nodes !in 1..AppDefaults.Table.MAX_SUPPORTED_NODES ||
                schedule.currentWeekOverride < 0 ||
                !isIsoDate(schedule.startDate)
            ) {
                invalidReference("schedule:${schedule.sourceId}")
            }
            validateColor(schedule.textColor)
            validateColor(schedule.courseTextColor)
            validateColor(schedule.strokeColor)
            val courseIds = schedule.courses.map { item -> item.sourceId }
            val detailIds = schedule.details.map { item -> item.sourceId }
            val shiftIds = schedule.shifts.map { item -> item.sourceId }
            schedule.courses.forEach { course ->
                validateColor(course.color)
                if (!course.credit.isFinite()) {
                    throw BackupException(BackupException.Reason.INVALID_FORMAT, "course-credit")
                }
            }
            if (courseIds.any { it <= 0L } || courseIds.size != courseIds.toSet().size ||
                detailIds.any { it <= 0L } || detailIds.size != detailIds.toSet().size ||
                shiftIds.any { it <= 0L } || shiftIds.size != shiftIds.toSet().size ||
                schedule.details.any { detail -> detail.courseSourceId !in courseIds } ||
                schedule.shifts.any { shift ->
                    !isIsoDate(shift.fromDate) || !isIsoDate(shift.toDate) ||
                            shift.fromDate == shift.toDate
                } ||
                schedule.shifts.map { shift -> shift.fromDate }
                    .let { dates -> dates.size != dates.toSet().size }
            ) invalidReference("schedule-children:${schedule.sourceId}")
            validateStoredValue(
                schedule.background,
                payloads,
                "assets/schedules/${schedule.sourceId}/background.",
            )
        }
        globalSettings?.let(::validateGlobalSettings)
        widgetSettings?.let { settings -> validateWidgetSettings(settings, payloads) }
        validateAssetReferences(schedules, widgetSettings, payloads.keys)
    }

    /**
     * 校验资源文件与结构化图片引用一一对应。
     *
     * 这既能拒绝引用缺失，也能阻止归档借允许的 `assets/` 路径夹带未被任何设置使用的文件。
     */
    private fun validateAssetReferences(
        schedules: List<BackupSchedule>,
        widgetSettings: BackupWidgetSettings?,
        payloadPaths: Set<String>,
    ) {
        val referencedAssets = buildSet {
            schedules.forEach { schedule ->
                if (schedule.background.kind == BackupStoredValue.KIND_ASSET) {
                    add(schedule.background.value)
                }
            }
            widgetSettings?.let { settings ->
                if (settings.background.kind == BackupStoredValue.KIND_ASSET) {
                    add(settings.background.value)
                }
                if (settings.emptyImage.kind == BackupStoredValue.KIND_ASSET) {
                    add(settings.emptyImage.value)
                }
            }
        }
        val actualAssets =
            payloadPaths.filterTo(linkedSetOf()) { path -> path.startsWith("assets/") }
        if (referencedAssets != actualAssets) {
            throw BackupException(BackupException.Reason.INTEGRITY_CHECK_FAILED, "asset-reference")
        }
    }

    /** 判断文本是否为严格的 ISO 本地日期。 */
    private fun isIsoDate(value: String): Boolean = runCatching { LocalDate.parse(value) }.isSuccess

    /** 检查全局设置枚举和值域，禁止使用枚举转换函数的默认回退。 */
    private fun validateGlobalSettings(snapshot: BackupGlobalSettings) {
        val validTheme = AppThemeMode.entries.any { mode -> mode.storedValue == snapshot.themeMode }
        val validLocale =
            AppLocale.entries.any { locale -> locale.storedValue == snapshot.appLocale }
        val validDate = AppDatePattern.ALL.any { pattern -> pattern.id == snapshot.dateFormat }
        if (!validTheme || !validLocale || !validDate || snapshot.reminderMinutes < 0) {
            throw BackupException(BackupException.Reason.INVALID_FORMAT, "global-settings")
        }
    }

    /** 检查小部件设置的枚举、范围、颜色和图片引用。 */
    private fun validateWidgetSettings(
        snapshot: BackupWidgetSettings,
        payloads: Map<String, ByteArray>,
    ) {
        if (WidgetEmptyViewMode.entries.none { mode -> mode.storedValue == snapshot.emptyViewMode } ||
            snapshot.headerTextSize !in 8..24 || snapshot.itemAlpha !in 0..100 ||
            snapshot.textSize !in 8..24 || snapshot.secondaryTextAlpha !in 0..100
        ) {
            throw BackupException(BackupException.Reason.INVALID_FORMAT, "widget-settings")
        }
        validateColor(snapshot.headerColor)
        validateColor(snapshot.textColor)
        validateColor(snapshot.strokeColor)
        validateStoredValue(snapshot.background, payloads, "assets/widget/background.")
        validateStoredValue(snapshot.emptyImage, payloads, "assets/widget/empty.")
    }

    /** 校验结构化颜色、空值或指定前缀下的图片引用。 */
    private fun validateStoredValue(
        stored: BackupStoredValue,
        payloads: Map<String, ByteArray>,
        expectedAssetPrefix: String,
    ) {
        when (stored.kind) {
            BackupStoredValue.KIND_EMPTY -> if (stored.value.isNotEmpty()) {
                throw BackupException(BackupException.Reason.INVALID_FORMAT, "empty-value")
            }

            BackupStoredValue.KIND_COLOR -> validateColor(stored.value)
            BackupStoredValue.KIND_ASSET -> {
                if (!stored.value.startsWith(expectedAssetPrefix)) {
                    throw BackupException(BackupException.Reason.INVALID_IMAGE, stored.value)
                }
                val bytes = payloads[stored.value]
                    ?: throw BackupException(BackupException.Reason.INVALID_IMAGE, stored.value)
                validateImage(bytes, stored.value)
            }

            else -> throw BackupException(BackupException.Reason.INVALID_FORMAT, stored.kind)
        }
    }

    /** 校验应用使用的 `#RRGGBB` 或 `#AARRGGBB` 颜色。 */
    private fun validateColor(value: String) {
        if (!COLOR_PATTERN.matches(value) || runCatching { Color.parseColor(value) }.isFailure) {
            throw BackupException(BackupException.Reason.INVALID_FORMAT, "color")
        }
    }

    /** 使用只读尺寸解码验证图片，避免把任意字节写入受管图片目录。 */
    private fun validateImage(bytes: ByteArray, name: String) {
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) {
            throw BackupException(BackupException.Reason.INVALID_IMAGE, name)
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth !in 1..MAX_IMAGE_DIMENSION || options.outHeight !in 1..MAX_IMAGE_DIMENSION) {
            throw BackupException(BackupException.Reason.INVALID_IMAGE, name)
        }
    }

    /** 从文件名提取受限制的小写图片扩展名。 */
    private fun safeExtension(name: String): String {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (!extension.matches(Regex("^[a-z0-9]{1,8}$"))) {
            throw BackupException(BackupException.Reason.INVALID_IMAGE, name)
        }
        return extension
    }

    /** 抛出统一引用错误，简化嵌套实体校验表达式。 */
    private fun invalidReference(detail: String): Nothing =
        throw BackupException(BackupException.Reason.INVALID_REFERENCE, detail)

    /** 获取当前应用版本信息，不依赖已关闭生成的 BuildConfig。 */
    @Suppress("DEPRECATION")
    private fun appInfo(): BackupAppInfo {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        return BackupAppInfo(
            packageName = appContext.packageName,
            versionName = info.versionName.orEmpty(),
            versionCode = info.longVersionCode,
        )
    }

    /** 把 Room 时间表实体转换为归档模型。 */
    private fun TimeTableEntity.toBackup(details: List<TimeDetailEntity>): BackupTimeTable =
        BackupTimeTable(
            sourceId = id,
            name = name,
            sameDuration = sameDuration,
            durationMinutes = durationMinutes,
            details = details.map { detail ->
                BackupTimeDetail(
                    sourceId = detail.id,
                    node = detail.node,
                    startTime = detail.startTime,
                    endTime = detail.endTime,
                )
            },
        )

    /** 把 Room 课表聚合转换为独立 JSON 快照。 */
    private fun TableEntity.toBackup(
        courses: List<CourseEntity>,
        details: List<CourseDetailEntity>,
        shifts: List<ScheduleShiftEntity>,
        background: BackupStoredValue,
    ): BackupSchedule = BackupSchedule(
        sourceId = id,
        tableName = tableName,
        startDate = startDate,
        maxWeek = maxWeek,
        nodes = nodes,
        timeTableSourceId = timeTableId,
        currentWeekOverride = currentWeekOverride,
        background = background,
        textColor = textColor,
        courseTextColor = courseTextColor,
        itemTextSize = itemTextSize,
        itemAlpha = itemAlpha,
        itemHeight = itemHeight,
        itemRadius = itemRadius,
        strokeColor = strokeColor,
        useDottedLine = useDottedLine,
        showGrid = showGrid,
        showTimeBar = showTimeBar,
        headerTextSize = headerTextSize,
        textColorCompose = textColorCompose,
        strokeColorCompose = strokeColorCompose,
        showSat = showSat,
        showSun = showSun,
        showTime = showTime,
        showTeacher = showTeacher,
        showLocation = showLocation,
        showRoomPrefix = showRoomPrefix,
        showOtherWeekCourse = showOtherWeekCourse,
        otherWeekCourseAlpha = otherWeekCourseAlpha,
        itemCenterHorizontal = itemCenterHorizontal,
        itemCenterVertical = itemCenterVertical,
        slogan = slogan,
        tableOrder = tableOrder,
        courses = courses.map { course ->
            BackupCourse(
                sourceId = course.id,
                courseName = course.courseName,
                color = course.color,
                credit = course.credit,
                note = course.note,
            )
        },
        details = details.map { detail ->
            BackupCourseDetail(
                sourceId = detail.id,
                courseSourceId = detail.courseId,
                day = detail.day,
                startNode = detail.startNode,
                step = detail.step,
                startWeek = detail.startWeek,
                endWeek = detail.endWeek,
                type = detail.type,
                teacher = detail.teacher,
                room = detail.room,
                ownTime = detail.ownTime,
                startTime = detail.startTime,
                endTime = detail.endTime,
            )
        },
        shifts = shifts.map { shift ->
            BackupScheduleShift(
                sourceId = shift.id,
                fromDate = shift.fromDate,
                toDate = shift.toDate,
                createdAt = shift.createdAt,
            )
        },
    )

    /** 把课表快照转换为待插入的新 Room 实体。 */
    private fun BackupSchedule.toEntity(
        timeTableId: Long,
        tableOrder: Int,
        background: String,
    ): TableEntity = TableEntity(
        id = 0,
        tableName = tableName,
        startDate = startDate,
        maxWeek = maxWeek,
        nodes = nodes,
        timeTableId = timeTableId,
        currentWeekOverride = currentWeekOverride,
        background = background,
        textColor = textColor,
        courseTextColor = courseTextColor,
        itemTextSize = itemTextSize,
        itemAlpha = itemAlpha,
        itemHeight = itemHeight,
        itemRadius = itemRadius,
        strokeColor = strokeColor,
        useDottedLine = useDottedLine,
        showGrid = showGrid,
        showTimeBar = showTimeBar,
        headerTextSize = headerTextSize,
        textColorCompose = textColorCompose,
        strokeColorCompose = strokeColorCompose,
        showSat = showSat,
        showSun = showSun,
        showTime = showTime,
        showTeacher = showTeacher,
        showLocation = showLocation,
        showRoomPrefix = showRoomPrefix,
        showOtherWeekCourse = showOtherWeekCourse,
        otherWeekCourseAlpha = otherWeekCourseAlpha,
        itemCenterHorizontal = itemCenterHorizontal,
        itemCenterVertical = itemCenterVertical,
        slogan = slogan,
        tableOrder = tableOrder,
    )

    /**
     * 已提前写入的小部件资源及其旧引用。
     *
     * 该对象只在单次恢复调用中存在，不属于 `.openwakebak` 协议模型。
     */
    private data class PreparedWidgetSettings(
        val background: String,
        val emptyImage: String,
        val previousBackground: String,
        val previousEmptyImage: String,
    )

    /**
     * 一份已经完成全部业务校验的归档。
     *
     * Map 键均为 manifest 中的来源 ID，只在单次检查或恢复调用期间存在。
     */
    private data class DecodedBackupArchive(
        val archive: BackupArchiveContent,
        val timeTables: Map<Long, BackupTimeTable>,
        val schedules: Map<Long, BackupSchedule>,
        val globalSettings: BackupGlobalSettings?,
        val widgetSettings: BackupWidgetSettings?,
    )

    private companion object {
        const val WIDGET_IMAGE_DIRECTORY = "widget_images"
        const val MAX_IMAGE_BYTES = 24 * 1024 * 1024
        const val MAX_IMAGE_DIMENSION = 16_384
        const val MIN_DURATION_MINUTES = 10
        const val MAX_DURATION_MINUTES = 180
        val COLOR_PATTERN = Regex("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$")
        val TIME_PATTERN = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    }
}
