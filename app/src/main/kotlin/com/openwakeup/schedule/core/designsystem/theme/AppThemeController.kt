package com.openwakeup.schedule.core.designsystem.theme

import androidx.appcompat.app.AppCompatDelegate
import com.openwakeup.schedule.core.data.AppThemeMode

/**
 * 应用主题模式到 AppCompat 夜间模式的唯一映射入口。
 *
 * LIGHT/DARK/FOLLOW_SYSTEM 三种模式分别映射 AppCompat 对应常量；
 * 暗色语义色由 values-night 的 md_theme_* token 提供，桌面小组件不受
 * AppCompat 夜间覆盖影响（其默认配色在固定背景素材上两种模式均可读）。
 */
object AppThemeController {

    /**
     * 应用指定主题模式。
     *
     * @param mode 用户在全局设置中选择的主题模式
     */
    fun apply(mode: AppThemeMode) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                AppThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                AppThemeMode.FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }
}
