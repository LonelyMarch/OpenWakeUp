package com.openwakeup.parser

import com.openwakeup.parser.web.AicParser
import com.openwakeup.parser.web.BfaParser
import com.openwakeup.parser.web.BfaPostParser
import com.openwakeup.parser.web.BjtuParser
import com.openwakeup.parser.web.BuaaParser
import com.openwakeup.parser.web.CcibeParser
import com.openwakeup.parser.web.CfNewParser
import com.openwakeup.parser.web.CfParser
import com.openwakeup.parser.web.ChangzhouParser
import com.openwakeup.parser.web.CidpParser
import com.openwakeup.parser.web.CnuParser
import com.openwakeup.parser.web.CppuParser
import com.openwakeup.parser.web.CquParser
import com.openwakeup.parser.web.CquptParser
import com.openwakeup.parser.web.CtguParser
import com.openwakeup.parser.web.CumtbParser
import com.openwakeup.parser.web.CuplPostParser
import com.openwakeup.parser.web.DhuParser
import com.openwakeup.parser.web.EcjtuParser
import com.openwakeup.parser.web.EcuplParser
import com.openwakeup.parser.web.FduParser
import com.openwakeup.parser.web.FstvcParser
import com.openwakeup.parser.web.GdbhParser
import com.openwakeup.parser.web.GdbyxyParser
import com.openwakeup.parser.web.GdeiParser
import com.openwakeup.parser.web.GxicParser
import com.openwakeup.parser.web.GxnuParser
import com.openwakeup.parser.web.GzhuyjsParser
import com.openwakeup.parser.web.HbmzuParser
import com.openwakeup.parser.web.HitParser
import com.openwakeup.parser.web.HitszParser
import com.openwakeup.parser.web.HniuParser
import com.openwakeup.parser.web.HnjmParser
import com.openwakeup.parser.web.HrbeuPostParser
import com.openwakeup.parser.web.HuatParser
import com.openwakeup.parser.web.HunnuShuweiParser
import com.openwakeup.parser.web.HustParser
import com.openwakeup.parser.web.JavtcParser
import com.openwakeup.parser.web.JlictQzOldParser
import com.openwakeup.parser.web.JluPostParser
import com.openwakeup.parser.web.JmptParser
import com.openwakeup.parser.web.JmucyParser
import com.openwakeup.parser.web.JxauParser
import com.openwakeup.parser.web.JxnuParser
import com.openwakeup.parser.web.JzParser
import com.openwakeup.parser.web.KgZxParser
import com.openwakeup.parser.web.KingoNewParser
import com.openwakeup.parser.web.LoginChaoxingParser
import com.openwakeup.parser.web.LzParser
import com.openwakeup.parser.web.NauParser
import com.openwakeup.parser.web.NfuParser
import com.openwakeup.parser.web.NjuParser
import com.openwakeup.parser.web.NnutcParser
import com.openwakeup.parser.web.NuaParser
import com.openwakeup.parser.web.NuaajcParser
import com.openwakeup.parser.web.NuistParser
import com.openwakeup.parser.web.NwpuPostParser
import com.openwakeup.parser.web.NyistParser
import com.openwakeup.parser.web.PkuParser
import com.openwakeup.parser.web.QmuParser
import com.openwakeup.parser.web.Qz2017Parser
import com.openwakeup.parser.web.QzAhutParser
import com.openwakeup.parser.web.QzBjfuParser
import com.openwakeup.parser.web.QzBrParser
import com.openwakeup.parser.web.QzCrazyParser
import com.openwakeup.parser.web.QzEcustParser
import com.openwakeup.parser.web.QzFsptParser
import com.openwakeup.parser.web.QzNjustParser
import com.openwakeup.parser.web.QzOldParser
import com.openwakeup.parser.web.QzParser
import com.openwakeup.parser.web.QzSingleNodeParser
import com.openwakeup.parser.web.QzUstbParser
import com.openwakeup.parser.web.QzWithNodeParser
import com.openwakeup.parser.web.RucParser
import com.openwakeup.parser.web.ScauParser
import com.openwakeup.parser.web.SdpeiParser
import com.openwakeup.parser.web.SeigParser
import com.openwakeup.parser.web.Seu2017Parser
import com.openwakeup.parser.web.ShccParser
import com.openwakeup.parser.web.ShtuPost2024Parser
import com.openwakeup.parser.web.ShtuPostParser
import com.openwakeup.parser.web.Shu2024Parser
import com.openwakeup.parser.web.ShuParser
import com.openwakeup.parser.web.ShuweiJsonParser
import com.openwakeup.parser.web.ShuweiMParser
import com.openwakeup.parser.web.ShuweiNewParser
import com.openwakeup.parser.web.SiasShuweiParser
import com.openwakeup.parser.web.SimcParser
import com.openwakeup.parser.web.SouthSoftParser
import com.openwakeup.parser.web.SudaPostParser
import com.openwakeup.parser.web.SuesParser
import com.openwakeup.parser.web.SwjtuPostParser
import com.openwakeup.parser.web.SwustParser
import com.openwakeup.parser.web.SysuParser
import com.openwakeup.parser.web.ThuParser
import com.openwakeup.parser.web.TjuParser
import com.openwakeup.parser.web.UcasParser
import com.openwakeup.parser.web.UestcPostParser
import com.openwakeup.parser.web.UestcShuweiParser
import com.openwakeup.parser.web.UmoocParser
import com.openwakeup.parser.web.UrpNewParser
import com.openwakeup.parser.web.UrpParser
import com.openwakeup.parser.web.UstcPostParser
import com.openwakeup.parser.web.VatuuParser
import com.openwakeup.parser.web.WhuPostParser
import com.openwakeup.parser.web.WistParser
import com.openwakeup.parser.web.XatuShuweiParser
import com.openwakeup.parser.web.XauatPostParser
import com.openwakeup.parser.web.XhtdParser
import com.openwakeup.parser.web.XjtuPostParser
import com.openwakeup.parser.web.XjuPostParser
import com.openwakeup.parser.web.XsyuShuweiParser
import com.openwakeup.parser.web.XytcParser
import com.openwakeup.parser.web.YguParser
import com.openwakeup.parser.web.YlParser
import com.openwakeup.parser.web.YsuPostParser
import com.openwakeup.parser.web.YzzyParser
import com.openwakeup.parser.web.ZfParser
import com.openwakeup.parser.web.ZjuPostParser
import com.openwakeup.parser.web.ZptcParser
import com.openwakeup.parser.web.ZtvtitParser

