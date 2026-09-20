package com.openwakeup.schedule.feature.importexport

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.core.designsystem.theme.AppThemeController
import com.openwakeup.schedule.core.format.AppLocaleResolver
import com.openwakeup.schedule.data.backup.BACKUP_MIME_TYPE
import com.openwakeup.schedule.data.backup.BackupCatalog
import com.openwakeup.schedule.data.backup.BackupRepository
import com.openwakeup.schedule.data.backup.BackupSelection
import com.openwakeup.schedule.data.backup.BackupSelectionChange
import com.openwakeup.schedule.data.backup.BackupSelectionNotice
import com.openwakeup.schedule.data.backup.BackupSelectionRules
import com.openwakeup.schedule.databinding.ActivityBackupSelectionBinding
import com.openwakeup.schedule.feature.settings.CategoryItem
import com.openwakeup.schedule.feature.settings.HeaderItem
import com.openwakeup.schedule.feature.settings.SettingsItem
import com.openwakeup.schedule.feature.settings.SettingsListAdapter
import com.openwakeup.schedule.feature.settings.SwitchItem
import com.openwakeup.schedule.feature.settings.VerticalItem
import com.openwakeup.schedule.platform.appwidget.WidgetRefreshScheduler
import com.openwakeup.schedule.platform.reminder.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap

/**
 * 选择 `.openwakebak` 内容并增量导入的独立页面。
 *
 * 文件在展示目录时先做一次完整校验，用户确认后仓库会重新打开并复检，确保两次操作之间
 * URI 内容发生变化时不会把未验证数据写入数据库。
 */
class BackupImportActivity : AppCompatActivity() {

    private val repository by lazy { BackupRepository(this) }
    private lateinit var binding: ActivityBackupSelectionBinding
    private lateinit var adapter: SettingsListAdapter
    private var selectedFileUri: Uri? = null
    private var selectedFileName = ""
    private var catalog: BackupCatalog? = null
    private var selection = BackupSelection()
    private var restoredSelection: BackupSelection? = null
    private var busy = false
    private val itemTargets = IdentityHashMap<SettingsItem, SelectionTarget>()

    /** 选择 OpenWakeUp 新备份文件，并保留后续重新校验所需的读取权限。 */
    private val selectBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            selectedFileUri = uri
            selectedFileName = displayName(uri)
            // 新文件必须重新建立目录与默认选择，不能沿用上一个文件恰好相同的来源 ID。
            catalog = null
            selection = BackupSelection()
            restoredSelection = null
            inspectSelectedFile()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityBackupSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        restoreState(savedInstanceState)
        binding.tvTitle.setText(R.string.backup_import_title)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAction.apply {
            setIconResource(R.drawable.ms_download_24)
            contentDescription = getString(R.string.backup_import_action)
            setOnClickListener { showImportConfirmation() }
        }
        adapter = SettingsListAdapter().apply {
            onItemClickListener = ::onItemClicked
            onItemCheckListener = ::onItemChecked
        }
        binding.rvList.layoutManager = LinearLayoutManager(this)
        binding.rvList.adapter = adapter

