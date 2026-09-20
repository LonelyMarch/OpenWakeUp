package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 中山大学课表解析器。
 *
 * `type=sysu` 直接决定使用本解析器；解析器再根据互斥的 DOM 指纹兼容旧版
 * `class-schedule-table2` 与 2024 版 `table-bot` 页面。页面同时命中两个指纹，或两个指纹
 * 均未命中时一律失败关闭，禁止根据内容相似度猜测页面版本。
 */
object SysuParser : Parser {

    /**
     * 按唯一页面指纹选择中山大学页面版本并解析课程。
     *
     * @param input WebView 当前页的完整 HTML
     * @return 页面中的全部课程时间段
     * @throws ParserException 页面指纹冲突、结构不完整或没有课程时抛出
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val document = Jsoup.parse(input.text)
        val legacyTables = document.select("table.com-table.class-schedule-table2")
        val modernTable = document.getElementById("table-bot")
        val hasLegacyFingerprint = legacyTables.size >= LEGACY_TABLE_COUNT
        val hasModernFingerprint = modernTable != null

        val courses = when {
            hasLegacyFingerprint && hasModernFingerprint -> {
                throw ParserException.parse("中山大学页面同时包含旧版和 2024 版课表，无法确定页面版本")
            }

            hasLegacyFingerprint -> parseLegacy(legacyTables)
            hasModernFingerprint -> parseModern(requireNotNull(modernTable))
            else -> throw ParserException.parse("中山大学页面缺少 class-schedule-table2 或 table-bot 课表")
        }

        if (courses.isEmpty()) {
            throw ParserException.empty("中山大学课表中没有课程")
        }
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("中山大学课表解析失败：${error.message}", error)
    }

    /**
     * 解析旧版双表格课表。
     *
     * 第一张表只负责声明各星期的实际顺序与列宽，第二张表包含课程网格。旧页面既可能以周日
     * 开头，也可能以周一开头，因此必须读取表头文字，不能按列下标硬编码星期。
     *
     * @param tables 页面中命中旧版指纹的表格集合
     * @return 旧版页面中的课程时间段
     */
    private fun parseLegacy(tables: List<Element>): List<CoursePreview> {
        require(tables.size >= LEGACY_TABLE_COUNT) { "中山大学旧版页面缺少两张课表" }
        val headerRow = tables[0].selectFirst("tr")
            ?: throw IllegalArgumentException("中山大学旧版课表缺少星期表头")
        val headerCells = directChildren(headerRow, "th").filterNot { cell -> cell.hasClass("th2") }
        val dayBands = parseDayBands(headerCells, "中山大学旧版")
        val body = tables[1].selectFirst("tbody")
            ?: throw IllegalArgumentException("中山大学旧版课表缺少 tbody")

        val courses = mutableListOf<CoursePreview>()
        directChildren(body, "tr").forEachIndexed { rowIndex, row ->
            var gridColumn = 0
            directChildren(row, "td").forEach { cell ->
                // `td2-1` 是节次标题，不占用星期课程网格的横向列宽。
                if (cell.hasClass("td2-1")) return@forEach

                val columnSpan = requirePositiveSpan(cell, "colspan", "旧版第 ${rowIndex + 1} 行")
                val startColumn = gridColumn + 1
                gridColumn += columnSpan
                val dayBand = requireDayBand(dayBands, startColumn, gridColumn, rowIndex + 1)

                // `11111` 是旧系统的空白占位格；纯空白格同样不能伪造为空名称课程。
                if (cell.hasClass("11111") || cell.text().isBlank()) return@forEach

                val rowSpan = requirePositiveSpan(cell, "rowspan", "旧版第 ${rowIndex + 1} 行")
                val fields = cell.select("span").map { span ->
                    span.text().trim().replace("/", "")
                }
                courses += parseLegacyCell(
                    fields = fields,
                    day = dayBand.day,
                    startNode = rowIndex + 1,
                    step = rowSpan,
                )
            }
        }
        return courses
    }

