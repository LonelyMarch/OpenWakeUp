package com.openwakeup.schedule.feature.settings.widget

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.RemoteViews
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.config.AppDefaults
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.core.data.WidgetEmptyViewMode
import com.openwakeup.schedule.core.database.entity.CourseDetailEntity
import com.openwakeup.schedule.core.database.entity.CourseEntity
import com.openwakeup.schedule.core.database.entity.TimeDetailEntity
import com.openwakeup.schedule.core.designsystem.component.colorpicker.OpacitySliderDialog
import com.openwakeup.schedule.core.format.AppDateFormatter
import com.openwakeup.schedule.data.schedule.ScheduleRepository
import com.openwakeup.schedule.databinding.ActivityWidgetSettingsBinding
import com.openwakeup.schedule.feature.courseedit.ColorPickerDialog
import com.openwakeup.schedule.feature.settings.CategoryItem
import com.openwakeup.schedule.feature.settings.HeaderItem
import com.openwakeup.schedule.feature.settings.HorizontalItem
import com.openwakeup.schedule.feature.settings.NumberItem
import com.openwakeup.schedule.feature.settings.SettingsAppearance
import com.openwakeup.schedule.feature.settings.SettingsItem
import com.openwakeup.schedule.feature.settings.SettingsListAdapter
import com.openwakeup.schedule.feature.settings.SettingsRestoreDialog
import com.openwakeup.schedule.feature.settings.SwitchItem
import com.openwakeup.schedule.feature.settings.VerticalItem
import com.openwakeup.schedule.platform.appwidget.RecentCourseWidgetProvider
import com.openwakeup.schedule.platform.appwidget.ScheduleWidgetProvider
import com.openwakeup.schedule.platform.appwidget.TodayCourseWidgetProvider
import com.openwakeup.schedule.platform.appwidget.TodayWidgetProvider
import com.openwakeup.schedule.platform.appwidget.WidgetCourseRowRenderer
import com.openwakeup.schedule.platform.appwidget.WidgetImageStore
import com.openwakeup.schedule.platform.appwidget.WidgetSnapshot
import com.openwakeup.schedule.platform.appwidget.WidgetUpdateCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 全局桌面小部件设置页。
 *
 * 页面提供小部件样式配置，但配置全局唯一，因此不提供“以此设为默认样式”。自定义背景、
 * 空视图内容、标题和课程格子样式会持久化到 [Prefs]，修改后立即刷新已添加的小部件。
 */
class WidgetSettingsActivity : AppCompatActivity() {

    private val repo by lazy { ScheduleRepository(this) }
    private val prefs by lazy { Prefs.get(this) }
    private lateinit var binding: ActivityWidgetSettingsBinding
    private lateinit var adapter: SettingsListAdapter
    private var widgetTableName: String = ""
    private var requestBackgroundAfterAutostart: Boolean = false
    private var previewWidgetRoot: View? = null
    private var previewBackgroundBitmap: Bitmap? = null
    private val previewCourseAdapter = PreviewCourseAdapter()

