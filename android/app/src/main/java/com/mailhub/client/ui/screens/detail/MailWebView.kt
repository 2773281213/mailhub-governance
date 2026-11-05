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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.viewinterop.AndroidView
import com.mailhub.client.ui.swiss.SwissPalette
import com.mailhub.client.ui.swiss.SwissSpace
import com.mailhub.client.ui.swiss.SwissType
import java.io.ByteArrayInputStream
import java.net.URI
import kotlin.math.roundToInt

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

/**
 * 配置变化后是否需要重新加载正文。
 *
 * Android 官方文档对 `WebSettings.setBlockNetworkLoads` 的说明是：值从 true 改成 false 之后，
 * 已经显示的正文引用的网络资源**不会**被重新抓取，必须先调用 `WebView#reload()`。
 * 在 `about:blank` 基址下 `reload()` 的行为不可靠，所以这里改判为「重新加载一遍正文」，
 * 由调用方再走一次 `loadDataWithBaseURL(...)`。
 *
 * 只有影响网络加载的两项会触发重载：[MailWebViewConfig.blockNetworkLoads] 与
 * [MailWebViewConfig.allowRemoteImages]；其余安全设置的开关不改变已经渲染出来的内容。
 * [old] 为空表示还没应用过配置（第一次加载），同样按需要加载处理。
 */
fun needsReload(old: MailWebViewConfig?, new: MailWebViewConfig): Boolean {
    if (old == null) return true
    return old.blockNetworkLoads != new.blockNetworkLoads ||
        old.allowRemoteImages != new.allowRemoteImages
}

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

/** 正文纸面的颜色：永远是 Paper 米白，深色模式下也是米白（正文是一张纸，不是界面底色）。 */
val MailBodyPaper: Color = SwissPalette.Paper

/** 正文底色（ARGB）。WebView 的 setBackgroundColor 只认 int，颜色仍然取自 [SwissPalette]。 */
fun mailBodyBackgroundArgb(): Int = MailBodyPaper.toArgb()

/** 颜色转成 CSS 用的 #RRGGBB，颜色值只从 SwissPalette 生成，不在别处再写一遍。 */
internal fun cssHex(color: Color): String =
    String.format("#%06X", color.toArgb() and 0xFFFFFF)

/**
 * 把邮件正文包进基础样式：只在 `<head>` 里加一段 `<style>`，不引入任何脚本。
 *
 * 基础样式负责三件事：
 * - 去掉 html / body 的默认外边距，底色和文字色取三色（Paper / Ink），
 *   这样正文框里不会出现一块纯白，上下留白也均匀；
 * - 正文字号和行高跟 [SwissType.body] 一致（13/20，按 px 计）；
 * - 图片不超出正文宽度。
 *
 * 邮件自带的样式排在后面，可以覆盖这些基础样式，这一点不做处理。
 * 纯函数，方便单元测试直接断言。
 */
fun wrapMailBodyHtml(html: String, bodyStyle: TextStyle): String {
    val paper = cssHex(SwissPalette.Paper)
    val ink = cssHex(SwissPalette.Ink)
    val fontSize = "${bodyStyle.fontSize.value.roundToInt()}px"
    val lineHeight = "${bodyStyle.lineHeight.value.roundToInt()}px"
    return buildString(html.length + 512) {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>")
        append("html, body { margin:0; padding:0; background:$paper; color:$ink; }")
        append("body { font-size:$fontSize; line-height:$lineHeight; }")
        append("img { max-width:100%; height:auto; }")
        append("</style></head><body>")
        append(html)
        append("</body></html>")
    }
}

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
internal class MailBodyView(context: Context) : WebView(context) {
    var appliedHtml: String? = null
    var appliedConfig: MailWebViewConfig? = null
}

/**
 * 创建正文 WebView：纸面底色先设好，安全配置全部应用，正文不在这里加载。
 * 底色和配置是「View 自带」的属性，与正文无关，所以放在工厂里；正文由调用方
 * 在页面摆好位置之后再 load，见 [MailBodyWebView]。
 */
internal fun createMailBodyView(
    context: Context,
    config: MailWebViewConfig,
    onLink: (String) -> Unit,
): MailBodyView = MailBodyView(context).apply {
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
    // 纸面底色：WebView 默认是 #FFFFFF，不在三色体系里
    setBackgroundColor(mailBodyBackgroundArgb())
    settings.applyMailConfig(config)
    appliedConfig = config
    webViewClient = MailBodyClient(config, onLink)
}

/**
 * 正文 HTML 的渲染容器：不启用脚本、不调用 addJavascriptInterface，
 * 默认不加载任何外部资源；[onLink] 收到点击的链接，由页面层弹确认框。
 *
 * 底色固定是 Paper（深色模式下也不变），正文外面再包一层基础样式，
 * 所以正文框里不会出现一块纯白，框内的上下留白也和纯文本正文一致。
 *
 * 首帧遮挡的原因与改法：WebView 默认是不透明白底，`AndroidView` 的工厂一创建它就会
 * 渲染一次，而此时 Compose 还没给它量出位置和尺寸，那块白底就先按初始布局画了出来，
 * 于是短暂盖住了上面的风险块和键值表。这里把第一次加载推迟到 [onGloballyPositioned]
 * 之后（`placed` 为真才 loadDataWithBaseURL），再加上底色改成 Paper，
 * 首帧就是一张空白的纸面，不会再盖住任何内容。
 */
@Composable
fun MailBodyWebView(
    html: String,
    config: MailWebViewConfig,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bodyStyle = SwissType.body()
    val document = remember(html, bodyStyle) { wrapMailBodyHtml(html, bodyStyle) }
    // 摆上位置之后才允许加载正文，避免首帧按初始尺寸画一块白底。
    // 这里在组合里读一次，保证状态翻转一定触发重组，update 才会带着新值重新跑一遍。
    var placed by remember { mutableStateOf(false) }
    val ready = placed
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SwissSpace.Xxxl * 3)
            .onGloballyPositioned { placed = true },
        factory = { context ->
            createMailBodyView(context = context, config = config, onLink = onLink)
        },
        update = { view ->
            // 配置是否要求重新加载正文，必须在写回 appliedConfig 之前算出来
            val reloadForConfig = needsReload(view.appliedConfig, config)
            val reloadForHtml = view.appliedHtml != document
            if (view.appliedConfig != config) {
                view.settings.applyMailConfig(config)
                view.webViewClient = MailBodyClient(config, onLink)
                view.appliedConfig = config
            }
            // 放开 / 收回外部图片后必须重新加载正文，否则图片不会出现（也不会消失）
            if (ready && (reloadForConfig || reloadForHtml)) {
                view.loadDataWithBaseURL(NoBaseUrl, document, HtmlMime, Charset, null)
                view.appliedHtml = document
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