    /**
     * 解析 2024 版 `table-bot` 课表。
     *
     * 该页面用 `colspan` 表示同一天可并排显示的课程轨道，用 `rowspan` 表示连续节次。星期
     * 必须由表头文字和列宽共同确定；任何越过星期边界或落在总列宽之外的课程都会失败关闭。
     *
     * @param table 已通过 `table-bot` 指纹确认的课表
     * @return 2024 版页面中的课程时间段
     */
    private fun parseModern(table: Element): List<CoursePreview> {
        val dayHeaderCells = table.select("th[colspan]").mapNotNull { cell ->
            runCatching { TextUtils.requireDay(cell.text()) }.getOrNull()?.let { cell }
        }
        val dayBands = parseDayBands(dayHeaderCells, "中山大学 2024 版")
        val body = table.selectFirst("tbody")
            ?: throw IllegalArgumentException("中山大学 2024 版课表缺少 tbody")

        val courses = mutableListOf<CoursePreview>()
        directChildren(body, "tr").forEachIndexed { rowIndex, row ->
            val cells = directChildren(row, "td")
            require(cells.isNotEmpty()) { "中山大学 2024 版第 ${rowIndex + 1} 行缺少节次单元格" }
            var gridColumn = 0

            // 每行第一个 td 是节次与时间说明，不属于星期课程网格。
            cells.drop(1).forEach { cell ->
                val columnSpan =
                    requirePositiveSpan(cell, "colspan", "2024 版第 ${rowIndex + 1} 行")
                val startColumn = gridColumn + 1
                gridColumn += columnSpan
                val dayBand = requireDayBand(dayBands, startColumn, gridColumn, rowIndex + 1)
                val fields = cell.select("span")
                if (fields.isEmpty() && cell.text().isBlank()) return@forEach
                require(fields.size >= MODERN_FIELD_COUNT) {
                    "中山大学 2024 版第 ${rowIndex + 1} 行课程字段不足"
                }

                val weekText = fields[0].text().trim()
                val name = cleanLabeledField(
                    text = fields[1].text(),
                    labels = listOf("课程名称：", "课程名称:"),
                    fieldName = "课程名称",
                    labelRequired = true,
                )
                val teacher = cleanLabeledField(
                    text = fields[2].text(),
                    labels = listOf("授课教师：", "授课教师:", "教师：", "教师:"),
                    fieldName = "授课教师",
                    labelRequired = false,
                )
                val room = cleanLabeledField(
                    text = fields[3].text(),
                    labels = listOf(
                        "上课地点：",
                        "上课地点:",
                        "开课地点：",
                        "开课地点:",
                        "地点：",
                        "地点:",
                    ),
                    fieldName = "上课地点",
                    labelRequired = false,
                )
                val rowSpan = requirePositiveSpan(cell, "rowspan", "2024 版第 ${rowIndex + 1} 行")

                // WeekUtils 会将逗号、连续范围和单双周无损拆成 CoursePreview 可表达的片段。
                WeekUtils.parse(weekText).forEach { segment ->
                    courses += CoursePreview(
                        name = name,
                        teacher = teacher,
                        room = room,
                        day = dayBand.day,
                        startNode = rowIndex + 1,
                        step = rowSpan,
                        startWeek = segment.startWeek,
                        endWeek = segment.endWeek,
                        type = segment.type,
                    )
                }
            }
        }
        return courses
    }

    /**
     * 将旧版固定顺序字段转换为课程记录。
     *
     * @param fields 周次、课程名称、教师和教室字段
     * @param day 由旧版表头解析得到的真实星期
     * @param startNode 起始节次
     * @param step 连续节数
     * @return 与原始周集合严格等价的一组课程记录
     */
    private fun parseLegacyCell(
        fields: List<String>,
        day: Int,
        startNode: Int,
        step: Int,
    ): List<CoursePreview> {
        require(fields.size >= LEGACY_FIELD_COUNT) { "中山大学旧版课程单元格字段不足" }
        val nameParts = fields[1].split(Regex("[()]"))
        require(nameParts.size >= 3 && nameParts[2].isNotBlank()) {
            "中山大学旧版课程名称字段无法识别：${fields[1]}"
        }
        val name = nameParts[2].trim()
        return WeekUtils.parse(fields[0]).map { segment ->
            CoursePreview(
                name = name,
                teacher = fields[2].trim(),
                room = fields[3].trim(),
                day = day,
                startNode = startNode,
                step = step,
                startWeek = segment.startWeek,
                endWeek = segment.endWeek,
                type = segment.type,
            )
        }
    }