/**
 * 学校网页解析器的编译期静态工厂。
 *
 * `type` 是唯一行为选择键。每个已启用 type 必须直接对应自己的专用 Parser；
 * 尚未通过启用门禁的 type 明确失败，不使用别名、前缀匹配或通用解析器回落。
 */
object ParserFactory {

    /**
     * 按 type 惰性选择唯一 Parser。
     *
     * `when` 只会访问命中的 Kotlin `object`，因此某个学校 Parser 的静态字段初始化失败时，
     * 不会在导入其他学校时连带毒化整个 [ParserFactory]。这里仍保持编译期穷举映射，不使用
     * 别名、前缀匹配、反射或通用解析器回落。
     *
     * @param type `schools.json` 中的教务解析类型
     * @return 仅初始化并返回 type 唯一对应的 Parser
     */
    internal fun create(type: String): Parser = when (type) {
        "aic" -> AicParser
        "bfa" -> BfaParser
        "bfa_post" -> BfaPostParser
        "bjtu" -> BjtuParser
        "buaa" -> BuaaParser
        "ccibe" -> CcibeParser
        "cf" -> CfParser
        "cf_new" -> CfNewParser
        "changzhou" -> ChangzhouParser
        "cidp" -> CidpParser
        "cnu" -> CnuParser
        "cqu" -> CquParser
        "cqupt" -> CquptParser
        "cppu" -> CppuParser
        "ctgu" -> CtguParser
        "cumtb" -> CumtbParser
        "cupl_post" -> CuplPostParser
        "dhu" -> DhuParser
        "ecjtu" -> EcjtuParser
        "ecupl" -> EcuplParser
        "fdu" -> FduParser
        "fstvc" -> FstvcParser
        "gdbh" -> GdbhParser
        "gdbyxy" -> GdbyxyParser
        "gdei" -> GdeiParser
        "gxic" -> GxicParser
        "gxnu" -> GxnuParser
        "gzhuyjs" -> GzhuyjsParser
        "hbmzu" -> HbmzuParser
        "hit" -> HitParser
        "hitsz" -> HitszParser
        "hniu" -> HniuParser
        "hnjm" -> HnjmParser
        "hrbeu_post" -> HrbeuPostParser
        "huat" -> HuatParser
        "hunnu_shuwei" -> HunnuShuweiParser
        "hust" -> HustParser
        "javtc" -> JavtcParser
        "jlict_qz_old" -> JlictQzOldParser
        "jlu_post" -> JluPostParser
        "jmpt" -> JmptParser
        "jmucy" -> JmucyParser
        "jxau" -> JxauParser
        "jxnu" -> JxnuParser
        "jz" -> JzParser
        "kg_zx" -> KgZxParser
        "kingo_new" -> KingoNewParser
        "login_chaoxing" -> LoginChaoxingParser
        "lz" -> LzParser
        "nau" -> NauParser
        "nfu" -> NfuParser
        "nju" -> NjuParser
        "nnutc" -> NnutcParser
        "nua" -> NuaParser
        "nuaajc" -> NuaajcParser
        "nuist" -> NuistParser
        "nwpu_post" -> NwpuPostParser
        "nyist" -> NyistParser
        "pku" -> PkuParser
        "qmu" -> QmuParser
        "qz" -> QzParser
        "qz_2017" -> Qz2017Parser
        "qz_ahut" -> QzAhutParser
        "qz_bjfu" -> QzBjfuParser
        "qz_br" -> QzBrParser
        "qz_crazy" -> QzCrazyParser
        "qz_ecust" -> QzEcustParser
        "qz_fspt" -> QzFsptParser
        "qz_njust" -> QzNjustParser
        "qz_old" -> QzOldParser
        "qz_single_node" -> QzSingleNodeParser
        "qz_ustb" -> QzUstbParser
        "qz_with_node" -> QzWithNodeParser
        "ruc" -> RucParser
        "scau" -> ScauParser
        "sdpei" -> SdpeiParser
        "seig" -> SeigParser
        "seu_2017" -> Seu2017Parser
        "shcc" -> ShccParser
        "shtu_post" -> ShtuPostParser
        "shtu_post_2024" -> ShtuPost2024Parser
        "shu" -> ShuParser
        "shu2024" -> Shu2024Parser
        "shuwei_json" -> ShuweiJsonParser
        "shuwei_m" -> ShuweiMParser
        "shuwei_new" -> ShuweiNewParser
        "sias_shuwei" -> SiasShuweiParser
        "simc" -> SimcParser
        "south_soft" -> SouthSoftParser
        "suda_post" -> SudaPostParser
        "sues" -> SuesParser
        "swjtu_post" -> SwjtuPostParser
        "swust" -> SwustParser
        "sysu" -> SysuParser
        "thu" -> ThuParser
        "tju" -> TjuParser
        "ucas" -> UcasParser
        "uestc_post" -> UestcPostParser
        "uestc_shuwei" -> UestcShuweiParser
        "umooc" -> UmoocParser
        "urp" -> UrpParser
        "urp_new" -> UrpNewParser
        "ustc_post" -> UstcPostParser
        "vatuu" -> VatuuParser
        "whu_post" -> WhuPostParser
        "wist" -> WistParser
        "xatu_shuwei" -> XatuShuweiParser
        "xauat_post" -> XauatPostParser
        "xhtd" -> XhtdParser
        "xjtu_post" -> XjtuPostParser
        "xju_post" -> XjuPostParser
        "xsyu_shuwei" -> XsyuShuweiParser
        "xytc" -> XytcParser
        "yl" -> YlParser
        "ysu_post" -> YsuPostParser
        "ygu" -> YguParser
        "yzzy" -> YzzyParser
        "zf" -> ZfParser
        "zju_post" -> ZjuPostParser
        "zptc" -> ZptcParser
        "ztvtit" -> ZtvtitParser
        else -> throw UnsupportedParserTypeException(type)
    }

    /**
     * 根据输入中的唯一 type 直接完成解析。
     *
     * App 模块只依赖这个入口，不持有具体 Parser 实例，也不能绕过静态 type 路由。
     *
     * @param input App 已取得的原始文本输入
     * @return 非空课程预览列表
     */
    fun parse(input: ParserInput): List<CoursePreview> = create(input.type).parse(input)

}
