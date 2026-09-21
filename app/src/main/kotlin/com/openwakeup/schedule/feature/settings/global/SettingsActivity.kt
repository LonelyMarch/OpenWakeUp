package com.openwakeup.schedule.feature.settings.global

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.config.AppDefaults
import com.openwakeup.schedule.core.data.AppLocale
import com.openwakeup.schedule.core.data.AppThemeMode
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.core.designsystem.theme.AppThemeController
import com.openwakeup.schedule.core.format.AppDateFormatter
import com.openwakeup.schedule.core.format.AppDatePattern
import com.openwakeup.schedule.core.format.AppLocaleResolver
import com.openwakeup.schedule.databinding.ActivitySettingsBinding
import com.openwakeup.schedule.feature.settings.CategoryItem
import com.openwakeup.schedule.feature.settings.HeaderItem
import com.openwakeup.schedule.feature.settings.HorizontalItem
import com.openwakeup.schedule.feature.settings.SettingsItem
import com.openwakeup.schedule.feature.settings.SettingsListAdapter
import com.openwakeup.schedule.feature.settings.SettingsRestoreDialog
import com.openwakeup.schedule.feature.settings.SwitchItem
import com.openwakeup.schedule.feature.settings.VerticalItem
import com.openwakeup.schedule.feature.settings.appearance.TableConfigActivity
import com.openwakeup.schedule.platform.appwidget.WidgetUpdateCoordinator
import com.openwakeup.schedule.platform.reminder.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 全局设置页。
 *
 * 页面包含主题与课表外观入口、显示与通知、后台运行等真正随应用全局生效的配置。
 * 每张课表独立的显示项目和日期调课不放在这里，统一由课表设置页管理。
 */
