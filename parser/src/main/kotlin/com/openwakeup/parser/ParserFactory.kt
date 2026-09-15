package com.openwakeup.parser

import com.openwakeup.parser.web.GenericTableParser

/**
 * 解析器工厂：type → Parser 注册表。
 * 各家族解析器在应用启动时注册；未注册 type 回落到通用表格解析器。
 */
object ParserFactory {

    private val registry = LinkedHashMap<String, (ParserInput) -> Parser>()
    private val aliases = HashMap<String, String>()

    /** 注册 type（一个解析器实例可声明覆盖多个 type） */
    fun register(types: List<String>, provider: (ParserInput) -> Parser) {
        types.forEach { registry[it] = provider }
    }

    /** 注册别名（如 zf_1 → zf 家族同款） */
    fun registerAlias(alias: String, target: String) {
        aliases[alias] = target
    }

    private fun resolve(type: String): String? = when {
        registry.containsKey(type) -> type
        aliases.containsKey(type) -> aliases[type]
        else -> null
    }

    /**
     * 取解析器；优先精确 type，其次别名，最后使用通用解析器。
     */
    fun create(type: String): Parser {
        val resolved = resolve(type)
        return if (resolved != null) {
            registry.getValue(resolved)(ParserInput("", type))
        } else {
            GenericTableParser
        }
    }
}
