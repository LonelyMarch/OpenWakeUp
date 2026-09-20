package com.openwakeup.schedule.feature.importexport

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import com.openwakeup.schedule.core.util.WebSessionClient
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONTokener
import org.json.JSONObject
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * App 内部的网页导入原始载荷。
 *
 * 本类型只表达有序原始文本，不表达课程、周次或任意 Parser 私有 JSON 封套。A1 增加了
 * `shuwei_new/sues` 的 `/print-data` 原始响应捕获；其余 type 仍读取当前 WebView 页面。
 *
 * @property primaryText 当前页面或主响应的完整原文
 * @property additionalTexts 按获取顺序排列的其余原文
 */
internal data class WebImportPayload(
    val primaryText: String,
    val additionalTexts: List<String> = emptyList(),
)

/** 网页原始输入获取失败时使用的 App 层异常。 */
internal class WebImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 在已认证 WebView 会话内取得 Parser 原始文本。
 *
 * type 只用于选择后续输入流程；本阶段不读取课程字段，也不引用 parser 模块中的任何实现。
 * 具体 ParserInput 的构造仍由 WebLoginActivity 负责，保持 App/Parser 只有一个转换边界。
 *
 * @param webView 当前网页导入会话的 WebView
 * @param sessionClient 当前会话的原生请求门面，供后续 A～D 节点使用
 */
