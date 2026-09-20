package com.openwakeup.schedule.core.data

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.config.AppDefaults
import com.openwakeup.schedule.core.format.AppDatePattern
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 轻量全局偏好（当前选中课表等；每表样式配置在 TableEntity 字段中）。
 *
 * 进程内单例：`currentTableIdFlow` 是内存 StateFlow，若每个持有者各建一份，
 * 在 A 界面切表只会改 A 自己的 StateFlow，B 界面（如主课表页）永远收不到通知。
 * 必须经 [Prefs.get] 取同一实例。
 */
class Prefs private constructor(context: Context) {

    /** 应用级 Context 用于解析跟随系统主题时的当前深浅模式。 */
    private val appContext = context.applicationContext

    private val sp: SharedPreferences =
        appContext.getSharedPreferences("openwakeup_settings", Context.MODE_PRIVATE)

    private val _currentTableId =
        MutableStateFlow(sp.getLong(KEY_CURRENT_TABLE, AppDefaults.Global.CURRENT_TABLE_ID))

    /** 当前选中课表 id（可观察）；0 表示未选择（回退到首张表） */
    val currentTableIdFlow: StateFlow<Long> = _currentTableId

    /** 当前选中的课表 id；0 表示未选择 */
    var currentTableId: Long
        get() = _currentTableId.value
        set(value) {
            _currentTableId.value = value
            sp.edit().putLong(KEY_CURRENT_TABLE, value).apply()
        }

    /** 课前提醒提前分钟数（0=关闭） */
    var reminderMinutes: Int
        get() = sp.getInt(KEY_REMINDER_MINUTES, AppDefaults.Global.REMINDER_MINUTES)
        set(value) = sp.edit().putInt(KEY_REMINDER_MINUTES, value).apply()

    /**
     * 全局主题模式。
     *
     * 默认固定为浅色；暗色与跟随系统的值会正常持久化，待暗色资源完成后即可直接启用。
     */
    var themeMode: AppThemeMode
        get() = AppThemeMode.fromStoredValue(
            sp.getInt(
                KEY_THEME_MODE,
                AppDefaults.Global.THEME_MODE.storedValue
            )
        )
        set(value) = sp.edit().putInt(KEY_THEME_MODE, value.storedValue).apply()

    /**
     * 应用显示语言。
     *
     * 值由 [com.openwakeup.schedule.core.format.AppLocaleResolver] 负责生效；
     * 默认跟随系统。
     */
    var appLocale: AppLocale
        get() = AppLocale.fromStoredValue(
            sp.getInt(
                KEY_APP_LOCALE,
                AppDefaults.Global.APP_LOCALE.storedValue
            )
        )
        set(value) = sp.edit().putInt(KEY_APP_LOCALE, value.storedValue).apply()

    /**
     * 全局日期显示格式（可观察）。
     *
     * 值域限制为 [AppDatePattern] 白名单；[dateFormatFlow] 让主界面、表头和设置页
     * 能在格式变化时立即重绘，无需重启应用。
     */
    private val _dateFormat = MutableStateFlow(readDateFormat())

    /** 当前全局日期格式；收集方在用户切换格式后立即收到新值。 */
    val dateFormatFlow: StateFlow<AppDatePattern> = _dateFormat

    /** 当前全局日期显示格式。 */
    var dateFormat: AppDatePattern
        get() = _dateFormat.value
        set(value) {
            sp.edit().putString(KEY_DATE_FORMAT, value.id).apply()
            _dateFormat.value = value
        }

    /**
     * 读取全局日期格式并执行一次性兼容迁移。
     *
     * 旧版本把格式存在小组件专属 key `widget_date_format`（值为 pattern 字符串）；
     * 新 key `date_format` 不存在而旧 key 存在时，把旧值映射为枚举 ID 写入新 key
     * 并删除旧 key，保证用户升级后格式选择无损。
     */
    private fun readDateFormat(): AppDatePattern {
        val existing = sp.getString(KEY_DATE_FORMAT, null)
        if (existing != null) return AppDatePattern.fromId(existing)
        val legacy = sp.getString(KEY_WIDGET_DATE_FORMAT_LEGACY, null)
            ?: return AppDefaults.Global.DATE_FORMAT
        val migrated = AppDatePattern.fromId(legacy)
        sp.edit()
            .putString(KEY_DATE_FORMAT, migrated.id)
            .remove(KEY_WIDGET_DATE_FORMAT_LEGACY)
            .apply()
        return migrated
    }

