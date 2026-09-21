package com.openwakeup.schedule.feature.settings.appearance

import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.InputType
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.config.AppDefaults
import com.openwakeup.schedule.core.database.entity.CourseDetailEntity
import com.openwakeup.schedule.core.database.entity.CourseEntity
import com.openwakeup.schedule.core.database.entity.TableEntity
import com.openwakeup.schedule.core.database.entity.TimeDetailEntity
import com.openwakeup.schedule.core.designsystem.component.colorpicker.OpacitySliderDialog
import com.openwakeup.schedule.core.image.TableBackgroundImageStore
import com.openwakeup.schedule.core.util.DateUtils
import com.openwakeup.schedule.core.validation.CourseRangePolicy
import com.openwakeup.schedule.data.schedule.ScheduleRepository
import com.openwakeup.schedule.databinding.ActivityTableConfigBinding
import com.openwakeup.schedule.feature.courseedit.ColorPickerDialog
import com.openwakeup.schedule.feature.schedule.ScheduleThemeColors
import com.openwakeup.schedule.feature.schedule.WeekPageFragment
import com.openwakeup.schedule.feature.schedule.WeekPageSnapshot
import com.openwakeup.schedule.feature.schedule.WeekPageSnapshotProvider
import com.openwakeup.schedule.feature.settings.CategoryItem
import com.openwakeup.schedule.feature.settings.HeaderItem
import com.openwakeup.schedule.feature.settings.NumberItem
import com.openwakeup.schedule.feature.settings.SettingsItem
import com.openwakeup.schedule.feature.settings.SettingsListAdapter
import com.openwakeup.schedule.feature.settings.SettingsRestoreDialog
import com.openwakeup.schedule.feature.settings.SwitchItem
import com.openwakeup.schedule.feature.settings.VerticalItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 课表外观：上半屏课表实时预览（父高 43.75%），
 * 下半屏 RecyclerView 设置项列表 =「整体」卡（课程表背景/界面文字颜色/网格辅助线/节数栏时间）+
 * 「课程格子」卡（课程文字颜色/边框颜色/高度/文字大小/圆角/不透明度）。
 * 周末、课程时间、教师、地点和非本周课程等显示开关统一放在课表设置页。
 * 颜色行走取色器（alpha 可调，id1/2 落盘前 alpha≥60），背景行点击选择类型；全部可配置项
 * 均支持按住 1.6 秒并确认后恢复默认。数值行弹 dialog_edit_text，改动即时写库并刷新预览。
 */
class TableConfigActivity : AppCompatActivity(), WeekPageSnapshotProvider {

    private val repo by lazy { ScheduleRepository(this) }
    private lateinit var binding: ActivityTableConfigBinding
    private lateinit var adapter: SettingsListAdapter
    private var table: TableEntity? = null
    private var weekFragment: WeekPageFragment? = null
    private var times: List<TimeDetailEntity> = emptyList()
    private var source: List<Pair<CourseEntity, CourseDetailEntity>> = emptyList()

    /** 外观预览专用快照，避免与主课表页面通过进程级静态字段互相覆盖。 */
    private var previewSnapshot: WeekPageSnapshot? = null

    /** 外观预览当前正在执行的背景图解码任务。 */
    private var previewBackgroundJob: Job? = null

    /** 预览背景请求递增序号，保证快速连续选择图片时只采用最后一次结果。 */
    private var previewBackgroundRequestId = 0L

    /** 已显示预览图的请求身份；修改其他外观设置时避免重复解码同一文件。 */
    private var displayedPreviewBackgroundKey: TableBackgroundImageStore.RequestKey? = null

