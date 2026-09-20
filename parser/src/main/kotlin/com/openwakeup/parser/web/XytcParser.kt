package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.TextUtils
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** `xytc` 使用的 `report_tSxsgrkbcx` 编码报表解析器。 */
object XytcParser : Parser {

    /**
     * 解析课表单元格中由 `&nbsp;` 编码的一门或两门课程。
     *
     * 原页面会把并列课程压缩到同一单元格：6、7 个字段表示一门课程，11、13 个字段表示
     * 两门课程。这里严格保留原版的字段重组顺序，无法识别的字段数直接失败关闭。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹缺失、字段数量未知或时间信息不完整
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("report_tSxsgrkbcx")
            ?: throw ParserException.parse("盐城师范页面中缺少 report_tSxsgrkbcx 课表")
        val records = mutableListOf<List<String>>()

        table.select("td").forEach { cell ->
            val rawHtml = normalizeNbsp(cell.html())
            if (!rawHtml.contains(NBSP_TOKEN)) return@forEach
            val fields = rawHtml.split(NBSP_TOKEN)
            records += rebuildRecords(fields)
        }

        val courses = records.flatMap(::parseRecord)
        if (courses.isEmpty()) throw ParserException.empty("盐城师范课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("盐城师范课表解析失败：${error.message}", error)
    }

    /**
     * 根据原版四种字段数量，把单元格恢复为统一的六字段课程记录。
     *
     * @param fields 按不换行空格切开的原始 HTML 字段
     * @return 每项均为“名称、周次、星期、节次、教室、教师”的六字段记录
     */
    private fun rebuildRecords(fields: List<String>): List<List<String>> = when (fields.size) {
        6 -> listOf(fields)
        7 -> listOf(listOf(fields[0], fields[1], fields[3], fields[4], fields[5], fields[6]))
        13 -> listOf(
            listOf(
                fields[0],
                fields[1],
                fields[3],
                fields[4],
                fields[5],
                splitSharedField(fields[6], 0)
            ),
            listOf(
                splitSharedField(fields[6], 1),
                fields[7],
                fields[9],
                fields[10],
                fields[11],
                fields[12]
            ),
        )

        11 -> listOf(
            listOf(
                fields[0],
                fields[1],
                fields[2],
                fields[3],
                fields[4],
                splitSharedField(fields[5], 0)
            ),
            listOf(
                splitSharedField(fields[5], 1),
                fields[6],
                fields[7],
                fields[8],
                fields[9],
                fields[10]
            ),
        )

        else -> throw IllegalArgumentException("盐城师范课程格字段数无法识别：${fields.size}")
    }

    /**
     * 拆分两门课程共用的 `<br>` 字段。
     *
     * 三段内容沿用原版的短前缀判定：前两段总长度小于 4 时归入左侧，否则后两段归入右侧。
     *
     * @param source 可能含两段或三段内容的原始 HTML
     * @param index 0 取左侧课程字段，1 取右侧课程字段
     * @return 尚未去除 HTML 标签的目标字段
     */
    private fun splitSharedField(source: String, index: Int): String {
        require(index in 0..1) { "共享字段索引必须为 0 或 1" }
        val parts = source.split(BR_PATTERN)
        return when (parts.size) {
            2 -> parts[index]
            3 -> {
                val firstTwoLength = parts.take(2).sumOf { part -> cleanField(part).length }
                if (firstTwoLength < 4) {
                    if (index == 0) parts[0] + parts[1] else parts[2]
                } else {
                    if (index == 0) parts[0] else parts[1] + parts[2]
                }
            }

            else -> throw IllegalArgumentException("盐城师范并列课程共享字段无法拆分")
        }
    }

    /** 将统一六字段记录转换为一项或多项无损周次课程预览。 */
    private fun parseRecord(fields: List<String>): List<CoursePreview> {
        require(fields.size == 6) { "盐城师范课程记录必须包含六个字段" }
        val name = cleanField(fields[0])
        require(name.isNotEmpty()) { "盐城师范课程名称为空" }
        val weekText = cleanField(fields[1])
        require(weekText.isNotEmpty()) { "课程 $name 缺少明确周次" }
        val day = TextUtils.requireDay(cleanField(fields[2]))
        val nodes = TextUtils.requirePositiveRange(cleanField(fields[3]), "课程 $name 的节次")
        val room = cleanField(fields[4])
        val teacher = cleanField(fields[5])

        return WeekUtils.parse(weekText).map { week ->
            CoursePreview(
                name = name,
                teacher = teacher,
                room = room,
                day = day,
                startNode = nodes.first,
                step = nodes.last - nodes.first + 1,
                startWeek = week.startWeek,
                endWeek = week.endWeek,
                type = week.type,
            )
        }
    }

    /** 将字段中的标签和换行清除，但不制造任何默认课程值。 */
    private fun cleanField(source: String): String = Jsoup.parse(
        source.replace(BR_PATTERN, ""),
    ).text().trim()

    /** 统一浏览器和 jsoup 可能产生的几种不换行空格写法。 */
    private fun normalizeNbsp(source: String): String = source.replace(NBSP_PATTERN, NBSP_TOKEN)

    private const val NBSP_TOKEN = "&nbsp;"
    private val NBSP_PATTERN = Regex("(?i)&nbsp;|&#x0*a0;|&#0*160;|\u00a0")
    private val BR_PATTERN = Regex("(?i)<br\\s*/?>")
}