    /** 是否根据桌面壁纸启用动态主题色。 */
    var dynamicColors: Boolean
        get() = sp.getBoolean(KEY_DYNAMIC_COLORS, AppDefaults.Global.DYNAMIC_COLORS)
        set(value) = sp.edit().putBoolean(KEY_DYNAMIC_COLORS, value).apply()

    /** 主课表底部是否显示额外留白区域。 */
    var scheduleBlankArea: Boolean
        get() = sp.getBoolean(KEY_SCHEDULE_BLANK_AREA, AppDefaults.Global.SCHEDULE_BLANK_AREA)
        set(value) = sp.edit().putBoolean(KEY_SCHEDULE_BLANK_AREA, value).apply()

    /** 小部件固定展示的课表 id；0 表示跟随应用当前课表。 */
    var widgetTableId: Long
        get() = sp.getLong(KEY_WIDGET_TABLE_ID, AppDefaults.Widget.TABLE_ID)
        set(value) = sp.edit().putLong(KEY_WIDGET_TABLE_ID, value).apply()

    /** 小部件是否显示整体背景。 */
    var widgetShowBackground: Boolean
        get() = sp.getBoolean(KEY_WIDGET_SHOW_BACKGROUND, AppDefaults.Widget.SHOW_BACKGROUND)
        set(value) = sp.edit().putBoolean(KEY_WIDGET_SHOW_BACKGROUND, value).apply()

    /** 当前实际主题对应的默认纯色背景。 */
    val widgetDefaultBackground: String
        get() = if (usesDarkTheme()) AppDefaults.Widget.BACKGROUND_DARK else AppDefaults.Widget.BACKGROUND_LIGHT

    /**
     * 当前应显示的小部件背景，返回当前主题的纯色值或应用私有图片路径。
     *
     * 纯色值按浅色、暗色主题分别保存；切换主题后会自动读取目标主题对应的自定义值，
     * 目标主题没有自定义值时则直接使用该主题默认色。图片路径不区分主题。
     */
    var widgetBackground: String
        get() = if (isWidgetBackgroundImage) {
            sp.getString(KEY_WIDGET_BACKGROUND_IMAGE, "").orEmpty()
        } else {
            sp.getString(currentThemeBackgroundKey(), widgetDefaultBackground)
                ?: widgetDefaultBackground
        }
        set(value) {
            if (value.startsWith("#")) {
                sp.edit()
                    .putString(KEY_WIDGET_BACKGROUND_MODE, WIDGET_BACKGROUND_MODE_COLOR)
                    .putString(currentThemeBackgroundKey(), value)
                    .apply()
            } else {
                // 进入图片模式前固定当前可见标题色，之后切换主题时图片上的标题保持不变。
                val visibleHeaderColor = widgetHeaderColor
                sp.edit()
                    .putString(KEY_WIDGET_BACKGROUND_MODE, WIDGET_BACKGROUND_MODE_IMAGE)
                    .putString(KEY_WIDGET_BACKGROUND_IMAGE, value)
                    .putString(KEY_WIDGET_HEADER_COLOR_IMAGE, visibleHeaderColor)
                    .apply()
            }
        }

    /** 当前小部件是否使用图片背景。 */
    val isWidgetBackgroundImage: Boolean
        get() = sp.getString(
            KEY_WIDGET_BACKGROUND_MODE,
            WIDGET_BACKGROUND_MODE_COLOR,
        ) == WIDGET_BACKGROUND_MODE_IMAGE

    /** 删除当前主题的纯色覆盖值，并把背景模式恢复为纯色。 */
    fun resetWidgetBackground() {
        sp.edit()
            .putString(KEY_WIDGET_BACKGROUND_MODE, WIDGET_BACKGROUND_MODE_COLOR)
            .remove(currentThemeBackgroundKey())
            .apply()
    }

