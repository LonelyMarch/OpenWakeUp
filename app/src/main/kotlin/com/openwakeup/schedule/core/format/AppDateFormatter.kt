package com.openwakeup.schedule.core.format

import android.content.Context
import com.openwakeup.schedule.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 全应用用户可见绝对日期的唯一格式化入口。
 *
 * 使用方只允许传入 [LocalDate] 与目标场景，由本对象按当前格式偏好产出文本；
 * 星期与月份名称从当前应用 locale 生成，任何页面不得自行拼接 pattern。
 * Room/备份/iCalendar/闹钟等协议日期继续使用各自的固定格式，不经过本类。
 */
object AppDateFormatter {

    /** 完整格式缓存（按 pattern 复用 DateTimeFormatter，避免表头七列重复构建）。 */
    private val fullCache = mutableMapOf<String, DateTimeFormatter>()

    /** 紧凑格式缓存（周表头等狭窄位置）。 */
    private val compactCache = mutableMapOf<String, DateTimeFormatter>()

    /**
     * 宽度充足位置的完整日期（主页标题、设置页、小组件顶部）。
     *
     * @param date 待格式化的日期
     * @param pattern 当前全局格式偏好
     * @return 完整日期文本
     */
    fun formatFull(date: LocalDate, pattern: AppDatePattern): String =
        date.format(fullFormatter(pattern))

    /**
     * 狭窄位置的紧凑日期（七列周表头）。
     *
     * 紧凑格式由完整格式派生：用户选了年月日就保留月日，选了月日则原样输出，
     * 避免 `yyyy-MM-dd` 被硬塞进七列导致换行溢出。
     *
     * @param date 待格式化的日期
     * @param pattern 当前全局格式偏好
     * @return 紧凑日期文本
     */
    fun formatCompact(date: LocalDate, pattern: AppDatePattern): String =
        date.format(compactFormatter(pattern))

    /**
     * 完整日期 + 本地化星期（开学日期、调课页等需要同时展示星期的位置）。
     *
     * @param date 待格式化的日期
     * @param pattern 当前全局格式偏好
     * @return “完整日期 星期”文本
     */
    fun formatFullWithWeekday(date: LocalDate, pattern: AppDatePattern): String =
        formatFull(date, pattern) + " " + weekdayShort(date)

    /**
     * 短星期名：中文“周一”，英文“Mon”。
     *
     * @param date 目标日期
     * @return 当前 locale 的短星期文本
     */
    fun weekdayShort(date: LocalDate): String =
        date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())

    /**
     * 完整星期名：中文“星期一”，英文“Monday”。
     *
     * @param date 目标日期
     * @return 当前 locale 的完整星期文本
     */
    fun weekdayFull(date: LocalDate): String =
        date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())

    /**
     * 周视图狭窄列头使用的星期单字形式：中文为“一”，英文为“M”。
     *
     * @param date 目标日期
     * @return 当前 locale 的最窄星期文本，不包含中文“周”前缀
     */
    fun weekdayNarrow(date: LocalDate): String =
        date.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault())

    /**
     * 设置弹窗的单行标签：格式骨架 + 当天示例。
     *
     * @param context 用于读取字符串资源的上下文
     * @param pattern 目标格式
     * @param sample 展示用示例日期（默认今天）
     * @return “骨架    示例”文本
     */
    fun label(
        context: Context,
        pattern: AppDatePattern,
        sample: LocalDate = LocalDate.now()
    ): String =
        context.getString(R.string.date_format_label, pattern.id, formatFull(sample, pattern))

    private fun fullFormatter(pattern: AppDatePattern): DateTimeFormatter =
        fullCache.getOrPut(pattern.fullPattern) {
            DateTimeFormatter.ofPattern(pattern.fullPattern, Locale.getDefault())
        }

    private fun compactFormatter(pattern: AppDatePattern): DateTimeFormatter =
        compactCache.getOrPut(pattern.compactPattern) {
            DateTimeFormatter.ofPattern(pattern.compactPattern, Locale.getDefault())
        }
}
