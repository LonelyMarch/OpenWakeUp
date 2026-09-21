package com.openwakeup.schedule.feature.importexport

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.webkit.CustomHeader
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserFactory
import com.openwakeup.parser.ParserInput
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.config.AppDefaults
import com.openwakeup.schedule.core.util.WebSessionClient
import com.openwakeup.schedule.data.schedule.ScheduleRepository
import com.openwakeup.schedule.databinding.ActivityWebLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * WebView 登录抓取层：
 * - 加载学校教务地址（可手动导航到课表页面）；
 * - 默认以桌面 UA + 宽视口载入，并可即时切换手机/电脑模式；
 * - 页面加载完成后注入 getPageHtml（递归同源 iframe）；
 * - "导入此页" 先选择覆盖当前课表或新建课表，再解析并入库；
 * - 解析异常映射为对应文案（密码错/验证码/排队/空表）。
 * 注：不移植 TrustAllCerts（避免信任所有证书的安全隐患）。
 */
class WebLoginActivity : AppCompatActivity() {

    private val repo by lazy { ScheduleRepository(this) }
    private lateinit var binding: ActivityWebLoginBinding
    private val type: String by lazy { intent.getStringExtra(EXTRA_TYPE).orEmpty() }
    private val schoolName: String by lazy { intent.getStringExtra(EXTRA_NAME).orEmpty() }
    private val mobileUserAgent: String by lazy { WebSettings.getDefaultUserAgent(this) }

    /** GET 代发发生在 WebView 工作线程，模式状态需要保证跨线程可见。 */
    @Volatile
    private var isDesktopMode = true

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    /** 为所有 HTTP/HTTPS GET 提供不含 X-Requested-With 的原生响应。 */
    private lateinit var webSessionClient: WebSessionClient

    /** App 内唯一的网页原始输入门面；不暴露 Parser 实现给 WebView 层。 */
    private lateinit var webImportSource: WebImportSource

    /** 当前页面安装的全局 Service Worker 控制器，销毁时用于解除请求拦截。 */
    private var serviceWorkerController: ServiceWorkerControllerCompat? = null

    /** 旧 WebView 无法拦截 Service Worker 时暂时保存其原网络阻断状态。 */
    private var previousServiceWorkerBlockNetworkLoads: Boolean? = null

    /** 保存 Service Worker 原 Cookie 拦截状态，页面销毁时恢复进程级配置。 */
    private var previousServiceWorkerCookieIntercept: Boolean? = null

    /** WebView 是否支持在拦截回调中提供精确 Cookie 请求上下文。 */
    private var cookieInterceptEnabled = false

    /** 防止渲染进程异常回调和 Activity 销毁流程重复释放同一个 WebView。 */
    private var webViewDestroyed = false

