package com.openwakeup.schedule.feature.importexport

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ics.IcsParser
import com.openwakeup.parser.ics.IcsTimeSlot
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.config.AppDefaults
import com.openwakeup.schedule.core.util.ExternalWebLinkLauncher
import com.openwakeup.schedule.data.schedule.ScheduleRepository
import com.openwakeup.schedule.databinding.ActivityHtmlImportBinding
import com.openwakeup.schedule.feature.settings.CategoryItem
import com.openwakeup.schedule.feature.settings.HeaderItem
import com.openwakeup.schedule.feature.settings.HorizontalItem
import com.openwakeup.schedule.feature.settings.SettingsItem
import com.openwakeup.schedule.feature.settings.SettingsListAdapter
import com.openwakeup.schedule.feature.settings.VerticalItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.time.LocalTime
import java.time.ZoneId

/**
 * 从 ICS 文件导入的准备页。
 *
 * 本页面直接复用 [ActivityHtmlImportBinding] 对应的 HTML 导入布局，以保证标题栏、分组列表、
 * 页面留白、系统栏处理以及右下角 Material 3 Expressive 导入按钮完全一致。列表内提供教程、
 * ICS 文件与字符编码三个入口，解析及数据库写入均在后台协程执行。
 */
class IcsImportActivity : AppCompatActivity() {

    private val repo by lazy { ScheduleRepository(this) }
    private lateinit var binding: ActivityHtmlImportBinding
    private lateinit var adapter: SettingsListAdapter

    private var selectedFileUri: Uri? = null
    private var selectedFileName = ""
    private var selectedEncoding = IcsEncoding.UTF8