    /** 小部件空视图的展示模式；未配置时直接采用默认文字模式。 */
    var widgetEmptyViewMode: WidgetEmptyViewMode
        get() = WidgetEmptyViewMode.fromStoredValue(
            sp.getString(
                KEY_WIDGET_EMPTY_VIEW_MODE,
                AppDefaults.Widget.EMPTY_VIEW_MODE.storedValue,
            ) ?: AppDefaults.Widget.EMPTY_VIEW_MODE.storedValue,
        )
        set(value) = sp.edit().putString(KEY_WIDGET_EMPTY_VIEW_MODE, value.storedValue).apply()

    /** 小部件空视图自定义图片的应用私有路径。 */
    var widgetEmptyImage: String
        get() = sp.getString(KEY_WIDGET_EMPTY_IMAGE, AppDefaults.Widget.EMPTY_IMAGE)
            ?: AppDefaults.Widget.EMPTY_IMAGE
        set(value) = sp.edit().putString(KEY_WIDGET_EMPTY_IMAGE, value).apply()

    /** 当天没有课程时显示的自定义文案；未配置时跟随当前应用语言。 */
    var widgetEmptyTodayText: String
        get() = widgetEmptyTodayTextOverride
            ?.takeIf { value -> value.isNotBlank() }
            ?: appContext.getString(R.string.widget_empty_today)
        set(value) = sp.edit().putString(KEY_WIDGET_EMPTY_TODAY_TEXT, value).apply()

    /**
     * 当天空视图文案的原始覆盖值。
     *
     * `null` 表示没有用户覆盖，渲染时继续跟随应用语言；该语义供备份精确保存和恢复，
     * 不应使用 [widgetEmptyTodayText] 的本地化展示结果代替。
     */
    var widgetEmptyTodayTextOverride: String?
        get() = sp.getString(KEY_WIDGET_EMPTY_TODAY_TEXT, null)
        set(value) {
            val editor = sp.edit()
            if (value == null) editor.remove(KEY_WIDGET_EMPTY_TODAY_TEXT)
            else editor.putString(KEY_WIDGET_EMPTY_TODAY_TEXT, value)
            editor.apply()
        }

    /** 第二天没有课程时显示的自定义文案；未配置时跟随当前应用语言。 */
    var widgetEmptyTomorrowText: String
        get() = widgetEmptyTomorrowTextOverride
            ?.takeIf { value -> value.isNotBlank() }
            ?: appContext.getString(R.string.widget_empty_tomorrow)
        set(value) = sp.edit().putString(KEY_WIDGET_EMPTY_TOMORROW_TEXT, value).apply()

    /**
     * 次日空视图文案的原始覆盖值；`null` 表示继续使用随语言变化的默认文案。
     */
    var widgetEmptyTomorrowTextOverride: String?
        get() = sp.getString(KEY_WIDGET_EMPTY_TOMORROW_TEXT, null)
        set(value) {
            val editor = sp.edit()
            if (value == null) editor.remove(KEY_WIDGET_EMPTY_TOMORROW_TEXT)
            else editor.putString(KEY_WIDGET_EMPTY_TOMORROW_TEXT, value)
            editor.apply()
        }

    /** 将空视图的模式、图片和两条文案整体恢复为初始状态。 */
    fun resetWidgetEmptyView() {
        sp.edit()
            .putString(KEY_WIDGET_EMPTY_VIEW_MODE, AppDefaults.Widget.EMPTY_VIEW_MODE.storedValue)
            .putString(KEY_WIDGET_EMPTY_IMAGE, AppDefaults.Widget.EMPTY_IMAGE)
            // 删除文案键可让恢复后的默认文字继续跟随应用语言切换。
            .remove(KEY_WIDGET_EMPTY_TODAY_TEXT)
            .remove(KEY_WIDGET_EMPTY_TOMORROW_TEXT)
            .apply()
    }

    /** 小部件标题区域开关。 */
    var widgetShowHeader: Boolean
        get() = sp.getBoolean(KEY_WIDGET_SHOW_HEADER, AppDefaults.Widget.SHOW_HEADER)
        set(value) = sp.edit().putBoolean(KEY_WIDGET_SHOW_HEADER, value).apply()

