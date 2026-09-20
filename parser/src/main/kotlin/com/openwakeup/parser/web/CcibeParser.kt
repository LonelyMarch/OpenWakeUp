package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.JsonUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * 重庆对外经贸学院 JSON 课表解析器。
 *
 * 主输入必须是包含 `data.calendar` 的学期响应，附加输入按请求顺序对应第 1..N 周响应；
 * 本类只解析调用方已取得的响应，不发起网络请求。
 */
object CcibeParser : Parser {

    /**
     * 将学期响应和逐周响应转换为课程预览。
     *
     * @param input 主响应加按周排列的原始 JSON 响应
     * @return 接口中的全部有效课程
     * @throws ParserException JSON 结构错误、课程字段非法或响应为空
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val primary = Json.parseToJsonElement(input.text).jsonObject
        val data = JsonUtils.requiredObject(primary, "data")
        val calendar = JsonUtils.requiredObject(data, "calendar")
        val allWeek = JsonUtils.requiredPositiveInt(calendar, "allWeek")
        require(allWeek <= MAX_WEEKS) { "重庆对外经贸学院课表周数超过上限：$allWeek" }
        require(input.additionalTexts.size == allWeek) {
            "重庆对外经贸学院逐周响应数量不完整：期望 $allWeek，实际 ${input.additionalTexts.size}"
        }

        val courses = input.additionalTexts.flatMapIndexed { weekIndex, text ->
            val week = weekIndex + 1
            val weekRoot = Json.parseToJsonElement(text).jsonObject
            val weekData = JsonUtils.requiredObject(weekRoot, "data")
            val rows = JsonUtils.requiredArray(weekData, "wdkb")
            rows.mapIndexed { rowIndex, element ->
                val row = element.jsonObject
                val nodeRange = parseNodeRange(JsonUtils.requiredString(row, "jc"), rowIndex + 1)
                val day = parseDay(JsonUtils.requiredString(row, "xqj"), rowIndex + 1)
                CoursePreview(
                    name = JsonUtils.requiredString(row, "kcmc"),
                    teacher = JsonUtils.optionalString(row, "jsxm"),
                    room = JsonUtils.optionalString(row, "jxdd"),
                    day = day,
                    startNode = nodeRange.first,
                    step = nodeRange.last - nodeRange.first + 1,
                    startWeek = week,
                    endWeek = week,
                )
            }
        }
        if (courses.isEmpty()) throw ParserException.empty("重庆对外经贸学院课表响应中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("重庆对外经贸学院课表 JSON 解析失败：${error.message}", error)
    }

    /**
     * 解析单节或首尾节次字段。
     *
     * @param text 例如 `1` 或 `1-2`
     * @param rowNumber 用于错误定位的课程序号
     * @return 闭区间节次
     */
    private fun parseNodeRange(text: String, rowNumber: Int): IntRange {
        val values = text.split('-', '－', '—').map { it.trim() }.filter { it.isNotEmpty() }
        require(values.size in 1..2) { "第 $rowNumber 门课程的节次格式无效：$text" }
        val start = parseChineseOrArabicNumber(values.first())
        val end = parseChineseOrArabicNumber(values.last())
        require(start > 0 && end >= start) { "第 $rowNumber 门课程的节次范围无效：$text" }
        return start..end
    }

    /**
     * 解析中文或阿拉伯数字星期。
     *
     * @param text 原始星期值
     * @param rowNumber 用于错误定位的课程序号
     * @return 1～7 的星期编号
     */
    private fun parseDay(text: String, rowNumber: Int): Int {
        val normalized = text.removePrefix("星期").removePrefix("周").trim()
        val day =
            parseChineseOrArabicNumber(normalized).let { if (normalized == "日" || normalized == "天") 7 else it }
        require(day in 1..7) { "第 $rowNumber 门课程的星期无效：$text" }
        return day
    }

    /**
     * 转换上游会使用的中文数字或阿拉伯数字。
     *
     * @param text 待转换文本
     * @return 转换结果；无法识别时返回 -1
     */
    private fun parseChineseOrArabicNumber(text: String): Int = text.toIntOrNull() ?: when (text) {
        "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5
        "六" -> 6; "七" -> 7; "八" -> 8; "九" -> 9; "十" -> 10
        "十一" -> 11; "十二" -> 12; "十三" -> 13; "十四" -> 14; "十五" -> 15
        "十六" -> 16; "十七" -> 17; "十八" -> 18; "十九" -> 19; "二十" -> 20
        else -> -1
    }

    private const val MAX_WEEKS = 64
}
