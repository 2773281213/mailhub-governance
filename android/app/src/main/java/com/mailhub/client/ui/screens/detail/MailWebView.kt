package com.mailhub.client.ui.screens.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.mailhub.client.ui.swiss.SwissSpace
import java.io.ByteArrayInputStream
import java.net.URI

/**
 * 正文 WebView 的安全配置。全部是纯数据，不依赖 Android 类型，方便单元测试直接断言。
 *
 * 默认姿态是「什么都别做」：脚本、文件访问、content 访问全部关闭，网络加载默认拦截，
 * 链接一律不在 WebView 里跳转。用户为单封邮件显式放开外部图片后才允许网络加载。
 */
data class MailWebViewConfig(
    val javaScriptEnabled: Boolean = false,
    val allowFileAccess: Boolean = false,
    val allowContentAccess: Boolean = false,
    /** 默认 true：WebView 完全不发起网络请求，外部图片（追踪像素）也就不会被加载。 */
    val blockNetworkLoads: Boolean = true,
    /** 风险邮件禁用所有链接。 */
    val allowLinks: Boolean = false,
    /** 用户对本封邮件放开了外部图片。 */
    val allowRemoteImages: Boolean = false,
)

/** 链接点击的处理方式：要么弹确认框交给系统浏览器，要么直接提示不可用。 */
enum class LinkTap { Confirm, Blocked }

/**
 * 按邮件是否有风险和用户是否放开图片算出 WebView 配置。
 * 风险邮件的链接一律禁用；图片只有用户显式点过「显示外部图片」才放开。
 */
fun mailWebViewConfig(isRisky: Boolean, showExternalImages: Boolean): MailWebViewConfig =
    MailWebViewConfig(
        javaScriptEnabled = false,
        allowFileAccess = false,
        allowContentAccess = false,
        blockNetworkLoads = !showExternalImages,
        allowLinks = !isRisky,
        allowRemoteImages = showExternalImages,
    )

/** 点击链接后的处理方式：风险邮件或非 http(s) 地址一律拦下。 */
fun linkTapFor(url: String, config: MailWebViewConfig): LinkTap = when {
    !config.allowLinks -> LinkTap.Blocked
    !url.startsWith("https://", ignoreCase = true) &&
        !url.startsWith("http://", ignoreCase = true) -> LinkTap.Blocked

    else -> LinkTap.Confirm
}

/**
 * 是否放行这一次网络请求。只有用户放开了外部图片、且请求是 https 的 GET 才放行，
 * 其余（http、POST、其它 scheme）一律拦成空响应。
 */
fun allowsNetworkRequest(url: String, method: String, config: MailWebViewConfig): Boolean {
    if (!config.allowRemoteImages) return false
    if (!method.equals("GET", ignoreCase = true)) return false
    return url.startsWith("https://", ignoreCase = true)
}

/** 确认框里显示的完整域名；解析不出主机名时原样返回地址。 */
fun hostOf(url: String): String = runCatching { URI(url).host ?: url }.getOrDefault(url)

/** 把配置写进真实的 WebSettings。 */
fun WebSettings.applyMailConfig(config: MailWebViewConfig) {
    javaScriptEnabled = config.javaScriptEnabled
    allowFileAccess = config.allowFileAccess
    allowContentAccess = config.allowContentAccess
    blockNetworkLoads = config.blockNetworkLoads
    // 剩下的都往最保守的方向设：不开本地存储、不开多窗口、不混用 http/https
    domStorageEnabled = false
    databaseEnabled = false
    javaScriptCanOpenWindowsAutomatically = false
    setSupportMultipleWindows(false)
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    cacheMode = WebSettings.LOAD_NO_CACHE
}

/** 拦截后返回的空响应。 */
private fun emptyResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

private const val NoBaseUrl = "about:blank"
private const val HtmlMime = "text/html"
private const val Charset = "utf-8"

/**
 * 正文 WebView 的客户端：
 * - 链接一律不在 WebView 里加载，只把地址交给页面层（风险邮件只提示）；
 * - 未放行外部图片时，任何 http(s) 请求都返回空响应。
 */
private class MailBodyClient(
    private val config: MailWebViewConfig,
    private val onLink: (String) -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url?.toString().orEmpty()
        if (!url.startsWith("http", ignoreCase = true)) return null
        return if (allowsNetworkRequest(url, request.method.orEmpty(), config)) {
            null
        } else {
            emptyResponse()
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url?.toString().orEmpty()
        if (url.isNotEmpty()) onLink(url)
        // 永远交给页面层处理，WebView 自己不跳转
        return true
    }
}

/** 内容高度跟着 HTML 变，所以每次换内容/换配置都重新加载一次。 */
private class MailBodyView(context: Context) : WebView(context) {
    var appliedHtml: String? = null
    var appliedConfig: MailWebViewConfig? = null
}

/**
 * 正文 HTML 的渲染容器：不启用脚本、不调用 addJavascriptInterface，
 * 默认不加载任何外部资源；[onLink] 收到点击的链接，由页面层弹确认框。
 */
@Composable
fun MailBodyWebView(
    html: String,
    config: MailWebViewConfig,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SwissSpace.Xxxl * 3),
        factory = { context ->
            MailBodyView(context).apply {
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                settings.applyMailConfig(config)
                appliedConfig = config
                webViewClient = MailBodyClient(config, onLink)
                loadDataWithBaseURL(NoBaseUrl, html, HtmlMime, Charset, null)
                appliedHtml = html
            }
        },
        update = { view ->
            if (view.appliedConfig != config) {
                view.settings.applyMailConfig(config)
                view.webViewClient = MailBodyClient(config, onLink)
                view.appliedConfig = config
            }
            if (view.appliedHtml != html) {
                view.loadDataWithBaseURL(NoBaseUrl, html, HtmlMime, Charset, null)
                view.appliedHtml = html
            }
        },
    )
}

/** 用系统浏览器打开链接；没有可用浏览器时静默失败，不把应用拖崩。 */
fun openInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // 设备上没有浏览器：保持原样，不弹任何东西
    }
}
