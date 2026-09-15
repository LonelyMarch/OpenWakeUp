package com.openwakeup.schedule.core.format

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.openwakeup.schedule.core.data.AppLocale
import com.openwakeup.schedule.core.data.Prefs

/**
 * 应用语言偏好的唯一生效入口。
 *
 * 基于 AppCompat 提供的 per-app language API（`AppCompatDelegate.setApplicationLocales`），
 * 不引入额外语言切换依赖；系统语言既非中文也非英文时，默认英文资源自动回退。
 */
object AppLocaleResolver {

    /**
     * 在 Application 启动时恢复持久化的语言选择。
     *
     * @param locale 用户保存的应用语言偏好
     */
    fun apply(locale: AppLocale) {
        AppCompatDelegate.setApplicationLocales(
            when (locale) {
                // 跟随系统：清空应用级覆盖，交还系统 per-app 语言或系统语言
                AppLocale.FOLLOW_SYSTEM -> LocaleListCompat.getEmptyLocaleList()
                AppLocale.CHINESE -> LocaleListCompat.forLanguageTags("zh")
                AppLocale.ENGLISH -> LocaleListCompat.forLanguageTags("en")
            },
        )
    }

    /**
     * 持久化并立即应用新的语言选择。
     *
     * @param context 任意上下文
     * @param locale 用户选择的应用语言
     */
    fun persistAndApply(context: Context, locale: AppLocale) {
        Prefs.get(context).appLocale = locale
        apply(locale)
    }
}
