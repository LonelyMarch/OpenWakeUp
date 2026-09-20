package com.openwakeup.parser.utils

/**
 * 正方课程时间文本解析结果。
 *
 * @property day 星期，范围为 1～7
 * @property startNode 起始节次
 * @property step 连续节数
 * @property week 周次区间
 */
internal data class ZfCourseTime(
    val day: Int,
    val startNode: Int,
    val step: Int,
    val week: WeekSegment,
)

/**
 * 解析旧版和新版正方页面共同使用的时间描述。
 *
 * 该工具不决定 Parser，也不读取 type。星期、节次或周次缺失时直接失败，调用方负责把异常包装成
 * `ParserException` 并补充课程上下文。
 */
internal object ZfTimeParser {

    /**
     * 将一个课程的时间描述拆成一个或多个无损时间段。
     *
     * @param text 正方页面中的完整时间描述
     * @param fallbackDay 由网格列确定的星期
     * @param fallbackNode 由网格行确定的起始节次
     * @param fallbackStep 由单元格 rowspan 确定的连续节数
     * @return 每个时间段、每个周次片段对应的结构化结果
     * @throws IllegalArgumentException 任一必填时间字段无法可靠识别
     */
    fun parse(
        text: String,
        fallbackDay: Int,
        fallbackNode: Int,
        fallbackStep: Int,
    ): List<ZfCourseTime> {
        require(text.isNotBlank()) { "正方课程时间为空" }
        return text.split(';', '；')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .flatMap { part -> parsePart(part, fallbackDay, fallbackNode, fallbackStep) }
            .also { result -> require(result.isNotEmpty()) { "正方课程时间没有可用时间段：$text" } }
    }

    /** 解析分号分隔后的单个时间段。 */
    private fun parsePart(
        text: String,
        fallbackDay: Int,
        fallbackNode: Int,
        fallbackStep: Int,
    ): List<ZfCourseTime> {
        val day = DAY_PATTERN.find(text)?.groupValues?.get(1)?.let(::parseDay) ?: fallbackDay
        require(day in 1..7) { "无法识别星期：$text" }

        val explicitRange = NODE_RANGE_PATTERN.find(text)
        val explicitSingle = NODE_SINGLE_PATTERN.find(text)
        val nodeList = NODE_LIST_PATTERN.find(text)?.groupValues?.get(1)
            ?.split(',', '，')
            ?.mapNotNull { value -> value.trim().toIntOrNull() }

        val startNode: Int
        val step: Int
        when {
            explicitRange != null -> {
                startNode = explicitRange.groupValues[1].toInt()
                val endNode = explicitRange.groupValues[2].toInt()
                require(endNode >= startNode) { "结束节次小于起始节次：$text" }
                step = endNode - startNode + 1
            }

            !nodeList.isNullOrEmpty() -> {
                val expected = (nodeList.first()..nodeList.last()).toList()
                require(nodeList == expected) { "离散节次无法由连续节数表达：$text" }
                startNode = nodeList.first()
                step = nodeList.size
            }

            explicitSingle != null -> {
                startNode = explicitSingle.groupValues[1].toInt()
                step = 1
            }

            else -> {
                require(fallbackNode > 0) { "正方网格没有可用起始节次：$text" }
                require(fallbackStep > 0) { "正方网格没有可用连续节数：$text" }
                startNode = fallbackNode
                step = SESSION_COUNT_PATTERN.find(text)?.groupValues?.get(1)?.toInt()
                    ?: fallbackStep
            }
        }
        require(startNode > 0 && step > 0) { "节次字段非法：$text" }

        val braceWeeks = BRACE_PATTERN.findAll(text)
            .map { result -> result.groupValues[1].substringBefore('|') }
            .filter { value -> value.contains('周') }
            .toList()
        val weekText = if (braceWeeks.isNotEmpty()) {
            braceWeeks.joinToString(",")
        } else {
            // 新正方没有花括号，先删除星期和节次片段，剩余内容应当只描述周次。
            text.replace(DAY_PATTERN, "")
                .replace(NODE_RANGE_PATTERN, "")
                .replace(NODE_LIST_PATTERN, "")
                .replace(NODE_SINGLE_PATTERN, "")
                .replace(SESSION_COUNT_PATTERN, "")
                .replace(ORPHAN_SESSION_PER_WEEK_PATTERN, "")
                .trim()
        }
        require(weekText.contains('周')) { "无法识别周次：$text" }

        return WeekUtils.parse(weekText).map { week ->
            ZfCourseTime(day, startNode, step, week)
        }
    }

    /** 将中文星期字符转换为 `CoursePreview.day`。 */
    private fun parseDay(value: String): Int = when (value) {
        "一" -> 1
        "二" -> 2
        "三" -> 3
        "四" -> 4
        "五" -> 5
        "六" -> 6
        "日", "天" -> 7
        else -> -1
    }

    private val DAY_PATTERN = Regex("""(?:周|星期)([一二三四五六日天])""")
    private val NODE_RANGE_PATTERN = Regex("""(?:第|\()?\s*(\d{1,2})\s*[-~～—–]\s*(\d{1,2})\s*节""")
    private val NODE_LIST_PATTERN = Regex("""(?:第|\()?\s*((?:\d{1,2}\s*[,，]\s*)+\d{1,2})\s*节""")
    private val NODE_SINGLE_PATTERN = Regex("""第\s*(\d{1,2})\s*节""")
    private val SESSION_COUNT_PATTERN = Regex("""(\d{1,2})\s*节\s*/\s*周""")
    private val ORPHAN_SESSION_PER_WEEK_PATTERN = Regex("""/\s*周""")
    private val BRACE_PATTERN = Regex("""\{([^}]*)}""")
}
