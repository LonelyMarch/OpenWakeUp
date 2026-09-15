package com.openwakeup.schedule.feature.importexport

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.openwakeup.schedule.R
import com.openwakeup.schedule.data.backup.BACKUP_FILE_EXTENSION
import com.openwakeup.schedule.data.backup.BACKUP_MIME_TYPE
import com.openwakeup.schedule.data.backup.BackupCatalog
import com.openwakeup.schedule.data.backup.BackupException
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
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.IdentityHashMap

/**
 * 选择并导出 `.openwakebak` 的独立页面。
 *
 * 页面读取本机全部课表和时间表，默认全选；课表与时间表的依赖联动统一委托给
 * [BackupSelectionRules]，右下角按钮只负责请求 SAF 创建最终文件。
 */
class BackupExportActivity : AppCompatActivity() {

    private val repository by lazy { BackupRepository(this) }
    private lateinit var binding: ActivityBackupSelectionBinding
    private lateinit var adapter: SettingsListAdapter
    private var catalog: BackupCatalog? = null
    private var selection = BackupSelection()
    private var restoredSelection: BackupSelection? = null
    private var busy = false
    private val itemTargets = IdentityHashMap<SettingsItem, SelectionTarget>()

    /** SAF 创建文档后，把已选择内容写入用户指定位置。 */
    private val createBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)) { uri ->
            uri?.let(::exportTo)
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

        restoredSelection = savedInstanceState
            ?.takeIf { state -> state.getBoolean(STATE_SELECTION_INITIALIZED) }
            ?.toBackupSelection()
        binding.tvTitle.setText(R.string.backup_export_title)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAction.apply {
            setIconResource(R.drawable.ms_save_24)
            contentDescription = getString(R.string.backup_export_action)
            setOnClickListener {
                createBackupLauncher.launch(defaultBackupFileName())
            }
        }
        adapter = SettingsListAdapter().apply {
            onItemCheckListener = ::onItemChecked
        }
        binding.rvList.layoutManager = LinearLayoutManager(this)
        binding.rvList.adapter = adapter
        loadCatalog()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBackupSelection(selection)
        // 目录尚未加载完成时的空选择不是用户决定，旋转后仍应执行默认全选。
        outState.putBoolean(STATE_SELECTION_INITIALIZED, catalog != null)
        super.onSaveInstanceState(outState)
    }

    /** 读取当前数据库目录，并应用默认全选或配置变化前保存的选择。 */
    private fun loadCatalog() {
        setBusy(true)
        lifecycleScope.launch {
            runCatching { repository.exportCatalog() }
                .onSuccess { loaded ->
                    catalog = loaded
                    selection = restoredSelection
                        ?.takeIf { saved -> isSelectionWithinCatalog(saved, loaded) }
                        ?: initialSelection(loaded)
                }
                .onFailure { error -> showError(error) }
            setBusy(false)
            render()
        }
    }

    /**
     * 主页面入口默认全选；课表管理行入口只预选对应课表及关联时间表。
     */
    private fun initialSelection(loaded: BackupCatalog): BackupSelection {
        val preselectedId = intent.getLongExtra(EXTRA_PRESELECTED_SCHEDULE_ID, 0L)
        if (preselectedId <= 0L) return loaded.selectAll()
        return BackupSelectionRules.toggleSchedule(
            current = BackupSelection(),
            catalog = loaded,
            scheduleId = preselectedId,
            checked = true,
        ).selection
    }

    /**
     * 生成不含文件系统保留字符的默认备份文件名。
     *
     * 日期与时分秒之间统一使用连字符，避免 Android 文档提供器或桌面系统拒绝包含冒号的名称。
     *
     * @param now 用户点击导出按钮时的本地日期时间；参数可注入以便稳定验证格式
     * @return `yyyy-MM-dd-HH-mm-ss.openwakebak` 格式的文件名
     */
    private fun defaultBackupFileName(now: LocalDateTime = LocalDateTime.now()): String =
        now.format(BACKUP_FILE_NAME_FORMATTER) + BACKUP_FILE_EXTENSION

    /** 根据目录构建课表、时间表和设置三个 Expressive 分组。 */
    private fun buildItems(): List<CategoryItem> {
        itemTargets.clear()
        val loaded = catalog ?: return emptyList()
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
        val globalRow = SwitchItem(
            name = R.string.backup_global_settings,
            checked = selection.includeGlobalSettings,
            description = getString(R.string.backup_global_settings_desc),
            leadingIconRes = R.drawable.ms_settings_24,
        ).also { row -> itemTargets[row] = SelectionTarget(SelectionKind.GLOBAL_SETTINGS) }
        val widgetRow = SwitchItem(
            name = R.string.backup_widget_settings,
            checked = selection.includeWidgetSettings,
            description = getString(R.string.backup_widget_settings_desc),
            leadingIconRes = R.drawable.ms_widgets_24,
        ).also { row -> itemTargets[row] = SelectionTarget(SelectionKind.WIDGET_SETTINGS) }
        return listOf(
            CategoryItem(listOf(HeaderItem(R.string.backup_schedules_header)) + scheduleRows),
            CategoryItem(listOf(HeaderItem(R.string.backup_time_tables_header)) + timeTableRows),
            CategoryItem(listOf(HeaderItem(R.string.backup_settings_header), globalRow, widgetRow)),
        )
    }

    /** 处理用户开关，并在自动补选或取消依赖时显示指定提示。 */
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

            SelectionKind.GLOBAL_SETTINGS ->
                BackupSelectionChange(
                    selection.copy(includeGlobalSettings = checked),
                )

            SelectionKind.WIDGET_SETTINGS ->
                BackupSelectionChange(
                    selection.copy(includeWidgetSettings = checked),
                )
        }
        selection = change.selection
        render()
        change.notice?.let(::showSelectionNotice)
    }

    /** 创建并写出用户选择的备份。 */
    private fun exportTo(uri: Uri) {
        setBusy(true)
        lifecycleScope.launch {
            runCatching { repository.export(uri, selection) }
                .onSuccess {
                    Toast.makeText(
                        this@BackupExportActivity,
                        R.string.backup_export_success,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .onFailure(::showError)
            setBusy(false)
            render()
        }
    }

    /** 更新列表、进度指示和操作按钮。 */
    private fun render() {
        if (::adapter.isInitialized) adapter.submit(buildItems())
        if (::binding.isInitialized) {
            binding.progressIndicator.visibility = if (busy) View.VISIBLE else View.GONE
            binding.btnAction.isEnabled = !busy && catalog != null && !selection.isEmpty
        }
    }

    /** 切换忙碌状态并立即刷新主要控件。 */
    private fun setBusy(value: Boolean) {
        busy = value
        if (::binding.isInitialized) {
            binding.progressIndicator.visibility = if (value) View.VISIBLE else View.GONE
            // 忙碌时隐藏列表，避免子 MaterialSwitch 仍能独立接收触摸并制造假选中状态。
            binding.rvList.visibility = if (value) View.INVISIBLE else View.VISIBLE
            binding.btnAction.isEnabled = !value && catalog != null && !selection.isEmpty
        }
    }

    /** 显示课表与时间表联动的固定底部提示。 */
    private fun showSelectionNotice(notice: BackupSelectionNotice) {
        val message = when (notice) {
            BackupSelectionNotice.TIME_TABLE_AUTO_SELECTED -> R.string.backup_auto_selected_time_table
            BackupSelectionNotice.SCHEDULE_AUTO_DESELECTED -> R.string.backup_auto_deselected_schedule
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT)
            // 提示位于右下角操作按钮上方，避免被 64dp 的 Expressive 按钮遮挡。
            .setAnchorView(binding.btnAction)
            .show()
    }

    /** 显示本地化备份错误。 */
    private fun showError(error: Throwable) {
        Snackbar.make(binding.root, backupErrorText(error), Snackbar.LENGTH_LONG)
            .setAnchorView(binding.btnAction)
            .show()
    }

    /** 检查配置变化保存的选择是否仍属于刚读取的目录。 */
    private fun isSelectionWithinCatalog(saved: BackupSelection, loaded: BackupCatalog): Boolean {
        val schedules = loaded.schedules.map { item -> item.sourceId }
        val timeTables = loaded.timeTables.map { item -> item.sourceId }
        val dependencies = loaded.schedules
            .filter { item -> item.sourceId in saved.scheduleSourceIds }
            .map { item -> item.timeTableSourceId }
        return schedules.containsAll(saved.scheduleSourceIds) &&
                timeTables.containsAll(saved.timeTableSourceIds) &&
                saved.timeTableSourceIds.containsAll(dependencies)
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

    companion object {
        /** 课表管理页可指定只预选一张课表；主页面入口不传该值并默认全选。 */
        const val EXTRA_PRESELECTED_SCHEDULE_ID = "preselected_schedule_id"

        /** 导出文件名使用的稳定本地日期时间格式，不受应用日期显示设置影响。 */
        private val BACKUP_FILE_NAME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
    }
}

/**
 * 把仓库稳定错误原因转换为当前语言的用户提示。
 *
 * 此函数由两个备份 Activity 共用，留在 `feature/importexport` 避免数据层依赖 Android 资源。
 */
internal fun Context.backupErrorText(error: Throwable): String {
    val reason = (error as? BackupException)?.reason
    val message = when (reason) {
        BackupException.Reason.OPEN_FAILED -> R.string.backup_error_open
        BackupException.Reason.INVALID_FORMAT -> R.string.backup_error_format
        BackupException.Reason.UNSUPPORTED_VERSION -> R.string.backup_error_version
        BackupException.Reason.DATABASE_VERSION_MISMATCH -> R.string.backup_error_database_version
        BackupException.Reason.INTEGRITY_CHECK_FAILED -> R.string.backup_error_integrity
        BackupException.Reason.UNSAFE_ARCHIVE -> R.string.backup_error_unsafe
        BackupException.Reason.INVALID_SELECTION -> R.string.backup_error_selection
        BackupException.Reason.INVALID_REFERENCE -> R.string.backup_error_reference
        BackupException.Reason.INVALID_IMAGE -> R.string.backup_error_image
        BackupException.Reason.WRITE_FAILED -> R.string.backup_error_write
        null -> R.string.backup_error_write
    }
    return getString(message)
}

/** 把备份选择写入配置变化状态。 */
private fun Bundle.putBackupSelection(selection: BackupSelection) {
    putLongArray(STATE_SCHEDULE_IDS, selection.scheduleSourceIds.toLongArray())
    putLongArray(STATE_TIME_TABLE_IDS, selection.timeTableSourceIds.toLongArray())
    putBoolean(STATE_GLOBAL_SETTINGS, selection.includeGlobalSettings)
    putBoolean(STATE_WIDGET_SETTINGS, selection.includeWidgetSettings)
}

/** 从配置变化状态恢复备份选择。 */
private fun Bundle.toBackupSelection(): BackupSelection = BackupSelection(
    scheduleSourceIds = getLongArray(STATE_SCHEDULE_IDS)?.toSet().orEmpty(),
    timeTableSourceIds = getLongArray(STATE_TIME_TABLE_IDS)?.toSet().orEmpty(),
    includeGlobalSettings = getBoolean(STATE_GLOBAL_SETTINGS),
    includeWidgetSettings = getBoolean(STATE_WIDGET_SETTINGS),
)

private const val STATE_SCHEDULE_IDS = "backup_schedule_ids"
private const val STATE_TIME_TABLE_IDS = "backup_time_table_ids"
private const val STATE_GLOBAL_SETTINGS = "backup_global_settings"
private const val STATE_WIDGET_SETTINGS = "backup_widget_settings"
private const val STATE_SELECTION_INITIALIZED = "backup_selection_initialized"
