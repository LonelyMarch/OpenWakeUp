package com.openwakeup.schedule.core.format

/**
 * 全局日期显示格式的安全白名单。
 *
 * SharedPreferences 只保存稳定 ID，而不是任意 pattern 字符串；这样损坏值或旧版本
 * 值都能安全回退到默认格式，避免 [java.time.format.DateTimeFormatter] 因非法
 * 格式在桌面小部件刷新进程中崩溃。
 *
 * @property id 写入 SharedPreferences 的稳定标识，调整枚举顺序也不会破坏旧数据
 * @property fullPattern 宽度充足位置（主页、设置页、小组件标题）使用的完整格式
 * @property compactPattern 狭窄位置（周表头七列）使用的紧凑格式
 */
enum class AppDatePattern(val id: String, val fullPattern: String, val compactPattern: String) {
    YMD_DASH("yyyy-MM-dd", "yyyy-MM-dd", "M-d"),
    YMD_SLASH("yyyy/M/d", "yyyy/M/d", "M/d"),
    YMD_DOT("yyyy.MM.dd", "yyyy.MM.dd", "M.d"),
    MD_DASH("M-d", "M-d", "M-d"),
    MD_SLASH("M/d", "M/d", "M/d"),
    MD_DOT("M.d", "M.d", "M.d");

    companion object {

        /** 旧版本与未知值回退的默认格式（横线完整日期）。 */
        val DEFAULT: AppDatePattern = YMD_DASH

        /** 设置页按声明顺序展示可选格式。 */
        val ALL: List<AppDatePattern> = entries.toList()

        /**
         * 将持久化字符串安全转换为格式枚举。
         *
         * @param id SharedPreferences 中保存的稳定 ID
         * @return 对应格式；未知或损坏值回退默认格式
         */
        fun fromId(id: String): AppDatePattern =
            ALL.firstOrNull { it.id == id } ?: DEFAULT
    }
}
