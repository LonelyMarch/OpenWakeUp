package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.JsonUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** `cppu` 使用的中国人民警察大学多响应 JSON 解析器。 */
object CppuParser : Parser {

    /**
     * 解析包含 `jxzqsrq` 的开学日响应和包含 `rows` 的课表响应。
     *
     * `JC` 的 `@` 前半段表示双节组号，具体上课日期 `SKRQ` 决定星期和单周周次。原版获取
     * 这些响应前需要额外网络请求，本 Parser 不执行任何请求，只消费调用方已经取得的原始响应。
     *
     * @param input 两个原始 JSON 响应；通过根字段识别响应角色
     * @return 尚未写入数据库的单周课程预览列表
     * @throws ParserException JSON、日期、节次组或教学周无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        require(input.additionalTexts.size == 1) { "中国人民警察大学需要开学日和课表两个响应" }
        val roots = input.allTexts.map { Json.parseToJsonElement(it).jsonObject }
        val startRoot = roots.singleOrNull { it.containsKey("jxzqsrq") }
            ?: throw ParserException.parse("中国人民警察大学缺少开学日期响应")
        val courseRoot = roots.singleOrNull { it.containsKey("rows") }
            ?: throw ParserException.parse("中国人民警察大学缺少课表响应")
        require(!startRoot.containsKey("rows") && !courseRoot.containsKey("jxzqsrq")) {
            "中国人民警察大学响应同时包含冲突角色"
        }
        val semesterStart = LocalDate.parse(JsonUtils.requiredString(startRoot, "jxzqsrq"))
        val rows = JsonUtils.requiredArray(courseRoot, "rows")
        val courses = rows.mapIndexed { index, element ->
            parseCourse(element.jsonObject, semesterStart, index + 1)
        }
        if (courses.isEmpty()) throw ParserException.empty("中国人民警察大学响应中没有课程")
        courses
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("中国人民警察大学课表解析失败：${error.message}", error)
    }

    /** 把一条具体日期安排转换为固定两节、单一教学周的课程。 */
    private fun parseCourse(
        row: kotlinx.serialization.json.JsonObject,
        semesterStart: LocalDate,
        rowNumber: Int,
    ): CoursePreview {
        val name = JsonUtils.requiredString(row, "KCMC")
        val courseDate = LocalDate.parse(JsonUtils.requiredString(row, "SKRQ"))
        val groupText = JsonUtils.requiredString(row, "JC").substringBefore('@').trim()
        val group = groupText.toIntOrNull()
        require(group != null && group > 0) { "第 $rowNumber 条课程的节次组无效：$groupText" }
        val week = calculateWeek(semesterStart, courseDate)
        require(week > 0) { "课程 $name 的日期早于学期起始周" }

        return CoursePreview(
            name = name,
            teacher = JsonUtils.optionalString(row, "JS"),
            room = JsonUtils.optionalString(row, "DDMC"),
            day = courseDate.dayOfWeek.value,
            startNode = (group - 1) * 2 + 1,
            step = 2,
            startWeek = week,
            endWeek = week,
        )
    }

    /**
     * 按原版教学周边界算法计算日期所在周次。
     *
     * 边界以 `semesterStart` 自身的星期为准，而不是强制以周一重新对齐。
     */
    private fun calculateWeek(semesterStart: LocalDate, courseDate: LocalDate): Int {
        val days = ChronoUnit.DAYS.between(semesterStart, courseDate).toInt()
        val dayOffset = courseDate.dayOfWeek.value - semesterStart.dayOfWeek.value
        val alignedDays = if (dayOffset >= 0) days - dayOffset else days - (dayOffset + 7)
        return alignedDays / 7 + 1
    }
}
