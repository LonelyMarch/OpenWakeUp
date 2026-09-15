package com.openwakeup.parser

/** 课表内容为空或页面结构无法解析时抛出的异常。 */
class ParserException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    companion object {
        /** 创建“未获取到课程”异常。 */
        fun empty(message: String = "未获取到课程数据") = ParserException(message)

        /** 创建“页面结构无法解析”异常，并保留可选的底层原因。 */
        fun parse(
            message: String = "页面解析失败，请确认已进入正确页面",
            cause: Throwable? = null,
        ) = ParserException(message, cause)
    }
}
