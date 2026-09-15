package com.openwakeup.schedule.feature.importexport

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.csv.CsvParser
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.config.AppDefaults
import com.openwakeup.schedule.core.database.entity.TableEntity
import com.openwakeup.schedule.data.schedule.ScheduleRepository
import com.openwakeup.schedule.databinding.ActivityCsvImportBinding
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
 * 从 CSV 文件导入的准备页。
 *
 * 页面沿用全局设置的分组列表，提供文件与编码选择；下方把内置 CSV 模板渲染成
 * 可横向滚动的彩色表格，颜色全部来自 Material 3 语义色并自动适配浅色/暗色主题。
 */
class CsvImportActivity : AppCompatActivity() {

    private val repo by lazy { ScheduleRepository(this) }
    private lateinit var binding: ActivityCsvImportBinding
    private lateinit var adapter: SettingsListAdapter

    private var selectedFileUri: Uri? = null
    private var selectedFileName = ""
    private var selectedEncoding = CsvEncoding.UTF8

    /** 使用系统文档选择器选择 CSV，并持久保留对该文档的读取权限。 */
    private val selectCsvLauncher =
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
            renderSettings()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityCsvImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
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
        binding.btnImport.setOnClickListener { showImportModeDialog() }
        renderSettings()
        renderTemplate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_FILE_URI, selectedFileUri?.toString())
        outState.putString(STATE_FILE_NAME, selectedFileName)
        outState.putString(STATE_ENCODING, selectedEncoding.name)
        super.onSaveInstanceState(outState)
    }

    /** 从配置变化保存的数据恢复文件与编码选择。 */
    private fun restoreState(state: Bundle?) {
        state ?: return
        selectedFileUri = state.getString(STATE_FILE_URI)?.let(Uri::parse)
        selectedFileName = state.getString(STATE_FILE_NAME).orEmpty()
        selectedEncoding = state.getString(STATE_ENCODING)
            ?.let { name -> runCatching { CsvEncoding.valueOf(name) }.getOrNull() }
            ?: CsvEncoding.UTF8
    }

    /** 构建与全局设置页一致的 CSV 文件、字符编码分组。 */
    private fun buildItems(): List<CategoryItem> = listOf(
        CategoryItem(
            listOf(
                HeaderItem(R.string.setting_blank),
                VerticalItem(
                    R.string.csv_import_file,
                    selectedFileName.ifBlank { getString(R.string.csv_import_file_unselected) },
                    leadingIconRes = R.drawable.ms_table_rows_24,
                    showChevron = true,
                    usePrimaryDescriptionColor = selectedFileUri != null,
                ),
                HorizontalItem(
                    R.string.csv_import_encoding,
                    getString(selectedEncoding.labelRes),
                    leadingIconRes = R.drawable.ms_language_24,
                    showChevron = true,
                ),
            ),
        ),
    )

    /** 更新设置卡片与导入按钮可用状态。 */
    private fun renderSettings() {
        if (::adapter.isInitialized) adapter.submit(buildItems())
        if (::binding.isInitialized) binding.btnImport.isEnabled = selectedFileUri != null
    }

    /**
     * 把内置 CSV 模板渲染为带行号和语法色的代码窗口。
     *
     * 每行左侧显示行号，右侧保留真实 CSV 逗号，并按列为课程名称、星期、
     * 节数、教师、地点与周数分配语法色。所有语法色都有 values/values-night 变体。
     */
    private fun renderTemplate() {
        val rows = resources.openRawResource(R.raw.schedule_import_template)
            .bufferedReader(Charsets.UTF_8)
            .useLines { lines -> lines.filter { it.isNotBlank() }.map(::splitTemplateRow).toList() }
        val tokenColors = intArrayOf(
            ContextCompat.getColor(this, R.color.csv_template_token_name),
            ContextCompat.getColor(this, R.color.csv_template_token_day),
            ContextCompat.getColor(this, R.color.csv_template_token_start),
            ContextCompat.getColor(this, R.color.csv_template_token_end),
            ContextCompat.getColor(this, R.color.csv_template_token_teacher),
            ContextCompat.getColor(this, R.color.csv_template_token_room),
            ContextCompat.getColor(this, R.color.csv_template_token_weeks),
        )
        val lineNumberColor = ContextCompat.getColor(this, R.color.csv_template_line_number)
        val separatorColor = ContextCompat.getColor(this, R.color.csv_template_separator)
        val templateText = SpannableStringBuilder()
        rows.forEachIndexed { rowIndex, cells ->
            val lineNumberStart = templateText.length
            templateText.append((rowIndex + 1).toString()).append("  ")
            templateText.setSpan(
                ForegroundColorSpan(lineNumberColor),
                lineNumberStart,
                templateText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            templateText.append(syntaxHighlightedLine(cells, tokenColors, separatorColor))
            if (rowIndex != rows.lastIndex) templateText.append('\n')
        }
        binding.templateCode.text = templateText
    }

    /**
     * 生成一行保留逗号的彩色 CSV 文本。
     *
     * @param cells 当前 CSV 行的各列
     * @param tokenColors 依次对应七列的浅色/暗色语法色
     * @param separatorColor 逗号分隔符颜色
     */
    private fun syntaxHighlightedLine(
        cells: List<String>,
        tokenColors: IntArray,
        separatorColor: Int,
    ): CharSequence = SpannableStringBuilder().apply {
        cells.forEachIndexed { index, cell ->
            val tokenStart = length
            append(cell)
            setSpan(
                ForegroundColorSpan(tokenColors.getOrElse(index) { tokenColors.last() }),
                tokenStart,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            if (index != cells.lastIndex) {
                val separatorStart = length
                append(',')
                setSpan(
                    ForegroundColorSpan(separatorColor),
                    separatorStart,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }

    /** 模板本身不含引号内逗号，按标准逗号切分后保留每个字段原文。 */
    private fun splitTemplateRow(line: String): List<String> = line.split(',')

    /** 处理文件与编码设置项。 */
    private fun onItemClicked(item: SettingsItem, ignoredPosition: Int) {
        when (item.name) {
            R.string.csv_import_file -> selectCsvLauncher.launch(
                arrayOf("text/csv", "text/plain", "application/vnd.ms-excel", "*/*"),
            )

            R.string.csv_import_encoding -> showEncodingDialog()
        }
    }

    /** 选择 UTF-8 或兼容 GB2312 的 GBK 解码方式。 */
    private fun showEncodingDialog() {
        val encodings = CsvEncoding.entries
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.csv_import_encoding)
            .setSingleChoiceItems(
                encodings.map { getString(it.labelRes) }.toTypedArray(),
                encodings.indexOf(selectedEncoding),
            ) { dialog, which ->
                selectedEncoding = encodings[which]
                renderSettings()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 在开始读取 CSV 前选择覆盖当前课表、新建课表或取消。 */
    private fun showImportModeDialog() {
        if (selectedFileUri == null) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.csv_import_confirm_title)
            .setItems(
                arrayOf(
                    getString(R.string.web_import_overwrite_current),
                    getString(R.string.web_import_create_new),
                    getString(R.string.cancel),
                ),
            ) { dialog, which ->
                when (which) {
                    0 -> importSelectedFile(CsvImportMode.OVERWRITE_CURRENT)
                    1 -> importSelectedFile(CsvImportMode.CREATE_NEW)
                    else -> dialog.dismiss()
                }
            }
            .show()
    }

    /** 读取、解码并解析 CSV；成功得到课程后才修改目标课表。 */
    private fun importSelectedFile(mode: CsvImportMode) {
        val uri = selectedFileUri ?: return
        binding.btnImport.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error(getString(R.string.err_read_failed))
                }
                val csv = bytes.toString(Charset.forName(selectedEncoding.charsetName))
                val previews = withContext(Dispatchers.Default) {
                    CsvParser().parse(ParserInput(csv, "csv"))
                }
                val currentTable = repo.currentTableId()
                    .takeIf { tableId -> tableId > 0 }
                    ?.let { tableId -> repo.tableOnce(tableId) }
                val targetTableId = when {
                    mode == CsvImportMode.OVERWRITE_CURRENT && currentTable != null -> {
                        repo.clearCourses(currentTable.id)
                        currentTable.id
                    }
                    // 数据库没有课表时，“覆盖当前课表”在语义上等同于创建第一张课表。
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
                    this@CsvImportActivity,
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
            renderSettings()
        }
    }

    /**
     * 复制当前学期日期与周数，新建以 CSV 文件名命名的课表。
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
                getString(R.string.csv_import_title),
            ),
            startDate = currentTable?.startDate ?: fallbackStart,
            // 新建导入以默认配置为基础；导入策略会在写入前按合法最大周数继续扩展。
            maxWeek = AppDefaults.Table.MAX_WEEK,
        )
    }

    /** 查询系统文档提供器中的文件名，失败时回退到 URI 末段。 */
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
            ?: getString(R.string.csv_import_file)
    }

    /** CSV 文件支持的字符编码。GBK 解码器兼容 GB2312 文件。 */
    private enum class CsvEncoding(
        val charsetName: String,
        val labelRes: Int,
    ) {
        UTF8("UTF-8", R.string.csv_import_encoding_utf8),
        GBK2312("GBK", R.string.csv_import_encoding_gbk2312),
    }

    /** CSV 课程的写入目标。 */
    private enum class CsvImportMode {
        OVERWRITE_CURRENT,
        CREATE_NEW,
    }

    private companion object {
        const val STATE_FILE_URI = "file_uri"
        const val STATE_FILE_NAME = "file_name"
        const val STATE_ENCODING = "encoding"
    }
}