    /** 小部件日期标题开关。 */
    var widgetShowDate: Boolean
        get() = sp.getBoolean(KEY_WIDGET_SHOW_DATE, AppDefaults.Widget.SHOW_DATE)
        set(value) = sp.edit().putBoolean(KEY_WIDGET_SHOW_DATE, value).apply()

    /** 小部件设置与翻页按钮开关。 */
    var widgetShowButtons: Boolean
        get() = sp.getBoolean(KEY_WIDGET_SHOW_BUTTONS, AppDefaults.Widget.SHOW_BUTTONS)
        set(value) = sp.edit().putBoolean(KEY_WIDGET_SHOW_BUTTONS, value).apply()

    /** 当前主题下“首次创建/恢复默认”时采用的标题颜色。 */
    val widgetDefaultHeaderColor: String
        get() = if (usesDarkTheme()) AppDefaults.Widget.HEADER_COLOR_DARK else AppDefaults.Widget.HEADER_COLOR_LIGHT

    /**
     * 小部件标题文字颜色。
     *
     * 纯色背景下按浅色、暗色主题分别读取和保存；图片背景下读取固定值，使主题切换不会
     * 改变图片上的标题颜色。用户在图片背景下选色时仍会记录当前主题的值，之后切回该
     * 主题的纯色背景时可以继续使用。
     */
    var widgetHeaderColor: String
        get() = if (isWidgetBackgroundImage) {
            sp.getString(KEY_WIDGET_HEADER_COLOR_IMAGE, widgetDefaultHeaderColor)
                ?: widgetDefaultHeaderColor
        } else {
            sp.getString(currentThemeHeaderColorKey(), widgetDefaultHeaderColor)
                ?: widgetDefaultHeaderColor
        }
        set(value) {
            val editor = sp.edit().putString(currentThemeHeaderColorKey(), value)
            if (isWidgetBackgroundImage) {
                // 图片模式的可见颜色单独固定，避免浅色/暗色切换改变图片上的标题。
                editor.putString(KEY_WIDGET_HEADER_COLOR_IMAGE, value)
            }
            editor.apply()
        }

    /** 删除当前主题的标题颜色覆盖值，并恢复该主题默认色。 */
    fun resetWidgetHeaderColor() {
        val editor = sp.edit().remove(currentThemeHeaderColorKey())
        if (isWidgetBackgroundImage) {
            // 图片模式需要同步更新固定值，确保长按恢复后预览立即显示当前主题默认色。
            editor.putString(KEY_WIDGET_HEADER_COLOR_IMAGE, widgetDefaultHeaderColor)
        }
        editor.apply()
    }