    /** 使用系统文档选择器取得 ICS 文件，并尽量持久保留该 URI 的读取权限。 */
    private val selectIcsLauncher =
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
            render()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 与 HTML 导入页共用同一布局资源，避免维护两份 XML 后逐渐产生视觉差异。
        binding = ActivityHtmlImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvTitle.setText(R.string.ics_import_title)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        restoreState(savedInstanceState)
        binding.btnBack.setOnClickListener { finish() }
        adapter = SettingsListAdapter().apply { onItemClickListener = ::onItemClicked }
        binding.rvList.layoutManager = LinearLayoutManager(this)
        binding.rvList.adapter = adapter
        binding.btnImport.setOnClickListener { showOverwriteConfirmationDialog() }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_FILE_URI, selectedFileUri?.toString())
        outState.putString(STATE_FILE_NAME, selectedFileName)
        outState.putString(STATE_ENCODING, selectedEncoding.name)
        super.onSaveInstanceState(outState)
    }

    /** 从配置变化保存的数据恢复文件与字符编码选择。 */
    private fun restoreState(state: Bundle?) {
        state ?: return
        selectedFileUri = state.getString(STATE_FILE_URI)?.let(Uri::parse)
        selectedFileName = state.getString(STATE_FILE_NAME).orEmpty()
        selectedEncoding = state.getString(STATE_ENCODING)
            ?.let { name -> runCatching { IcsEncoding.valueOf(name) }.getOrNull() }
            ?: IcsEncoding.UTF8
    }

    /** 使用与 HTML 导入页相同的设置卡片层级构建 ICS 导入步骤。 */
    private fun buildItems(): List<CategoryItem> = listOf(
        CategoryItem(
            listOf(
                HeaderItem(R.string.setting_blank),
                VerticalItem(
                    R.string.ics_import_requirements_title,
                    getString(R.string.ics_import_requirements_desc),
                    leadingIconRes = R.drawable.ms_info_24,
                ),
            ),
        ),
        CategoryItem(
            listOf(
                HeaderItem(R.string.ics_import_prepare),
                VerticalItem(
                    R.string.ics_import_tutorial,
                    getString(R.string.ics_import_tutorial_desc),
                    leadingIconRes = R.drawable.ms_help_24,
                    showChevron = true,
                ),
                VerticalItem(
                    R.string.ics_import_file,
                    selectedFileName.ifBlank { getString(R.string.ics_import_file_unselected) },
                    leadingIconRes = R.drawable.ms_calendar_month_24,
                    showChevron = true,
                    usePrimaryDescriptionColor = selectedFileUri != null,
                ),
                HorizontalItem(
                    R.string.ics_import_encoding,
                    getString(selectedEncoding.labelRes),
                    leadingIconRes = R.drawable.ms_language_24,
                    showChevron = true,
                ),
            ),
        ),
    )

    /** 更新设置列表，并仅在用户已经选择文件时启用导入按钮。 */
    private fun render() {
        if (::adapter.isInitialized) adapter.submit(buildItems())
        if (::binding.isInitialized) binding.btnImport.isEnabled = selectedFileUri != null
    }

    /**
     * 处理教程、ICS 文件与字符编码三类设置项。
     *
     * @param item 被点击的设置项
     * @param ignoredPosition 当前页面按资源 id 分发，不依赖适配器位置
     */
    private fun onItemClicked(item: SettingsItem, ignoredPosition: Int) {
        when (item.name) {
            R.string.ics_import_tutorial -> openTutorial()
            R.string.ics_import_file -> selectIcsLauncher.launch(
                arrayOf("text/calendar", "application/ics", "text/plain", "*/*"),
            )

            R.string.ics_import_encoding -> showEncodingDialog()
        }
    }

    /** 在系统浏览器中打开当前项目内浏览器 ICS 导出工具的使用教程。 */
    private fun openTutorial() {
        ExternalWebLinkLauncher.open(this, ICS_IMPORT_TUTORIAL_URL) {
            Snackbar.make(
                binding.root,
                R.string.ics_import_tutorial_unavailable,
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    /** 以与 HTML 导入页一致的 Material 3 单选弹窗选择 UTF-8 或 GBK。 */
    private fun showEncodingDialog() {
        val encodings = IcsEncoding.entries
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ics_import_encoding)
            .setSingleChoiceItems(
                encodings.map { getString(it.labelRes) }.toTypedArray(),
                encodings.indexOf(selectedEncoding),
            ) { dialog, which ->
                selectedEncoding = encodings[which]
                render()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 在读取文件前仅确认覆盖当前课表；ICS 导入不再提供新建课表分支。 */
    private fun showOverwriteConfirmationDialog() {
        if (selectedFileUri == null) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ics_import_confirm_title)
            .setItems(
                arrayOf(
                    getString(R.string.web_import_overwrite_current),
                    getString(R.string.cancel),
                ),
            ) { dialog, which ->
                when (which) {
                    0 -> importSelectedFile()
                    else -> dialog.dismiss()
                }
            }
            .show()
    }

    /**
     * 读取、解码并解析 ICS；当前课表存在且完整解析成功后才清空并写入新课程。
     */
    private fun importSelectedFile() {
        val uri = selectedFileUri ?: return
        binding.btnImport.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error(getString(R.string.err_read_failed))
                }
                val icsText = bytes.toString(Charset.forName(selectedEncoding.charsetName))
                val currentTableId = repo.currentTableId()
                check(currentTableId > 0) { getString(R.string.web_import_no_current_table) }
                val currentTable = repo.tableOnce(currentTableId)
                    ?: error(getString(R.string.web_import_no_current_table))
                val currentSlots = repo.timeDetailsOnce(currentTable.timeTableId).map { detail ->
                    IcsTimeSlot(
                        node = detail.node,
                        startTime = LocalTime.parse(detail.startTime),
                        endTime = LocalTime.parse(detail.endTime),
                    )
                }
                val parseResult = withContext(Dispatchers.Default) {
                    IcsParser(currentSlots, ZoneId.systemDefault()).parse(
                        text = icsText,
                        // 传空表示从 ICS 的显式字段或事件日期推断学期起点，并在成功后覆盖当前配置。
                        semesterStart = null,
                        maxWeek = currentTable.maxWeek,
                    )
                }
                val rangeReport = CourseImportPolicy.prepareTarget(
                    repo = repo,
                    tableId = currentTable.id,
                    previews = parseResult.courses,
                )
                // ICS 仅支持覆盖；解析阶段不修改数据库，避免格式错误时丢失当前课程。
                repo.clearCourses(currentTable.id)
                CourseImportPolicy.writeCourses(repo, currentTable.id, parseResult.courses)
                // prepareTarget 可能扩展合法周数，因此基于仓库中的最新实体修改开学日期，
                // 避免用解析前的旧实体把范围更新覆盖回去。
                val preparedTable = repo.tableOnce(currentTable.id) ?: currentTable
                repo.updateTable(
                    preparedTable.copy(
                        startDate = parseResult.semesterStart.toString(),
                        // 节数与绑定作息均保留用户导入前的配置；超出实际网格的课程由统一
                        // 范围策略标记为非法，直至用户在课程管理页修正。
                        currentWeekOverride = AppDefaults.Table.CURRENT_WEEK_OVERRIDE,
                    ),
                )
                setResult(RESULT_OK)
                CourseImportResult(parseResult.courses.size, rangeReport)
            }.onSuccess { result ->
                Snackbar.make(
                    binding.root,
                    resources.getQuantityString(
                        R.plurals.import_ok_courses,
                        result.importedSessionCount,
                        result.importedSessionCount,
                    ),
                    Snackbar.LENGTH_LONG,
                )
                    .setAnchorView(binding.btnImport)
                    .show()
                CourseImportPolicy.showInvalidCourseDialog(
                    this@IcsImportActivity,
                    result.rangeReport
                )
            }.onFailure { error ->
                val detail = (error as? ParserException)?.message
                    ?: error.message
                    ?: getString(R.string.err_parse_failed)
                Snackbar.make(
                    binding.root,
                    getString(R.string.import_fail, detail),
                    Snackbar.LENGTH_LONG,
                ).setAnchorView(binding.btnImport).show()
            }
            render()
        }
    }

    /** 查询系统文档提供器中的展示文件名，失败时回退到 URI 末段。 */
    private fun displayName(uri: Uri): String {
        val queriedName = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        }.getOrNull()
        return queriedName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
            ?: getString(R.string.ics_import_file)
    }

    /** ICS 文件支持的两种文本编码。 */
    private enum class IcsEncoding(
        val charsetName: String,
        val labelRes: Int,
    ) {
        UTF8("UTF-8", R.string.ics_import_encoding_utf8),
        GBK("GBK", R.string.ics_import_encoding_gbk),
    }

    private companion object {
        /** ICS 导入教程的公开地址；随仓库一起发布，避免依赖本地或上游项目路径。 */
        const val ICS_IMPORT_TUTORIAL_URL =
            "https://github.com/LonelyMarch/OpenWakeUp/blob/dev/tools/ics-formatter/README.md"
        const val STATE_FILE_URI = "file_uri"
        const val STATE_FILE_NAME = "file_name"
        const val STATE_ENCODING = "encoding"
    }
}
