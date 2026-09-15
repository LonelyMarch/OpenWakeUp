package com.openwakeup.schedule.feature.schedule

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.openwakeup.schedule.R

/**
 * 主课表专用的主题颜色解析器。
 *
 * 浅色模式继续尊重每张课表保存的自定义文字色；暗色模式则统一使用 Material 3 Expressive
 * 的 surface/onSurface 语义色，避免旧课表中保存的浅色背景与深色文字破坏夜间可读性。
 */
internal object ScheduleThemeColors {

    /**
     * 解析主课表文字颜色。
     *
     * @param context 当前界面上下文，用于读取夜间模式和 Material 主题颜色
     * @param configuredColor 课表中保存的 `#AARRGGBB` 自定义颜色
     * @return 暗色模式下的 onSurface 颜色；浅色模式下返回已解析的自定义颜色
     */
    @ColorInt
    fun tableTextColor(context: Context, configuredColor: String): Int {
        if (isNightMode(context)) {
            // onSurface 是 M3E 暗色表面的高对比浅色文字，比生硬的纯白更贴合主题色阶。
            return ContextCompat.getColor(context, R.color.md_theme_onSurface)
        }
        return runCatching { Color.parseColor(configuredColor) }.getOrDefault(Color.BLACK)
    }

    /**
     * 获取暗色模式下主课表应使用的表面色。
     *
     * @param context 当前界面上下文
     * @return 夜间模式返回 surfaceContainerLow；浅色模式返回 null，表示沿用课表自定义背景
     */
    @ColorInt
    fun darkTableBackgroundColor(context: Context): Int? =
        if (isNightMode(context)) {
            // surfaceContainerLow 保留了 M3E 的层级感，同时足够深，适合作为大面积课表底色。
            ContextCompat.getColor(context, R.color.md_theme_surfaceContainerLow)
        } else {
            null
        }

    /** 判断当前资源配置是否处于夜间模式。 */
    private fun isNightMode(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
}