    /** 为系统恢复或刚创建 View 的预览 Fragment 提供本页面自己的最新数据。 */
    override fun currentWeekPageSnapshot(): WeekPageSnapshot? = previewSnapshot

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val t = table ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val storedPath = TableBackgroundImageStore.importOriginal(
                    context = this@TableConfigActivity,
                    sourceUri = uri,
                    tableId = t.id,
                ) ?: return@launch
                updateTable { current -> current.copy(background = storedPath) }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityTableConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        binding.btnBack.setOnClickListener { finish() }
        adapter = SettingsListAdapter()
        binding.rvList.layoutManager = LinearLayoutManager(this)
        (binding.rvList.itemAnimator as? DefaultItemAnimator)?.addDuration = 250
        binding.rvList.adapter = adapter
        adapter.onItemClickListener = ::onItemClicked
        adapter.onItemLongClickListener = { item, _ -> requestRestoreDefault(item) }
        adapter.onItemCheckListener = { item, checked ->
            when (item.name) {
                R.string.setting_show_grid -> updateTable { it.copy(showGrid = checked) }
                R.string.setting_show_time_bar -> updateTable { it.copy(showTimeBar = checked) }
            }
        }
        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            val t = repo.currentTable.first() ?: return@launch
            table = t
            times = repo.timeDetailsOnce(t.timeTableId)
            val visibleNodeLimit = CourseRangePolicy.visibleNodeLimit(t.nodes)
            source = repo.coursesWithDetails(t.id).first()
                .filter { courseWithDetails ->
                    CourseRangePolicy.isCourseValid(courseWithDetails.details, visibleNodeLimit)
                }
                .flatMap { courseWithDetails ->
                    courseWithDetails.details.map { detail -> courseWithDetails.course to detail }
                }
            pushPreview()
            renderPreviewBackground()
            render()
        }
    }

    /** 推送预览数据；复用周页 Fragment，但快照只保存在当前外观设置 Activity 中。 */
    private fun pushPreview() {
        val t = table ?: return
        val week = (t.currentWeekOverride.takeIf { it > 0 }
            ?: DateUtils.currentWeek(LocalDate.parse(t.startDate))).coerceIn(1, t.maxWeek)
        val snapshot = WeekPageSnapshot(
            table = t,
            times = times,
            startDate = LocalDate.parse(t.startDate),
            source = source,
        )
        previewSnapshot = snapshot
        val existing = weekFragment
        if (existing == null) {
            val f = WeekPageFragment.newInstance(week).apply {
                // 外观页仅用于预览渲染，禁止创建或拖动快速添加草稿。
                isQuickAddEnabled = false
                onCourseClick = { _, _ -> }
            }
            weekFragment = f
            supportFragmentManager.beginTransaction()
                .replace(R.id.preview_container, f)
                .commit()
        } else {
            existing.isQuickAddEnabled = false
            existing.update(snapshot)
        }
    }

    /**
     * 渲染外观预览背景。
     *
     * 未配置自定义背景时，暗色模式使用主题表面色，浅色模式使用默认渐变；用户明确配置的
     * 纯色或图片在两种主题下都会生效。图片只有在预览容器完成布局后才会按实际尺寸解码；
     * 路径、尺寸和文件版本均未变化时直接复用现有 Drawable，避免重复读取同一张原图。
     */
    private fun renderPreviewBackground() {
        val t = table ?: return
        val requestId = ++previewBackgroundRequestId
        previewBackgroundJob?.cancel()
        previewBackgroundJob = null
        val bg = t.background
        when {
            bg.isBlank() -> {
                displayedPreviewBackgroundKey = null
                val darkBackground = ScheduleThemeColors.darkTableBackgroundColor(this)
                if (darkBackground != null) {
                    // 预览与主页规则一致：只有默认背景跟随夜间主题，自定义背景始终生效。
                    binding.previewContainer.setBackgroundColor(darkBackground)
                } else {
                    binding.previewContainer.setBackgroundResource(R.drawable.main_gradient_background)
                }
            }

            bg.startsWith("#") -> {
                displayedPreviewBackgroundKey = null
                runCatching { android.graphics.Color.parseColor(bg) }
                    .onSuccess(binding.previewContainer::setBackgroundColor)
                    .onFailure {
                        binding.previewContainer.setBackgroundResource(R.drawable.main_gradient_background)
                    }
            }

            else -> {
                binding.previewContainer.doOnLayout { preview ->
                    if (requestId != previewBackgroundRequestId) return@doOnLayout
                    val request = TableBackgroundImageStore.requestKey(
                        source = bg,
                        targetWidth = preview.width,
                        targetHeight = preview.height,
                    ) ?: return@doOnLayout
                    if (
                        request == displayedPreviewBackgroundKey &&
                        preview.background is BitmapDrawable
                    ) {
                        return@doOnLayout
                    }

                    previewBackgroundJob = lifecycleScope.launch {
                        val bitmap = TableBackgroundImageStore.decodeForView(
                            context = this@TableConfigActivity,
                            request = request,
                        )
                        if (requestId != previewBackgroundRequestId) {
                            bitmap?.recycle()
                            return@launch
                        }
                        if (bitmap == null) {
                            displayedPreviewBackgroundKey = null
                            preview.setBackgroundResource(R.drawable.main_gradient_background)
                        } else {
                            preview.background = BitmapDrawable(resources, bitmap)
                            displayedPreviewBackgroundKey = request
                        }
                        previewBackgroundJob = null
                    }
                }
            }
        }
    }

    /** Activity 销毁时取消预览解码，并解除容器到 BitmapDrawable 的引用。 */
    override fun onDestroy() {
        previewBackgroundRequestId++
        previewBackgroundJob?.cancel()
        previewBackgroundJob = null
        displayedPreviewBackgroundKey = null
        if (::binding.isInitialized) binding.previewContainer.background = null
        super.onDestroy()
    }

    /**
     * 更新当前课表外观并刷新预览。
     *
     * 背景字段发生替换时，先确保新值成功写入数据库，再清理旧的应用受管图片。清理失败不会
     * 回滚已经保存的外观设置，且存储层会拒绝删除外部 URI、任意路径和其他课表的图片。
     *
     * @param transform 基于更新前课表生成新配置的转换函数
     */
    private fun updateTable(transform: (TableEntity) -> TableEntity) {
        val previous = table ?: return
        lifecycleScope.launch {
            val updated = transform(previous)
            table = updated
            repo.updateTable(updated)
            TableBackgroundImageStore.deleteReplacedManagedImage(
                context = this@TableConfigActivity,
                oldSource = previous.background,
                newSource = updated.background,
                tableId = previous.id,
            )
            render()
            pushPreview()
            renderPreviewBackground()
        }
    }

    private fun render() {
        val t = table ?: return
        adapter.submit(buildItems(t))
    }

    private fun buildItems(t: TableEntity): List<CategoryItem> = listOf(
        CategoryItem(
            listOf(
                HeaderItem(R.string.setting_widget_global),
                VerticalItem(
                    R.string.setting_background,
                    getString(R.string.desc_background),
                    leadingIconRes = R.drawable.ms_image_24,
                    showChevron = true,
                    colorHex = t.background.takeIf { it.startsWith("#") },
                ),
                VerticalItem(
                    R.string.setting_header_color,
                    getString(R.string.desc_header_color),
                    leadingIconRes = R.drawable.ms_format_color_text_24,
                    colorHex = t.textColor,
                ),
                SwitchItem(
                    R.string.setting_show_grid,
                    t.showGrid,
                    getString(R.string.desc_show_grid),
                    R.drawable.ms_grid_on_24,
                ),
                SwitchItem(
                    R.string.setting_show_time_bar,
                    t.showTimeBar,
                    leadingIconRes = R.drawable.ms_schedule_24
                ),
            )
        ),
        CategoryItem(
            listOf(
                HeaderItem(R.string.setting_item_grid),
                VerticalItem(
                    R.string.setting_course_text_color,
                    getString(R.string.desc_course_text_color),
                    leadingIconRes = R.drawable.ms_format_color_text_24,
                    colorHex = t.courseTextColor,
                ),
                VerticalItem(
                    R.string.setting_stroke_color,
                    getString(R.string.desc_stroke_color),
                    leadingIconRes = R.drawable.ms_border_color_24,
                    colorHex = t.strokeColor,
                ),
                NumberItem(
                    R.string.setting_item_height,
                    t.itemHeight,
                    32,
                    128,
                    "dp",
                    leadingIconRes = R.drawable.ms_height_24
                ),
                NumberItem(
                    R.string.setting_course_text_size,
                    t.itemTextSize,
                    8,
                    32,
                    "sp",
                    leadingIconRes = R.drawable.ms_format_size_24
                ),
                NumberItem(
                    R.string.setting_item_radius,
                    t.itemRadius,
                    0,
                    32,
                    "dp",
                    leadingIconRes = R.drawable.ms_rounded_corner_24
                ),
                NumberItem(
                    R.string.setting_item_alpha,
                    t.itemAlpha,
                    0,
                    100,
                    "%",
                    leadingIconRes = R.drawable.ms_opacity_24
                ),
            )
        ),
    )

    private fun onItemClicked(item: SettingsItem, position: Int) {
        val t = table ?: return
        when (item.name) {
            R.string.setting_background -> showBackgroundTypeDialog(t)
            R.string.setting_header_color -> showColorDialog(t.textColor, 1)
            R.string.setting_course_text_color -> showColorDialog(t.courseTextColor, 2)
            R.string.setting_stroke_color -> showColorDialog(t.strokeColor, 3)
            R.string.setting_item_alpha -> showOpacitySlider(item)
            else -> {
                if (item is NumberItem) {
                    showNumberDialog(item) { value ->
                        when (item.name) {
                            R.string.setting_item_height -> updateTable { it.copy(itemHeight = value) }
                            R.string.setting_course_text_size -> updateTable { it.copy(itemTextSize = value) }
                            R.string.setting_item_radius -> updateTable { it.copy(itemRadius = value) }
                        }
                    }
                }
            }
        }
    }

    /** 长按任意外观设置项时，在恢复当前课表字段前显示统一确认弹窗。 */
    private fun requestRestoreDefault(item: SettingsItem): Boolean {
        val supported = item.name in setOf(
            R.string.setting_background,
            R.string.setting_header_color,
            R.string.setting_show_grid,
            R.string.setting_show_time_bar,
            R.string.setting_course_text_color,
            R.string.setting_stroke_color,
            R.string.setting_item_height,
            R.string.setting_course_text_size,
            R.string.setting_item_radius,
            R.string.setting_item_alpha,
        )
        if (!supported) return false
        SettingsRestoreDialog.show(this, item.name) { restoreDefault(item) }
        return true
    }

    /**
     * 将一个外观字段恢复为 [AppDefaults.Table] 中的统一默认值，并立即刷新只读预览。
     */
    private fun restoreDefault(item: SettingsItem) {
        updateTable { current ->
            when (item.name) {
                R.string.setting_background -> current.copy(background = AppDefaults.Table.BACKGROUND)
                R.string.setting_header_color -> current.copy(textColor = AppDefaults.Table.TEXT_COLOR)
                R.string.setting_show_grid -> current.copy(showGrid = AppDefaults.Table.SHOW_GRID)
                R.string.setting_show_time_bar -> current.copy(showTimeBar = AppDefaults.Table.SHOW_TIME_BAR)
                R.string.setting_course_text_color -> current.copy(
                    courseTextColor = AppDefaults.Table.COURSE_TEXT_COLOR,
                )

                R.string.setting_stroke_color -> current.copy(strokeColor = AppDefaults.Table.STROKE_COLOR)
                R.string.setting_item_height -> current.copy(itemHeight = AppDefaults.Table.ITEM_HEIGHT)
                R.string.setting_course_text_size -> current.copy(itemTextSize = AppDefaults.Table.ITEM_TEXT_SIZE)
                R.string.setting_item_radius -> current.copy(itemRadius = AppDefaults.Table.ITEM_RADIUS)
                R.string.setting_item_alpha -> current.copy(itemAlpha = AppDefaults.Table.ITEM_ALPHA)
                else -> current
            }
        }
    }

    /**
     * 点击背景行选择图片或纯色，并用单选点回显当前背景类型。
     *
     * 默认渐变背景不属于弹窗内的图片或纯色选项，因此恢复默认后不预选任何一项；用户选择
     * 新类型后继续进入对应选择器，恢复默认仍统一通过 1.6 秒长按确认流程。
     */
    private fun showBackgroundTypeDialog(t: TableEntity) {
        val labels = arrayOf(
            getString(R.string.bg_type_image),
            getString(R.string.bg_type_color),
        )
        val checkedItem = when {
            t.background.startsWith("#") -> BACKGROUND_TYPE_COLOR_INDEX
            t.background.isNotBlank() -> BACKGROUND_TYPE_IMAGE_INDEX
            else -> NO_BACKGROUND_TYPE_SELECTED
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_bg_type)
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    BACKGROUND_TYPE_IMAGE_INDEX -> requestBackgroundImage()
                    BACKGROUND_TYPE_COLOR_INDEX -> showColorDialog(t.background, 4)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 使用系统照片选择器打开课表背景图片，无需申请媒体库读取权限。 */
    private fun requestBackgroundImage() {
        launchBackgroundPicker()
    }

    /** 启动系统图片选择器。 */
    private fun launchBackgroundPicker() {
        pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /** id1/2 透明度低于 60 时拉回 60，背景存 "#RRGGBBAA" */
    private fun showColorDialog(initial: String, id: Int) {
        val init = if (id == 4 && !initial.startsWith("#")) "#E8E8F4" else initial
        ColorPickerDialog.newInstance(init).apply {
            onSaved = { hex ->
                var color = runCatching { android.graphics.Color.parseColor(hex) }.getOrDefault(0)
                if ((id == 1 || id == 2) && android.graphics.Color.alpha(color) < 60) {
                    color = (color and 0x00FFFFFF) or (60 shl 24)
                }
                val value = String.format("#%08X", color)
                updateTable { t ->
                    when (id) {
                        1 -> t.copy(textColor = value)
                        2 -> t.copy(courseTextColor = value)
                        3 -> t.copy(strokeColor = value)
                        else -> t.copy(background = value)
                    }
                }
            }
        }.show(supportFragmentManager, "color-picker-dialog")
    }

    /**
     * 使用与取色页相同的 ARGB 轨道修改当前课表的课程格子不透明度。
     *
     * 轨道优先使用当前课表第一门课程的颜色，让透明变化与预览中的实际色块一致；
     * 空课表则使用统一蓝色示例。数值只在点击保存后写入数据库并刷新预览。
     */
    private fun showOpacitySlider(item: SettingsItem) {
        val initialValue = (item as? NumberItem)?.value ?: return
        val trackColor = source.firstOrNull()?.first?.color ?: DEFAULT_OPACITY_TRACK_COLOR
        OpacitySliderDialog.newInstance(item.name, initialValue, trackColor).apply {
            // 保存时基于最新课表对象只替换透明度，避免覆盖面板打开后发生的其他配置变化。
            onSaved = { value -> updateTable { current -> current.copy(itemAlpha = value) } }
        }.show(supportFragmentManager, "table-opacity-slider")
    }

    /** NumberItem 弹窗：dialog_edit_text + 范围 helper + 后缀 + 数字键盘 */
    private fun showNumberDialog(item: NumberItem, onSet: (Int) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_text, null)
        val inputLayout = dialogView.findViewById<TextInputLayout>(R.id.text_input_layout)
        val editText = dialogView.findViewById<TextInputEditText>(R.id.edit_text)
        inputLayout.helperText = getString(R.string.range_format, item.min, item.max)
        if (item.prefix.isNotEmpty()) inputLayout.prefixText = item.prefix
        inputLayout.suffixText = item.unit
        editText.inputType = InputType.TYPE_CLASS_NUMBER
        val clamped = item.value.coerceIn(item.min, item.max)
        editText.setText(clamped.toString())
        editText.setSelection(clamped.toString().length)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(item.name)
            .setView(dialogView)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = editText.text?.toString()?.toIntOrNull() ?: return@setOnClickListener
            onSet(value.coerceIn(item.min, item.max))
            dialog.dismiss()
        }
    }

    private companion object {
        /** 课表背景类型弹窗中“图片”所在位置。 */
        const val BACKGROUND_TYPE_IMAGE_INDEX = 0

        /** 课表背景类型弹窗中“纯色”所在位置。 */
        const val BACKGROUND_TYPE_COLOR_INDEX = 1

        /** 默认渐变背景不属于可选类型，使用 -1 表示不预选。 */
        const val NO_BACKGROUND_TYPE_SELECTED = -1

        /** 当前课表没有课程时，不透明度轨道使用的统一示例颜色。 */
        const val DEFAULT_OPACITY_TRACK_COLOR = "#FF2979FF"
    }
}