class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { Prefs.get(this) }
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var adapter: SettingsListAdapter
    private var pendingReminderMinutes: Int = 0

    /** 仅在用户把课前提醒从关闭切换为启用时申请通知权限。 */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                applyReminderMinutes(pendingReminderMinutes)
            } else {
                pendingReminderMinutes = 0
                applyReminderMinutes(0)
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_SHORT)
                    .show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
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
        adapter.onItemCheckListener = ::onBooleanChanged
        adapter.onItemLongClickListener = { item, _ -> requestRestoreDefault(item) }
        render()
    }

    /** 构建“显示与通知 / 其他”分组。 */
    private fun buildItems(): List<CategoryItem> = listOf(
        CategoryItem(
            listOf(
                HeaderItem(R.string.widget_tip_header),
                VerticalItem(
                    R.string.restore_default_title,
                    getString(R.string.restore_settings_hint),
                    leadingIconRes = R.drawable.ms_info_24,
                ),
            ),
        ),
        CategoryItem(
            listOf(
                HeaderItem(R.string.setting_theme),
                HorizontalItem(
                    R.string.theme_mode_title,
                    themeModeLabel(prefs.themeMode),
                    R.drawable.ms_contrast_24,
                    showChevron = true,
                ),
                HorizontalItem(
                    R.string.setting_app_language,
                    appLocaleLabel(prefs.appLocale),
                    R.drawable.ms_language_24,
                    showChevron = true,
                ),
                HorizontalItem(
                    R.string.setting_date_format,
                    AppDateFormatter.formatFull(LocalDate.now(), prefs.dateFormat),
                    R.drawable.ms_calendar_month_24,
                    showChevron = true,
                ),
                HorizontalItem(
                    R.string.setting_schedule_appearance,
                    getString(R.string.click_here_to_change),
                    R.drawable.ms_palette_24,
                    showChevron = true,
                ),
                SwitchItem(
                    R.string.setting_dynamic_colors,
                    prefs.dynamicColors,
                    leadingIconRes = R.drawable.ms_wallpaper_24,
                ),
                SwitchItem(
                    R.string.setting_blank_area,
                    prefs.scheduleBlankArea,
                    getString(R.string.desc_blank_area),
                    R.drawable.ms_space_bar_24,
                ),
            ),
        ),
        CategoryItem(
            listOf(
                HeaderItem(R.string.setting_notifications),
                HorizontalItem(
                    R.string.reminder_lead,
                    reminderLabel(prefs.reminderMinutes),
                    R.drawable.ms_alarm_24,
                ),
            ),
        ),
        CategoryItem(
            listOf(
                HeaderItem(R.string.setting_other),
                VerticalItem(
                    R.string.setting_auto_launch,
                    getString(R.string.desc_auto_launch),
                    leadingIconRes = R.drawable.ms_rocket_launch_24,
                    showChevron = true,
                ),
                VerticalItem(
                    R.string.setting_ingore_battery_opt,
                    getString(R.string.desc_ignore_battery),
                    leadingIconRes = R.drawable.ms_battery_saver_24,
                    showChevron = true,
                ),
                VerticalItem(
                    R.string.setting_clear_webview_cache,
                    "",
                    leadingIconRes = R.drawable.ms_delete_sweep_24,
                ),
            ),
        ),
    )

    /** 重新提交全局设置模型。 */
    private fun render() {
        adapter.submit(buildItems())
    }

    /**
     * 处理会打开子页面、系统设置或操作弹窗的项目。
     *
     * @param item 被点击的设置项
     * @param position 列表位置，本页不依赖该值
     */
    private fun onItemClicked(item: SettingsItem, position: Int) {
        when (item.name) {
            R.string.theme_mode_title -> showThemeDialog()
            R.string.setting_app_language -> showAppLanguageDialog()
            R.string.setting_date_format -> showDateFormatDialog()
            R.string.setting_schedule_appearance -> startActivity(
                Intent(
                    this,
                    TableConfigActivity::class.java
                )
            )

            R.string.reminder_lead -> showReminderDialog()
            R.string.setting_auto_launch -> openApplicationSettings()
            R.string.setting_ingore_battery_opt -> openBatterySettings()
            R.string.setting_clear_webview_cache -> clearWebViewCache()
        }
    }

    /**
     * 持久化所有全局布尔配置。
     *
     * @param item 发生变化的设置项
     * @param checked 新的状态
     */
    private fun onBooleanChanged(item: SettingsItem, checked: Boolean) {
        when (item.name) {
            R.string.setting_dynamic_colors -> prefs.dynamicColors = checked
            R.string.setting_blank_area -> prefs.scheduleBlankArea = checked
        }
        render()
    }

    /**
     * 判断全局设置项是否有确定默认值，并在恢复前展示统一确认弹窗。
     *
     * @param item 长按达到 1.6 秒的设置项
     * @return 能恢复时返回 true，用于阻止抬手后的普通点击
     */
    private fun requestRestoreDefault(item: SettingsItem): Boolean {
        val supported = item.name in setOf(
            R.string.theme_mode_title,
            R.string.setting_app_language,
            R.string.setting_date_format,
            R.string.setting_dynamic_colors,
            R.string.setting_blank_area,
            R.string.reminder_lead,
        )
        if (!supported) return false
        SettingsRestoreDialog.show(this, item.name) { restoreDefault(item) }
        return true
    }

    /** 把单个全局设置项恢复为 [Prefs] 声明的默认值。 */
    private fun restoreDefault(item: SettingsItem) {
        when (item.name) {
            R.string.theme_mode_title -> {
                prefs.themeMode = AppDefaults.Global.THEME_MODE
                AppThemeController.apply(AppDefaults.Global.THEME_MODE)
                // 主题变化后立即让桌面小部件重新读取对应主题的纯色背景与标题颜色。
                refreshWidgets()
            }

            R.string.setting_app_language -> {
                AppLocaleResolver.persistAndApply(this, AppDefaults.Global.APP_LOCALE)
                refreshWidgets()
            }

            R.string.setting_date_format -> {
                prefs.dateFormat = AppDefaults.Global.DATE_FORMAT
                refreshWidgets()
                render()
            }

            R.string.setting_dynamic_colors -> {
                prefs.dynamicColors = AppDefaults.Global.DYNAMIC_COLORS
                render()
            }

            R.string.setting_blank_area -> {
                prefs.scheduleBlankArea = AppDefaults.Global.SCHEDULE_BLANK_AREA
                render()
            }

            R.string.reminder_lead -> applyReminderMinutes(AppDefaults.Global.REMINDER_MINUTES)
        }
    }

    /** 展示浅色、暗色、跟随系统三个主题选项。 */
    private fun showThemeDialog() {
        val modes = arrayOf(AppThemeMode.LIGHT, AppThemeMode.DARK, AppThemeMode.FOLLOW_SYSTEM)
        val labels = modes.map(::themeModeLabel).toTypedArray()
        val checked = modes.indexOf(prefs.themeMode).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.theme_mode_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val selected = modes[which]
                prefs.themeMode = selected
                AppThemeController.apply(selected)
                // Provider 收到广播时会按新主题读取对应颜色；图片背景的标题颜色保持固定值。
                refreshWidgets()
                render()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 展示应用语言选项。
     *
     * 切换后由 AppLocaleResolver 立即生效并持久化；系统语言既非中文也非英文时
     * 回退英文默认资源。
     */
    private fun showAppLanguageDialog() {
        val locales = arrayOf(AppLocale.FOLLOW_SYSTEM, AppLocale.CHINESE, AppLocale.ENGLISH)
        val labels = locales.map(::appLocaleLabel).toTypedArray()
        val checked = locales.indexOf(prefs.appLocale).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setting_app_language)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                AppLocaleResolver.persistAndApply(this, locales[which])
                refreshWidgets()
                render()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 展示全局日期显示格式选项。
     *
     * 选择后持久化并立即刷新当前页面与所有桌面小组件。
     */
    private fun showDateFormatDialog() {
        val today = LocalDate.now()
        val labels =
            AppDatePattern.ALL.map { AppDateFormatter.label(this, it, today) }.toTypedArray()
        val checked = AppDatePattern.ALL.indexOf(prefs.dateFormat).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setting_date_format)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.dateFormat = AppDatePattern.ALL[which]
                render()
                // 日期格式变化需要同步到所有已添加的桌面小组件。
                refreshWidgets()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 展示课前提醒提前量选项。 */
    private fun showReminderDialog() {
        val values = intArrayOf(0, 5, 10, 15, 30)
        val labels = values.map(::reminderLabel).toTypedArray()
        val checked = values.indexOf(prefs.reminderMinutes).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reminder_lead)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val selectedMinutes = values[which]
                if (selectedMinutes > 0 && ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingReminderMinutes = selectedMinutes
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    applyReminderMinutes(selectedMinutes)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 持久化提醒提前量，并重新安排或取消系统闹钟。 */
    private fun applyReminderMinutes(minutes: Int) {
        prefs.reminderMinutes = minutes
        pendingReminderMinutes = 0
        lifecycleScope.launch(Dispatchers.IO) {
            ReminderScheduler.rearrange(this@SettingsActivity)
        }
        render()
    }

    /** 只刷新真实存在的桌面小组件；没有实例时不会发送广播或读取数据库。 */
    private fun refreshWidgets() {
        // 主题和语言切换可能立即重建 Activity，直接发送轻量广播可避免 lifecycleScope 被取消。
        WidgetUpdateCoordinator.refreshAllInstalled(applicationContext)
    }

    /** 打开应用详情页，供用户配置后台运行与自启权限。 */
    private fun openApplicationSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            },
        )
    }

    /** 打开系统电池优化设置。 */
    private fun openBatterySettings() {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /**
     * 完整清除内置浏览器的站点数据并给出操作反馈。
     *
     * 除 HTTP 缓存与历史外，同时删除持久/会话 Cookie、LocalStorage、
     * IndexedDB、表单数据、HTTP Auth 凭据、TLS 偏好与定位授权。
     */
    private fun clearWebViewCache() {
        WebStorage.getInstance().deleteAllData()
        GeolocationPermissions.getInstance().clearAll()
        WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
        WebView(this).apply {
            clearCache(true)
            clearHistory()
            clearFormData()
            clearSslPreferences()
            destroy()
        }
        CookieManager.getInstance().apply {
            removeSessionCookies(null)
            removeAllCookies {
                flush()
                Toast.makeText(this@SettingsActivity, R.string.cache_cleared, Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    /** 返回主题模式的本地化标签。 */
    private fun themeModeLabel(mode: AppThemeMode): String = when (mode) {
        AppThemeMode.LIGHT -> getString(R.string.theme_mode_light)
        AppThemeMode.DARK -> getString(R.string.theme_mode_dark)
        AppThemeMode.FOLLOW_SYSTEM -> getString(R.string.theme_mode_follow_system)
    }

    /** 返回应用语言的本地化标签。 */
    private fun appLocaleLabel(locale: AppLocale): String = when (locale) {
        AppLocale.FOLLOW_SYSTEM -> getString(R.string.app_language_follow_system)
        AppLocale.CHINESE -> getString(R.string.app_language_chinese)
        AppLocale.ENGLISH -> getString(R.string.app_language_english)
    }

    /** 返回提醒提前量的本地化标签。 */
    private fun reminderLabel(minutes: Int): String =
        if (minutes <= 0) getString(R.string.reminder_disabled)
        else resources.getQuantityString(R.plurals.reminder_minutes_before, minutes, minutes)
}