        if (selectedFileUri != null) inspectSelectedFile() else render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_FILE_URI, selectedFileUri?.toString())
        outState.putString(STATE_FILE_NAME, selectedFileName)
        outState.putLongArray(STATE_SCHEDULE_IDS, selection.scheduleSourceIds.toLongArray())
        outState.putLongArray(STATE_TIME_TABLE_IDS, selection.timeTableSourceIds.toLongArray())
        outState.putBoolean(STATE_GLOBAL_SETTINGS, selection.includeGlobalSettings)
        outState.putBoolean(STATE_WIDGET_SETTINGS, selection.includeWidgetSettings)
        // 目录尚未校验完成时的空选择不是用户决定，旋转后应继续采用文件的默认全选。
        outState.putBoolean(STATE_SELECTION_INITIALIZED, catalog != null)
        super.onSaveInstanceState(outState)
    }

    /** 恢复配置变化状态，或接收系统文件管理器通过 ACTION_VIEW 传入的 URI。 */
    private fun restoreState(state: Bundle?) {
        val stateUri = state?.getString(STATE_FILE_URI)?.let(Uri::parse)
        selectedFileUri = stateUri ?: intent?.data
        selectedFileName = state?.getString(STATE_FILE_NAME).orEmpty()
        if (selectedFileName.isBlank()) selectedFileUri?.let { uri ->
            selectedFileName = displayName(uri)
        }
        restoredSelection = state
            ?.takeIf { saved -> saved.getBoolean(STATE_SELECTION_INITIALIZED) }
            ?.let { saved ->
                // 只有旧页面已经完成目录校验时，这些数组才表示真实的用户选择；加载期间的空数组不恢复。
                BackupSelection(
                    scheduleSourceIds = saved.getLongArray(STATE_SCHEDULE_IDS)?.toSet().orEmpty(),
                    timeTableSourceIds = saved.getLongArray(STATE_TIME_TABLE_IDS)?.toSet()
                        .orEmpty(),
                    includeGlobalSettings = saved.getBoolean(STATE_GLOBAL_SETTINGS),
                    includeWidgetSettings = saved.getBoolean(STATE_WIDGET_SETTINGS),
                )
            }
    }

    /** 打开系统文档选择器。 */
    private fun selectFile() {
        selectBackupLauncher.launch(
            arrayOf(BACKUP_MIME_TYPE, "application/zip", "application/octet-stream"),
        )
    }

    /** 完整校验文件并加载可选目录。 */
    private fun inspectSelectedFile() {
        val uri = selectedFileUri ?: return
        setBusy(true)
        lifecycleScope.launch {
            runCatching { repository.inspect(uri) }
                .onSuccess { loaded ->
                    catalog = loaded
                    selection = restoredSelection
                        ?.takeIf { saved -> isSelectionWithinCatalog(saved, loaded) }
                        ?: loaded.selectAll()
                }
                .onFailure { error ->
                    catalog = null
                    selection = BackupSelection()
                    showError(error)
                }
            setBusy(false)
            render()
        }
    }

    /** 构建文件、覆盖说明、课表、时间表和设置分组。 */
    private fun buildItems(): List<CategoryItem> {
        itemTargets.clear()
        val groups = mutableListOf<CategoryItem>()
        groups += CategoryItem(
            listOf(
                HeaderItem(R.string.setting_blank),
                VerticalItem(
                    name = R.string.backup_select_file,
                    description = selectedFileName.ifBlank { getString(R.string.backup_file_unselected) },
                    leadingIconRes = R.drawable.ms_dataset_24,
                    showChevron = true,
                    usePrimaryDescriptionColor = selectedFileUri != null,
                ),
                VerticalItem(
                    name = R.string.backup_import_behavior_title,
                    description = getString(R.string.backup_import_behavior_desc),
                    leadingIconRes = R.drawable.ms_info_24,
                ),
            ),
        )
        val loaded = catalog ?: return groups
        val timeTableNames = loaded.timeTables.associate { item -> item.sourceId to item.name }
        val scheduleRows: List<SettingsItem> = if (loaded.schedules.isEmpty()) {
            listOf(
                VerticalItem(
                    R.string.backup_no_schedules,
                    "",
                    leadingIconRes = R.drawable.ms_view_week_24
                )
            )
        } else {
            loaded.schedules.map { item ->
                SwitchItem(
                    name = R.string.backup_schedule_item,
                    checked = item.sourceId in selection.scheduleSourceIds,
                    description = getString(
                        R.string.backup_schedule_uses_time_table,
                        timeTableNames[item.timeTableSourceId].orEmpty(),
                    ),
                    leadingIconRes = R.drawable.ms_view_week_24,
                    titleText = item.name,
                ).also { row ->
                    itemTargets[row] = SelectionTarget(SelectionKind.SCHEDULE, item.sourceId)
                }
            }
        }
        val timeTableRows: List<SettingsItem> = if (loaded.timeTables.isEmpty()) {
            listOf(
                VerticalItem(
                    R.string.backup_no_time_tables,
                    "",
                    leadingIconRes = R.drawable.ms_schedule_24
                )
            )
        } else {
            loaded.timeTables.map { item ->
                SwitchItem(
                    name = R.string.backup_time_table_item,
                    checked = item.sourceId in selection.timeTableSourceIds,
                    description = resources.getQuantityString(
                        R.plurals.backup_time_table_nodes,
                        item.nodeCount,
                        item.nodeCount,
                    ),
                    leadingIconRes = R.drawable.ms_schedule_24,
                    titleText = item.name,
                ).also { row ->
                    itemTargets[row] = SelectionTarget(SelectionKind.TIME_TABLE, item.sourceId)
                }
            }
        }
        groups += CategoryItem(listOf(HeaderItem(R.string.backup_schedules_header)) + scheduleRows)
        groups += CategoryItem(listOf(HeaderItem(R.string.backup_time_tables_header)) + timeTableRows)

        val settingRows = mutableListOf<SettingsItem>()
        if (loaded.hasGlobalSettings) {
            settingRows += SwitchItem(
                name = R.string.backup_global_settings,
                checked = selection.includeGlobalSettings,
                description = getString(R.string.backup_global_settings_desc),
                leadingIconRes = R.drawable.ms_settings_24,
            ).also { row -> itemTargets[row] = SelectionTarget(SelectionKind.GLOBAL_SETTINGS) }
        }
        if (loaded.hasWidgetSettings) {
            settingRows += SwitchItem(
                name = R.string.backup_widget_settings,
                checked = selection.includeWidgetSettings,
                description = getString(R.string.backup_widget_settings_desc),
                leadingIconRes = R.drawable.ms_widgets_24,
            ).also { row -> itemTargets[row] = SelectionTarget(SelectionKind.WIDGET_SETTINGS) }
        }
        if (settingRows.isNotEmpty()) {
            groups += CategoryItem(listOf(HeaderItem(R.string.backup_settings_header)) + settingRows)
        }
        return groups
    }

    /** 文件行打开选择器，其余普通行没有点击命令。 */
    private fun onItemClicked(item: SettingsItem, position: Int) {
        if (!busy && item.name == R.string.backup_select_file) selectFile()
    }

    /** 更新导入选择，并展示依赖自动变化提示。 */
    private fun onItemChecked(item: SettingsItem, checked: Boolean) {
        if (busy) return
        val loaded = catalog ?: return
        val target = itemTargets[item] ?: return
        val change = when (target.kind) {
            SelectionKind.SCHEDULE -> BackupSelectionRules.toggleSchedule(
                selection,
                loaded,
                target.sourceId,
                checked,
            )

            SelectionKind.TIME_TABLE -> BackupSelectionRules.toggleTimeTable(
                selection,
                loaded,
                target.sourceId,
                checked,
            )

            SelectionKind.GLOBAL_SETTINGS -> BackupSelectionChange(
                selection.copy(includeGlobalSettings = checked),
            )

            SelectionKind.WIDGET_SETTINGS -> BackupSelectionChange(
                selection.copy(includeWidgetSettings = checked),
            )
        }
        selection = change.selection
        render()
        change.notice?.let(::showSelectionNotice)
    }

    /** 展示增量数据和设置覆盖摘要，用户再次确认后才执行写入。 */
    private fun showImportConfirmation() {
        if (selection.isEmpty || selectedFileUri == null) return
        val settings = when {
            selection.includeGlobalSettings && selection.includeWidgetSettings -> R.string.backup_settings_both
            selection.includeGlobalSettings -> R.string.backup_settings_global
            selection.includeWidgetSettings -> R.string.backup_settings_widget
            else -> R.string.backup_settings_none
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_confirm_import_title)
            .setMessage(
                getString(
                    R.string.backup_confirm_import_message,
                    selection.scheduleSourceIds.size,
                    selection.timeTableSourceIds.size,
                    getString(settings),
                ),
            )
            .setPositiveButton(R.string.import_tap) { _, _ -> restoreSelected() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 执行导入，完成后刷新小部件、提醒、主题和语言。 */
    private fun restoreSelected() {
        val uri = selectedFileUri ?: return
        setBusy(true)
        lifecycleScope.launch {
            val outcome = runCatching { repository.restore(uri, selection) }
            val result = outcome.getOrElse { error ->
                showError(error)
                setBusy(false)
                render()
                return@launch
            }

            // 刷新任务使用应用 Context，且在主题或语言触发 Activity 重建前完成调度请求。
            withContext(Dispatchers.IO) {
                runCatching {
                    WidgetRefreshScheduler.refreshAll(applicationContext)
                    ReminderScheduler.rearrange(applicationContext)
                }
            }
            setResult(RESULT_OK)
            ImportSuccessFeedback.showThenReturnToSchedule(
                activity = this@BackupImportActivity,
                root = binding.root,
                anchor = binding.btnAction,
                successMessage = getString(
                    R.string.backup_import_success,
                    result.scheduleCount,
                    result.timeTableCount,
                ),
                onNavigationStarted = {
                    applyRestoredGlobalSettings(result.globalSettingsOverwritten)
                },
                onNavigationSkipped = {
                    setBusy(false)
                    render()
                    applyRestoredGlobalSettings(result.globalSettingsOverwritten)
                },
            )
        }
    }

    /**
     * 在成功反馈流程结束后应用备份恢复的主题与语言。
     *
     * 这两个 AppCompat API 都可能重建 Activity，因此必须等 Snackbar 完成淡出并且主课表启动请求
     * 已经发出后调用；若用户已切到后台而跳过导航，也要应用设置并恢复当前页，保持备份语义完整。
     *
     * @param overwritten 本次备份是否包含并覆盖了全局设置
     */
    private fun applyRestoredGlobalSettings(overwritten: Boolean) {
        if (!overwritten) return
        val prefs = Prefs.get(applicationContext)
        AppThemeController.apply(prefs.themeMode)
        AppLocaleResolver.apply(prefs.appLocale)
    }

    /** 更新列表与操作按钮。 */
    private fun render() {
        if (::adapter.isInitialized) adapter.submit(buildItems())
        if (::binding.isInitialized) {
            binding.progressIndicator.visibility = if (busy) View.VISIBLE else View.GONE
            binding.btnAction.isEnabled = !busy && catalog != null && !selection.isEmpty
        }
    }

    /** 切换文件检查或导入期间的忙碌视觉状态。 */
    private fun setBusy(value: Boolean) {
        busy = value
        if (::binding.isInitialized) {
            binding.progressIndicator.visibility = if (value) View.VISIBLE else View.GONE
            // 忙碌时隐藏列表，避免子 MaterialSwitch 在文件复检期间继续改变本地视觉状态。
            binding.rvList.visibility = if (value) View.INVISIBLE else View.VISIBLE
            binding.btnAction.isEnabled = !value && catalog != null && !selection.isEmpty
        }
    }

    /** 显示固定的依赖联动提示。 */
    private fun showSelectionNotice(notice: BackupSelectionNotice) {
        val message = when (notice) {
            BackupSelectionNotice.TIME_TABLE_AUTO_SELECTED -> R.string.backup_auto_selected_time_table
            BackupSelectionNotice.SCHEDULE_AUTO_DESELECTED -> R.string.backup_auto_deselected_schedule
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT)
            // 与其他文件导入页一致，把联动提示放在右下角导入按钮上方。
            .setAnchorView(binding.btnAction)
            .show()
    }

    /** 显示本地化文件或恢复错误。 */
    private fun showError(error: Throwable) {
        Snackbar.make(binding.root, backupErrorText(error), Snackbar.LENGTH_LONG)
            .setAnchorView(binding.btnAction)
            .show()
    }

    /** 查询 SAF 文档名；查询失败时使用 URI 末段。 */
    private fun displayName(uri: Uri): String {
        val queried = runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        return queried?.takeIf { value -> value.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
    }

    /** 判断配置变化保存的选择能否原样应用到重新检查后的目录。 */
    private fun isSelectionWithinCatalog(saved: BackupSelection, loaded: BackupCatalog): Boolean {
        val scheduleIds = loaded.schedules.map { item -> item.sourceId }
        val timeTableIds = loaded.timeTables.map { item -> item.sourceId }
        val dependencies = loaded.schedules
            .filter { item -> item.sourceId in saved.scheduleSourceIds }
            .map { item -> item.timeTableSourceId }
        return scheduleIds.containsAll(saved.scheduleSourceIds) &&
                timeTableIds.containsAll(saved.timeTableSourceIds) &&
                saved.timeTableSourceIds.containsAll(dependencies) &&
                (!saved.includeGlobalSettings || loaded.hasGlobalSettings) &&
                (!saved.includeWidgetSettings || loaded.hasWidgetSettings)
    }

    private data class SelectionTarget(
        val kind: SelectionKind,
        val sourceId: Long = 0L,
    )

    private enum class SelectionKind {
        SCHEDULE,
        TIME_TABLE,
        GLOBAL_SETTINGS,
        WIDGET_SETTINGS,
    }

    private companion object {
        const val STATE_FILE_URI = "backup_file_uri"
        const val STATE_FILE_NAME = "backup_file_name"
        const val STATE_SCHEDULE_IDS = "backup_import_schedule_ids"
        const val STATE_TIME_TABLE_IDS = "backup_import_time_table_ids"
        const val STATE_GLOBAL_SETTINGS = "backup_import_global_settings"
        const val STATE_WIDGET_SETTINGS = "backup_import_widget_settings"
        const val STATE_SELECTION_INITIALIZED = "backup_import_selection_initialized"
    }
}
