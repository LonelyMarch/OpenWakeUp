package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup

/** 南软研究生教务学生课表解析器，规范 type 为 `south_soft`。 */
object SouthSoftParser : Parser {

    /**
     * 解析 `#kb` 网格中的南软课程。
     *
     * 表格行位置在扣除“无节次”占位行后表示起始节次，列位置表示星期。课程块中每个明确的
     * `起始周-结束周` 字段与其相邻课程名、教师和教室组成一条课程记录。
     *
     * @param input 已由调用方取得的“培养管理 -> 学生课表查询”完整 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹缺失、课表为空或字段无法定位
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val table = Jsoup.parse(input.text).getElementById("kb")
            ?: throw ParserException.parse("南软页面中缺少 kb 课表")
        val body = table.select("tbody").lastOrNull()
            ?: throw ParserException.parse("南软 kb 课表中缺少 tbody")
        val rows = body.select("tr")
        if (rows.isEmpty()) throw ParserException.parse("南软 kb 课表中缺少课程行")

        val courses = mutableListOf<CoursePreview>()
        var skippedNoNodeRows = 0
        rows.forEachIndexed rowLoop@{ rowIndex, row ->
            if (row.select("td").lastOrNull()?.text()?.contains("无节次") == true) {
                skippedNoNodeRows += 1
            }
            val node = rowIndex + 1 - skippedNoNodeRows

            row.select("td").forEachIndexed cellLoop@{ columnIndex, cell ->
                // 南软网格第 0 列是节次标题，第 1～7 列才分别对应周一至周日。
                if (columnIndex !in 1..7) return@cellLoop
                cell.select("div").forEach { block ->
                    val tokens = NON_BR_TAG_PATTERN.split(block.html()).map { fragment ->
                        Jsoup.parse(fragment).text().trim()
                    }

                    tokens.forEachIndexed tokenLoop@{ tokenIndex, token ->
                        if (!WEEK_RANGE_PATTERN.containsMatchIn(token)) return@tokenLoop
                        require(node > 0) { "南软课程位于无效节次行" }

                        val firstCandidate = tokens.getOrNull(tokenIndex - 1)
                            ?.removeCourseClassPrefix()
                            .orEmpty()
                        val courseMetadata = if (firstCandidate.isNotEmpty()) {
                            firstCandidate
                        } else {
                            tokens.getOrNull(tokenIndex - 2)?.removeCourseClassPrefix().orEmpty()
                        }
                        val name = courseMetadata
                            .removeCourseNamePrefix()
                            .replace("||##", "")
                            .substringBefore("||")
                            .trim()
                        require(name.isNotEmpty()) { "南软周次字段前缺少课程名称" }

                        val teacher = tokens.getOrNull(tokenIndex + 1)
                            ?.replace("(连续周)", "")
                            ?.replace("连续周", "")
                            ?.trim()
                            .orEmpty()
                        val room = tokens.getOrNull(tokenIndex + 2)?.trim().orEmpty()
                        WeekUtils.parse(token).forEach { week ->
                            courses += CoursePreview(
                                name = name,
                                teacher = teacher,
                                room = room,
                                day = columnIndex,
                                startNode = node,
                                startWeek = week.startWeek,
                                endWeek = week.endWeek,
                                type = week.type,
                            )
                        }
                    }
                }
            }
        }

        if (courses.isEmpty()) throw ParserException.empty("南软课表中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("南软课表解析失败：${error.message}", error)
    }

    /** 去除南软课程元数据前可能存在的中英文班级标签。 */
    private fun String.removeCourseClassPrefix(): String =
        replace("班级：", "").replace("班级:", "").trim()

    /** 去除课程元数据中位于课程名之前的中英文标签。 */
    private fun String.removeCourseNamePrefix(): String = when {
        contains("课程名称:") -> substringAfter("课程名称:")
        contains("课程名称：") -> substringAfter("课程名称：")
        else -> this
    }

    private val WEEK_RANGE_PATTERN = Regex("""\d+\s*[-~～至—–]\s*\d+\s*周""")
    private val NON_BR_TAG_PATTERN = Regex(
        """(?:</?(?!br\b)[^>]+>\s*)+""",
        RegexOption.IGNORE_CASE,
    )
}