internal class WebImportSource(
    private val webView: WebView,
    private val sessionClient: WebSessionClient,
    private val type: String,
) : AutoCloseable {

    /** 当前由输入提供器发起、等待目标页面完成的导航。只允许同时存在一个导航。 */
    private var pendingNavigation: CancellableContinuation<Unit>? = null
    private var pendingNavigationUrl: String? = null

    init {
        // 客户端只按 URL 路径捕获响应，不读取 JSON 字段，也不依赖 Parser 实现。
        sessionClient.configureResponseCapture(
            if (type == SHUWEI_NEW_TYPE || type == SUES_TYPE) {
                { url -> Uri.parse(url).path?.endsWith(PRINT_DATA_SUFFIX) == true }
            } else {
                null
            },
        )
    }

    /** 获取指定 type 的原始载荷，并对 A1 的响应型 type 执行失败关闭。 */
    suspend fun acquire(type: String): WebImportPayload {
        if (type.isBlank()) throw WebImportException("未指定教务解析类型")
        if (type == UESTC_SHUWEI_TYPE) return WebImportPayload(acquireUestcShuwei())
        if (type == CUMTB_TYPE) return WebImportPayload(acquireCumtb())
        if (type == BUAA_TYPE) return WebImportPayload(acquireBuaa())
        if (type == LOGIN_CHAOXING_TYPE) return acquireLoginChaoxing()
        if (type == URP_NEW_TYPE) return WebImportPayload(acquireUrpNew())
        if (type == JLU_POST_TYPE) return WebImportPayload(acquireJluPost())
        if (type == ZJU_POST_TYPE) return acquireZjuPost()
        if (type == FSTVC_TYPE) return acquireFstvc()
        if (type == CCIBE_TYPE) return acquireCcibe()
        if (type == CPPU_TYPE) return acquireCppu()
        if (type == GDBH_TYPE) return WebImportPayload(acquireGdbh())
        if (type == YGU_TYPE) return acquireYgu()
        if (type == SHUWEI_NEW_TYPE || type == SUES_TYPE) {
            val captured = sessionClient.latestCapturedResponse()
                ?: throw WebImportException("尚未捕获到本次页面代次的 print-data 响应")
            val path = Uri.parse(captured.url).path.orEmpty()
            if (captured.code !in 200..299 || !path.endsWith(PRINT_DATA_SUFFIX)) {
                throw WebImportException("捕获到的课表响应路径或状态无效")
            }
            return WebImportPayload(
                captured.body.takeIf { it.isNotBlank() }
                    ?: throw WebImportException("print-data 响应为空"),
            )
        }
        val html = evaluateJavascript(GET_PAGE_HTML_JS)
            .takeIf { it.isNotBlank() }
            ?: throw WebImportException("当前网页没有可读取的页面内容")
        return WebImportPayload(html)
    }

    /**
     * 从电子科技大学本科旧 EAMS 页面提取 `window.table0.activities`。
     *
     * 该课表的课程语义只存在于页面运行时对象中，普通 DOM 网格不含可供 MTT 解析器读取的
     * `kcb_container` 或 `mtt_item_sksj`。固定 JavaScript 会递归检查当前页面与同源 frame，
     * 只复制 Parser 所需的原始字段，并统一把历史拼写 `vaildWeeks` 输出为 `validWeeks`。
     *
     * @return 不解释星期、节次和周次语义的稳定 JSON 封套
     * @throws WebImportException 页面尚未进入课表、课表模型未加载或脚本返回空值
     */
    private suspend fun acquireUestcShuwei(): String {
        val payload = evaluateJavascript(GET_UESTC_ACTIVITIES_JS)
            .takeIf { value -> value.isNotBlank() }
            ?: throw WebImportException(
                "电子科大页面中未找到 table0.activities，请进入“我的课表”并等待加载完成",
            )
        return payload
    }

    /**
     * 取得北航已登录页面的课表 JSON。
     *
     * 学期代码只能来自当前页面的明确文本或 `kbappTimeXQText`，不再使用原版固定回退学期。
     */
    private suspend fun acquireBuaa(): String {
        val html = evaluateJavascript(GET_PAGE_HTML_JS)
        val currentUrl = webView.url?.takeIf { it.isNotBlank() }
            ?: throw WebImportException("北航当前页面地址为空")
        val term = TERM_CODE_PATTERN.find(html)?.groupValues?.getOrNull(1)
            ?: throw WebImportException("北航页面缺少当前学期代码")
        val target = URI(currentUrl).resolve("../../../sys/homeapp/api/home/student/getMyScheduleDetail.do")
        validateSameOrigin(target, currentUrl, "北航课表接口")
        val response = sessionPostFormText(
            target.toString(),
            fields = mapOf("termCode" to term, "campusCode" to "", "type" to "term"),
            headers = mapOf("Accept" to "application/json"),
        )
        requireSuccess(response.code, "北航课表接口")
        return response.body.takeIf { it.isNotBlank() }
            ?: throw WebImportException("北航课表接口响应为空")
    }

    /**
     * 取得超星教务主响应与备用响应，Parser 决定主/备语义，不在 App 层按课程数回退。
     */
    private suspend fun acquireLoginChaoxing(): WebImportPayload {
        val params = evaluateJavascript(GET_CHAOXING_PARAMS_JS)
        val values = runCatching { JSONObject(params) }.getOrElse { error ->
            throw WebImportException("超星页面参数读取失败", error)
        }
        val xhid = values.optString("xhid").trim().takeIf { it.matches(CONTROL_VALUE_PATTERN) }
            ?: throw WebImportException("超星页面缺少合法 xhid")
        val xnxq = values.optString("xnxq").trim().takeIf { it.matches(CONTROL_VALUE_PATTERN) }
            ?: throw WebImportException("超星页面缺少合法 xnxq")
        val currentUrl = webView.url?.takeIf { it.isNotBlank() }
            ?: throw WebImportException("超星当前页面地址为空")
        val current = Uri.parse(currentUrl)
        if (current.scheme !in HTTP_SCHEMES || current.authority.isNullOrBlank()) {
            throw WebImportException("超星当前页面不是 HTTP(S) 页面")
        }
        val origin = "${current.scheme}://${current.authority}/"
        val primary = Uri.parse(origin).buildUpon()
            .appendPath("admin")
            .appendPath("api")
            .appendPath("getXskb")
            .appendQueryParameter("xnxq", xnxq)
            .appendQueryParameter("userId", xhid)
            .appendQueryParameter("xqid", "")
            .appendQueryParameter("role", "xs")
            .build().toString()
        val backup = Uri.parse(origin).buildUpon()
            .appendPath("admin")
            .appendPath("pkgl")
            .appendPath("xskb")
            .appendPath("sdpkkbList")
            .appendQueryParameter("xnxq", xnxq)
            .appendQueryParameter("xhid", xhid)
            .build().toString()
        val primaryResponse = sessionGetText(primary, headers = mapOf("Accept" to "application/json"))
        requireSuccess(primaryResponse.code, "超星主课表接口")
        val backupResponse = sessionGetText(backup, headers = mapOf("Accept" to "application/json"))
        requireSuccess(backupResponse.code, "超星备用课表接口")
        if (primaryResponse.body.isBlank() && backupResponse.body.isBlank()) {
            throw WebImportException("超星主、备用课表接口均返回空响应")
        }
        return WebImportPayload(primaryResponse.body, listOf(backupResponse.body))
    }

    /** 验证请求目标保持当前页面同源。 */
    private fun validateSameOrigin(target: URI, currentUrl: String, description: String) {
        val current = Uri.parse(currentUrl)
        if (target.scheme !in HTTP_SCHEMES || target.authority != current.authority) {
            throw WebImportException("$description 与当前页面不同源")
        }
    }

    /**
     * 从新版 URP 页面脚本中严格发现课表接口，并读取接口导航后的 JSON 页面文本。
     *
     * 这里只读取原始文本和控制字段；`dateList` 等课程字段仍由 [UrpNewParser] 解释。
     */
    private suspend fun acquireUrpNew(): String {
        val html = evaluateJavascript(GET_PAGE_HTML_JS)
        val currentUrl = webView.url?.takeIf { it.isNotBlank() }
            ?: throw WebImportException("新版 URP 当前页面地址为空")
        val target = findUrpTarget(currentUrl, html)
        val text = navigateAndReadBodyText(target)
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: throw WebImportException("新版 URP 接口页面为空")
        validateUrpResponse(text)
        return text
    }

    /**
     * 按吉林大学研究生系统的固定路径取得当前学期课表 JSON。
     */
    private suspend fun acquireJluPost(): String {
        val currentUrl = webView.url?.takeIf { it.isNotBlank() }
            ?: throw WebImportException("吉林大学当前页面地址为空")
        val semester = evaluateJavascript(GET_JLU_SEMESTER_JS)
            .trim()
            .takeIf { it.matches(SEMESTER_PATTERN) }
            ?: throw WebImportException("吉林大学页面缺少合法学期代码")
        val current = Uri.parse(currentUrl)
        val origin = "${current.scheme}://${current.authority}"
        if (current.scheme !in HTTP_SCHEMES || current.authority.isNullOrBlank()) {
            throw WebImportException("吉林大学当前页面不是 HTTP(S) 页面")
        }
        if (!current.path.orEmpty().contains("/gsapp/sys/")) {
            throw WebImportException("吉林大学当前页面不在研究生系统路径下")
        }
        val target = "$origin/gsapp/sys/wdkbapp/modules/xskcb/xsjxrwcx.do?XNXQDM=${Uri.encode(semester)}"
        val text = navigateAndReadBodyText(target)
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: throw WebImportException("吉林大学课表接口页面为空")
        validateJluResponse(text)
        return text
    }

    /**
     * 按原版 `#zc` 选择器取得浙江研究生课表的逐周 HTML。
     *
     * 原版在页面回传后读取 `#zc option`：首项文本包含“全”时只请求首项对应的值，否则按
     * 选项数量请求 `zc=1..N`。这里保留同样的请求顺序，但把页面作为独立原始文本交给 Parser。
     */
    private suspend fun acquireZjuPost(): WebImportPayload {
        val html = evaluateJavascript(GET_PAGE_HTML_JS)
        val currentUrl = webView.url?.takeIf { it.isNotBlank() }
            ?: throw WebImportException("zju_post 当前页面地址为空")
        val current = Uri.parse(currentUrl)
        if (current.scheme !in HTTP_SCHEMES || current.authority.isNullOrBlank()) {
            throw WebImportException("zju_post 当前页面不是 HTTP(S) 页面")
        }
        val selector = SELECT_PATTERN.find(html)?.groupValues?.getOrNull(1)
            ?: throw WebImportException("zju_post 页面缺少周次选择器")
        val options = OPTION_PATTERN.findAll(selector).mapNotNull { match ->
            val value = match.groupValues.getOrNull(1)?.trim().orEmpty()
            val label = stripTags(match.groupValues.getOrNull(2).orEmpty()).trim()
            value.takeIf { it.matches(NUMBER_PATTERN) }?.let { it to label }
        }.toList()
        require(options.isNotEmpty()) { "zju_post 周次选择器没有有效选项" }
        require(currentUrl.contains("zc=")) { "zju_post 当前地址缺少 zc 参数" }
        val targets = if (options.first().second.contains("全")) {
            listOf(currentUrl.replace(Regex("zc=-?\\d+"), "zc=${options.first().first}"))
        } else {
            (1..options.size).map { week ->
                currentUrl.replace(Regex("zc=-?\\d+"), "zc=$week")
            }
        }
        val pages = targets.mapIndexed { index, target ->
            val uri = Uri.parse(target)
            require(uri.scheme in HTTP_SCHEMES && uri.authority == current.authority) {
                "zju_post 第 ${index + 1} 页请求与当前页面不同源"
            }
            val response = sessionGetText(target, headers = mapOf("Accept" to "text/html"))
            requireSuccess(response.code, "zju_post 第 ${index + 1} 页")
            response.body.takeIf { it.isNotBlank() }
                ?: throw WebImportException("zju_post 第 ${index + 1} 页响应为空")
        }
        return WebImportPayload(pages.first(), pages.drop(1))
    }

    /**
     * 取得福州软件职业技术学院培养计划分页 JSON。
     *
     * API 路径必须来自当前会话的 `table#mainlist[data-options]` 的明确 `url` 字段；请求参数
     * 和最大页数与原版 FSTVC fetcher 一致，禁止写死 `jw.fzrjxy.com` 或跨源猜测。
     */
    private suspend fun acquireFstvc(): WebImportPayload {
        val currentUrl = webView.url?.takeIf { it.isNotBlank() }
            ?: throw WebImportException("fstvc 当前页面地址为空")
        val current = Uri.parse(currentUrl)
        if (current.scheme !in HTTP_SCHEMES || current.authority.isNullOrBlank()) {
            throw WebImportException("fstvc 当前页面不是 HTTP(S) 页面")
        }
        val origin = "${current.scheme}://${current.authority}"
        val planPage = URI(currentUrl).resolve("/studentportal.php/Jxxx/xxjdxx").toString()
        validateSameOrigin(URI(planPage), currentUrl, "fstvc 培养计划页面")
        val planResponse = sessionGetText(planPage, headers = mapOf("Accept" to "text/html"))
        requireSuccess(planResponse.code, "fstvc 培养计划页面")
        val options = MAINLIST_OPTIONS_PATTERN.find(planResponse.body)?.groupValues?.getOrNull(1)
            ?: throw WebImportException("fstvc 培养计划页面缺少 mainlist data-options")
        val apiPath = API_URL_PATTERN.find(options)?.groupValues?.getOrNull(1)
            ?: throw WebImportException("fstvc 培养计划页面缺少 API URL")
        val apiUrl = URI(origin + "/").resolve(apiPath).toString()
        validateSameOrigin(URI(apiUrl), currentUrl, "fstvc 培养计划接口")
        val pages = mutableListOf<String>()
        var page = 1
        var rowCount = 0
        var total = -1
        while (page <= MAX_FSTVC_PAGES) {
            val response = sessionPostFormText(
                apiUrl,
                fields = mapOf(
                    "page" to page.toString(),
                    "rows" to FSTVC_PAGE_SIZE.toString(),
                    "zc" to "0",
                    "sort" to "skrq",
                    "order" to "asc",
                ),
                headers = mapOf("Accept" to "application/json", "Referer" to planPage),
            )
            requireSuccess(response.code, "fstvc 第 $page 页")
            val body = response.body.takeIf { it.isNotBlank() }
                ?: throw WebImportException("fstvc 第 $page 页响应为空")
            val root = parseJsonObject(body, "fstvc 第 $page 页")
            val rows = root.optJSONArray("rows") ?: throw WebImportException("fstvc 第 $page 页缺少 rows")
            val pageTotal = root.optInt("total", -1)
            require(pageTotal >= 0) { "fstvc 第 $page 页缺少合法 total" }
            if (total < 0) total = pageTotal else require(total == pageTotal) { "fstvc 分页 total 不一致" }
            pages += body
            rowCount += rows.length()
            if (rowCount >= total) break
            require(rows.length() > 0) { "fstvc 第 $page 页为空但 total 未完成" }
            page += 1
        }
        require(total >= 0 && rowCount == total) { "fstvc 分页未完整取得全部 rows" }
        require(pages.isNotEmpty()) { "fstvc 没有取得任何分页响应" }
        return WebImportPayload(pages.first(), pages.drop(1))
    }

    /**
     * 取得重庆对外经贸学院主学期响应和逐周原始响应。
     *
     * 原版页面位于 `my.ccibe.edu.cn`，接口固定位于 `my.vpn.ccibe.edu.cn`。这是该学校的
     * 明确 WebVPN 规则，不允许把它泛化为任意跨源请求；课程字段和周次仍全部留给 Parser。
     */
    private suspend fun acquireCcibe(): WebImportPayload {
        val html = evaluateJavascript(GET_PAGE_HTML_JS)
        val currentUrl = webView.url?.takeIf { it.isNotBlank() }
            ?: throw WebImportException("ccibe 当前页面地址为空")
        val current = Uri.parse(currentUrl)
        require(current.scheme in HTTP_SCHEMES && current.host.equals(CCIBE_PAGE_HOST, true)) {
            "ccibe 当前页面必须位于 $CCIBE_PAGE_HOST"
        }
        val selectedText = CCIBE_SELECTED_PATTERN.find(html)?.groupValues?.getOrNull(1)
            ?.let(::stripTags)
            ?.replace("&amp;", "&")
            ?.trim()
            ?.takeIf { it.matches(CONTROL_VALUE_PATTERN) }
            ?: throw WebImportException("ccibe 页面缺少合法的当前学期标识")
        val cookie = CookieManager.getInstance().getCookie(currentUrl).orEmpty()
        val requestHeaders = buildMap {
            put("Accept", "application/json")
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
        val baseUrl = "$CCIBE_VPN_ORIGIN/api/manager/synchronize/synchronizestudent/curriculumNew"
        val primaryUrl = Uri.parse(baseUrl).buildUpon()
            .appendQueryParameter("calendarId", selectedText)
            .appendQueryParameter("week", "")
            .build().toString()
        val primary = sessionGetText(primaryUrl, requestHeaders)
        requireSuccess(primary.code, "ccibe 学期接口")
        val primaryRoot = parseJsonObject(primary.body, "ccibe 学期接口")
        val data = primaryRoot.optJSONObject("data")
            ?: throw WebImportException("ccibe 学期接口缺少 data")
        val calendar = data.optJSONObject("calendar")
            ?: throw WebImportException("ccibe 学期接口缺少 calendar")
        val calendarId = calendar.optString("id").trim().takeIf { it.matches(CONTROL_VALUE_PATTERN) }
            ?: throw WebImportException("ccibe 学期接口缺少合法 calendar.id")
        val allWeek = calendar.optString("allWeek").trim().toIntOrNull()
            ?: throw WebImportException("ccibe 学期接口缺少合法 allWeek")
        require(allWeek in 1..MAX_CCIBE_WEEKS) { "ccibe 学期周数超出允许范围：$allWeek" }
        val weekly = (1..allWeek).map { week ->
            val target = Uri.parse(baseUrl).buildUpon()
                .appendQueryParameter("calendarId", calendarId)
                .appendQueryParameter("week", week.toString())
                .build().toString()
            val response = sessionGetText(target, requestHeaders)
            requireSuccess(response.code, "ccibe 第 $week 周接口")
            response.body.takeIf { it.isNotBlank() }
                ?: throw WebImportException("ccibe 第 $week 周响应为空")
        }
        return WebImportPayload(primary.body, weekly)
    }

    /**
     * 按 6.3.0 原版顺序取得中国人民警察大学的开学日和课表原始响应。
     *
     * 学期响应只用于提取 `XNXQ_CODE`，不进入 Parser；最终交给 Parser 的仅是包含 `jxzqsrq`
     * 和 `rows` 的两个响应，从而避免 App 构造私有 JSON 封套。
     */
    private suspend fun acquireCppu(): WebImportPayload {
        val currentUrl = webView.url?.takeIf { it.isNotBlank() }
            ?: throw WebImportException("cppu 当前页面地址为空")
        val current = Uri.parse(currentUrl)
        require(current.scheme in HTTP_SCHEMES && current.host.equals(CPPU_HOST, true)) {
            "cppu 当前页面必须位于 $CPPU_HOST"
        }
        val origin = "${current.scheme}://${current.authority}"
        val termMode = evaluateJavascript(GET_CPPU_TERM_MODE_JS)
        val termPath = if (termMode == CPPU_NEXT_TERM_MODE) {
            "/je/system/getNowNextXnxq"
        } else {
            "/je/system/getNowXnxq"
        }
        val termResponse = sessionGetText(origin + termPath, mapOf("Accept" to "application/json"))
        requireSuccess(termResponse.code, "cppu 学期接口")
        val termRoot = parseJsonObject(termResponse.body, "cppu 学期接口")
        val termCode = termRoot.opt("data")?.let { dataValue ->
            if (dataValue is JSONObject) dataValue.optJSONObject("values")?.optString("DM") else dataValue.toString()
        }?.trim()?.takeIf { it.matches(CONTROL_VALUE_PATTERN) && !it.equals("null", true) }
            ?: throw WebImportException("cppu 学期接口缺少合法 data.values.DM")

        val startResponse = sessionPostMultipartText(
            origin + "/edumis_je/pkgl/pksz/xlsj/getClassFormDefault.action",
            fields = mapOf("xnxqCode" to termCode),
            headers = mapOf("Accept" to "application/json"),
        )
        requireSuccess(startResponse.code, "cppu 开学日接口")
        val startRoot = parseJsonObject(startResponse.body, "cppu 开学日接口")
        startRoot.optString("jxzqsrq").trim().takeIf { it.isNotEmpty() }
            ?: throw WebImportException("cppu 开学日接口缺少 jxzqsrq")

        val query = """{"custom":[{"type":"and","value":[{"type":"=","code":"XNXQ_CODE","value":"$termCode","cn":"and"}]}],"_custom_types":["strategy"]}"""
        val courseResponse = sessionPostMultipartText(
            origin + "/je/load",
            fields = mapOf(
                "funcCode" to "V_JWBZK_PK_XSKBZHCX",
                "funcId" to "n3VPye8LJPUucKtVB1H",
                "columnLazy" to "true",
                "mark" to "false",
                "postil" to "",
                "funcEdit" to "false",
                "coverJquery" to "0",
                "tableCode" to "V_JWBZK_PK_XSKBZHCX",
                "_isFunc_" to "true",
                "j_query" to query,
                "j_order" to "[]",
                "dbQueryObj" to "{}",
                "page" to "1",
                "start" to "0",
                "limit" to "100000",
            ),
            headers = mapOf("Accept" to "application/json"),
        )
        requireSuccess(courseResponse.code, "cppu 课表接口")
        val courseRoot = parseJsonObject(courseResponse.body, "cppu 课表接口")
        require(courseRoot.has("rows")) { "cppu 课表接口缺少 rows" }
        return WebImportPayload(startResponse.body, listOf(courseResponse.body))
    }

    /**
     * 取得桂电北海校区最终课表响应。
     *
     * 原版先从固定的 `/student/StuInfo` 读取顶层 `term`，再以固定 Referer 请求
     * `/student/getstutable`。中间响应只用于控制请求，不进入 Parser。
     */
    private suspend fun acquireGdbh(): String {
        val currentUrl = webView.url?.takeIf { it.isNotBlank() }
            ?: throw WebImportException("gdbh 当前页面地址为空")
        val current = Uri.parse(currentUrl)
        require(current.scheme == "http" && current.host == GDBH_HOST) {
            "gdbh 当前页面必须位于 http://$GDBH_HOST"
        }
        val origin = "http://$GDBH_HOST"
        val referer = "$origin/Login/MainDesktop"
        val cookie = CookieManager.getInstance().getCookie(currentUrl).orEmpty()
        val headers = buildMap {
            put("Accept", "application/json")
            put("Referer", referer)
            put("Content-Type", "application/json")
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
        val info = sessionGetText("$origin/student/StuInfo", headers)
        requireSuccess(info.code, "gdbh 学生信息接口")
        val infoRoot = parseJsonObject(info.body, "gdbh 学生信息接口")
        val term = infoRoot.optString("term").trim()
            .takeIf { it.matches(TERM_CONTROL_PATTERN) }
            ?: throw WebImportException("gdbh 学生信息接口缺少合法 term")
        val target = Uri.parse("$origin/student/getstutable").buildUpon()
            .appendQueryParameter("_dc", System.currentTimeMillis().toString())
            .appendQueryParameter("term", term)
            .appendQueryParameter("page", "1")
            .appendQueryParameter("start", "0")
            .appendQueryParameter("limit", GDBH_PAGE_LIMIT.toString())
            .build().toString()
        val table = sessionGetText(target, headers)
        requireSuccess(table.code, "gdbh 课表接口")
        val tableRoot = parseJsonObject(table.body, "gdbh 课表接口")
        require(tableRoot.optJSONArray("data") != null) { "gdbh 课表接口缺少 data" }
        return table.body.takeIf { it.isNotBlank() }
            ?: throw WebImportException("gdbh 课表响应为空")
    }

    /**
     * 取得阳光学院最多两个候选学期的原始课程响应。
     *
     * App 只读取学期代码、用户身份和响应状态来编排请求，不读取 `weekList/kcbVoList`；
     * 候选响应完整传入 `YguParser`，由 Parser 选择第一个有效课程响应。
     */
    private suspend fun acquireYgu(): WebImportPayload {
        val currentUrl = webView.url?.takeIf { it.isNotBlank() }
            ?: throw WebImportException("ygu 当前页面地址为空")
        val current = Uri.parse(currentUrl)
        require(current.scheme in HTTP_SCHEMES && current.host.equals(YGU_HOST, true)) {
            "ygu 当前页面必须位于 $YGU_HOST"
        }
        val cookieHeader = CookieManager.getInstance().getCookie(currentUrl).orEmpty()
        val accessToken = ACCESS_TOKEN_PATTERN.find(cookieHeader)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() && it.length <= MAX_TOKEN_LENGTH }
            ?: throw WebImportException("ygu 当前会话缺少 Access-Token Cookie")
        val headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json;charset=UTF-8",
            "Cookie" to "Access-Token=$accessToken",
        )
        val apiOrigin = "${current.scheme}://${current.authority}/api"
        val semestersResponse = sessionPostJsonText(
            "$apiOrigin/jw/xlgl/listR",
            "{}",
            headers,
        )
        requireSuccess(semestersResponse.code, "ygu 学期列表接口")
        val semestersRoot = parseJsonObject(semestersResponse.body, "ygu 学期列表接口")
        require(semestersRoot.optInt("code", Int.MIN_VALUE) == 0) { "ygu 学期列表接口返回失败" }
        val semesterRows = semestersRoot.optJSONArray("data")
            ?: throw WebImportException("ygu 学期列表接口缺少 data")
        require(semesterRows.length() > 0) { "ygu 学期列表为空" }
        val semesterCodes = (0 until minOf(semesterRows.length(), YGU_MAX_SEMESTERS)).map { index ->
            semesterRows.optJSONObject(index)?.optString("xqjc")?.trim()
                ?.takeIf { it.matches(CONTROL_VALUE_PATTERN) }
                ?: throw WebImportException("ygu 第 ${index + 1} 个学期缺少合法 xqjc")
        }
        val userResponse = sessionGetText("$apiOrigin/system/user/getInfo", headers)
        requireSuccess(userResponse.code, "ygu 用户信息接口")
        val userRoot = parseJsonObject(userResponse.body, "ygu 用户信息接口")
        require(userRoot.optInt("code", Int.MIN_VALUE) == 0) { "ygu 用户信息接口返回失败" }
        val user = userRoot.optJSONObject("user")
            ?: throw WebImportException("ygu 用户信息接口缺少 user")
        val studentNumber = user.optString("studentsNumber").trim()
            .takeIf { it.isNotEmpty() && it.length <= MAX_USER_FIELD_LENGTH }
            ?: throw WebImportException("ygu 用户信息接口缺少 studentsNumber")
        val nickname = user.optString("nickName").trim()
            .takeIf { it.isNotEmpty() && it.length <= MAX_USER_FIELD_LENGTH }
            ?: throw WebImportException("ygu 用户信息接口缺少 nickName")
        val responses = semesterCodes.map { semesterCode ->
            val body = JSONObject()
                .put("xm", nickname)
                .put("xh", studentNumber)
                .put("xqjc", semesterCode)
                .toString()
            val response = sessionPostJsonText(
                "$apiOrigin/jw/kpkglkcb/student",
                body,
                headers,
            )
            requireSuccess(response.code, "ygu $semesterCode 课表接口")
            response.body.takeIf { it.isNotBlank() }
                ?: throw WebImportException("ygu $semesterCode 课表响应为空")
        }
        return WebImportPayload(responses.first(), responses.drop(1))
    }

    /** 从原版明确的函数体中提取唯一接口 URL，并验证同源。 */
    private fun findUrpTarget(currentUrl: String, html: String): String {
        val current = Uri.parse(currentUrl)
        if (current.scheme !in HTTP_SCHEMES || current.authority.isNullOrBlank()) {
            throw WebImportException("新版 URP 当前页面不是 HTTP(S) 页面")
        }
        val searchBodies = (FUNCTION_BODY_PATTERN.findAll(html) + SEARCH_FUNCTION_PATTERN.findAll(html))
            .mapNotNull { it.groupValues.getOrNull(1) }
            .toList()
        val getBodies = (GET_COURSE_BODY_PATTERN.findAll(html) + GET_COURSE_FUNCTION_PATTERN.findAll(html))
            .mapNotNull { it.groupValues.getOrNull(1) }
            .toList()
        val candidates = buildList {
            searchBodies.forEach { body ->
                URL_ASSIGNMENT_PATTERN.findAll(body).forEach { add(it.groupValues[1]) }
            }
            if (isEmpty() && getBodies.isNotEmpty()) {
                val plan = Regex("id\\s*=\\s*[\\\"']planCodec[\\\"'][^>]*value\\s*=\\s*[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.getOrNull(1)
                val clazz = Regex("id\\s*=\\s*[\\\"']classCodec[\\\"'][^>]*value\\s*=\\s*[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.getOrNull(1)
                if (!plan.isNullOrBlank() && !clazz.isNullOrBlank()) {
                    getBodies.forEach { body ->
                        URL_ASSIGNMENT_PATTERN.findAll(body).forEach { match ->
                            add(Uri.parse(match.groupValues[1]).buildUpon()
                                .appendQueryParameter("planCode", plan)
                                .appendQueryParameter("classCode", clazz)
                                .build().toString())
                        }
                    }
                }
            }
        }.map { URI(currentUrl).resolve(it).toString() }.distinct()
        if (candidates.size != 1) throw WebImportException("新版 URP 未找到唯一课表接口")
        val target = Uri.parse(candidates.single())
        if (target.scheme !in HTTP_SCHEMES || target.authority != current.authority) {
            throw WebImportException("新版 URP 课表接口与当前页面不同源")
        }
        return candidates.single()
    }

    /** 验证 URP 目标响应是 Parser 约定的根结构，拒绝登录页或错误页。 */
    private fun validateUrpResponse(text: String) {
        val root = parseJsonObject(text, "新版 URP 课表接口")
        if (root.optJSONArray("dateList") == null) {
            throw WebImportException("新版 URP 接口返回的不是预期课表 JSON")
        }
    }

    /** 验证吉林大学目标响应包含研究生课表数据根节点。 */
    private fun validateJluResponse(text: String) {
        val root = parseJsonObject(text, "吉林大学课表接口")
        val rows = root.optJSONObject("datas")?.optJSONObject("xsjxrwcx")?.optJSONArray("rows")
        if (rows == null) throw WebImportException("吉林大学接口返回的不是预期课表 JSON")
    }

    /**
     * 导航到由本地规则构造的同源目标，等待页面完成后读取 body.innerText。
     * 页面完成回调由 Activity 转发，超时或销毁时均失败关闭。
     */
    private suspend fun navigateAndReadBodyText(targetUrl: String): String {
        val target = Uri.parse(targetUrl)
        val current = Uri.parse(webView.url.orEmpty())
        if (target.scheme !in HTTP_SCHEMES || target.authority.isNullOrBlank() || target.authority != current.authority) {
            throw WebImportException("目标课表接口与当前页面不同源")
        }
        withTimeout(NAVIGATION_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { continuation ->
                check(pendingNavigation == null) { "已有课表接口导航正在执行" }
                pendingNavigation = continuation
                pendingNavigationUrl = targetUrl
                continuation.invokeOnCancellation {
                    if (pendingNavigation === continuation) {
                        pendingNavigation = null
                        pendingNavigationUrl = null
                    }
                }
                webView.post { webView.loadUrl(targetUrl) }
            }
        }
        return evaluateJavascript(GET_BODY_TEXT_JS)
    }

    /** 顶层页面开始导航时取消旧目标，避免误把前一代回调交给当前等待。 */
    internal fun onPageStarted(url: String?) {
        if (pendingNavigation != null && url != pendingNavigationUrl) {
            pendingNavigation?.cancel(WebImportException("课表接口导航被新的页面导航打断"))
        }
    }

    /** 顶层页面完成导航时，仅对同源且路径一致的目标恢复等待。 */
    internal fun onPageFinished(url: String?) {
        val continuation = pendingNavigation ?: return
        val target = pendingNavigationUrl ?: return
        val actual = Uri.parse(url.orEmpty())
        val expected = Uri.parse(target)
        if (actual.scheme == expected.scheme && actual.authority == expected.authority && actual.path == expected.path) {
            pendingNavigation = null
            pendingNavigationUrl = null
            continuation.resume(Unit)
        }
    }

    /**
     * 按 6.3.0 的页面控制字段取得 cumtb 最终原始响应。
     *
     * App 只读取 semester/dataId/bizTypeId/上下文路径等请求控制信息；lessonIds 仅用于判断
     * 下一步请求和构造原始 POST，不读取课程字段，也不在这里判断课程是否有效。
     */
    private suspend fun acquireCumtb(): String {
        val html = evaluateJavascript(GET_PAGE_HTML_JS)
        val currentUrl = webView.url?.takeIf { it.isNotBlank() }
            ?: throw WebImportException("cumtb 当前页面地址为空")
        val baseUrl = deriveCumtbBase(currentUrl, html)
        val semesterId = Regex(
            "id\\s*=\\s*[\\\"']allSemesters[\\\"'][\\s\\S]*?<option[^>]*value\\s*=\\s*[\\\"']([^\\\"']+)",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw WebImportException("cumtb 页面缺少当前学期值")
        val dataId = Regex("var\\s+dataId\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()
            ?.takeUnless { it.equals("null", true) || it.isBlank() }
        val bizTypeId = Regex("bizTypeId\\s*:\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: throw WebImportException("cumtb 页面缺少 bizTypeId")
        val personId = Regex("var\\s+studentId\\s*=\\s*\\[([^]]*)]", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotEmpty() }

        val firstResponse = sessionGetText(
            buildGetDataUrl(baseUrl, semesterId, bizTypeId, dataId),
            headers = mapOf("Accept" to "application/json"),
        )
        requireSuccess(firstResponse.code, "cumtb get-data")
        var finalBody = firstResponse.body
        var root = parseJsonObject(finalBody, "cumtb get-data")
        if (isFinalCumtbResponse(root)) return finalBody

        var lessonIds = root.optJSONArray("lessonIds")
        if (lessonIds != null && lessonIds.length() == 0 && bizTypeId != "23") {
            val retryResponse = sessionGetText(
                buildGetDataUrl(baseUrl, semesterId, "23", dataId),
                headers = mapOf("Accept" to "application/json"),
            )
            requireSuccess(retryResponse.code, "cumtb get-data bizTypeId=23")
            finalBody = retryResponse.body
            root = parseJsonObject(finalBody, "cumtb get-data bizTypeId=23")
            if (isFinalCumtbResponse(root)) return finalBody
            lessonIds = root.optJSONArray("lessonIds")
        }
        if (lessonIds == null) {
            if (!personId.isNullOrBlank()) {
                val printResponse = sessionGetText(
                    "$baseUrl/for-std/course-table/semester/$semesterId/print-data/0/$personId",
                    headers = mapOf("Accept" to "application/json"),
                )
                requireSuccess(printResponse.code, "cumtb print-data")
                return printResponse.body.takeIf { it.isNotBlank() }
                    ?: throw WebImportException("cumtb print-data 响应为空")
            }
            throw WebImportException("cumtb get-data 缺少 lessonIds")
        }
        require(lessonIds.length() > 0) { "cumtb get-data 的 lessonIds 为空" }
        val datumBody = JSONObject()
            .put("lessonIds", lessonIds)
            .put("studentId", "")
            .put("weekIndex", "")
            .toString()
        val datumResponse = sessionPostJsonText(
            "$baseUrl/ws/schedule-table/datum",
            datumBody,
            headers = mapOf("Accept" to "application/json"),
        )
        requireSuccess(datumResponse.code, "cumtb schedule-table/datum")
        return datumResponse.body.takeIf { it.isNotBlank() }
            ?: throw WebImportException("cumtb datum 响应为空")
    }

    /** 构造 6.3.0 get-data 查询地址。 */
    private fun buildGetDataUrl(
        baseUrl: String,
        semesterId: String,
        bizTypeId: String,
        dataId: String?,
    ): String = buildString {
        append(baseUrl).append("/for-std/course-table/get-data?bizTypeId=")
        append(Uri.encode(bizTypeId)).append("&semesterId=").append(Uri.encode(semesterId))
        if (!dataId.isNullOrBlank()) append("&dataId=").append(Uri.encode(dataId))
    }

    /** 从当前 URL 与页面声明的 CONTEXT_PATH 推导同源业务根地址。 */
    private fun deriveCumtbBase(currentUrl: String, html: String): String {
        val uri = Uri.parse(currentUrl)
        val authority = uri.authority?.takeIf { it.isNotBlank() }
            ?: throw WebImportException("cumtb 当前 URL 缺少主机")
        val origin = "${uri.scheme}://$authority"
        val path = uri.path.orEmpty()
        val forStdIndex = path.indexOf("/for-std")
        if (forStdIndex >= 0) return (origin + path.substring(0, forStdIndex)).trimEnd('/')
        val context = Regex("CONTEXT_PATH\\s*=\\s*['\\\"]([^'\\\"]*)", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()?.trim('/')
            ?: throw WebImportException("cumtb 页面缺少 CONTEXT_PATH")
        return if (context.isBlank()) origin else "$origin/$context"
    }

    /** 判断响应是否已经是 Parser 所需的最终原始结构。 */
    private fun isFinalCumtbResponse(root: JSONObject): Boolean =
        root.has("studentTableVms") || root.has("studentTableVm") ||
            (root.optJSONObject("result")?.has("lessonList") == true &&
                root.optJSONObject("result")?.has("scheduleList") == true)

    /** 解析请求控制阶段的 JSON 对象，不解释课程字段。 */
    private fun parseJsonObject(text: String, stage: String): JSONObject =
        runCatching { JSONObject(text) }.getOrElse { error ->
            throw WebImportException("$stage 未返回 JSON 对象", error)
        }

    /** 校验原生请求状态码，异常时立即失败关闭。 */
    private fun requireSuccess(code: Int, stage: String) {
        require(code in 200..299) { "$stage 请求失败：HTTP $code" }
    }

    /** 去除周次选项标签，仅用于判断原版“全”选项，不读取课程字段。 */
    private fun stripTags(value: String): String = value.replace(Regex("(?is)<[^>]+>"), "")

    /** 在 IO 调度器执行同步会话 GET，避免阻塞 WebView 主线程。 */
    private suspend fun sessionGetText(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): WebSessionClient.TextResponse = withContext(Dispatchers.IO) {
        sessionClient.getText(url, headers)
    }

    /** 在 IO 调度器执行同步会话表单 POST。 */
    private suspend fun sessionPostFormText(
        url: String,
        fields: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): WebSessionClient.TextResponse = withContext(Dispatchers.IO) {
        sessionClient.postFormText(url, fields, headers)
    }

    /** 在 IO 调度器执行同步会话 JSON POST。 */
    private suspend fun sessionPostJsonText(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
    ): WebSessionClient.TextResponse = withContext(Dispatchers.IO) {
        sessionClient.postJsonText(url, json, headers)
    }

    /** 在 IO 调度器执行同步会话 multipart POST。 */
    private suspend fun sessionPostMultipartText(
        url: String,
        fields: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): WebSessionClient.TextResponse = withContext(Dispatchers.IO) {
        sessionClient.postMultipartText(url, fields, headers)
    }

    /** 释放输入门面并清除短期响应，避免 Activity 结束后保留课表原文。 */
    override fun close() {
        pendingNavigation?.cancel(WebImportException("网页导入已结束"))
        pendingNavigation = null
        pendingNavigationUrl = null
        sessionClient.configureResponseCapture(null)
    }

    /** 以可取消挂起方式执行固定 JavaScript，并解码 evaluateJavascript 的 JSON 字符串结果。 */
    private suspend fun evaluateJavascript(script: String): String =
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(script) { result ->
                if (continuation.isActive) {
                    runCatching {
                        JSONTokener(result ?: "null").nextValue()?.toString().orEmpty()
                    }.onSuccess { value -> continuation.resume(value) }
                        .onFailure { error ->
                            continuation.resumeWithException(
                                WebImportException("读取当前网页失败", error),
                            )
                        }
                }
            }
            continuation.invokeOnCancellation { /* WebView 销毁时回调结果直接丢弃。 */ }
        }

    private companion object {
        private val HTTP_SCHEMES = setOf("http", "https")
        private val SEMESTER_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        private val CONTROL_VALUE_PATTERN = Regex("[A-Za-z0-9_:-]{1,128}")
        private val TERM_CODE_PATTERN = Regex("(20\\d\\d-20\\d\\d-[1-9])")
        private const val NAVIGATION_TIMEOUT_MS = 30_000L
        const val CUMTB_TYPE = "cumtb"
        const val BUAA_TYPE = "buaa"
        const val UESTC_SHUWEI_TYPE = "uestc_shuwei"
        const val LOGIN_CHAOXING_TYPE = "login_chaoxing"
        const val URP_NEW_TYPE = "urp_new"
        const val JLU_POST_TYPE = "jlu_post"
        const val ZJU_POST_TYPE = "zju_post"
        const val FSTVC_TYPE = "fstvc"
        const val CCIBE_TYPE = "ccibe"
        const val CPPU_TYPE = "cppu"
        const val GDBH_TYPE = "gdbh"
        const val YGU_TYPE = "ygu"
        const val SHUWEI_NEW_TYPE = "shuwei_new"
        const val SUES_TYPE = "sues"
        const val PRINT_DATA_SUFFIX = "/print-data"
        private const val FSTVC_PAGE_SIZE = 30
        private const val MAX_FSTVC_PAGES = 64
        private const val MAX_CCIBE_WEEKS = 64
        private const val CCIBE_PAGE_HOST = "my.ccibe.edu.cn"
        private const val CCIBE_VPN_ORIGIN = "https://my.vpn.ccibe.edu.cn"
        private const val CPPU_HOST = "jw.cppu.edu.cn"
        private const val GDBH_HOST = "192.168.5.88"
        private const val YGU_HOST = "ygu.edu.cn"
        private const val GDBH_PAGE_LIMIT = 50
        private const val YGU_MAX_SEMESTERS = 2
        private const val MAX_TOKEN_LENGTH = 4096
        private const val MAX_USER_FIELD_LENGTH = 256
        private val NUMBER_PATTERN = Regex("-?\\d+")
        private val TERM_CONTROL_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        private val ACCESS_TOKEN_PATTERN = Regex("(?:^|;\\s*)Access-Token=([^;]+)")
        private val CCIBE_SELECTED_PATTERN = Regex(
            "(?is)<[^>]*id\\s*=\\s*[\\\"']rc_select_0_list[\\\"'][^>]*>[\\s\\S]*?<[^>]*aria-selected\\s*=\\s*[\\\"']true[\\\"'][^>]*>([\\s\\S]*?)</[^>]+>",
        )
        private val SELECT_PATTERN = Regex(
            "(?is)<select\\b(?=[^>]*\\bid\\s*=\\s*[\\\"']zc[\\\"'])[^>]*>(.*?)</select>",
        )
        private val OPTION_PATTERN = Regex(
            "(?is)<option\\b[^>]*\\bvalue\\s*=\\s*[\\\"']([^\\\"']+)[\\\"'][^>]*>(.*?)</option>",
        )
        private val MAINLIST_OPTIONS_PATTERN = Regex(
            "(?is)<table\\b(?=[^>]*\\bid\\s*=\\s*[\\\"']mainlist[\\\"'])(?=[^>]*\\bdata-options\\s*=\\s*[\\\"']([^\\\"']+)[\\\"'])",
        )
        private val API_URL_PATTERN = Regex("(?i)\\burl\\s*:\\s*['\\\"]([^'\\\"]+)")

        /** 递归读取当前文档及同源 iframe 的完整 HTML。 */
        const val GET_PAGE_HTML_JS =
            "(function(){function f(d){var s='';if(d){try{s=d.documentElement.outerHTML;}catch(e){}" +
                "var fr=d.querySelectorAll('iframe');for(var i=0;i<fr.length;i++){try{s+='\\n'+f(fr[i].contentDocument);}catch(e){}}}" +
                "return s;}return f(window.document);})()"

        /**
         * 递归查找电子科大旧 EAMS 的 `table0.activities`，并只序列化解析器需要的原始字段。
         *
         * 使用传统函数与 `var` 保持对学校旧 WebView 脚本环境的兼容；frame 访问异常只跳过当前
         * frame，不得阻断其他同源上下文的探测。
         */
        const val GET_UESTC_ACTIVITIES_JS =
            "(function(){" +
                "var visited=[];" +
                "function weeks(values){var source=values||[];var result=[];" +
                "for(var i=0;i<source.length;i++){result.push(source[i]===true||Number(source[i])===1?1:0);}return result;}" +
                "function serialize(model){var slots=[];" +
                "for(var i=0;i<model.length;i++){var sourceSlot=model[i]||[];var slot=[];" +
                "for(var j=0;j<sourceSlot.length;j++){var course=sourceSlot[j]||{};slot.push({" +
                "courseName:String(course.courseName||''),teacherName:String(course.teacherName||'')," +
                "roomName:String(course.roomName||''),validWeeks:weeks(course.vaildWeeks||course.validWeeks)});}" +
                "slots.push(slot);}return JSON.stringify({activities:slots});}" +
                "function find(candidate){if(!candidate||visited.indexOf(candidate)>=0)return '';visited.push(candidate);" +
                "try{if(candidate.table0&&candidate.table0.activities&&" +
                "typeof candidate.table0.activities.length==='number'){return serialize(candidate.table0.activities);}" +
                "var frames=candidate.document.querySelectorAll('frame,iframe');" +
                "for(var i=0;i<frames.length;i++){try{var found=find(frames[i].contentWindow);if(found)return found;}catch(e){}}" +
                "}catch(e){}return '';}return find(window);})()"

        const val GET_BODY_TEXT_JS = "(function(){return document.body ? document.body.innerText : '';})()"
        const val GET_JLU_SEMESTER_JS = "(function(){var e=document.getElementById('myXnxqSelect');if(!e)return '';return e.value || (e.options[e.selectedIndex] ? e.options[e.selectedIndex].value : '');})()"
        const val GET_CHAOXING_PARAMS_JS = "(function(){function v(id){var e=document.getElementById(id);return e ? (e.value || e.getAttribute('value') || '') : '';}var xhid=v('xhid');var xnxq=v('xnxq');if(!xnxq){var nodes=document.querySelectorAll('input[name=xnxq]');for(var i=0;i<nodes.length;i++){if(nodes[i].value){xnxq=nodes[i].value;break;}}}return JSON.stringify({xhid:xhid,xnxq:xnxq});})()"
        const val GET_CPPU_TERM_MODE_JS = "(function(){var e=document.querySelector('.je-grid-btnbar-queryStrategy');var p=e&&e.parentElement;return p&&p.innerText&&p.innerText.indexOf('下一')>=0?'next':'current';})()"
        const val CPPU_NEXT_TERM_MODE = "next"
        val FUNCTION_BODY_PATTERN = Regex("searchSemester\\s*=\\s*function\\s*\\([^)]*\\)\\s*\\{([\\s\\S]*?)\\}", RegexOption.IGNORE_CASE)
        val SEARCH_FUNCTION_PATTERN = Regex("function\\s+searchSemester\\s*\\([^)]*\\)\\s*\\{([\\s\\S]*?)\\}", RegexOption.IGNORE_CASE)
        val GET_COURSE_BODY_PATTERN = Regex("getCourseInfo\\s*=\\s*function\\s*\\([^)]*\\)\\s*\\{([\\s\\S]*?)\\}", RegexOption.IGNORE_CASE)
        val GET_COURSE_FUNCTION_PATTERN = Regex("function\\s+getCourseInfo\\s*\\([^)]*\\)\\s*\\{([\\s\\S]*?)\\}", RegexOption.IGNORE_CASE)
        val URL_ASSIGNMENT_PATTERN = Regex("(?:url|uri)\\s*[:=]\\s*[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE)
    }
}