    /**
     * 根据星期表头建立连续列宽区间。
     *
     * @param cells 仅包含星期标题的表头单元格
     * @param pageName 错误信息中的页面版本名称
     * @return 按页面显示顺序排列的星期列宽区间
     */
    private fun parseDayBands(cells: List<Element>, pageName: String): List<DayBand> {
        require(cells.size == DAYS_PER_WEEK) { "$pageName 星期表头数量不是 7" }
        var endColumn = 0
        val bands = cells.map { cell ->
            val day = TextUtils.requireDay(cell.text())
            val width = requirePositiveSpan(cell, "colspan", "$pageName 星期 $day 表头")
            val startColumn = endColumn + 1
            endColumn += width
            DayBand(day = day, startColumn = startColumn, endColumn = endColumn)
        }
        require(bands.map { band -> band.day }.toSet() == (1..DAYS_PER_WEEK).toSet()) {
            "$pageName 星期表头存在重复或缺失"
        }
        return bands
    }

    /**
     * 确认课程横向区间完整落在唯一星期列宽内。
     *
     * @param bands 星期列宽区间
     * @param startColumn 课程占用的起始列
     * @param endColumn 课程占用的结束列
     * @param rowNumber 当前课表行号，仅用于错误说明
     * @return 课程所属的星期区间
     */
    private fun requireDayBand(
        bands: List<DayBand>,
        startColumn: Int,
        endColumn: Int,
        rowNumber: Int,
    ): DayBand {
        val band = bands.singleOrNull { candidate ->
            startColumn >= candidate.startColumn && endColumn <= candidate.endColumn
        }
        return requireNotNull(band) {
            "中山大学课表第 $rowNumber 行的列宽 $startColumn..$endColumn 越过星期边界"
        }
    }

    /**
     * 读取 HTML 单元格的正整数跨度属性。
     *
     * @param cell 待读取的表头或课程单元格
     * @param attribute `colspan` 或 `rowspan`
     * @param context 错误信息中的页面位置
     * @return 大于零的跨度
     */
    private fun requirePositiveSpan(cell: Element, attribute: String, context: String): Int =
        cell.attr(attribute).toIntOrNull()?.takeIf { value -> value > 0 }
            ?: throw IllegalArgumentException("$context 的 $attribute 无效")

    /**
     * 清理新版页面的字段标签和真实分隔符。
     *
     * 这里只删除明确出现的标签、末尾 `/` 以及完整包裹字段的成对中英文括号，禁止使用固定
     * 字符下标截断，以免课程名称、教师或教室被误删。
     *
     * @param text 页面中的原始字段文本
     * @param labels 该字段允许出现的标签
     * @param fieldName 错误信息中的字段名称
     * @param labelRequired 是否要求原文必须带有已知标签
     * @return 清理后的字段值；非必填字段允许为空
     */
    private fun cleanLabeledField(
        text: String,
        labels: List<String>,
        fieldName: String,
        labelRequired: Boolean,
    ): String {
        var value = text.trim()
        val matchedLabel = labels.firstOrNull { label -> value.startsWith(label) }
        require(!labelRequired || matchedLabel != null) {
            "中山大学 2024 版${fieldName}标签无法识别：$text"
        }
        require(matchedLabel != null || (':' !in value && '：' !in value)) {
            "中山大学 2024 版${fieldName}包含未知标签：$text"
        }
        if (matchedLabel != null) value = value.removePrefix(matchedLabel).trim()
        if (value.endsWith('/')) value = value.dropLast(1).trimEnd()

        val pairedBrackets = listOf('(' to ')', '（' to '）')
        val enclosingPair = pairedBrackets.firstOrNull { (opening, closing) ->
            value.length >= 2 && value.first() == opening && value.last() == closing
        }
        if (enclosingPair != null) value = value.substring(1, value.lastIndex).trim()
        require(!labelRequired || value.isNotEmpty()) { "中山大学 2024 版${fieldName}为空" }
        return value
    }

    /** 只读取直接子节点，避免把课程内容中的嵌套表格误认为课表行或单元格。 */
    private fun directChildren(parent: Element, tagName: String): List<Element> =
        parent.children().filter { child -> child.tagName().equals(tagName, ignoreCase = true) }

    /** 星期在横向课程网格中占用的闭区间。 */
    private data class DayBand(
        val day: Int,
        val startColumn: Int,
        val endColumn: Int,
    )

    private const val DAYS_PER_WEEK = 7
    private const val LEGACY_TABLE_COUNT = 2
    private const val LEGACY_FIELD_COUNT = 4
    private const val MODERN_FIELD_COUNT = 4
}