    /** WebView 上传控件的系统文件选择器，兼容需上传附件的教务页。 */
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            fileChooserCallback?.onReceiveValue(uri?.let { arrayOf(it) })
            fileChooserCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityWebLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        // WebView 有历史记录时优先网页后退，否则结束当前导入页面。
        onBackPressedDispatcher.addCallback(this) {
            if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
        }
        title = getString(R.string.web_login_title, schoolName)
        binding.btnBack.setOnClickListener { finish() }
        configureWebView()
        webSessionClient = WebSessionClient(
            userAgentProvider = {
                if (isDesktopMode) WINDOWS_DESKTOP_USER_AGENT else mobileUserAgent
            },
            additionalHeadersProvider = {
                if (isDesktopMode) WIN11_CHROME_HEADERS else emptyMap()
            },
            forbiddenRequestedWithValue = packageName,
            cookieInterceptEnabled = cookieInterceptEnabled,
        )
        webImportSource = WebImportSource(binding.webView, webSessionClient, type)
        configureServiceWorkerInterception()
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // 每次顶层导航开启新的页面代次，不能复用上一次 print-data 响应。
                webSessionClient.clearCapturedResponse()
                webImportSource.onPageStarted(url)
                binding.editUrl.setText(url)
                binding.pageProgress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // 页面登录成功后主动落盘 Cookie，下次进入内置浏览器可复用登录态。
                CookieManager.getInstance().flush()
                webImportSource.onPageFinished(url)
                binding.pageProgress.visibility = View.GONE
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                if (uri.scheme in WEB_SCHEMES) return false
                return runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                }.getOrDefault(false)
            }

            /**
             * 接管全部普通 HTTP/HTTPS GET，由原生网络层返回非空响应，并移除 WebView 注入的
             * `X-Requested-With` 应用包名值。
             *
             * 非 GET 保持原来的 WebView 网络行为；已识别为 GET 的请求即使原生访问失败，也由
             * [WebSessionClient] 返回本地错误响应，不能返回 `null` 触发 WebView 兜底。
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val currentRequest = request ?: return null
                if (!webSessionClient.shouldIntercept(currentRequest)) return null
                return webSessionClient.intercept(currentRequest)
            }

            /**
             * 接管 WebView 渲染进程崩溃或被系统回收的场景。
             *
             * 返回 `true` 表示应用已经清理失效实例，避免系统继续沿默认路径终止整个进程。
             */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                disposeWebView(view ?: binding.webView)
                val message = if (detail?.didCrash() == true) {
                    R.string.web_renderer_crashed
                } else {
                    R.string.web_renderer_reclaimed
                }
                Toast.makeText(this@WebLoginActivity, message, Toast.LENGTH_LONG).show()
                finish()
                return true
            }
        }
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.pageProgress.progress = newProgress
                binding.pageProgress.visibility =
                    if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                this@WebLoginActivity.fileChooserCallback?.onReceiveValue(null)
                this@WebLoginActivity.fileChooserCallback = filePathCallback ?: return false
                val acceptedTypes = fileChooserParams?.acceptTypes
                    ?.filter { it.isNotBlank() }
                    ?.toTypedArray()
                    ?.takeIf { it.isNotEmpty() }
                    ?: arrayOf("*/*")
                return runCatching {
                    fileChooserLauncher.launch(acceptedTypes)
                    true
                }.getOrElse {
                    this@WebLoginActivity.fileChooserCallback = null
                    false
                }
            }
        }
        binding.btnGo.setOnClickListener {
            loadAddressBarUrl()
        }
        binding.editUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadAddressBarUrl()
                true
            } else {
                false
            }
        }
        binding.btnDeviceMode.setOnClickListener { toggleDeviceMode() }
        binding.btnHelp.setOnClickListener { showImportNotice() }
        binding.btnImportPage.setOnClickListener { showImportModeDialog() }

        intent.getStringExtra(EXTRA_URL)
            ?.let(::normalizeSchoolUrl)
            ?.takeIf { url -> url.isNotBlank() && url != "http://" && url != "https://" }
            ?.let(::loadUrlForCurrentMode)
    }

    /**
     * 开启老旧教务站常用的 WebView 兼容能力。
     *
     * JavaScript、DOM Storage、Cookie、混合内容、宽视口、图片、缩放、
     * 媒体播放与文件选择均可用；仍保留 Android 的 TLS 证书校验，不接受任意证书。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadsImagesAutomatically = true
            blockNetworkImage = false
            blockNetworkLoads = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            defaultTextEncodingName = "UTF-8"
            textZoom = 100
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            // 导入页始终可见，不需要离屏预栅格化；关闭它可降低复杂教务 SPA 的渲染进程峰值内存。
            offscreenPreRaster = false
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
        }
        binding.webView.isHorizontalScrollBarEnabled = true
        binding.webView.isVerticalScrollBarEnabled = true
        binding.webView.setInitialScale(0)
        configureInterceptedRequestCookies()
        configurePersistentRequestHeaders()
        applyDeviceMode(reload = false)
    }

    /**
     * 在 WebView 支持时，把浏览器已按 SameSite、第三方与分区规则筛选的 Cookie 放进拦截请求。
     *
     * 不支持该能力时，GET 工具会退回 [CookieManager.getCookie]；该兼容路径不会影响
     * `X-Requested-With` 的删除保证，但复杂 Cookie 场景需要以真机验证结果为准。
     *
     * WebKit 1.17.0 已公开 `COOKIE_INTERCEPT`，但其 `WebViewSupportFeature` 的 StringDef
     * 遗漏了该常量，因此这里只抑制 `WrongConstant`；运行时能力检查仍必须保留。
     */
    @SuppressLint("WrongConstant")
    private fun configureInterceptedRequestCookies() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.COOKIE_INTERCEPT)) return
        cookieInterceptEnabled = runCatching {
            WebSettingsCompat.setCookiesIncludedInShouldInterceptRequest(
                binding.webView.settings,
                true,
            )
            true
        }.getOrDefault(false)
    }

    /**
     * 让 Service Worker 发起的 HTTP/HTTPS GET 复用同一个原生代发工具。
     *
     * Service Worker 控制器是进程级对象。当前 WebView 不支持请求拦截时，为避免出现绕过路径，
     * 临时阻断 Service Worker 网络访问，并在页面销毁时恢复进入页面前的状态。
     */
    private fun configureServiceWorkerInterception() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) return
        val controller = ServiceWorkerControllerCompat.getInstance()
        serviceWorkerController = controller

        if (
            !WebViewFeature.isFeatureSupported(
                WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST,
            )
        ) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BLOCK_NETWORK_LOADS)) {
                val settings = controller.serviceWorkerWebSettings
                previousServiceWorkerBlockNetworkLoads = settings.getBlockNetworkLoads()
                settings.setBlockNetworkLoads(true)
            }
            return
        }

        if (cookieInterceptEnabled) {
            runCatching {
                configureServiceWorkerCookieIntercept(controller)
            }
        }
        controller.setServiceWorkerClient(
            object : ServiceWorkerClientCompat() {
                /** Service Worker GET 同样失败关闭；非 GET 不在本次改造范围内。 */
                override fun shouldInterceptRequest(
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    if (!webSessionClient.shouldIntercept(request)) return null
                    return webSessionClient.intercept(request)
                }
            },
        )
    }

    /**
     * 在特性受支持时启用 Service Worker 请求 Cookie，并保存进入页面前的设置。
     *
     * 主 WebView 已启用 Cookie Intercept 才会调用本函数；这里仍重新检查当前 Provider，避免
     * WebView 在页面生命周期中更新后直接调用已不受支持的 API。WebKit 1.17.0 的 StringDef
     * 遗漏了该正式常量，因此仅在这个最小兼容边界抑制 `WrongConstant`。
     *
     * @param controller 当前进程的 Service Worker 控制器
     */
    @SuppressLint("WrongConstant")
    private fun configureServiceWorkerCookieIntercept(
        controller: ServiceWorkerControllerCompat,
    ) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.COOKIE_INTERCEPT)) return

        val settings = controller.serviceWorkerWebSettings
        previousServiceWorkerCookieIntercept =
            settings.isIncludeCookiesOnShouldInterceptRequestEnabled()
        settings.setIncludeCookiesOnShouldInterceptRequestEnabled(true)
    }

    /** 解除进程级 Service Worker 客户端，并恢复为进入页面前的网络阻断状态。 */
    private fun clearServiceWorkerInterception() {
        val controller = serviceWorkerController ?: return
        controller.setServiceWorkerClient(null)
        previousServiceWorkerBlockNetworkLoads?.let { previousValue ->
            if (
                WebViewFeature.isFeatureSupported(
                    WebViewFeature.SERVICE_WORKER_BLOCK_NETWORK_LOADS,
                )
            ) {
                controller.serviceWorkerWebSettings.setBlockNetworkLoads(previousValue)
            }
        }
        previousServiceWorkerCookieIntercept?.let { previousValue ->
            restoreServiceWorkerCookieIntercept(controller, previousValue)
        }
        previousServiceWorkerBlockNetworkLoads = null
        previousServiceWorkerCookieIntercept = null
        serviceWorkerController = null
    }

    /**
     * 在特性仍受支持时恢复进入页面前的 Service Worker Cookie Intercept 设置。
     *
     * @param controller 当前进程的 Service Worker 控制器
     * @param previousValue 进入教务导入页面前的 Cookie Intercept 开关值
     */
    @SuppressLint("WrongConstant")
    private fun restoreServiceWorkerCookieIntercept(
        controller: ServiceWorkerControllerCompat,
        previousValue: Boolean,
    ) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.COOKIE_INTERCEPT)) return

        controller.serviceWorkerWebSettings
            .setIncludeCookiesOnShouldInterceptRequestEnabled(previousValue)
    }

    /**
     * 为 WebView Profile 配置持续请求头。
     *
     * 新版 WebView 会把这些头应用到主文档、子资源与 Service Worker 请求；
     * 不支持 Profile 自定义头的实现由 [loadUrlForCurrentMode] 为主导航补充。
     */
    private fun configurePersistentRequestHeaders() {
        // 分开校验两个能力，让静态分析与运行时都能明确对应每个受保护 API 的前置条件。
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.CUSTOM_REQUEST_HEADERS)) return
        val profile = WebViewCompat.getProfile(binding.webView)
        WIN11_CHROME_HEADERS.forEach { (name, value) ->
            if (!profile.hasCustomHeader(name)) {
                profile.addCustomHeader(CustomHeader(name, value, setOf("*")))
            }
        }
    }

    /** 根据地址栏进入新地址；地址未变时明确执行刷新。 */
    private fun loadAddressBarUrl() {
        val targetUrl = normalizeSchoolUrl(binding.editUrl.text?.toString().orEmpty())
        if (targetUrl.isBlank()) return
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(binding.editUrl.windowToken, 0)
        // loadUrl 同时覆盖“地址已修改”与“原地刷新”，并保证首跳附带桌面请求头。
        loadUrlForCurrentMode(targetUrl)
    }

    /** 在电脑与手机 UA 之间切换，并重载当前页使新模式生效。 */
    private fun toggleDeviceMode() {
        isDesktopMode = !isDesktopMode
        applyDeviceMode(reload = true)
        Snackbar.make(
            binding.root,
            if (isDesktopMode) R.string.web_desktop_mode_enabled else R.string.web_mobile_mode_enabled,
            Snackbar.LENGTH_SHORT,
        ).setAnchorView(binding.btnImportPage).show()
    }

    /**
     * 更新 UA、页面缩放策略和模式图标。
     *
     * @param reload 是否重载当前页面
     */
    private fun applyDeviceMode(reload: Boolean) {
        binding.webView.settings.apply {
            userAgentString = if (isDesktopMode) WINDOWS_DESKTOP_USER_AGENT else mobileUserAgent
            useWideViewPort = true
            loadWithOverviewMode = isDesktopMode
        }
        applyUserAgentMetadata()
        binding.btnDeviceMode.setIconResource(
            if (isDesktopMode) R.drawable.ms_computer_24 else R.drawable.ms_smartphone_24,
        )
        binding.btnDeviceMode.contentDescription = getString(
            if (isDesktopMode) R.string.web_desktop_mode else R.string.web_mobile_mode,
        )
        if (reload) binding.webView.url?.let(::loadUrlForCurrentMode)
    }

    /**
     * 在 WebView 支持时同步设置 Win11 Chrome 的 User-Agent Client Hints。
     *
     * 这些元数据由 Chromium 生成 `Sec-CH-UA*` 请求头和 `navigator.userAgentData`，
     * 避免只改 User-Agent 字符串却仍暴露 Android 平台。
     */
    private fun applyUserAgentMetadata() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        val metadata = if (isDesktopMode) {
            val brands = listOf(
                UserAgentMetadata.BrandVersion.Builder()
                    .setBrand("Not_A Brand")
                    .setMajorVersion("99")
                    .setFullVersion("99.0.0.0")
                    .build(),
                UserAgentMetadata.BrandVersion.Builder()
                    .setBrand("Chromium")
                    .setMajorVersion(CHROME_MAJOR_VERSION)
                    .setFullVersion(CHROME_FULL_VERSION)
                    .build(),
                UserAgentMetadata.BrandVersion.Builder()
                    .setBrand("Google Chrome")
                    .setMajorVersion(CHROME_MAJOR_VERSION)
                    .setFullVersion(CHROME_FULL_VERSION)
                    .build(),
            )
            UserAgentMetadata.Builder()
                .setBrandVersionList(brands)
                .setFullVersion(CHROME_FULL_VERSION)
                .setPlatform("Windows")
                .setPlatformVersion(WINDOWS_11_PLATFORM_VERSION)
                .setArchitecture("x86")
                .setBitness(WINDOWS_11_BITNESS)
                .setWow64(false)
                .setModel("")
                .setMobile(false)
                .apply {
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA_FORM_FACTORS)) {
                        setFormFactors(listOf(UserAgentMetadata.FORM_FACTOR_DESKTOP))
                    }
                }
                .build()
        } else {
            // 空 Builder 表示恢复 WebView 默认 Client Hints，与手机 UA 保持一致。
            UserAgentMetadata.Builder().build()
        }
        WebSettingsCompat.setUserAgentMetadata(binding.webView.settings, metadata)
    }

    /** 按当前 UA 模式加载 URL，并在电脑模式为主导航附加 Win11 Chrome 请求头。 */
    private fun loadUrlForCurrentMode(url: String) {
        if (isDesktopMode) {
            binding.webView.loadUrl(url, WIN11_CHROME_HEADERS)
        } else {
            binding.webView.loadUrl(url)
        }
    }

    /** 展示从教务网页抓取课表前的三条必读注意事项。 */
    private fun showImportNotice() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.web_import_notice_title)
            .setMessage(R.string.web_import_notice_message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /** 展示居中的导入目标选择弹窗。 */
    private fun showImportModeDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_this_page)
            .setItems(
                arrayOf(
                    getString(R.string.web_import_overwrite_current),
                    getString(R.string.web_import_create_new),
                    getString(R.string.cancel),
                ),
            ) { dialog, which ->
                when (which) {
                    0 -> importCurrentPage(WebImportMode.OVERWRITE_CURRENT)
                    1 -> importCurrentPage(WebImportMode.CREATE_NEW)
                    else -> dialog.dismiss()
                }
            }
            .show()
    }

    /**
     * 规范学校表中的教务网址。
     *
     * 学校数据中仍有少量网址未携带协议头；WebView 直接加载这类文本会停留在空白页，
     * 因此与地址栏“前往”按钮共用同一套补全规则。file/content 等已有协议的网址保持原样。
     */
    private fun normalizeSchoolUrl(rawUrl: String): String {
        val url = rawUrl.trim()
        return when {
            url.isBlank() || "://" in url -> url
            else -> "http://$url"
        }
    }

    /**
     * 抓取当前页（含同源 iframe）HTML，先完成解析，再按选定模式写入课表。
     *
     * 覆盖模式下只有在解析成功且结果非空后才会清空当前课表，
     * 避免用户在登录页或课表未加载完时误点而丢失原有数据。
     *
     * @param mode 覆盖当前课表或新建课表后导入
     */
    private fun importCurrentPage(mode: WebImportMode) {
        setImportButtonsEnabled(false)
        lifecycleScope.launch {
            var importSucceeded = false
            runCatching {
                // WebImportSource 只取得原始文本；这里是 App/Parser 唯一转换边界。
                val payload = webImportSource.acquire(type)
                val previews = withContext(Dispatchers.Default) {
                    ParserFactory.parse(
                        ParserInput(
                            text = payload.primaryText,
                            type = type,
                            additionalTexts = payload.additionalTexts,
                        ),
                    )
                }
                    check(previews.isNotEmpty()) { getString(R.string.web_import_no_courses) }
                    val currentTable = repo.currentTableId()
                        .takeIf { tableId -> tableId > 0 }
                        ?.let { tableId -> repo.tableOnce(tableId) }
                    val overwriteExisting =
                        mode == WebImportMode.OVERWRITE_CURRENT && currentTable != null
                    val targetTable = when {
                        overwriteExisting -> requireNotNull(currentTable)

                        else -> {
                            val fallbackStart = LocalDate.now()
                                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                                .toString()
                            val tableId = repo.createTable(
                                // 教务导入没有源文件名，按实际创建时刻生成唯一且可辨识的名称。
                                name = ImportTableNames.fromSchoolImport(),
                                startDate = currentTable?.startDate ?: fallbackStart,
                                // 新建导入以默认配置为基础，不继承当前课表的周数。
                                maxWeek = AppDefaults.Table.MAX_WEEK,
                            )
                            repo.tableOnce(tableId)
                                ?: error("Target schedule does not exist")
                        }
                    }
                    val preparedImport = CourseImportPolicy.prepareImport(
                        table = targetTable,
                        previews = previews,
                        overwriteExisting = overwriteExisting,
                    )
                    repo.applyCourseImport(preparedImport.writeRequest)
                    setResult(RESULT_OK)
                    CourseImportResult(previews.size, preparedImport.rangeReport)
            }.onSuccess { result ->
                importSucceeded = true
                ImportSuccessFeedback.showThenReturnToSchedule(
                    activity = this@WebLoginActivity,
                    root = binding.root,
                    anchor = binding.btnImportPage,
                    result = result,
                    onNavigationSkipped = { setImportButtonsEnabled(true) },
                )
            }.onFailure { e ->
                val message = getString(
                    R.string.import_fail,
                    (e as? ParserException)?.message
                        ?: e.message
                        ?: getString(R.string.err_import_failed),
                )
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.btnImportPage)
                    .show()
            }
            // 成功后保持按钮禁用直到返回主课表，防止淡入、停留和淡出期间再次提交同一页。
            if (!importSucceeded) setImportButtonsEnabled(true)
        }
    }

    /** 导入期间禁用可重复触发的浮动按钮，防止重复入库。 */
    private fun setImportButtonsEnabled(enabled: Boolean) {
        binding.btnImportPage.isEnabled = enabled
        binding.btnDeviceMode.isEnabled = enabled
        binding.btnGo.isEnabled = enabled
        binding.editUrl.isEnabled = enabled
    }

    /** 网页课表写入目标。 */
    private enum class WebImportMode {
        OVERWRITE_CURRENT,
        CREATE_NEW,
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) binding.webView.onResume()
    }

    override fun onPause() {
        if (::binding.isInitialized) {
            CookieManager.getInstance().flush()
            binding.webView.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        if (::binding.isInitialized) disposeWebView(binding.webView)
        super.onDestroy()
    }

    /**
     * 从视图树移除并释放 WebView。
     *
     * @param webView 正常销毁时为页面 WebView；渲染进程异常时优先使用回调给出的实例
     */
    private fun disposeWebView(webView: WebView) {
        if (webViewDestroyed) return
        webViewDestroyed = true
        clearServiceWorkerInterception()
        if (::webImportSource.isInitialized) webImportSource.close()
        if (::webSessionClient.isInitialized) webSessionClient.close()
        webView.stopLoading()
        webView.webChromeClient = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeAllViews()
        webView.destroy()
    }

    companion object {
        /** WebView 内部可直接处理的协议，其他协议交给对应的系统应用。 */
        private val WEB_SCHEMES = setOf("http", "https", "file", "content", "about", "data")

        /** Win11 Chrome 主导航与子资源共用的可安全自定义请求头。 */
        private val WIN11_CHROME_HEADERS = mapOf(
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8,en-US;q=0.7",
            "Upgrade-Insecure-Requests" to "1",
        )

        const val EXTRA_TYPE = "type"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"

        /**
         * 教务导入专用的 Windows 桌面 Chrome UA。
         *
         * 使用固定值可保证不同 Android WebView 版本获得一致的桌面页面，同时不暴露设备型号、
         * Android 版本或 WebView 的 `wv` 标记。
         */
        private const val CHROME_MAJOR_VERSION = "153"
        private const val CHROME_FULL_VERSION = "153.0.8010.37"
        private const val WINDOWS_11_PLATFORM_VERSION = "19.0.0"

        /** `Sec-CH-UA-Bitness` 使用的 Win11 x64 位数；AndroidX WebKit API 直接接收整数。 */
        private const val WINDOWS_11_BITNESS = 64

        const val WINDOWS_DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/$CHROME_MAJOR_VERSION.0.0.0 Safari/537.36"

    }
}
