package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.QzGridUtils

/** `qz_njust` 使用的南京理工大学非均匀节次强智解析器。 */
object QzNjustParser : Parser {

    /**
     * 解析新版 `kbtable/timetable` 网格，并应用南京理工大学专属节次组映射。
     *
     * 页面明确提供合法节次时优先使用页面值；缺失明确节次时，才按 3/2/2/3/3 的前五组
     * 分布恢复实际节次。Parser 入口保持独立，只复用无 type 路由的纯强智算法。
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
        ).ifEmpty { throw ParserException.empty("南京理工大学强智课表中没有课程") }
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("南京理工大学强智课表解析失败：${error.message}", error)
    }

    /** 将新版强智行组映射为南京理工大学实际节次范围。 */
    private fun mapNodeGroup(group: Int): IntRange {
        require(group > 0) { "南京理工大学强智节次组必须为正整数" }
        return when (group) {
            1 -> 1..3
            2 -> 4..5
            3 -> 6..7
            4 -> 8..10
            5 -> 11..13
            else -> (group * 2 - 1)..(group * 2)
        }
    }
}
