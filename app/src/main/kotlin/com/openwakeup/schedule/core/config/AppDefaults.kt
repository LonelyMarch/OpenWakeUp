package com.openwakeup.schedule.core.config

import com.openwakeup.schedule.core.data.AppLocale
import com.openwakeup.schedule.core.data.AppThemeMode
import com.openwakeup.schedule.core.data.WidgetEmptyViewMode
import com.openwakeup.schedule.core.format.AppDatePattern

/**
 * 应用全部“初次使用 / 新建 / 恢复默认”运行时默认值的唯一来源。
 *
 * Room 实体中的 `@ColumnInfo(defaultValue)` 描述历史数据库结构，不能随产品默认值直接修改；
 * 用户界面、SharedPreferences、实体构造器和恢复操作必须引用本对象，避免再次产生两套数值。
 */
object AppDefaults {

    /** 全局设置默认值。 */
    object Global {
        const val CURRENT_TABLE_ID = 0L
        const val REMINDER_MINUTES = 0
        val THEME_MODE = AppThemeMode.LIGHT
        val APP_LOCALE = AppLocale.FOLLOW_SYSTEM
        val DATE_FORMAT = AppDatePattern.DEFAULT
        const val DYNAMIC_COLORS = true

        /** 新安装或恢复默认设置后，在主课表底部保留便于滚动查看末尾课程的空白区域。 */
        const val SCHEDULE_BLANK_AREA = true
    }

    /** 每张课表的数据、显示选项和外观默认值。 */
    object Table {
        /** 系统允许配置和显示的最大学期周数。 */
        const val MAX_SUPPORTED_WEEKS = 48

        /** 系统允许配置和显示的单日最大节数。 */
        const val MAX_SUPPORTED_NODES = 24

        const val MAX_WEEK = 25

        /** 新建课表及恢复默认设置时采用的单日课程节数。 */
        const val NODES = 12
        const val TIME_TABLE_ID = 1L
        const val CURRENT_WEEK_OVERRIDE = 0
        const val BACKGROUND = ""
        const val TEXT_COLOR = "#FF000000"
        const val COURSE_TEXT_COLOR = "#FFFFFFFF"
        const val ITEM_TEXT_SIZE = 12
        const val ITEM_ALPHA = 50
        const val ITEM_HEIGHT = 64
        const val ITEM_RADIUS = 4
        const val STROKE_COLOR = "#B3FFFFFF"
        const val USE_DOTTED_LINE = false
        const val SHOW_GRID = false
        const val SHOW_TIME_BAR = true
        const val HEADER_TEXT_SIZE = 11
        const val TEXT_COLOR_COMPOSE = false
        const val STROKE_COLOR_COMPOSE = false
        const val SHOW_SATURDAY = true
        const val SHOW_SUNDAY = true
        const val SHOW_TIME = false
        const val SHOW_TEACHER = true
        const val SHOW_LOCATION = true
        const val SHOW_ROOM_PREFIX = true
        const val SHOW_OTHER_WEEK_COURSE = true
        const val OTHER_WEEK_COURSE_ALPHA = 50
        const val ITEM_CENTER_HORIZONTAL = false
        const val ITEM_CENTER_VERTICAL = false
        const val SLOGAN = "OpenWakeUp"
        const val TABLE_ORDER = 0
    }

    /** 桌面小部件设置默认值。 */
    object Widget {
        const val TABLE_ID = 0L
        const val SHOW_BACKGROUND = true

        /** 浅色主题创建小部件时使用的不透明淡紫灰背景。 */
        const val BACKGROUND_LIGHT = "#FFE8E8F4"

        /** 深色主题创建小部件时使用的半透明深暖灰背景。 */
        const val BACKGROUND_DARK = "#61211B1B"

        /** 无主题上下文时使用浅色默认背景；运行时会按首次使用时的主题固化具体颜色。 */
        const val BACKGROUND = BACKGROUND_LIGHT

        /** 空课时默认只展示可编辑文案，不再同时显示占位插图。 */
        val EMPTY_VIEW_MODE = WidgetEmptyViewMode.TEXT
        const val EMPTY_IMAGE = ""
        const val SHOW_HEADER = true
        const val SHOW_DATE = true
        const val SHOW_BUTTONS = true

        /** 浅色主题首次创建小部件时使用的标题颜色。 */
        const val HEADER_COLOR_LIGHT = "#FF222222"

        /** 深色主题首次创建小部件时使用的标题颜色。 */
        const val HEADER_COLOR_DARK = "#FFFFFFFF"

        /** 无主题上下文场景的兼容标题颜色。 */
        const val HEADER_COLOR = HEADER_COLOR_LIGHT
        const val HEADER_TEXT_SIZE = 11
        const val SHOW_COLOR = true
        const val ITEM_ALPHA = 50
        const val TEXT_SIZE = 12

        /** 辅助课程信息默认以 80% 不透明度显示。 */
        const val SECONDARY_TEXT_ALPHA = 80
        const val TEXT_COLOR = "#FFFFFFFF"
        const val TEXT_COMPOSE = false

        /** 小部件课程格子默认使用 50% 不透明度的白色边框。 */
        const val STROKE_COLOR = "#80FFFFFF"
        const val STROKE_COMPOSE = false
        const val RUNTIME_PERMISSION_ASKED = false
    }

    /** 作息时间表默认值。 */
    object Timetable {
        const val SAME_DURATION = true
        const val DURATION_MINUTES = 45
    }
}
