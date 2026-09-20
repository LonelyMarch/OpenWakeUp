package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.QzGridUtils

/** `qz_ecust` 使用的华东理工大学非均匀节次强智解析器。 */
object QzEcustParser : Parser {

    /**
     * 解析 `kbtable/timetable` 网格，并应用华东理工大学专属节次组映射。
     *
     * 页面明确提供合法节次时优先使用页面值；缺失明确节次时，第 3、6 组分别映射为第 4、8
     * 单节，其余前八组按学校实际作息恢复。该映射只属于 `qz_ecust`，不影响其他强智 Parser。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹、课程字段或节次范围无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        QzGridUtils.parse(
            source = input.text,
            explicitNodes = true,
            tableIds = listOf("kbtable", "timetable"),
            explicitNodeFallback = true,
            nodeRangeProvider = ::mapNodeGroup,
        ).ifEmpty { throw ParserException.empty("华东理工大学强智课表中没有课程") }
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("华东理工大学强智课表解析失败：${error.message}", error)
    }

    /** 将强智行组映射为华东理工大学实际节次范围。 */
    private fun mapNodeGroup(group: Int): IntRange {
        require(group > 0) { "华东理工大学强智节次组必须为正整数" }
        return when (group) {
            1 -> 1..2
            2 -> 3..4
            3 -> 4..4
            4 -> 5..6
            5 -> 7..8
            6 -> 8..8
            7 -> 9..10
            8 -> 11..12
            else -> (group * 2 - 1)..(group * 2)
        }
    }
}
