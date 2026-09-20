package com.openwakeup.parser.utils

/**
 * `CoursePreview` 可以表达的一个周次区间。
 *
 * @property startWeek 起始周，必须为正整数
 * @property endWeek 结束周，必须不小于起始周
 * @property type 周类型：0 为每周，1 为单周，2 为双周
 */
internal data class WeekSegment(
    val startWeek: Int,
    val endWeek: Int,
    val type: Int,
)

/**
 * 将外部周次文本或位图转换为可由 `CoursePreview` 无损表达的区间。
 *
 * 工具只处理周集合，不读取学校 type、名称或 URL。压缩结果会在返回前重新展开校验，确保既不
 * 增加也不丢失源周次；无法形成较长区间的离散周会退化为单周记录。
 */
internal object WeekUtils {

    /**
     * 解析课表字段中的周次文本。
     *
     * 支持逗号分隔的单周、连续范围以及单双周范围，例如 `1-16周`、`1-15周(单周)`、
     * `1周,3周,6-8周`。调用方必须只传入周次字段，不得混入星期或节次数字。
     *
     * @param text 完整周次字段
     * @return 与源周集合严格等价的区间列表
     * @throws IllegalArgumentException 文本为空、没有合法周次或周次边界非法
     */
    fun parse(text: String): List<WeekSegment> {
        val normalized = text
            .replace('，', ',')
            .replace('；', ';')
            .replace('、', ',')
            .trim()
        require(normalized.isNotEmpty()) { "周次字段为空" }

        val weeks = sortedSetOf<Int>()
        normalized.split(',', ';').forEach { rawPart ->
            val part = rawPart.trim()
            if (part.isEmpty()) return@forEach

            val numbers = NUMBER_PATTERN.findAll(part)
                .map { result -> result.value.toInt() }
                .toList()
            require(numbers.isNotEmpty()) { "无法识别周次：$part" }
            require(numbers.all { it > 0 }) { "周次必须为正整数：$part" }

            val weekType = when {
                part.contains('单') -> 1
                part.contains('双') -> 2
                else -> 0
            }
            val expanded = if (RANGE_SEPARATOR_PATTERN.containsMatchIn(part)) {
                require(numbers.size == 2) { "周次范围必须且只能包含两个边界：$part" }
                require(numbers[1] >= numbers[0]) { "周次范围结束值小于起始值：$part" }
                (numbers[0]..numbers[1]).filter { week ->
                    weekType == 0 || week % 2 == weekType % 2
                }
            } else {
                numbers
            }
            require(expanded.isNotEmpty()) { "周次范围与单双周标记冲突：$part" }
            weeks.addAll(expanded)
        }

        require(weeks.isNotEmpty()) { "周次字段中没有可用周次：$text" }
        return compact(weeks)
    }

    /**
     * 将新 URP 的周次位图转换为无损区间。
     *
     * @param bitMask 从第一周开始、只允许包含 `0` 和 `1` 的周次位图
     * @return 位值为 `1` 的全部周次所形成的区间
     * @throws IllegalArgumentException 位图为空、含非法字符或没有任何上课周
     */
    fun parseBitMask(bitMask: String): List<WeekSegment> {
        val normalized = bitMask.trim()
        require(normalized.isNotEmpty()) { "周次位图为空" }
        require(normalized.all { it == '0' || it == '1' }) { "周次位图只能包含 0 和 1" }

        val weeks = normalized.mapIndexedNotNull { index, flag ->
            if (flag == '1') index + 1 else null
        }
        require(weeks.isNotEmpty()) { "周次位图没有任何上课周" }
        return compact(weeks)
    }

    /**
     * 把精确周集合压缩为连续每周区间或同奇偶区间。
     *
     * @param inputWeeks 正整数周集合，允许无序和重复
     * @return 与输入集合严格等价的最大片段列表
     * @throws IllegalArgumentException 集合为空或包含非正周次
     * @throws IllegalStateException 内部压缩结果不能还原输入集合
     */
    fun compact(inputWeeks: Collection<Int>): List<WeekSegment> {
        require(inputWeeks.isNotEmpty()) { "周集合为空" }
        require(inputWeeks.all { it > 0 }) { "周集合只能包含正整数" }

        val remaining = inputWeeks.toSortedSet()
        val result = mutableListOf<WeekSegment>()
        while (remaining.isNotEmpty()) {
            val start = remaining.first()
            val consecutive = collectRun(start, 1, remaining)
            val sameParity = collectRun(start, 2, remaining)
            // 连续区间与单双周区间都能无损表达时，优先选择覆盖周数更多的方案。
            val selected = if (consecutive.size >= sameParity.size) consecutive else sameParity
            val type = if (selected.size > 1 && selected[1] - selected[0] == 2) {
                if (start % 2 == 1) 1 else 2
            } else {
                0
            }
            result += WeekSegment(start, selected.last(), type)
            remaining.removeAll(selected.toSet())
        }

        val restored = result.flatMapTo(sortedSetOf()) { segment -> segment.expand() }
        check(restored == inputWeeks.toSortedSet()) { "周集合压缩结果不是无损表达" }
        return result
    }

    /** 按固定步长收集从起始周开始的最长片段。 */
    private fun collectRun(start: Int, step: Int, weeks: Set<Int>): List<Int> {
        val result = mutableListOf<Int>()
        var current = start
        while (current in weeks) {
            result += current
            current += step
        }
        return result
    }

    /** 将区间重新展开为精确周集合，用于内部无损校验。 */
    private fun WeekSegment.expand(): List<Int> = when (type) {
        0 -> (startWeek..endWeek).toList()
        1, 2 -> (startWeek..endWeek).filter { week -> week % 2 == type % 2 }
        else -> error("未知周类型：$type")
    }

    private val NUMBER_PATTERN = Regex("""\d{1,3}""")
    private val RANGE_SEPARATOR_PATTERN = Regex("""[-~～至—–]""")
}
