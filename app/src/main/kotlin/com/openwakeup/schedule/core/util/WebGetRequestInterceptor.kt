package com.openwakeup.schedule.core.util

import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebResourceResponseCompat
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.net.CookiePolicy
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 代发 WebView 产生的 HTTP/HTTPS GET 请求。
 *
 * Android WebView 会在应用的 `shouldInterceptRequest` 回调结束后再向直连请求注入
 * `X-Requested-With`。本工具对 GET 请求始终返回应用生成的 [WebResourceResponse]，并使用
 * [OkHttpClient] 独立访问服务器，使 WebView 原始 GET 不会离开设备。原生请求在首跳和每一次
 * 重定向中都会删除等于应用包名的 `X-Requested-With`，同时保留网页自己的 AJAX 标记。
 *
 * 非 GET、非 HTTP/HTTPS 请求不属于本工具的处理范围，调用方可让 WebView 按原行为处理。
 * 已进入本工具的 GET 即使失败也会返回本地错误响应，不能返回 `null` 触发 WebView 兜底。
 *
 * @param userAgentProvider 返回当前手机或电脑模式应使用的 User-Agent
 * @param additionalHeadersProvider 返回当前模式需要补充或覆盖的普通请求头
 * @param forbiddenRequestedWithValue WebView 包名识别值；仅该值会被完整删除，网页主动设置的
 * 其他值（例如标准 AJAX 的 `XMLHttpRequest`）会继续转发
 * @param cookieInterceptEnabled 是否已启用 AndroidX WebKit Cookie Intercept；启用后可以通过
 * [WebResourceResponseCompat] 将未经过重定向的响应 Cookie 精确交回 WebView
 */