    /** 判断当前应用主题是否实际使用暗色语义。 */
    private fun usesDarkTheme(): Boolean = when (themeMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.FOLLOW_SYSTEM ->
            (appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
    }

    /** 返回当前实际主题对应的纯色背景键。 */
    private fun currentThemeBackgroundKey(): String =
        if (usesDarkTheme()) KEY_WIDGET_BACKGROUND_DARK else KEY_WIDGET_BACKGROUND_LIGHT

    /** 返回当前实际主题对应的标题颜色键。 */
    private fun currentThemeHeaderColorKey(): String =
        if (usesDarkTheme()) KEY_WIDGET_HEADER_COLOR_DARK else KEY_WIDGET_HEADER_COLOR_LIGHT

    /** 小部件标题文字大小（sp）。 */
    var widgetHeaderTextSize: Int
        get() = sp.getInt(KEY_WIDGET_HEADER_TEXT_SIZE, AppDefaults.Widget.HEADER_TEXT_SIZE)
        set(value) = sp.edit().putInt(KEY_WIDGET_HEADER_TEXT_SIZE, value).apply()

    /** 课程格子是否显示课程色块。 */
    var widgetShowColor: Boolean
        get() = sp.getBoolean(KEY_WIDGET_SHOW_COLOR, AppDefaults.Widget.SHOW_COLOR)
        set(value) = sp.edit().putBoolean(KEY_WIDGET_SHOW_COLOR, value).apply()

    /** 课程格子不透明度百分比。 */
    var widgetItemAlpha: Int
        get() = sp.getInt(KEY_WIDGET_ITEM_ALPHA, AppDefaults.Widget.ITEM_ALPHA)
        set(value) = sp.edit().putInt(KEY_WIDGET_ITEM_ALPHA, value).apply()

    /** 小部件课程文字大小（sp）。 */
    var widgetTextSize: Int
        get() = sp.getInt(KEY_WIDGET_TEXT_SIZE, AppDefaults.Widget.TEXT_SIZE)
        set(value) = sp.edit().putInt(KEY_WIDGET_TEXT_SIZE, value).apply()

    /** 除课程名之外的节次、时间、地点和教师文字的不透明度百分比。 */
    var widgetSecondaryTextAlpha: Int
        get() = sp.getInt("widget_secondary_text_alpha", AppDefaults.Widget.SECONDARY_TEXT_ALPHA)
            .coerceIn(0, 100)
        set(value) = sp.edit().putInt("widget_secondary_text_alpha", value.coerceIn(0, 100)).apply()

    /** 小部件课程文字颜色；该颜色不随主题切换。 */
    var widgetTextColor: String
        get() = sp.getString(KEY_WIDGET_TEXT_COLOR, AppDefaults.Widget.TEXT_COLOR)
            ?: AppDefaults.Widget.TEXT_COLOR
        set(value) = sp.edit().putString(KEY_WIDGET_TEXT_COLOR, value).apply()

    /** 课程文字颜色是否与格子颜色叠加。 */
    var widgetTextCompose: Boolean
        get() = sp.getBoolean(KEY_WIDGET_TEXT_COMPOSE, AppDefaults.Widget.TEXT_COMPOSE)
        set(value) = sp.edit().putBoolean(KEY_WIDGET_TEXT_COMPOSE, value).apply()

    /** 小部件课程格子边框颜色。 */
    var widgetStrokeColor: String
        get() = sp.getString(KEY_WIDGET_STROKE_COLOR, AppDefaults.Widget.STROKE_COLOR)
            ?: AppDefaults.Widget.STROKE_COLOR
        set(value) = sp.edit().putString(KEY_WIDGET_STROKE_COLOR, value).apply()

    /** 边框颜色是否使用对应课程格子颜色。 */
    var widgetStrokeCompose: Boolean
        get() = sp.getBoolean(KEY_WIDGET_STROKE_COMPOSE, AppDefaults.Widget.STROKE_COMPOSE)
        set(value) = sp.edit().putBoolean(KEY_WIDGET_STROKE_COMPOSE, value).apply()

    /** 是否已经在首次进入小部件设置时展示过后台运行授权说明。 */
    var widgetRuntimePermissionAsked: Boolean
        get() = sp.getBoolean(
            KEY_WIDGET_RUNTIME_PERMISSION_ASKED,
            AppDefaults.Widget.RUNTIME_PERMISSION_ASKED
        )
        set(value) = sp.edit().putBoolean(KEY_WIDGET_RUNTIME_PERMISSION_ASKED, value).apply()

    companion object {
        private const val KEY_CURRENT_TABLE = "current_table_id"
        private const val KEY_REMINDER_MINUTES = "reminder_minutes"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_APP_LOCALE = "app_locale"
        private const val KEY_DATE_FORMAT = "date_format"

        /** 旧版小组件日期格式 key，仅用于一次性迁移读取。 */
        private const val KEY_WIDGET_DATE_FORMAT_LEGACY = "widget_date_format"
        private const val KEY_DYNAMIC_COLORS = "dynamic_colors"
        private const val KEY_SCHEDULE_BLANK_AREA = "schedule_blank_area"
        private const val KEY_WIDGET_TABLE_ID = "widget_table_id"
        private const val KEY_WIDGET_SHOW_BACKGROUND = "widget_show_background"
        private const val KEY_WIDGET_BACKGROUND_MODE = "widget_background_mode"
        private const val KEY_WIDGET_BACKGROUND_IMAGE = "widget_background_image"
        private const val KEY_WIDGET_BACKGROUND_LIGHT = "widget_background_light"
        private const val KEY_WIDGET_BACKGROUND_DARK = "widget_background_dark"
        private const val KEY_WIDGET_EMPTY_VIEW_MODE = "widget_empty_view_mode"
        private const val KEY_WIDGET_EMPTY_IMAGE = "widget_empty_image"
        private const val KEY_WIDGET_EMPTY_TODAY_TEXT = "widget_empty_today_text"
        private const val KEY_WIDGET_EMPTY_TOMORROW_TEXT = "widget_empty_tomorrow_text"
        private const val KEY_WIDGET_SHOW_HEADER = "widget_show_header"
        private const val KEY_WIDGET_SHOW_DATE = "widget_show_date"
        private const val KEY_WIDGET_SHOW_BUTTONS = "widget_show_buttons"
        private const val KEY_WIDGET_HEADER_COLOR_LIGHT = "widget_header_color_light"
        private const val KEY_WIDGET_HEADER_COLOR_DARK = "widget_header_color_dark"
        private const val KEY_WIDGET_HEADER_COLOR_IMAGE = "widget_header_color_image"
        private const val KEY_WIDGET_HEADER_TEXT_SIZE = "widget_header_text_size"
        private const val KEY_WIDGET_SHOW_COLOR = "widget_show_color"
        private const val KEY_WIDGET_ITEM_ALPHA = "widget_item_alpha"
        private const val KEY_WIDGET_TEXT_SIZE = "widget_text_size"
        private const val KEY_WIDGET_TEXT_COLOR = "widget_text_color"
        private const val KEY_WIDGET_TEXT_COMPOSE = "widget_text_compose"
        private const val KEY_WIDGET_STROKE_COLOR = "widget_stroke_color"
        private const val KEY_WIDGET_STROKE_COMPOSE = "widget_stroke_compose"
        private const val KEY_WIDGET_RUNTIME_PERMISSION_ASKED = "widget_runtime_permission_asked"

        /** 小部件使用纯色背景时保存的稳定模式值。 */
        private const val WIDGET_BACKGROUND_MODE_COLOR = "color"

        /** 小部件使用图片背景时保存的稳定模式值。 */
        private const val WIDGET_BACKGROUND_MODE_IMAGE = "image"

        @Volatile
        private var instance: Prefs? = null

        /** 取进程内唯一实例（切表通知依赖同一 StateFlow） */
        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * 应用支持的主题选择。
 *
 * @property storedValue 写入 SharedPreferences 的稳定值，枚举调整顺序时也不会破坏旧数据
 */
enum class AppThemeMode(val storedValue: Int) {
    LIGHT(0),
    DARK(1),
    FOLLOW_SYSTEM(2);

    companion object {
        /**
         * 将持久化整数安全转换为主题模式。
         *
         * @param value SharedPreferences 中读取出的整数
         * @return 对应主题；未知值回退为默认浅色
         */
        fun fromStoredValue(value: Int): AppThemeMode =
            entries.firstOrNull { it.storedValue == value } ?: LIGHT
    }
}

/**
 * 应用显示语言的选择。
 *
 * @property storedValue 写入 SharedPreferences 的稳定值，枚举调整顺序时也不会破坏旧数据
 */
enum class AppLocale(val storedValue: Int) {
    FOLLOW_SYSTEM(0),
    CHINESE(1),
    ENGLISH(2);

    companion object {
        /**
         * 将持久化整数安全转换为语言选择。
         *
         * @param value SharedPreferences 中读取出的整数
         * @return 对应语言；未知值回退为跟随系统
         */
        fun fromStoredValue(value: Int): AppLocale =
            entries.firstOrNull { it.storedValue == value } ?: FOLLOW_SYSTEM
    }
}

/**
 * 小部件无课程时可选的内容类型。
 *
 * @property storedValue 写入 SharedPreferences 的稳定字符串，枚举顺序调整不会改变已有设置
 */
enum class WidgetEmptyViewMode(val storedValue: String) {
    /** 仅显示当天或第二天对应的自定义文字。 */
    TEXT("text"),

    /** 仅显示用户从系统照片选择器导入的图片。 */
    IMAGE("image");

    companion object {
        /**
         * 将持久化字符串转换为空视图模式。
         *
         * @param value SharedPreferences 中保存的稳定值
         * @return 对应模式；未知值回退为文字模式
         */
        fun fromStoredValue(value: String): WidgetEmptyViewMode =
            entries.firstOrNull { mode -> mode.storedValue == value } ?: TEXT
    }
}
