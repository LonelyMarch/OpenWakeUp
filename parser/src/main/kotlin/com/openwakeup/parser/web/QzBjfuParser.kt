package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.QzGridUtils

/** `qz_bjfu` 使用的北京林业大学强智节次特例解析器。 */
object QzBjfuParser : Parser {

    /**
     * 解析强智 `kbtable/timetable` 网格，并应用北林专属非均匀节次组映射。
     *
     * 属性中存在合法明确节次时优先采用属性；属性缺失时才按行组映射，避免把午间和晚间
     * 单节课程错误扩展成双节。
     *
     * @param input 已由调用方取得的完整课表 HTML
     * @return 尚未写入数据库的课程预览列表
     * @throws ParserException 页面指纹缺失、课程属性不完整或时间字段无效
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        QzGridUtils.parse(
            source = input.text,
            nameBeforeBreak = true,
            explicitNodes = true,
            tableIds = listOf("kbtable", "timetable"),
            explicitNodeFallback = true,
            nodeRangeProvider = ::mapNodeGroup,
        ).ifEmpty { throw ParserException.empty("北林强智课表中没有课程") }
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("北林强智课表解析失败：${error.message}", error)
    }

    /**
     * 将强智课表行组转换为北京林业大学实际节次。
     *
     * 第 3 组和第 7 组分别只对应第 5、12 节；第 4～6 组从第 6 节开始恢复连续双节。
     */
    private fun mapNodeGroup(group: Int): IntRange {
        require(group > 0) { "北林强智节次组必须为正整数" }
        return when {
            group < 3 -> (group * 2 - 1)..(group * 2)
            group == 3 -> 5..5
            group in 4..6 -> (group * 2 - 2)..(group * 2 - 1)
            group == 7 -> 12..12
            else -> (group * 2 - 1)..(group * 2)
        }
    }
}
