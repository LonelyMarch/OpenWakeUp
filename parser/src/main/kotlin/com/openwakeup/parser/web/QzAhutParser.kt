package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.QzGridUtils

/** `qz_ahut` 使用的安徽工业大学强智变体解析器。 */
object QzAhutParser : Parser {

    /**
     * 解析课程内容类名为 `kbcontent1` 的强智网格。
     *
     * 前四组为标准双节，第五组覆盖第 9～11 节，第六组仅覆盖第 12 节。页面明确节次仍优先，
     * 只有属性缺失或无效时才使用该学校专属行映射。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹、课程字段或节次范围无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        QzGridUtils.parse(
            source = input.text,
            contentClass = "kbcontent1",
            explicitNodes = true,
            tableIds = listOf("kbtable", "timetable"),
            explicitNodeFallback = true,
            nodeRangeProvider = ::mapNodeGroup,
        ).ifEmpty { throw ParserException.empty("安徽工业大学强智课表中没有课程") }
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("安徽工业大学强智课表解析失败：${error.message}", error)
    }

    /** 将强智行组映射为安徽工业大学实际节次范围。 */
    private fun mapNodeGroup(group: Int): IntRange {
        require(group > 0) { "安徽工业大学强智节次组必须为正整数" }
        return when (group) {
            1 -> 1..2
            2 -> 3..4
            3 -> 5..6
            4 -> 7..8
            5 -> 9..11
            6 -> 12..12
            else -> (group * 2 - 1)..(group * 2)
        }
    }
}