class WebGetRequestInterceptor(
    private val userAgentProvider: () -> String,
    private val additionalHeadersProvider: () -> Map<String, String> = { emptyMap() },
    private val forbiddenRequestedWithValue: String,
    private val cookieInterceptEnabled: Boolean = false,
) : Closeable {

    /** WebView 与原生代发请求共用的 Cookie 存储，避免创建第二套登录态。 */
    private val cookieManager = CookieManager.getInstance()

    /**
     * 原生 GET 重定向使用的短期内存 CookieStore。
     *
     * WebView CookieManager 写入可能晚于紧接着发生的下一跳导航；该存储只保存原生 GET 响应
     * 已经返回的 Cookie，用于补齐这一时序窗口，最终仍同步写回 WebView CookieManager。
     */
    private val redirectCookieManager = java.net.CookieManager(null, CookiePolicy.ACCEPT_ALL)

    /** 正在执行或仍由 WebView 读取响应体的调用；Activity 销毁时统一取消。 */
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()

    /** 关闭后所有新请求均返回本地错误页，不允许重新落入 WebView 网络栈。 */
    private val closed = AtomicBoolean(false)

    /**
     * 判断请求是否属于本工具必须接管的范围。
     *
     * @param request WebView 或 Service Worker 提供的请求
     * @return 仅普通 HTTP/HTTPS GET 返回 `true`
     */
    fun shouldIntercept(request: WebResourceRequest): Boolean {
        if (!request.method.equals(GET_METHOD, ignoreCase = true)) return false
        return request.url.scheme?.lowercase(Locale.ROOT) in HTTP_SCHEMES
    }

    /**
     * 代发一个 GET 请求并转换成 WebView 响应。
     *
     * 调用前应先通过 [shouldIntercept] 判断请求范围。若调用方直接传入不支持的请求，本方法仍
     * 返回非空错误响应，避免误操作时把请求交还给 WebView。
     *
     * @param request WebView 或 Service Worker 提供的原始 GET 请求
     * @return 成功响应或本地失败响应，永不返回 `null`
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse {
        if (!shouldIntercept(request)) {
            return createErrorResponse(
                isForMainFrame = request.isForMainFrame,
                message = "Unsupported request",
            )
        }
        if (closed.get()) {
            return createErrorResponse(
                isForMainFrame = request.isForMainFrame,
                message = "Request interceptor closed",
            )
        }

        return runCatching { executeGet(request) }
            .getOrElse { error ->
                // 仅记录异常类型，不输出 URL、查询参数、请求头、Cookie 或响应正文。
                Log.w(LOG_TAG, "原生 GET 代发失败：${error.javaClass.simpleName}")
                // 错误页不拼接异常文本、URL、查询参数或请求头，避免把账号与 Cookie 暴露到 DOM。
                createErrorResponse(
                    isForMainFrame = request.isForMainFrame,
                    message = "Native GET failed",
                )
            }
    }

    /**
     * 执行 GET，并在原生层完成完整重定向链。
     *
     * @param request WebView 提供的首跳请求
     * @return 可直接交给 WebView 的最终响应
     */
    private fun executeGet(request: WebResourceRequest): WebResourceResponse {
        val initialUrl = request.url.toString()
        val requestStartedAtNanos = System.nanoTime()
        val navigationRequest = isNavigationRequest(request)
        val compatibilityUrl = rewriteNavigationUrlIfNeeded(request, initialUrl)
        if (compatibilityUrl != initialUrl) {
            // 先让 WebView 建立改写后的真实 Document URL，再由下一次 GET 拦截完成网络访问。
            return createNavigationRedirectResponse(compatibilityUrl)
        }
        var currentUrl = initialUrl
        var redirectCount = 0
        var requestHeaders = buildInitialHeaders(request)

        while (true) {
            check(!closed.get()) { "Interceptor has been closed" }
            if (isLoopbackUrl(currentUrl)) {
                // 部分统一认证脚本会轮询本机客户端端口；模拟器或未安装客户端时应立即失败，
                // 不能让每个端口都消耗完整 TLS/连接超时。
                return createErrorResponse(
                    isForMainFrame = request.isForMainFrame,
                    message = "Loopback request blocked",
                )
            }
            val call = createCall(currentUrl, requestHeaders)
            activeCalls += call
            if (closed.get()) {
                // 关闭动作可能恰好发生在创建 Call 与登记 Call 之间；登记后再次检查可封住该竞态窗口。
                cancelCall(call)
                error("Interceptor has been closed")
            }

            var response: Response? = null
            try {
                val networkResponse = call.execute()
                response = networkResponse
                val statusCode = networkResponse.code
                if (navigationRequest) {
                    val elapsedMs = (System.nanoTime() - requestStartedAtNanos) / NANOS_PER_MILLISECOND
                    if (elapsedMs >= SLOW_NAVIGATION_LOG_THRESHOLD_MS) {
                        // 只记录耗时和状态，不记录 URL、查询参数、Cookie 或其他请求头。
                        Log.i(LOG_TAG, "导航 GET 响应头耗时 ${elapsedMs}ms，状态 $statusCode")
                    }
                }
                val responseCookies = networkResponse.headers.values(SET_COOKIE_HEADER)

                if (statusCode in REDIRECT_STATUS_CODES) {
                    val location = networkResponse.header(LOCATION_HEADER)
                        ?: error("Redirect response misses Location")
                    if (redirectCount >= MAX_REDIRECTS) error("Too many redirects")

                    // WebView 不会再次可靠回调重定向后的请求，因此每一跳 Cookie 与请求头都在原生层更新。
                    storeCookies(currentUrl, responseCookies)
                    val nextUrl = networkResponse.request.url.resolve(location)?.toString()
                        ?: error("Unsupported redirect Location")
                    require(URL(nextUrl).protocol.lowercase(Locale.ROOT) in HTTP_SCHEMES) {
                        "Unsupported redirect protocol"
                    }
                    if (navigationRequest) {
                        // 自己吞掉顶层或 iframe 导航重定向会让 WebView 继续沿用首跳 URL 与 Origin，
                        // 造成相对资源错位甚至空白。返回仅执行 location.replace 的本地页面，让
                        // 下一跳成为新的导航 GET，并再次经过同一个拦截器。
                        closeResponse(networkResponse, call)
                        return createNavigationRedirectResponse(nextUrl)
                    }
                    requestHeaders = buildRedirectHeaders(
                        previousHeaders = requestHeaders,
                        previousUrl = currentUrl,
                        nextUrl = nextUrl,
                    )
                    closeResponse(networkResponse, call)
                    currentUrl = nextUrl
                    redirectCount += 1
                    continue
                }

                // WebResourceResponse 明确不支持 3xx；未识别的 3xx 必须失败关闭，不能交给 WebView 跟随。
                check(statusCode !in 300..399) { "Unsupported redirect status" }
                return createNetworkResponse(
                    response = networkResponse,
                    call = call,
                    requestUrl = currentUrl,
                    initialUrl = initialUrl,
                    responseCookies = responseCookies,
                    redirectCount = redirectCount,
                )
            } catch (error: Throwable) {
                // 在最终响应所有权交给 WebView 前发生异常时，必须同时释放响应体并取消调用。
                response?.close()
                cancelCall(call)
                throw error
            }
        }
    }

    /**
     * 创建配置完整、不会自动重定向的 OkHttp 调用。
     *
     * @param url 当前跳转地址
     * @param headers 已过滤的请求头
     * @return 尚未执行、可加入生命周期集合的 Call
     */
    private fun createCall(
        url: String,
        headers: Map<String, String>,
    ): Call {
        val requestBuilder = Request.Builder()
            .url(url)
            .get()

        headers.forEach { (name, value) ->
            // CR/LF 会构成请求头注入；遇到非法值时忽略该普通头，不使用不安全 API 放宽校验。
            if ('\r' !in value && '\n' !in value) {
                runCatching { requestBuilder.header(name, value) }
            }
        }
        // 不显式设置 Accept-Encoding，让 OkHttp 自动添加 gzip 并在返回 WebView 前透明解压。
        val nativeRequest = requestBuilder.build()
        check(nativeRequest.headers.values(X_REQUESTED_WITH_HEADER).none(::isForbiddenRequestedWithValue)) {
            "Forbidden X-Requested-With value"
        }
        return sharedClient.newCall(nativeRequest)
    }

    /**
     * 从 WebView 请求构造首跳请求头。
     *
     * @param request WebView 原始请求
     * @return 大小写不敏感、已删除禁止字段的请求头
     */
    private fun buildInitialHeaders(request: WebResourceRequest): Map<String, String> {
        val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        request.requestHeaders.forEach { (name, value) ->
            if (isForwardableRequestHeader(name)) headers[name] = value
        }
        additionalHeadersProvider().forEach { (name, value) ->
            if (isForwardableRequestHeader(name)) headers[name] = value
        }

        // UA 必须和 WebView 当前手机/电脑模式一致；禁止沿用 OkHttp 自己的默认 UA。
        headers[USER_AGENT_HEADER] = userAgentProvider()
        val webViewCookie = findHeader(request.requestHeaders, COOKIE_HEADER)
            ?: runCatching { cookieManager.getCookie(request.url.toString()) }.getOrNull()
        val cookie = mergedCookieHeader(request.url.toString(), webViewCookie)
        if (!cookie.isNullOrBlank()) headers[COOKIE_HEADER] = cookie

        // 先完整移除所有同名项，再只恢复网页主动设置且不等于应用包名的值。
        headers.keys.removeAll { name -> name.equals(X_REQUESTED_WITH_HEADER, ignoreCase = true) }
        findHeader(request.requestHeaders, X_REQUESTED_WITH_HEADER)
            ?.takeIf { value -> value.isNotBlank() && !isForbiddenRequestedWithValue(value) }
            ?.let { value -> headers[X_REQUESTED_WITH_HEADER] = value }
        return headers
    }

    /**
     * 为下一跳重定向重建请求头。
     *
     * 跨来源跳转会删除认证与来源敏感头，Cookie 始终按下一跳 URL 重新读取，避免把前一站登录态
     * 泄露给另一个域名。
     */
    private fun buildRedirectHeaders(
        previousHeaders: Map<String, String>,
        previousUrl: String,
        nextUrl: String,
    ): Map<String, String> {
        val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        headers.putAll(previousHeaders)
        headers.remove(COOKIE_HEADER)
        headers.remove(HOST_HEADER)

        // 重定向沿用网页自己的 AJAX 标记，但任何等于应用包名的值都必须在发送前删除。
        headers[X_REQUESTED_WITH_HEADER]
            ?.takeIf(::isForbiddenRequestedWithValue)
            ?.let { headers.remove(X_REQUESTED_WITH_HEADER) }

        if (!hasSameOrigin(previousUrl, nextUrl)) {
            // 浏览器不会把一个来源的认证凭据无条件转发给另一个来源，原生重定向保持相同边界。
            headers.remove(AUTHORIZATION_HEADER)
            headers.remove(ORIGIN_HEADER)
            headers.remove(REFERER_HEADER)
        }

        val cookie = mergedCookieHeader(
            url = nextUrl,
            webViewCookie = runCatching { cookieManager.getCookie(nextUrl) }.getOrNull(),
        )
        if (!cookie.isNullOrBlank()) headers[COOKIE_HEADER] = cookie
        headers[USER_AGENT_HEADER] = userAgentProvider()
        headers[X_REQUESTED_WITH_HEADER]
            ?.takeIf(::isForbiddenRequestedWithValue)
            ?.let { headers.remove(X_REQUESTED_WITH_HEADER) }
        return headers
    }

    /** 判断 `X-Requested-With` 是否正在暴露当前应用包名。 */
    private fun isForbiddenRequestedWithValue(value: String): Boolean {
        return value.trim().equals(forbiddenRequestedWithValue, ignoreCase = true)
    }

    /**
     * 把最终 OkHttp 响应转换为 WebView 响应，并将响应所有权交给响应流。
     *
     * @param response 已取得响应头、尚未关闭响应体的 OkHttp 响应
     * @param call 与响应关联的调用，用于在流关闭后退出活动集合
     */
    private fun createNetworkResponse(
        response: Response,
        call: Call,
        requestUrl: String,
        initialUrl: String,
        responseCookies: List<String>,
        redirectCount: Int,
    ): WebResourceResponse {
        val statusCode = response.code
        check(statusCode in 100..599 && statusCode !in 300..399) { "Invalid response status" }
        val contentType = parseContentType(
            response.body.contentType()?.toString() ?: response.header(CONTENT_TYPE_HEADER),
            requestUrl,
        )
        val webHeaders = flattenResponseHeaders(response.headers)
        val responseStream = if (statusCode == HTTP_NO_CONTENT || statusCode == HTTP_NOT_MODIFIED) {
            // 这两类响应按协议没有正文，立即释放底层 Response，无需等待 WebView 关闭空流。
            closeResponse(response, call)
            ByteArrayInputStream(ByteArray(0))
        } else {
            ResponseClosingInputStream(response.body.byteStream()) {
                closeResponse(response, call)
            }
        }

        val compatResponse = WebResourceResponseCompat(
            contentType.mimeType,
            contentType.charset,
            statusCode,
            safeReasonPhrase(statusCode, response.message),
            webHeaders,
            responseStream,
        )

        if (responseCookies.isNotEmpty()) {
            // 同步写入 CookieManager，保证重定向后的后续资源即使立即发起也能读取最新登录态。
            storeCookies(requestUrl, responseCookies)
            if (cookieInterceptEnabled && redirectCount == 0 && requestUrl == initialUrl) {
                // 无重定向时 AndroidX 能以真实请求上下文处理 SameSite、第三方及分区 Cookie。
                compatResponse.setCookies(responseCookies)
            }
        }
        return compatResponse.toWebResourceResponse()
    }

    /**
     * 把响应头压缩成 WebResourceResponse 支持的单值 Map。
     *
     * `Set-Cookie` 单独处理，不能用逗号连接；逐跳字段也不能转交给 WebView。
     */
    private fun flattenResponseHeaders(
        headers: Headers,
    ): Map<String, String> {
        val result = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        headers.names().forEach { name ->
            val values = headers.values(name)
            if (values.isEmpty()) return@forEach
            if (name.lowercase(Locale.ROOT) in FORBIDDEN_RESPONSE_HEADERS) return@forEach
            result[name] = values.joinToString(", ")
        }
        return result
    }

    /** 把当前响应中的 Cookie 按当前 URL 交给 WebView CookieManager。 */
    private fun storeCookies(url: String, cookies: List<String>) {
        if (cookies.isNotEmpty()) {
            // java.net.CookieManager 同步更新，确保紧接着发生的原生重定向可以立即取得新 Cookie。
            runCatching {
                redirectCookieManager.put(
                    URI(url),
                    mapOf(SET_COOKIE_HEADER to cookies),
                )
            }
        }
        cookies.forEach { cookie ->
            if (cookie.isNotBlank()) {
                // 使用无回调重载，允许在 shouldInterceptRequest 的工作线程调用。
                runCatching { cookieManager.setCookie(url, cookie) }
            }
        }
    }

    /**
     * 合并 WebView Cookie 与原生重定向期间刚收到、尚未及时回写完成的 Cookie。
     *
     * 同名 Cookie 以原生重定向存储中的最新值为准；只输出标准 `name=value` 对，避免把 Domain、
     * Path、SameSite 等 Set-Cookie 属性误放进请求 Cookie。
     */
    private fun mergedCookieHeader(url: String, webViewCookie: String?): String? {
        val cookiesByName = linkedMapOf<String, String>()
        addRequestCookies(cookiesByName, webViewCookie)
        val redirectCookies = runCatching {
            redirectCookieManager.get(URI(url), emptyMap())[COOKIE_HEADER]
                ?.joinToString("; ")
        }.getOrNull()
        addRequestCookies(cookiesByName, redirectCookies)
        return cookiesByName.values.joinToString("; ").takeIf { it.isNotBlank() }
    }

    /** 把请求 Cookie 字符串拆成同名可覆盖的 `name=value` 对。 */
    private fun addRequestCookies(target: MutableMap<String, String>, cookieHeader: String?) {
        cookieHeader.orEmpty().split(';').forEach { rawCookie ->
            val cookie = rawCookie.trim()
            val separator = cookie.indexOf('=')
            if (separator <= 0) return@forEach
            val name = cookie.substring(0, separator).trim()
            if (name.isBlank()) return@forEach
            target[name] = cookie
        }
    }

    /** 按大小写不敏感规则判断普通请求头是否允许交给 OkHttp。 */
    private fun isForwardableRequestHeader(name: String): Boolean {
        return name.lowercase(Locale.ROOT) !in FORBIDDEN_REQUEST_HEADERS
    }

    /**
     * 判断 GET 是否是会建立 Document URL 与 Origin 的页面导航。
     *
     * [WebResourceRequest.isForMainFrame] 只能识别顶层页面；iframe 导航依靠 Chromium 提供的
     * `Sec-Fetch-Mode: navigate` 或 `Sec-Fetch-Dest: iframe/frame` 识别。若这些头在旧 WebView
     * 中不可见，再以接受 HTML 的请求作为保守回落，避免把跨域登录页当作普通资源吞掉重定向。
     */
    private fun isNavigationRequest(request: WebResourceRequest): Boolean {
        if (request.isForMainFrame) return true
        val fetchMode = findHeader(request.requestHeaders, SEC_FETCH_MODE_HEADER)
        if (fetchMode.equals(NAVIGATE_FETCH_MODE, ignoreCase = true)) return true
        val fetchDestination = findHeader(request.requestHeaders, SEC_FETCH_DEST_HEADER)
        if (fetchDestination?.lowercase(Locale.ROOT) in DOCUMENT_FETCH_DESTINATIONS) return true
        val accept = findHeader(request.requestHeaders, ACCEPT_HEADER).orEmpty()
        return accept.split(',').any { value ->
            value.substringBefore(';').trim().equals(HTML_MIME_TYPE, ignoreCase = true)
        }
    }

    /**
     * 对已确认会在 Android WebView 中产生异常等待的登录导航做最小兼容改写。
     *
     * 微信开放登录页在 `fast_login` 缺失时仍会依次 POST 探测六个桌面微信本机端口，移动端
     * WebView 中这些端口通常不存在，会无意义阻塞约数秒。显式设置 `fast_login=0` 只关闭桌面
     * 客户端快速登录探测，二维码扫码登录仍按原页面流程工作。
     */
    private fun rewriteNavigationUrlIfNeeded(request: WebResourceRequest, url: String): String {
        if (!isNavigationRequest(request)) return url
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return url
        if (host !in WECHAT_QR_LOGIN_HOSTS || uri.path != WECHAT_QR_LOGIN_PATH) return url
        if (uri.getQueryParameter(WECHAT_FAST_LOGIN_PARAMETER) == DISABLED_QUERY_VALUE) return url

        val builder = uri.buildUpon().clearQuery()
        uri.queryParameterNames.forEach { name ->
            if (name.equals(WECHAT_FAST_LOGIN_PARAMETER, ignoreCase = true)) return@forEach
            uri.getQueryParameters(name).forEach { value ->
                builder.appendQueryParameter(name, value)
            }
        }
        builder.appendQueryParameter(WECHAT_FAST_LOGIN_PARAMETER, DISABLED_QUERY_VALUE)
        return builder.build().toString()
    }

    /** 从请求头 Map 中按大小写不敏感方式读取字段。 */
    private fun findHeader(headers: Map<String, String>, targetName: String): String? {
        return headers.entries.firstOrNull { (name, _) ->
            name.equals(targetName, ignoreCase = true)
        }?.value
    }

    /** 比较两个 URL 的协议、主机和有效端口。 */
    private fun hasSameOrigin(firstUrl: String, secondUrl: String): Boolean {
        val first = URL(firstUrl)
        val second = URL(secondUrl)
        return first.protocol.equals(second.protocol, ignoreCase = true) &&
                first.host.equals(second.host, ignoreCase = true) &&
                effectivePort(first) == effectivePort(second)
    }

    /**
     * 判断请求是否指向设备本机。
     *
     * 只识别明确的 localhost 与回环字面量，不解析普通域名，避免 DNS 重绑定判断影响校内地址。
     */
    private fun isLoopbackUrl(url: String): Boolean {
        val host = URL(url).host.trim('[', ']').lowercase(Locale.ROOT)
        if (host == "localhost" || host.endsWith(".localhost") || host == "::1") return true
        val ipv4Parts = host.split('.')
        return ipv4Parts.size == 4 && ipv4Parts.firstOrNull() == "127" &&
                ipv4Parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }

    /** 取得 URL 的显式端口或协议默认端口。 */
    private fun effectivePort(url: URL): Int {
        return if (url.port >= 0) url.port else url.defaultPort
    }

    /** 从 Content-Type 分离媒体类型与 charset，并为缺失类型提供有限回落。 */
    private fun parseContentType(value: String?, url: String): ParsedContentType {
        val parts = value.orEmpty().split(';')
        val mimeType = parts.firstOrNull()
            ?.trim()
            ?.takeIf { type -> '/' in type }
            ?: URLConnection.guessContentTypeFromName(URL(url).path)
            ?: DEFAULT_BINARY_MIME_TYPE
        val charset = parts.drop(1)
            .firstOrNull { part -> part.trim().startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf { it.isNotBlank() }
        return ParsedContentType(mimeType, charset)
    }

    /** WebResourceResponse 要求原因短语非空且只含可打印 ASCII。 */
    private fun safeReasonPhrase(statusCode: Int, original: String?): String {
        return original
            ?.takeIf { value -> value.isNotBlank() && value.all { it.code in 0x20..0x7E } }
            ?: DEFAULT_REASON_PHRASES[statusCode]
            ?: "HTTP Response"
    }

    /** 创建不会继续访问网络的本地错误响应。 */
    private fun createErrorResponse(isForMainFrame: Boolean, message: String): WebResourceResponse {
        val body = if (isForMainFrame) MAIN_FRAME_ERROR_HTML else ""
        val mimeType = if (isForMainFrame) HTML_MIME_TYPE else PLAIN_TEXT_MIME_TYPE
        return WebResourceResponse(
            mimeType,
            UTF_8_ENCODING,
            HTTP_BAD_GATEWAY,
            "Bad Gateway",
            mapOf(
                CACHE_CONTROL_HEADER to NO_STORE_CACHE_CONTROL,
                INTERCEPT_ERROR_HEADER to message,
            ),
            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)),
        )
    }

    /**
     * 把顶层 HTTP 重定向转换为新的 WebView 导航。
     *
     * [JSONObject.quote] 负责生成合法 JavaScript 字符串，避免 Location 中的引号或控制字符逃逸
     * 脚本。`location.replace` 不为中间跳转增加历史记录，并让最终页面获得正确 URL 与 Origin。
     */
    private fun createNavigationRedirectResponse(targetUrl: String): WebResourceResponse {
        val quotedUrl = JSONObject.quote(targetUrl)
        val body = "<!doctype html><html><head><meta charset=\"utf-8\"></head>" +
                "<body><script>location.replace($quotedUrl);</script></body></html>"
        return WebResourceResponse(
            HTML_MIME_TYPE,
            UTF_8_ENCODING,
            HTTP_OK,
            "OK",
            mapOf(CACHE_CONTROL_HEADER to NO_STORE_CACHE_CONTROL),
            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)),
        )
    }

    /**
     * 关闭一个已收到响应的调用，并从 Activity 生命周期集合移除。
     *
     * [Response.close] 会关闭其 ResponseBody；重复调用是安全的，因此异常清理可以复用本方法。
     */
    private fun closeResponse(response: Response, call: Call) {
        runCatching { response.close() }
        activeCalls -= call
    }

    /** 取消尚在连接、读取响应头或读取响应体的调用。 */
    private fun cancelCall(call: Call) {
        runCatching { call.cancel() }
        activeCalls -= call
    }

    /** Activity 销毁后终止所有仍在进行的 GET。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeCalls.toList().forEach(::cancelCall)
        activeCalls.clear()
        redirectCookieManager.cookieStore.removeAll()
    }

    /** Content-Type 的 WebView 构造参数。 */
    private data class ParsedContentType(
        val mimeType: String,
        val charset: String?,
    )

    /**
     * WebView 关闭响应体时同步关闭 OkHttp Response，并释放对应 Call 的生命周期登记。
     *
     * @param delegate 服务器响应流
     * @param onClosed 关闭 Response 并从活动 Call 集合移除的回调
     */
    private class ResponseClosingInputStream(
        delegate: InputStream,
        private val onClosed: () -> Unit,
    ) : FilterInputStream(delegate) {

        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                super.close()
            } finally {
                onClosed()
            }
        }
    }

    private companion object {
        /**
         * WebView GET 代发进程内共享客户端。
         *
         * Cookie 与重定向必须继续由本工具桥接，因此客户端不保存 Cookie、也不自动跟随重定向。
         * 所有拦截器实例共享 Dispatcher、连接池与 HTTP/2 连接；Activity 销毁时只取消自己的
         * [Call]，不能关闭这些进程级资源。
         */
        private val sharedClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            OkHttpClient.Builder()
                // 明确保持 OkHttp 5 的 Fast Fallback：首选地址族迟滞时并行尝试另一地址族。
                .fastFallback(true)
                .followRedirects(false)
                .followSslRedirects(false)
                .cookieJar(CookieJar.NO_COOKIES)
                .retryOnConnectionFailure(true)
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }

        /** 仅接管普通 Web GET。 */
        const val GET_METHOD = "GET"

        /** 支持的远程网页协议。 */
        val HTTP_SCHEMES = setOf("http", "https")

        /** 单个地址建立 TCP/TLS 连接的上限；双栈切换由 Fast Fallback 提前并行触发。 */
        const val CONNECT_TIMEOUT_SECONDS = 10L

        /** 已连接后读取响应数据的超时。 */
        const val READ_TIMEOUT_SECONDS = 30L

        /** 纳秒换算毫秒。 */
        const val NANOS_PER_MILLISECOND = 1_000_000L

        /** 只记录明显偏慢的页面导航，避免普通资源请求刷屏。 */
        const val SLOW_NAVIGATION_LOG_THRESHOLD_MS = 1_000L

        /** 防止循环重定向无限占用 WebView 工作线程。 */
        const val MAX_REDIRECTS = 10

        const val X_REQUESTED_WITH_HEADER = "X-Requested-With"
        const val USER_AGENT_HEADER = "User-Agent"
        const val COOKIE_HEADER = "Cookie"
        const val SET_COOKIE_HEADER = "Set-Cookie"
        const val LOCATION_HEADER = "Location"
        const val CONTENT_TYPE_HEADER = "Content-Type"
        const val ACCEPT_HEADER = "Accept"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val ORIGIN_HEADER = "Origin"
        const val REFERER_HEADER = "Referer"
        const val HOST_HEADER = "Host"
        const val SEC_FETCH_MODE_HEADER = "Sec-Fetch-Mode"
        const val SEC_FETCH_DEST_HEADER = "Sec-Fetch-Dest"
        const val NAVIGATE_FETCH_MODE = "navigate"
        const val WECHAT_QR_LOGIN_PATH = "/connect/qrconnect"
        const val WECHAT_FAST_LOGIN_PARAMETER = "fast_login"
        const val DISABLED_QUERY_VALUE = "0"
        const val CACHE_CONTROL_HEADER = "Cache-Control"
        const val INTERCEPT_ERROR_HEADER = "X-OpenWakeUp-Intercept-Error"
        const val NO_STORE_CACHE_CONTROL = "no-store"
        const val UTF_8_ENCODING = "UTF-8"
        const val HTML_MIME_TYPE = "text/html"
        const val PLAIN_TEXT_MIME_TYPE = "text/plain"
        const val DEFAULT_BINARY_MIME_TYPE = "application/octet-stream"
        const val HTTP_OK = 200
        const val HTTP_NO_CONTENT = 204
        const val HTTP_NOT_MODIFIED = 304
        const val HTTP_BAD_GATEWAY = 502

        /** 能够建立独立 Document 上下文的 Fetch 目标。 */
        val DOCUMENT_FETCH_DESTINATIONS = setOf("document", "iframe", "frame")

        /** 微信开放登录可能承载二维码 Document 的域名。 */
        val WECHAT_QR_LOGIN_HOSTS = setOf(
            "open.weixin.qq.com",
            "lp.open.weixin.qq.com",
            "long.open.weixin.qq.com",
        )

        /** 仅用于记录不含地址与凭据的诊断信息。 */
        const val LOG_TAG = "OpenWakeUpWebGet"

        /** WebView 不能转发、应由 OkHttp 自行生成或会影响缓存正确性的请求头。 */
        val FORBIDDEN_REQUEST_HEADERS = setOf(
            "x-requested-with",
            "host",
            "connection",
            "proxy-connection",
            "proxy-authorization",
            "keep-alive",
            "transfer-encoding",
            "te",
            "trailer",
            "upgrade",
            "content-length",
            "accept-encoding",
            "if-match",
            "if-none-match",
            "if-modified-since",
            "if-unmodified-since",
            "if-range",
        )

        /** WebResourceResponse 不应继续暴露给页面的逐跳或单独处理的响应头。 */
        val FORBIDDEN_RESPONSE_HEADERS = setOf(
            "set-cookie",
            "set-cookie2",
            // MIME 与编码已通过 WebResourceResponse 的专用参数传递，不能把服务器重复字段
            // 合并成 `text/javascript, text/javascript` 之类的非法单值。
            "content-type",
            "connection",
            "proxy-connection",
            "keep-alive",
            "transfer-encoding",
            "te",
            "trailer",
            "upgrade",
        )

        /** 必须在原生层消化的 GET 重定向状态。 */
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)

        /** 常见状态码的安全 ASCII 原因短语回落。 */
        val DEFAULT_REASON_PHRASES = mapOf(
            200 to "OK",
            201 to "Created",
            202 to "Accepted",
            204 to "No Content",
            206 to "Partial Content",
            400 to "Bad Request",
            401 to "Unauthorized",
            403 to "Forbidden",
            404 to "Not Found",
            405 to "Method Not Allowed",
            408 to "Request Timeout",
            429 to "Too Many Requests",
            500 to "Internal Server Error",
            502 to "Bad Gateway",
            503 to "Service Unavailable",
            504 to "Gateway Timeout",
        )

        /** 顶层 GET 失败时显示的最小本地页面，不包含请求地址或异常详情。 */
        const val MAIN_FRAME_ERROR_HTML =
            "<!doctype html><html><head><meta charset=\"utf-8\">" +
                    "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>" +
                    "<body><p>页面加载失败，请检查网络后重试。</p></body></html>"
    }
}
