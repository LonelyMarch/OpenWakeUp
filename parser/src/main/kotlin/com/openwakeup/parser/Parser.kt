package com.openwakeup.parser

/**
 * 解析产物：一门课程的一个时间段（预览态，导入时由界面/仓库层落库）。
 *
 * @property name 课程名称
 * @property color 卡片颜色（#AARRGGBB，可空=由界面分配色板）
 * @property teacher 教师
 * @property room 教室
 * @property day 星期（1=周一 … 7=周日）
 * @property startNode 起始节（1 起）
 * @property step 连续节数
 * @property startWeek 起始周
 * @property endWeek 结束周
 * @property type 周类型：0=每周，1=单周，2=双周
 *
 * 上游课程模型的 `credit/note/startTime/endTime` 当前没有对应的应用导入字段，因此不在解析层
 * 伪装或塞入教师、教室等其他字段；需要这些能力时必须先显式扩展应用数据模型。
 */
data class CoursePreview(
    val name: String,
    val color: String? = null,
    val teacher: String = "",
    val room: String = "",
    val day: Int,
    val startNode: Int,
    val step: Int = 1,
    val startWeek: Int = 1,
    val endWeek: Int = 25,
    val type: Int = 0,
)

/**
 * 解析器的统一文本输入。
 *
 * `type` 只供调用方和错误信息识别本次导入来源，具体 Parser 已由 [ParserFactory]
 * 在调用前选定，实现不得再次读取该字段并分派到其他 Parser。
 *
 * @property text 完整 HTML、JSON、XML、CSV，或 Parser 自己声明的多响应 JSON 输入封套
 * @property type `schools.json` 中用于选择 Parser 的唯一类型键
 */
data class ParserInput(
    val text: String,
    val type: String,
    /**
     * 按获取顺序排列的其余原始响应。
     *
     * 单响应 Parser 无需读取此字段；需要分页、逐周或主/备响应的 Parser 使用 [allTexts]。
     * 这里始终保存未经 Parser 专用封套改写的完整文本，避免 App 层解释课程语义。
     */
    val additionalTexts: List<String> = emptyList(),
) {

    /** 主响应与附加响应组成的稳定、有序视图。 */
    val allTexts: List<String>
        get() = listOf(text) + additionalTexts
}

/**
 * 课表解析器接口：全部家族解析器的统一抽象。
 */
interface Parser {
    /**
     * 解析输入并给出课程预览列表。
     *
     * @param input 已由调用方取得的完整文本输入
     * @return 非空的课程预览列表
     * @throws ParserException 正确课表为空、输入页面错误或页面结构不符合预期
     */
    fun parse(input: ParserInput): List<CoursePreview>
}

/**
 * 解析器领域异常。
 *
 * 页面结构错误、课程内容为空和解析类型未启用等可向用户解释的错误统一使用该异常。
 * App 层只负责展示异常消息，不需要识别具体解析器实现。
 *
 * @param message 面向用户的错误说明
 * @param cause 触发解析失败的底层异常，可空
 */
open class ParserException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    companion object {

        /**
         * 创建“未获取到课程”异常。
         *
         * @param message 可覆盖的空课表说明
         * @return 可由导入界面直接展示的解析异常
         */
        fun empty(message: String = "未获取到课程数据"): ParserException =
            ParserException(message)

        /**
         * 创建“页面结构无法解析”异常。
         *
         * @param message 页面结构错误说明
         * @param cause 可选的底层异常
         * @return 保留原始原因的解析异常
         */
        fun parse(
            message: String = "页面解析失败，请确认已进入正确页面",
            cause: Throwable? = null,
        ): ParserException = ParserException(message, cause)
    }
}

/**
 * 学校 type 尚未进入本地静态解析器映射时抛出的异常。
 *
 * 该异常用于失败关闭未知或尚未迁移完成的 type，禁止再回落到通用 HTML 表格解析器。
 *
 * @property type `schools.json` 或 HTML 文件导入入口传入的原始 type
 */
class UnsupportedParserTypeException(
    val type: String,
) : ParserException(
    if (type.isBlank()) {
        "未指定教务解析类型"
    } else {
        "当前版本暂不支持教务解析类型：$type"
    },
)