    /** 选择小部件背景图片。 */
    private val pickBackgroundLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let {
                savePickedImage(
                    uri = it,
                    fileName = "widget_background.webp",
                    maxDimension = WidgetImageStore.MAX_BACKGROUND_DIMENSION,
                ) { path -> prefs.widgetBackground = path }
            }
        }

    /** 选择覆盖在小部件背景之上的自定义空视图图片。 */
    private val pickEmptyImageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let {
                savePickedImage(
                    uri = it,
                    fileName = "widget_empty.webp",
                    maxDimension = WidgetImageStore.MAX_EMPTY_IMAGE_DIMENSION,
                ) { path ->
                    // 只有图片成功导入后才切换模式，取消选择或导入失败不会覆盖现有空视图。
                    prefs.widgetEmptyImage = path
                    prefs.widgetEmptyViewMode = WidgetEmptyViewMode.IMAGE
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityWidgetSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SettingsAppearance.applyCards(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.btnBack.setOnClickListener { finish() }
        adapter = SettingsListAdapter()
        binding.rvList.layoutManager = LinearLayoutManager(this)
        (binding.rvList.itemAnimator as? DefaultItemAnimator)?.addDuration = 250L
        binding.rvList.adapter = adapter
        adapter.onItemClickListener = ::onItemClicked
        adapter.onItemCheckListener = ::onItemChecked
        adapter.onItemLongClickListener = { item, _ -> requestRestoreDefault(item) }
        loadWidgetTableName()
        maybeRequestWidgetRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        renderDesktopWallpaperPreview()
        if (requestBackgroundAfterAutostart) {
            // 用户从厂商自启动页返回后，继续请求后台运行所需的电池优化豁免。
            requestBackgroundAfterAutostart = false
            requestBackgroundExecution()
        }
        if (::adapter.isInitialized && widgetTableName.isNotEmpty()) render()
    }

    override fun onDestroy() {
        previewBackgroundBitmap?.takeUnless { bitmap -> bitmap.isRecycled }?.recycle()
        previewBackgroundBitmap = null
        super.onDestroy()
    }

    /** 读取当前选中的小部件课表名称。 */
    private fun loadWidgetTableName() {
        lifecycleScope.launch {
            widgetTableName = if (prefs.widgetTableId > 0L) {
                repo.tableOnce(prefs.widgetTableId)?.tableName
                    ?: getString(R.string.widget_follow_current)
            } else {
                getString(R.string.widget_follow_current)
            }
            render()
        }
    }

    /** 构建提示、整体、标题、课程格子与运行权限分组（提示固定排在设置列表首位）。 */
    private fun buildItems(): List<CategoryItem> = listOf(
        CategoryItem(
            listOf(
                HeaderItem(R.string.widget_tip_header),
                VerticalItem(
                    R.string.setting_resize_widget,
                    getString(R.string.widget_tip_desc),
                    leadingIconRes = R.drawable.ms_aspect_ratio_24,
                ),
            ),
        ),
        CategoryItem(
            listOf(
                HeaderItem(R.string.setting_widget_global),
                HorizontalItem(
                    R.string.setting_widget_showing_schedule,
                    widgetTableName,
                    R.drawable.ms_view_week_24,
                    showChevron = true,
                ),
                SwitchItem(
                    R.string.setting_widget_show_bg,
                    prefs.widgetShowBackground,
                    leadingIconRes = R.drawable.ms_wallpaper_24
                ),
                VerticalItem(
                    R.string.setting_widget_bg,
                    getString(R.string.widget_background_desc),
                    leadingIconRes = R.drawable.ms_image_24,
                    showChevron = true,
                    colorHex = prefs.widgetBackground.takeIf { it.startsWith("#") },
                ),
                VerticalItem(
                    R.string.setting_empty_view,
                    getString(
                        if (prefs.widgetEmptyViewMode == WidgetEmptyViewMode.TEXT) {
                            R.string.widget_empty_view_text_desc
                        } else {
                            R.string.widget_empty_view_image_desc
                        },
                    ),
                    leadingIconRes = R.drawable.ms_image_24,
                    showChevron = true,
                ),
                VerticalItem(
                    R.string.setting_pin_appwidget,
                    getString(R.string.widget_pin_desc),
                    leadingIconRes = R.drawable.ms_widgets_24,
                ),
            ),
        ),
        CategoryItem(
            listOf(
                HeaderItem(R.string.setting_widget_header_area),
                SwitchItem(
                    R.string.setting_widget_show_header_area,
                    prefs.widgetShowHeader,
                    leadingIconRes = R.drawable.ms_format_size_24
                ),
                SwitchItem(
                    R.string.setting_widget_show_date,
                    prefs.widgetShowDate,
                    leadingIconRes = R.drawable.ms_calendar_month_24
                ),
                SwitchItem(
                    R.string.setting_widget_show_button,
                    prefs.widgetShowButtons,
                    leadingIconRes = R.drawable.ms_more_vert_24
                ),
                VerticalItem(
                    R.string.setting_widget_header_text_color,
                    "",
                    leadingIconRes = R.drawable.ms_format_color_text_24,
                    colorHex = prefs.widgetHeaderColor,
                ),
                NumberItem(
                    R.string.setting_header_text_size,
                    prefs.widgetHeaderTextSize,
                    8,
                    24,
                    "sp",
                    leadingIconRes = R.drawable.ms_format_size_24
                ),
            ),
        ),
        CategoryItem(
            listOf(
                HeaderItem(R.string.setting_item_grid),
                SwitchItem(
                    R.string.setting_widget_show_color,
                    prefs.widgetShowColor,
                    leadingIconRes = R.drawable.ms_palette_24
                ),
                NumberItem(
                    R.string.setting_item_alpha,
                    prefs.widgetItemAlpha,
                    0,
                    100,
                    "%",
                    leadingIconRes = R.drawable.ms_opacity_24
                ),
                NumberItem(
                    R.string.setting_course_text_size,
                    prefs.widgetTextSize,
                    8,
                    24,
                    "sp",
                    leadingIconRes = R.drawable.ms_format_size_24
                ),
                NumberItem(
                    R.string.widget_secondary_text_alpha,
                    prefs.widgetSecondaryTextAlpha,
                    0,
                    100,
                    "%",
                    leadingIconRes = R.drawable.ms_opacity_24
                ),
                VerticalItem(
                    R.string.widget_course_text_color,
                    "",
                    leadingIconRes = R.drawable.ms_format_color_text_24,
                    colorHex = prefs.widgetTextColor,
                ),
                SwitchItem(
                    R.string.widget_text_compose,
                    prefs.widgetTextCompose,
                    leadingIconRes = R.drawable.ms_layers_24
                ),
                VerticalItem(
                    R.string.widget_stroke_color,
                    "",
                    leadingIconRes = R.drawable.ms_border_color_24,
                    colorHex = prefs.widgetStrokeColor,
                ),
                SwitchItem(
                    R.string.widget_stroke_compose,
                    prefs.widgetStrokeCompose,
                    leadingIconRes = R.drawable.ms_layers_24
                ),
            ),
        ),
        CategoryItem(
            listOf(
                HeaderItem(R.string.widget_runtime_permissions),
                VerticalItem(
                    R.string.setting_auto_launch,
                    getString(R.string.widget_autostart_desc),
                    leadingIconRes = R.drawable.ms_rocket_launch_24,
                    showChevron = true,
                ),
                VerticalItem(
                    R.string.setting_ingore_battery_opt,
                    if (isIgnoringBatteryOptimizations()) {
                        getString(R.string.widget_background_allowed)
                    } else {
                        getString(R.string.widget_background_not_allowed)
                    },
                    leadingIconRes = R.drawable.ms_battery_saver_24,
                    showChevron = true,
                ),
            ),
        ),
    )

    /** 只刷新设置列表和本页预览；打开页面本身不应触发桌面小组件读库与重绘。 */
    private fun render() {
        adapter.submit(buildItems())
        renderPreview()
    }

    /** 用户成功保存小组件配置后，同步预览并刷新真实桌面实例与节点调度。 */
    private fun renderAndNotifyWidgets() {
        render()
        lifecycleScope.launch(Dispatchers.IO) {
            WidgetUpdateCoordinator.refreshAndReconcileScheduling(this@WidgetSettingsActivity)
        }
    }

    /** 处理普通、颜色和数值设置项。 */
    private fun onItemClicked(item: SettingsItem, position: Int) {
        when (item.name) {
            R.string.setting_widget_showing_schedule -> showScheduleDialog()
            R.string.setting_widget_bg -> showBackgroundDialog()
            R.string.setting_empty_view -> showEmptyViewModeDialog()
            R.string.setting_pin_appwidget -> showPinWidgetDialog()
            R.string.setting_auto_launch -> openAutoStartSettings()
            R.string.setting_ingore_battery_opt -> requestBackgroundExecution()
            R.string.setting_widget_header_text_color -> showColorPicker(
                prefs.widgetHeaderColor,
            ) { prefs.widgetHeaderColor = it }

            R.string.widget_course_text_color -> showColorPicker(
                prefs.widgetTextColor,
            ) { prefs.widgetTextColor = it }

            R.string.widget_stroke_color -> showColorPicker(
                prefs.widgetStrokeColor,
            ) { prefs.widgetStrokeColor = it }

            R.string.setting_item_alpha -> showOpacitySlider(
                item = item,
                trackColor = WIDGET_PREVIEW_COURSE_COLOR,
            ) { value -> prefs.widgetItemAlpha = value }

            R.string.widget_secondary_text_alpha -> showOpacitySlider(
                item = item,
                trackColor = prefs.widgetTextColor,
            ) { value -> prefs.widgetSecondaryTextAlpha = value }

            else -> if (item is NumberItem) {
                showNumberDialog(item) { value ->
                    when (item.name) {
                        R.string.setting_header_text_size -> prefs.widgetHeaderTextSize = value
                        R.string.setting_course_text_size -> prefs.widgetTextSize = value
                    }
                    renderAndNotifyWidgets()
                }
            }
        }
    }

    /** 首次进入页面时明确说明并请求小部件后台运行所需的两类系统授权。 */
    private fun maybeRequestWidgetRuntimePermissions() {
        if (prefs.widgetRuntimePermissionAsked) return
        prefs.widgetRuntimePermissionAsked = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.widget_runtime_permission_title)
            .setMessage(R.string.widget_runtime_permission_message)
            .setPositiveButton(R.string.widget_start_authorization) { _, _ ->
                requestBackgroundAfterAutostart = true
                openAutoStartSettings()
            }
            .setNeutralButton(R.string.widget_request_background) { _, _ -> requestBackgroundExecution() }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    /**
     * 打开主流厂商的自启动管理页；设备不支持时回退到应用详情页。
     */
    private fun openAutoStartSettings() {
        val candidates = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi" -> listOf(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            )

            "huawei", "honor" -> listOf(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
            )

            "oppo", "realme", "oneplus" -> listOf(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ),
            )

            "vivo", "iqoo" -> listOf(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ),
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                ),
            )

            else -> emptyList()
        }
        // Android 11 起 resolveActivity 会受包可见性限制；显式 Intent 可直接安全尝试，无需扩大 queries。
        candidates.forEach { component ->
            val opened = runCatching { startActivity(Intent().setComponent(component)) }.isSuccess
            if (opened) return
        }
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        })
    }

    /** 请求系统允许应用忽略电池优化，以提升跨天和后台刷新可靠性。 */
    private fun requestBackgroundExecution() {
        if (isIgnoringBatteryOptimizations()) {
            Toast.makeText(this, R.string.widget_background_already_allowed, Toast.LENGTH_SHORT)
                .show()
            return
        }
        // 用户从“小组件运行权限”入口主动发起豁免请求，以提高锁屏和跨天后的刷新可靠性。
        val directRequest = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        // 个别厂商未实现应用级授权页面，启动失败时回退到系统电池优化应用列表。
        runCatching { startActivity(directRequest) }
            .onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    /** 查询应用是否已被系统允许忽略电池优化。 */
    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    /** 持久化小部件布尔设置。 */
    private fun onItemChecked(item: SettingsItem, checked: Boolean) {
        when (item.name) {
            R.string.setting_widget_show_bg -> prefs.widgetShowBackground = checked
            R.string.setting_widget_show_header_area -> prefs.widgetShowHeader = checked
            R.string.setting_widget_show_date -> prefs.widgetShowDate = checked
            R.string.setting_widget_show_button -> prefs.widgetShowButtons = checked
            R.string.setting_widget_show_color -> prefs.widgetShowColor = checked
            R.string.widget_text_compose -> prefs.widgetTextCompose = checked
            R.string.widget_stroke_compose -> prefs.widgetStrokeCompose = checked
        }
        renderAndNotifyWidgets()
    }

    /** 长按可配置的小部件设置项时，先展示居中确认弹窗。 */
    private fun requestRestoreDefault(item: SettingsItem): Boolean {
        val supported = item.name in setOf(
            R.string.setting_widget_showing_schedule,
            R.string.setting_widget_show_bg,
            R.string.setting_widget_bg,
            R.string.setting_empty_view,
            R.string.setting_widget_show_header_area,
            R.string.setting_widget_show_date,
            R.string.setting_widget_show_button,
            R.string.setting_widget_header_text_color,
            R.string.setting_header_text_size,
            R.string.setting_widget_show_color,
            R.string.setting_item_alpha,
            R.string.setting_course_text_size,
            R.string.widget_secondary_text_alpha,
            R.string.widget_course_text_color,
            R.string.widget_text_compose,
            R.string.widget_stroke_color,
            R.string.widget_stroke_compose,
        )
        if (!supported) return false
        SettingsRestoreDialog.show(this, item.name) { restoreDefault(item) }
        return true
    }

    /** 将单个小部件设置恢复为 [Prefs] 中声明的默认值，并同步预览与桌面实例。 */
    private fun restoreDefault(item: SettingsItem) {
        when (item.name) {
            R.string.setting_widget_showing_schedule -> {
                prefs.widgetTableId = AppDefaults.Widget.TABLE_ID
                widgetTableName = getString(R.string.widget_follow_current)
            }

            R.string.setting_widget_show_bg -> prefs.widgetShowBackground =
                AppDefaults.Widget.SHOW_BACKGROUND

            R.string.setting_widget_bg -> prefs.resetWidgetBackground()
            R.string.setting_empty_view -> prefs.resetWidgetEmptyView()
            R.string.setting_widget_show_header_area -> prefs.widgetShowHeader =
                AppDefaults.Widget.SHOW_HEADER

            R.string.setting_widget_show_date -> prefs.widgetShowDate = AppDefaults.Widget.SHOW_DATE
            R.string.setting_widget_show_button -> prefs.widgetShowButtons =
                AppDefaults.Widget.SHOW_BUTTONS

            R.string.setting_widget_header_text_color ->
                prefs.resetWidgetHeaderColor()

            R.string.setting_header_text_size -> prefs.widgetHeaderTextSize =
                AppDefaults.Widget.HEADER_TEXT_SIZE

            R.string.setting_widget_show_color -> prefs.widgetShowColor =
                AppDefaults.Widget.SHOW_COLOR

            R.string.setting_item_alpha -> prefs.widgetItemAlpha = AppDefaults.Widget.ITEM_ALPHA
            R.string.setting_course_text_size -> prefs.widgetTextSize = AppDefaults.Widget.TEXT_SIZE
            R.string.widget_secondary_text_alpha -> prefs.widgetSecondaryTextAlpha =
                AppDefaults.Widget.SECONDARY_TEXT_ALPHA

            R.string.widget_course_text_color -> prefs.widgetTextColor =
                AppDefaults.Widget.TEXT_COLOR

            R.string.widget_text_compose -> prefs.widgetTextCompose =
                AppDefaults.Widget.TEXT_COMPOSE

            R.string.widget_stroke_color -> prefs.widgetStrokeColor =
                AppDefaults.Widget.STROKE_COLOR

            R.string.widget_stroke_compose -> prefs.widgetStrokeCompose =
                AppDefaults.Widget.STROKE_COMPOSE
        }
        renderAndNotifyWidgets()
    }

    /** 展示要由小部件固定显示的课表。 */
    private fun showScheduleDialog() {
        lifecycleScope.launch {
            val tables = repo.tables().first()
            val labels =
                listOf(getString(R.string.widget_follow_current)) + tables.map { it.tableName }
            val checked = if (prefs.widgetTableId == 0L) 0 else {
                tables.indexOfFirst { it.id == prefs.widgetTableId }
                    .let { if (it < 0) 0 else it + 1 }
            }
            MaterialAlertDialogBuilder(this@WidgetSettingsActivity)
                .setTitle(R.string.setting_widget_showing_schedule)
                .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                    prefs.widgetTableId = if (which == 0) 0L else tables[which - 1].id
                    widgetTableName = labels[which]
                    renderAndNotifyWidgets()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** 选择纯色或图片小部件背景，并用单选点回显当前背景类型。 */
    private fun showBackgroundDialog() {
        val labels = arrayOf(
            getString(R.string.widget_background_color),
            getString(R.string.widget_background_image),
        )
        val checkedItem = if (prefs.isWidgetBackgroundImage) {
            WIDGET_BACKGROUND_IMAGE_INDEX
        } else {
            WIDGET_BACKGROUND_COLOR_INDEX
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.widget_choose_background)
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    WIDGET_BACKGROUND_COLOR_INDEX -> showColorPicker(prefs.widgetBackground) {
                        prefs.widgetBackground = it
                    }

                    WIDGET_BACKGROUND_IMAGE_INDEX -> requestImagePicker(ImageTarget.BACKGROUND)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 选择空视图展示文字或图片。
     *
     * 选择文字后继续打开双文案编辑器；选择图片后进入系统照片选择器。模式只会在用户完成
     * 文案保存或图片导入后更新，关闭任一后续界面不会意外改变当前设置。
     */
    private fun showEmptyViewModeDialog() {
        val labels = arrayOf(
            getString(R.string.widget_empty_view_show_text),
            getString(R.string.widget_empty_view_show_image),
        )
        val checkedItem = if (prefs.widgetEmptyViewMode == WidgetEmptyViewMode.TEXT) 0 else 1
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setting_empty_view)
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> showEmptyViewTextDialog()
                    1 -> requestImagePicker(ImageTarget.EMPTY_VIEW)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 编辑当天与第二天无课程时显示的两条文字。
     *
     * 两项均为必填；保存前去除首尾空白，避免小部件显示看似为空的内容。
     */
    private fun showEmptyViewTextDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_widget_empty_text, null)
        val todayLayout = dialogView.findViewById<TextInputLayout>(R.id.today_text_input_layout)
        val tomorrowLayout =
            dialogView.findViewById<TextInputLayout>(R.id.tomorrow_text_input_layout)
        val todayInput = dialogView.findViewById<TextInputEditText>(R.id.today_text_input)
        val tomorrowInput = dialogView.findViewById<TextInputEditText>(R.id.tomorrow_text_input)
        todayInput.setText(prefs.widgetEmptyTodayText)
        tomorrowInput.setText(prefs.widgetEmptyTomorrowText)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.widget_empty_view_edit_text)
            .setView(dialogView)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val todayText = todayInput.text?.toString()?.trim().orEmpty()
            val tomorrowText = tomorrowInput.text?.toString()?.trim().orEmpty()
            todayLayout.error = if (todayText.isEmpty()) {
                getString(R.string.widget_empty_text_required)
            } else {
                null
            }
            tomorrowLayout.error = if (tomorrowText.isEmpty()) {
                getString(R.string.widget_empty_text_required)
            } else {
                null
            }
            if (todayText.isEmpty() || tomorrowText.isEmpty()) return@setOnClickListener

            prefs.widgetEmptyTodayText = todayText
            prefs.widgetEmptyTomorrowText = tomorrowText
            prefs.widgetEmptyViewMode = WidgetEmptyViewMode.TEXT
            renderAndNotifyWidgets()
            dialog.dismiss()
        }
    }

    /**
     * 打开系统照片选择器；该选择器直接授予所选媒体的访问权，无需读取整个媒体库。
     *
     * @param target 图片将用于小部件背景或空视图
     */
    private fun requestImagePicker(target: ImageTarget) {
        launchImagePicker(target)
    }

    /** 根据用途启动对应的图片选择器。 */
    private fun launchImagePicker(target: ImageTarget) {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        when (target) {
            ImageTarget.BACKGROUND -> pickBackgroundLauncher.launch(request)
            ImageTarget.EMPTY_VIEW -> pickEmptyImageLauncher.launch(request)
        }
    }

    /** 使用应用统一取色器选择任意 ARGB 颜色，并即时刷新预览与桌面小部件。 */
    private fun showColorPicker(current: String, onPicked: (String) -> Unit) {
        val initial = current.takeIf { value -> value.startsWith("#") }
            ?: prefs.widgetDefaultBackground
        ColorPickerDialog.newInstance(initial).apply {
            onSaved = { color ->
                onPicked(color)
                renderAndNotifyWidgets()
            }
        }.show(supportFragmentManager, "widget-color-picker")
    }

    /**
     * 使用统一 ARGB 透明度滑条编辑小部件百分比设置，确认后刷新预览与桌面实例。
     *
     * @param item 当前百分比设置项，用于读取标题与初始值
     * @param trackColor ARGB 轨道用于演示透明变化的颜色
     * @param onSaved 将最终百分比写入 [Prefs] 的回调
     */
    private fun showOpacitySlider(
        item: SettingsItem,
        trackColor: String,
        onSaved: (Int) -> Unit,
    ) {
        val initialValue = (item as? NumberItem)?.value ?: return
        OpacitySliderDialog.newInstance(item.name, initialValue, trackColor).apply {
            this.onSaved = { value ->
                onSaved(value)
                renderAndNotifyWidgets()
            }
        }.show(supportFragmentManager, "widget-opacity-slider")
    }

    /** 请求桌面添加四种课程小部件中的任意一种。 */
    private fun showPinWidgetDialog() {
        val labels = arrayOf(
            getString(R.string.title_week_widget),
            getString(R.string.title_today_widget),
            getString(R.string.title_recent_widget),
            getString(R.string.title_day_widget),
        )
        val providers = arrayOf(
            ScheduleWidgetProvider::class.java,
            TodayCourseWidgetProvider::class.java,
            RecentCourseWidgetProvider::class.java,
            TodayWidgetProvider::class.java,
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setting_pin_appwidget)
            .setItems(labels) { _, which ->
                val manager = AppWidgetManager.getInstance(this)
                if (!manager.isRequestPinAppWidgetSupported) {
                    Toast.makeText(this, R.string.widget_pin_not_supported, Toast.LENGTH_LONG)
                        .show()
                    return@setItems
                }
                manager.requestPinAppWidget(ComponentName(this, providers[which]), null, null)
            }
            .show()
    }

    /** 数值配置输入弹窗。 */
    private fun showNumberDialog(item: NumberItem, onSet: (Int) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_text, null)
        val inputLayout = dialogView.findViewById<TextInputLayout>(R.id.text_input_layout)
        val editText = dialogView.findViewById<TextInputEditText>(R.id.edit_text)
        inputLayout.helperText = getString(R.string.range_format, item.min, item.max)
        inputLayout.suffixText = item.unit
        editText.inputType = InputType.TYPE_CLASS_NUMBER
        editText.setText(item.value.toString())
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

    /**
     * 将系统图片缩放后保存到应用私有目录，避免临时 URI 失效及全尺寸 Bitmap 导致崩溃。
     *
     * @param uri 系统照片选择器返回的图片
     * @param fileName 私有目录中的目标文件名
     * @param maxDimension 保存图片的最长边上限
     * @param onSaved 保存成功后更新对应偏好的回调
     */
    private fun savePickedImage(
        uri: Uri,
        fileName: String,
        maxDimension: Int,
        onSaved: (String) -> Unit,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = WidgetImageStore.importImage(
                context = this@WidgetSettingsActivity,
                uri = uri,
                fileName = fileName,
                maxDimension = maxDimension,
            )
            withContext(Dispatchers.Main) {
                if (saved != null) {
                    onSaved(saved)
                    renderAndNotifyWidgets()
                } else {
                    Toast.makeText(
                        this@WidgetSettingsActivity,
                        R.string.widget_image_save_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    /** 根据当前设置绘制页面顶部的轻量预览。 */
    private fun renderPreview() {
        val today = LocalDate.now()
        val root = previewWidgetRoot ?: run {
            layoutInflater.inflate(
                R.layout.today_course_app_widget,
                binding.previewWidgetHost,
                true
            )
            val child = binding.previewWidgetHost.getChildAt(0)
                ?: error("日视图小部件预览布局加载失败")
            previewWidgetRoot = child
            child
        }
        val title = root.findViewById<View>(R.id.rl_title)
        val date = root.findViewById<android.widget.TextView>(R.id.tv_date)
        val scheduleName = root.findViewById<android.widget.TextView>(R.id.tv_schedule_name)
        val week = root.findViewById<android.widget.TextView>(R.id.tv_week)
        val settings = root.findViewById<ImageView>(R.id.iv_settings)
        val next = root.findViewById<ImageView>(R.id.iv_next)
        val back = root.findViewById<ImageView>(R.id.iv_back)
        val list = root.findViewById<ListView>(R.id.lv_course)

        date.text = AppDateFormatter.formatFull(today, prefs.dateFormat)
        scheduleName.text = widgetTableName
        week.text = getString(
            R.string.widget_preview_week_and_day,
            getString(R.string.week_num, 1),
            weekdayName(today.dayOfWeek.value),
        )
        title.visibility = if (prefs.widgetShowHeader) View.VISIBLE else View.GONE
        date.visibility =
            if (prefs.widgetShowHeader && prefs.widgetShowDate) View.VISIBLE else View.GONE
        val buttonVisibility =
            if (prefs.widgetShowHeader && prefs.widgetShowButtons) View.VISIBLE else View.GONE
        settings.visibility = buttonVisibility
        next.visibility = buttonVisibility
        back.visibility = if (buttonVisibility == View.VISIBLE) View.INVISIBLE else View.GONE
        val headerColor = parseColor(prefs.widgetHeaderColor, Color.DKGRAY)
        listOf(scheduleName, week).forEach {
            it.setTextColor(headerColor)
            it.textSize = prefs.widgetHeaderTextSize.toFloat()
        }
        date.setTextColor(headerColor)
        date.textSize = prefs.widgetHeaderTextSize + 3f
        listOf(settings, next, back).forEach { it.setColorFilter(headerColor) }

        val density = resources.displayMetrics.density
        val desktopWidthDp = binding.previewDesktopArea.width
            .takeIf { widthPx -> widthPx > 0 }
            ?.let { widthPx -> widthPx / density }
            ?: resources.configuration.screenWidthDp.toFloat()
        // 目标桌面图宽 1260px，全宽小部件左右仍各留约 87px。预览按相同比例缩进，
        // 再把扣除边距后的宽度传给课程卡，避免把“桌面全宽”误当成“设置页贴边”。
        val previewSideMarginDp = desktopWidthDp * PREVIEW_DESKTOP_SIDE_MARGIN_RATIO
        val previewWidthDp = (desktopWidthDp - previewSideMarginDp * 2f).coerceAtLeast(1f)
        (binding.previewWidgetHost.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            val horizontalMarginPx = (previewSideMarginDp * density).toInt()
            params.setMargins(
                horizontalMarginPx,
                params.topMargin,
                horizontalMarginPx,
                params.bottomMargin
            )
            // XML 使用相对方向边距；同步 start/end，防止布局解析时覆盖刚设置的 left/right。
            params.marginStart = horizontalMarginPx
            params.marginEnd = horizontalMarginPx
            binding.previewWidgetHost.layoutParams = params
        }
        previewCourseAdapter.row = createPreviewCourseRow(today, previewWidthDp)
        if (list.adapter !== previewCourseAdapter) list.adapter = previewCourseAdapter
        previewCourseAdapter.notifyDataSetChanged()
        // 预览只展示完整样例，不承担课程浏览；禁止 ListView 截断内容后再要求用户滚动。
        list.isVerticalScrollBarEnabled = false
        list.isScrollContainer = false
        list.overScrollMode = View.OVER_SCROLL_NEVER
        list.visibility = View.VISIBLE
        root.findViewById<View>(android.R.id.empty).visibility = View.GONE
        root.findViewById<View>(R.id.fl_course_left_count).visibility = View.GONE

        val appWidget = root.findViewById<ImageView>(R.id.iv_appwidget)
        val pictureBackground = root.findViewById<ImageView>(R.id.iv_appwidget_pic_bg)
        val content = root.findViewById<View>(R.id.rl_appwidget)
        val padding = (8f * resources.displayMetrics.density).toInt()
        val background = prefs.widgetBackground
        val backgroundBitmap = if (prefs.widgetShowBackground && !background.startsWith("#")) {
            WidgetImageStore.decodeFile(background, WidgetImageStore.MAX_BACKGROUND_DIMENSION)
        } else {
            null
        }
        when {
            !prefs.widgetShowBackground -> {
                appWidget.visibility = View.GONE
                pictureBackground.visibility = View.GONE
                replacePreviewBackground(pictureBackground, null)
                content.setPadding(0, 0, 0, 0)
            }

            background.startsWith("#") -> {
                val color = parseColor(background, Color.WHITE)
                appWidget.visibility = View.VISIBLE
                pictureBackground.visibility = View.GONE
                replacePreviewBackground(pictureBackground, null)
                appWidget.setImageResource(R.drawable.appwidget_bg)
                appWidget.imageAlpha = Color.alpha(color)
                appWidget.setColorFilter(
                    androidx.core.graphics.ColorUtils.setAlphaComponent(
                        color,
                        255
                    )
                )
                content.setPadding(padding, padding * 2, padding, 0)
            }

            backgroundBitmap != null -> {
                appWidget.visibility = View.GONE
                pictureBackground.visibility = View.VISIBLE
                replacePreviewBackground(pictureBackground, backgroundBitmap)
                content.setPadding(padding, padding * 2, padding, 0)
            }

            else -> {
                val color = parseColor(prefs.widgetDefaultBackground, Color.WHITE)
                appWidget.visibility = View.VISIBLE
                pictureBackground.visibility = View.GONE
                replacePreviewBackground(pictureBackground, null)
                appWidget.setImageResource(R.drawable.appwidget_bg)
                appWidget.imageAlpha = Color.alpha(color)
                appWidget.setColorFilter(
                    androidx.core.graphics.ColorUtils.setAlphaComponent(
                        color,
                        255
                    )
                )
                content.setPadding(padding, padding * 2, padding, 0)
            }
        }

        // 按真实全宽课程卡、标题区和底部栏共同计算宿主高度，确保两行课程信息完整显示。
        val backgroundTopPaddingDp = if (prefs.widgetShowBackground) 16f else 0f
        val titleHeightDp = if (prefs.widgetShowHeader) {
            48f + (prefs.widgetHeaderTextSize - 11).coerceAtLeast(0) * 2f
        } else {
            0f
        }
        val courseHeightDp = WidgetCourseRowRenderer.courseRowHeightDp(this, previewWidthDp)
        val hostHeightDp = backgroundTopPaddingDp + titleHeightDp + courseHeightDp + 28f
        binding.previewWidgetHost.layoutParams = binding.previewWidgetHost.layoutParams.apply {
            height = (hostHeightDp * density).toInt()
        }
        binding.previewDesktopArea.layoutParams = binding.previewDesktopArea.layoutParams.apply {
            height = ((hostHeightDp + 24f) * density).toInt()
        }
    }

    /**
     * 替换预览背景并回收上一张采样 Bitmap，避免每次调整其他设置都累积一份图片内存。
     *
     * @param imageView 预览中的图片背景层
     * @param bitmap 新背景；空值表示切换到纯色或隐藏背景
     */
    private fun replacePreviewBackground(imageView: ImageView, bitmap: Bitmap?) {
        val previous = previewBackgroundBitmap
        // 小部件可自由调整宽高，CENTER_CROP 会按当前预览比例等比填满并裁掉溢出部分。
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        imageView.setImageBitmap(bitmap)
        previewBackgroundBitmap = bitmap
        if (previous !== bitmap && previous?.isRecycled == false) previous.recycle()
    }

    /** 构造与真实“日视图”小部件服务完全相同的原生课程行。 */
    private fun createPreviewCourseRow(today: LocalDate, widthDp: Float): RemoteViews {
        val course = CourseEntity(
            id = 1L,
            tableId = 1L,
            courseName = getString(R.string.widget_preview_course_name),
            color = WIDGET_PREVIEW_COURSE_COLOR,
        )
        val detail = CourseDetailEntity(
            courseId = course.id,
            day = today.dayOfWeek.value,
            startNode = 1,
            step = 2,
            startWeek = 1,
            endWeek = 20,
            teacher = getString(R.string.widget_preview_teacher),
            room = getString(R.string.widget_preview_room),
        )
        val times = listOf(
            TimeDetailEntity(timeTableId = 1L, node = 1, startTime = "08:30", endTime = "09:15"),
            TimeDetailEntity(timeTableId = 1L, node = 2, startTime = "09:20", endTime = "10:05"),
        )
        val snapshot = WidgetSnapshot(
            tableName = widgetTableName,
            displayWeek = 1,
            details = listOf(detail),
            courses = mapOf(course.id to course),
            timeDetails = times,
            startDate = today.minusDays((today.dayOfWeek.value - 1).toLong()),
            shifts = emptyList(),
        )
        return WidgetCourseRowRenderer.create(this, snapshot, course, detail, widthDp = widthDp)
    }

    /** 单条预览列表适配器：直接应用真实小部件生成的 RemoteViews，避免维护第二套样式。 */
    private inner class PreviewCourseAdapter : BaseAdapter() {
        var row: RemoteViews? = null

        override fun getCount(): Int = if (row == null) 0 else 1
        override fun getItem(position: Int): Any? = row
        override fun getItemId(position: Int): Long = 0L

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            // Activity 的 LayoutInflater 带有 AppCompat Factory，会把原生控件替换成
            // AppCompat/Material 子类；RemoteViews 的反射动作依赖原生控件的远程方法声明。
            // 使用 Application 的独立 inflater，让预览与桌面宿主一样创建原生 TextView/ImageView，
            // 避免设置页加载预览时发生 RemoteViews.ActionException 或控件膨胀异常。
            return row?.apply(applicationContext, parent)
                ?: View(applicationContext)
        }
    }

    /** 将 ISO 星期转换为与桌面小部件一致的星期短名。 */
    private fun weekdayName(day: Int): String =
        getString(WEEKDAY_RES[day - 1])

    /**
     * 在小部件卡片后绘制当前手机桌面壁纸的一部分。
     *
     * 静态壁纸优先读取真实画面，动态壁纸则尝试读取其缩略图。部分系统会基于隐私策略拒绝读取
     * 壁纸像素，此时根据系统公开的壁纸主色生成渐变背景；整个过程不会额外申请图库权限。
     */
    @SuppressLint("MissingPermission")
    private fun renderDesktopWallpaperPreview() {
        lifecycleScope.launch(Dispatchers.IO) {
            val wallpaperManager = WallpaperManager.getInstance(this@WidgetSettingsActivity)
            // READ_WALLPAPER_INTERNAL 是签名权限，普通应用无法申请；读取失败时立即回退到公开壁纸色。
            @Suppress("DEPRECATION")
            val wallpaper = runCatching { wallpaperManager.drawable }.getOrNull()
                ?: runCatching {
                    wallpaperManager.wallpaperInfo?.loadThumbnail(packageManager)
                }.getOrNull()
            // 项目最低版本为 API 33，可直接读取公开的壁纸主色，无需保留旧系统分支。
            val primaryColor = runCatching {
                wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                    ?.primaryColor
                    ?.toArgb()
            }.getOrNull() ?: Color.parseColor("#26354A")

            withContext(Dispatchers.Main) {
                if (wallpaper != null) {
                    binding.previewWallpaper.setImageDrawable(wallpaper)
                } else {
                    // 公开壁纸颜色可在无像素读取权限时保持预览与桌面主题色一致。
                    val dark = androidx.core.graphics.ColorUtils.blendARGB(
                        primaryColor,
                        Color.BLACK,
                        0.42f
                    )
                    val light = androidx.core.graphics.ColorUtils.blendARGB(
                        primaryColor,
                        Color.WHITE,
                        0.16f
                    )
                    binding.previewWallpaper.setImageDrawable(
                        GradientDrawable(
                            GradientDrawable.Orientation.TR_BL,
                            intArrayOf(dark, primaryColor, light),
                        ),
                    )
                }
            }
        }
    }

    /** 安全解析颜色字符串。 */
    private fun parseColor(value: String, fallback: Int): Int =
        runCatching { Color.parseColor(value) }.getOrDefault(fallback)

    companion object {
        /** 小部件背景类型弹窗中“纯色背景”所在位置。 */
        private const val WIDGET_BACKGROUND_COLOR_INDEX = 0

        /** 小部件背景类型弹窗中“图片背景”所在位置。 */
        private const val WIDGET_BACKGROUND_IMAGE_INDEX = 1

        /** 设置页样例课程的颜色，同时用于课程格子不透明度轨道。 */
        private const val WIDGET_PREVIEW_COURSE_COLOR = "#FF2979FF"

        /** 目标桌面截图中全宽小部件的单侧留白比例：87px / 1260px。 */
        private const val PREVIEW_DESKTOP_SIDE_MARGIN_RATIO = 87f / 1260f

        /** 星期短名资源 id（预览与桌面小组件保持一致）。 */
        private val WEEKDAY_RES = intArrayOf(
            R.string.widget_weekday_short_1,
            R.string.widget_weekday_short_2,
            R.string.widget_weekday_short_3,
            R.string.widget_weekday_short_4,
            R.string.widget_weekday_short_5,
            R.string.widget_weekday_short_6,
            R.string.widget_weekday_short_7,
        )
    }

    /** 需要访问图库的图片用途。 */
    private enum class ImageTarget {
        BACKGROUND,
        EMPTY_VIEW,
    }
}
