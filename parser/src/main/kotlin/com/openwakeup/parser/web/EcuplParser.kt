package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.WeekUtils
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 华东政法大学 CourseTable 脚本解析器。 */
object EcuplParser : Parser {
    /** 解析学期起始日、节次钟点及 newActivity/addActivityByTime 组合，不保留作息对象。 */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        val document = Jsoup.parse(input.text)
        val script = document.selectFirst("script[language=JavaScript]")?.data().orEmpty()
        require(script.isNotBlank()) { "华东政法大学页面缺少课程脚本" }
        val tableMatch = COURSE_TABLE_PATTERN.find(script) ?: error("课程脚本缺少学期起始日和作息")
        val semesterStart = LocalDate.parse(tableMatch.groupValues[1])
        val periods = TIME_PATTERN.findAll(tableMatch.groupValues[2])
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
        require(periods.isNotEmpty()) { "华东政法大学作息列表为空" }
        val result = ACTIVITY_PATTERN.findAll(script).flatMap { match ->
            val startNode = periods.indexOfFirst { it.first == match.groupValues[7].toInt() } + 1
            val endNode = periods.indexOfFirst { it.second == match.groupValues[8].toInt() } + 1
            require(startNode > 0 && endNode >= startNode) { "课程钟点无法映射到节次" }
            val rawBits = match.groupValues[5].toLong()
            val offset =
                ChronoUnit.WEEKS.between(semesterStart, LocalDate.parse(match.groupValues[4]))
                    .toInt()
            val weeks = (0 until 64).filter { bit -> rawBits and (1L shl bit) != 0L }
                .map { it + offset + 1 }
            require(weeks.isNotEmpty()) { "课程周位图为空" }
            WeekUtils.compact(weeks).map { week ->
                CoursePreview(
                    name = match.groupValues[2].substringBeforeLast('('),
                    teacher = match.groupValues[1],
                    room = match.groupValues[3],
                    day = match.groupValues[6].toInt(),
                    startNode = startNode,
                    step = endNode - startNode + 1,
                    startWeek = week.startWeek,
                    endWeek = week.endWeek,
                    type = week.type
                )
            }.asSequence()
        }.toList()
        if (result.isEmpty()) throw ParserException.empty("华东政法大学课表中没有课程")
        result
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("华东政法大学课表解析失败：${error.message}", error)
    }

    private val COURSE_TABLE_PATTERN = Regex("""new CourseTable\('([-\d]+?)',\[([\d\[\],]+)]\)""")
    private val TIME_PATTERN = Regex("""\[(\d+),(\d+)]""")
    private val ACTIVITY_PATTERN =
        Regex("""newActivity\(".*?","(.*?)",".+?","(.+?)",".*?","(.*?)","([-\d]+)",(\d+)\);[\n\s\S]+?addActivityByTime\(activity,(\d),(\d+),(\d+)\);""")
}
