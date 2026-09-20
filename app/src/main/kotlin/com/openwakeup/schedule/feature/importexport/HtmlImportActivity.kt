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
import com.openwakeup.parser.ParserFactory
import com.openwakeup.parser.ParserInput
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.config.AppDefaults
import com.openwakeup.schedule.core.database.entity.TableEntity
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 从 HTML 文件导入的分步准备页。
 *
 * 页面复用全局设置的 [SettingsListAdapter] 分组卡片，依次提供教程、学校/教务类型、
 * HTML 文件和字符编码选择；准备完成后由右下角 Material 3 Expressive 图标按钮执行导入。
 */
class HtmlImportActivity : AppCompatActivity() {

    private val repo by lazy { ScheduleRepository(this) }
    private lateinit var binding: ActivityHtmlImportBinding
    private lateinit var adapter: SettingsListAdapter

    private var selectedSchoolName = ""
    private var selectedSchoolLabel = ""
    private var selectedParserType = ""
    private var selectedFileUri: Uri? = null
    private var selectedFileName = ""
    private var selectedEncoding = HtmlEncoding.UTF8

    /** 从学校列表的“仅选择”模式接收学校名称与解析器类型。 */
    private val selectSchoolLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            selectedSchoolName =
                data.getStringExtra(SchoolListActivity.RESULT_SCHOOL_NAME).orEmpty()
            selectedParserType =
                data.getStringExtra(SchoolListActivity.RESULT_PARSER_TYPE).orEmpty()
            selectedSchoolLabel = data.getStringExtra(SchoolListActivity.RESULT_SELECTION_LABEL)
                .orEmpty()
                .ifBlank { selectedSchoolName }
            render()
        }

    /** 使用系统文件选择器取得 HTML 文件，并持久保留该 URI 的读取权限。 */
    private val selectHtmlLauncher =
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
        binding = ActivityHtmlImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        restoreState(savedInstanceState)
        binding.btnBack.setOnClickListener { finish() }
        adapter = SettingsListAdapter().apply {
            onItemClickListener = ::onItemClicked
        }
        binding.rvList.layoutManager = LinearLayoutManager(this)
        binding.rvList.adapter = adapter
        binding.btnImport.setOnClickListener { showImportModeDialog() }

        // 外部 VIEW intent 传入 HTML 时只预选文件，仍由用户确认学校类型和导入目标。
        if (savedInstanceState == null) {
            intent?.data?.let { uri ->
                selectedFileUri = uri
                selectedFileName = displayName(uri)
            }
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SCHOOL_NAME, selectedSchoolName)
        outState.putString(STATE_SCHOOL_LABEL, selectedSchoolLabel)
        outState.putString(STATE_PARSER_TYPE, selectedParserType)
        outState.putString(STATE_FILE_URI, selectedFileUri?.toString())
        outState.putString(STATE_FILE_NAME, selectedFileName)
        outState.putString(STATE_ENCODING, selectedEncoding.name)
        super.onSaveInstanceState(outState)
    }

    /** 从配置变化保存的数据恢复当前导入准备状态。 */
    private fun restoreState(state: Bundle?) {
        state ?: return
        selectedSchoolName = state.getString(STATE_SCHOOL_NAME).orEmpty()
        selectedSchoolLabel = state.getString(STATE_SCHOOL_LABEL).orEmpty()
        selectedParserType = state.getString(STATE_PARSER_TYPE).orEmpty()
        selectedFileUri = state.getString(STATE_FILE_URI)?.let(Uri::parse)
        selectedFileName = state.getString(STATE_FILE_NAME).orEmpty()
        selectedEncoding = state.getString(STATE_ENCODING)
            ?.let { name -> runCatching { HtmlEncoding.valueOf(name) }.getOrNull() }
            ?: HtmlEncoding.UTF8
    }

    /** 使用与全局设置相同的分组卡片构建 HTML 导入步骤。 */
    private fun buildItems(): List<CategoryItem> = listOf(
        CategoryItem(
            listOf(
                HeaderItem(R.string.setting_blank),
                VerticalItem(
                    R.string.html_import_requirements_title,
                    getString(R.string.html_import_requirements_desc),
                    leadingIconRes = R.drawable.ms_info_24,
                ),
            ),
        ),
        CategoryItem(
            listOf(
                HeaderItem(R.string.html_import_prepare),
                VerticalItem(
                    R.string.html_import_tutorial,
                    getString(R.string.html_import_tutorial_desc),
                    leadingIconRes = R.drawable.ms_help_24,
                    showChevron = true,
                ),
                VerticalItem(
                    R.string.html_import_select_school,
                    selectedSchoolLabel.ifBlank { getString(R.string.html_import_school_unselected) },
                    leadingIconRes = R.drawable.ms_school_24,
                    showChevron = true,
                    usePrimaryDescriptionColor = selectedSchoolLabel.isNotBlank(),
                ),
                VerticalItem(
                    R.string.pick_html_file,
                    selectedFileName.ifBlank { getString(R.string.html_import_file_unselected) },
                    leadingIconRes = R.drawable.ms_code_24,
                    showChevron = true,
                    usePrimaryDescriptionColor = selectedFileUri != null,
                ),
                HorizontalItem(
                    R.string.html_import_encoding,
                    getString(selectedEncoding.labelRes),
                    leadingIconRes = R.drawable.ms_language_24,
                    showChevron = true,
                ),
            ),
        ),
    )

    /** 重新提交列表，并仅在学校类型和 HTML 文件均已选定时启用导入按钮。 */
    private fun render() {
        if (::adapter.isInitialized) adapter.submit(buildItems())
        if (::binding.isInitialized) {
            binding.btnImport.isEnabled = selectedParserType.isNotBlank() && selectedFileUri != null
        }
    }

    /**
     * 处理教程、学校、文件和编码四类设置项。
     *
     * @param item 被点击的设置项
     * @param ignoredPosition 当前页面不依赖适配器位置
     */
    private fun onItemClicked(item: SettingsItem, ignoredPosition: Int) {
        when (item.name) {
            R.string.html_import_tutorial -> openTutorial()
            R.string.html_import_select_school -> selectSchoolLauncher.launch(
                Intent(this, SchoolListActivity::class.java)
                    .putExtra(SchoolListActivity.EXTRA_SELECTION_ONLY, true),
            )

            R.string.pick_html_file -> selectHtmlLauncher.launch(
                arrayOf("text/html", "application/xhtml+xml", "text/plain", "*/*"),
            )

            R.string.html_import_encoding -> showEncodingDialog()
        }
    }

    /** 在系统浏览器中打开官方 HTML 导入教程。 */
    private fun openTutorial() {
        ExternalWebLinkLauncher.open(this, HTML_IMPORT_TUTORIAL_URL) {
            Snackbar.make(
                binding.root,
                R.string.html_import_tutorial_unavailable,
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    /** 以 Material 3 单选弹窗选择 UTF-8 或 GBK 文件编码。 */
    private fun showEncodingDialog() {
        val encodings = HtmlEncoding.entries
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.html_import_encoding)
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

    /** 在解析开始前选择覆盖当前课表、新建课表或取消。 */
    private fun showImportModeDialog() {
        if (selectedParserType.isBlank() || selectedFileUri == null) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.html_import_confirm_title)
            .setItems(
                arrayOf(
                    getString(R.string.web_import_overwrite_current),
                    getString(R.string.web_import_create_new),
                    getString(R.string.cancel),
                ),
            ) { dialog, which ->
                when (which) {
                    0 -> importSelectedFile(HtmlImportMode.OVERWRITE_CURRENT)
                    1 -> importSelectedFile(HtmlImportMode.CREATE_NEW)
                    else -> dialog.dismiss()
                }
            }
            .show()
    }

    /**
     * 读取并解析用户选择的 HTML；解析成功且非空后才清空或新建目标课表。
     *
     * @param mode HTML 课程的写入目标
     */
    private fun importSelectedFile(mode: HtmlImportMode) {
        val uri = selectedFileUri ?: return
        binding.btnImport.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error(getString(R.string.err_read_failed))
                }
                val html = bytes.toString(Charset.forName(selectedEncoding.charsetName))
                val previews = withContext(Dispatchers.Default) {
                    ParserFactory.parse(ParserInput(html, selectedParserType))
                }
                check(previews.isNotEmpty()) { getString(R.string.web_import_no_courses) }
                val currentTable = repo.currentTableId()
                    .takeIf { tableId -> tableId > 0 }
                    ?.let { tableId -> repo.tableOnce(tableId) }
                val targetTableId = when {
                    mode == HtmlImportMode.OVERWRITE_CURRENT && currentTable != null -> {
                        repo.clearCourses(currentTable.id)
                        currentTable.id
                    }
                    // 没有可覆盖对象时，即使用户选择“覆盖”，也必须创建第一张课表。
                    else -> createImportTable(currentTable)
                }
                val rangeReport = CourseImportPolicy.prepareTarget(repo, targetTableId, previews)
                CourseImportPolicy.writeCourses(repo, targetTableId, previews)
                setResult(RESULT_OK)
                CourseImportResult(previews.size, rangeReport)
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
                    this@HtmlImportActivity,
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

    /**
     * 复制当前学期起始日期与周数，创建以 HTML 文件名命名的新课表。
     *
     * @param currentTable 当前课表；数据库为空时为 `null`
     * @return 新课表 id
     */
    private suspend fun createImportTable(currentTable: TableEntity?): Long {
        val fallbackStart = LocalDate.now()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .toString()
        return repo.createTable(
            name = ImportTableNames.fromFileName(
                selectedFileName,
                getString(R.string.html_import),
            ),
            startDate = currentTable?.startDate ?: fallbackStart,
            // 新建导入以默认配置为基础；导入策略会在写入前按合法最大周数继续扩展。
            maxWeek = AppDefaults.Table.MAX_WEEK,
        )
    }

    /** 查询系统文档提供器中的展示文件名，查询失败时回退到 URI 末段。 */
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
            ?: getString(R.string.pick_html_file)
    }

    /** HTML 文件支持的两种教程约定编码。 */
    private enum class HtmlEncoding(
        val charsetName: String,
        val labelRes: Int,
    ) {
        UTF8("UTF-8", R.string.html_import_encoding_utf8),
        GBK("GBK", R.string.html_import_encoding_gbk),
    }

    /** HTML 解析结果的写入目标。 */
    private enum class HtmlImportMode {
        OVERWRITE_CURRENT,
        CREATE_NEW,
    }

    private companion object {
        const val HTML_IMPORT_TUTORIAL_URL = "https://openwakeup.fun/doc/import_from_html.html"
        const val STATE_SCHOOL_NAME = "school_name"
        const val STATE_SCHOOL_LABEL = "school_label"
        const val STATE_PARSER_TYPE = "parser_type"
        const val STATE_FILE_URI = "file_uri"
        const val STATE_FILE_NAME = "file_name"
        const val STATE_ENCODING = "encoding"
    }
}
