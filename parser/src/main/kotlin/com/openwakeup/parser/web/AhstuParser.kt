package com.openwakeup.parser.web

import com.openwakeup.parser.CoursePreview
import com.openwakeup.parser.Parser
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserInput
import com.openwakeup.parser.utils.SupwisdomUtils

/** 安徽科技学院最终 Supwisdom 课程脚本解析入口。 */
object AhstuParser : Parser {
    /**
     * 解析调用方已取得的课程脚本；SSO、验证码、Cookie 和课表请求均不属于 Parser。
     * 安徽科技学院上游未覆盖任何字段差异，因此复用同一份纯脚本语义，但保留独立 type 入口。
     */
    override fun parse(input: ParserInput): List<CoursePreview> = try {
        SupwisdomUtils.parse(input.text)
    } catch (error: ParserException) {
        throw error
    } catch (error: Exception) {
        throw ParserException.parse("安徽科技学院课表解析失败：${error.message}", error)
    }
}
