package com.openwakeup.parser.utils

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 读取 JSON Parser 共同需要的必填字段，并为结构错误生成稳定说明。
 *
 * 工具只处理 JSON 字段类型，不包含学校 type、课程规则或默认课程值。缺少必填字段时立即失败，
 * 避免把登录响应、错误响应或改版后的接口数据解析成看似正常的课程。
 */
internal object JsonUtils {

    /**
     * 读取必填 JSON 对象字段。
     *
     * @param source 当前 JSON 对象
     * @param key 字段名
     * @return 对象字段
     * @throws IllegalArgumentException 字段缺失或不是对象
     */
    fun requiredObject(source: JsonObject, key: String): JsonObject =
        source[key] as? JsonObject
            ?: throw IllegalArgumentException("缺少 JSON 对象字段：$key")

    /**
     * 读取必填 JSON 数组字段。
     *
     * @param source 当前 JSON 对象
     * @param key 字段名
     * @return 数组字段
     * @throws IllegalArgumentException 字段缺失或不是数组
     */
    fun requiredArray(source: JsonObject, key: String): JsonArray =
        source[key] as? JsonArray
            ?: throw IllegalArgumentException("缺少 JSON 数组字段：$key")

    /**
     * 读取非空字符串字段。
     *
     * @param source 当前 JSON 对象
     * @param key 字段名
     * @return 去除首尾空白后的字段值
     * @throws IllegalArgumentException 字段缺失、不是基础值或内容为空
     */
    fun requiredString(source: JsonObject, key: String): String {
        val value = source[key].asPrimitive(key).content.trim()
        require(value.isNotEmpty()) { "JSON 字段 $key 为空" }
        return value
    }

    /**
     * 读取允许缺失的字符串字段。
     *
     * @param source 当前 JSON 对象
     * @param key 字段名
     * @return 去除首尾空白后的字段值；字段不存在或为 JSON null 时返回空字符串
     * @throws IllegalArgumentException 字段存在但不是基础值
     */
    fun optionalString(source: JsonObject, key: String): String {
        val element = source[key] ?: return ""
        val primitive = element as? JsonPrimitive
            ?: throw IllegalArgumentException("JSON 字段 $key 不是基础值")
        if (primitive.toString() == "null") return ""
        return primitive.content.trim()
    }

    /**
     * 读取必填正整数字段，兼容 JSON 数字和数字字符串。
     *
     * @param source 当前 JSON 对象
     * @param key 字段名
     * @return 大于零的整数值
     * @throws IllegalArgumentException 字段缺失、不是整数或不为正数
     */
    fun requiredPositiveInt(source: JsonObject, key: String): Int {
        val primitive = source[key].asPrimitive(key)
        val value = primitive.intOrNull ?: primitive.content.trim().toIntOrNull()
        require(value != null && value > 0) { "JSON 字段 $key 必须是正整数" }
        return value
    }

    /**
     * 将 JSON 元素校验为基础值。
     *
     * @param key 用于错误说明的字段名
     * @return 已校验的 JSON 基础值
     */
    private fun JsonElement?.asPrimitive(key: String): JsonPrimitive =
        (this as? JsonPrimitive)?.takeUnless { it === JsonNull }
            ?: throw IllegalArgumentException("缺少 JSON 基础字段：$key")
}
