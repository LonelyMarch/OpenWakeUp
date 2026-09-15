package com.openwakeup.parser

import com.openwakeup.parser.csv.CsvParser
import com.openwakeup.parser.web.GenericTableParser
import com.openwakeup.parser.web.JzParser
import com.openwakeup.parser.web.KingosoftParser
import com.openwakeup.parser.web.QzParser
import com.openwakeup.parser.web.ShuweiParser
import com.openwakeup.parser.web.UrpParser
import com.openwakeup.parser.web.ZfParser

/**
 * 解析器注册中心：把各家族解析器挂到工厂，并声明每个家族覆盖的 type
 * 注册项必须覆盖离线学校数据声明的解析器类型。
 */
object Parsers {

    /** CSV 模板 type */
    private const val TYPE_CSV = "csv"

    private val csvParser = CsvParser()

    /** 家族 → type 全集（清单来自 schools.json；同一家族各 type 共用家族解析器） */
    private val familyTypes: Map<String, List<String>> = mapOf(
        "csv" to listOf(TYPE_CSV),
        "zf" to listOf("zf", "zf_1", "zf_new", "zf_cls"),
        "qz" to listOf(
            "qz", "qzType0", "qzType1", "qzType2", "qzType3", "qzType4", "qzType5", "qzType6",
            "qz_old", "qz_2017", "qz_2024",
        ),
        "urp" to listOf("urp", "urp_new", "urp_new_ajax"),
        "jz" to listOf("jz", "jz_1", "jz_x"),
        "kingosoft" to listOf("kingosoft", "kingo_new"),
        "shuwei" to listOf("shuwei", "shuwei_new"),
        "html" to listOf("html"),
        // 高校 POST 族 + 长尾：全部走 Web 抓取通路 + 通用/家族解析
        "generic" to listOf(
            "thu", "pku", "fdu", "cqu", "xmu", "tju", "hit", "buaa", "jlu", "hust",
            "nwpu", "sustech", "zju_post", "xjtu", "whu", "zzu", "swu", "nju",
            "sicau", "hebtu", "hzau",
            "cf", "vatuu", "yl", "south_soft", "umooc", "chaoxing",
        ),
    )

    /** 初始化注册（幂等） */
    fun registerAll() {
        familyTypes.forEach { (_, types) ->
            ParserFactory.register(types) { familyParser(types.first()) }
        }
        // 别名：老版本 type 写法
        ParserFactory.registerAlias("zf_1", "zf")
    }

    /** 家族解析器选择（家族间以表 id 偏好区分，正文解析共用容错网格核心） */
    private fun familyParser(type: String): Parser = when {
        type == TYPE_CSV -> csvParser
        type == "html" -> GenericTableParser
        type.startsWith("zf") -> ZfParser
        type.startsWith("qz") -> QzParser
        type.startsWith("urp") -> UrpParser
        type.startsWith("jz") -> JzParser
        type.startsWith("kingo") -> KingosoftParser
        type.startsWith("shuwei") -> ShuweiParser
        else -> GenericTableParser
    }
}
