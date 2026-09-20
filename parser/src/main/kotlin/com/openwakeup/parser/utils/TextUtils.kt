package com.openwakeup.parser.utils

/** 多个学校 Parser 共用的基础中文时间文本转换。 */
internal object TextUtils {

    /**
     * 将中文或数字星期转换为 1～7。
     *
     * @param text 可包含 `星期`、`周` 前缀的星期文本
     * @return 1 表示周一、7 表示周日
     * @throws IllegalArgumentException 文本中没有明确的合法星期
     */
    fun requireDay(text: String): Int {
        val normalized = text.trim().removePrefix("星期").removePrefix("周").trim()
        val direct = normalized.toIntOrNull()
        val value = direct ?: when (normalized.uppercase()) {
            "MON", "MONDAY" -> 1
            "TUE", "TUESDAY" -> 2
            "WED", "WEDNESDAY" -> 3
            "THU", "THURSDAY" -> 4
            "FRI", "FRIDAY" -> 5
            "SAT", "SATURDAY" -> 6
            "SUN", "SUNDAY" -> 7
            else -> when {
                normalized == "一" || normalized.contains("星期一") || normalized.contains("周一") -> 1
                normalized == "二" || normalized.contains("星期二") || normalized.contains("周二") -> 2
                normalized == "三" || normalized.contains("星期三") || normalized.contains("周三") -> 3
                normalized == "四" || normalized.contains("星期四") || normalized.contains("周四") -> 4
                normalized == "五" || normalized.contains("星期五") || normalized.contains("周五") -> 5
                normalized == "六" || normalized.contains("星期六") || normalized.contains("周六") -> 6
                // 部分旧教务把周日写成“星期七”；与“日、天、7”统一映射为第七天。
                normalized == "七" || normalized == "日" || normalized == "天" ||
                        normalized.contains("星期七") || normalized.contains("星期日") ||
                        normalized.contains("星期天") || normalized.contains("周七") ||
                        normalized.contains("周日") || normalized.contains("周天") -> 7

                else -> -1
            }
        }
        require(value in 1..7) { "星期字段无法识别：$text" }
        return value
    }

    /**
     * 从仅包含一个单值或一个范围的文本中读取正整数边界。
     *
     * @param text 例如 `1-16周`、`[3]` 或 `5`
     * @param fieldName 错误信息中的字段名称
     * @return 单值会转换为首尾相同的闭区间
     * @throws IllegalArgumentException 数字缺失、包含超过两个数字或范围倒置
     */
    fun requirePositiveRange(text: String, fieldName: String): IntRange {
        val values = NUMBER_PATTERN.findAll(text).map { it.value.toInt() }.toList()
        require(values.size in 1..2) { "$fieldName 必须包含一个单值或一组范围：$text" }
        val start = values.first()
        val end = values.last()
        require(start > 0 && end >= start) { "$fieldName 范围无效：$text" }
        return start..end
    }

    private val NUMBER_PATTERN = Regex("""\d+""")
}
