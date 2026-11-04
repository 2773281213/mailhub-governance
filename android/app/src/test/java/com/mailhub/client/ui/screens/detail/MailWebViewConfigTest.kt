package com.mailhub.client.ui.screens.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文 WebView 的安全配置：全是纯函数，不依赖 Android 运行时。
 * 断言的点就是 SECURITY.md 里的硬性要求：不执行脚本、不读本地文件、
 * 默认不联网、风险邮件禁链接、放开图片后只放行 https 的 GET。
 */
class MailWebViewConfigTest {

    @Test
    fun scriptAndLocalAccessAreOffByDefault() {
        val config = mailWebViewConfig(isRisky = false, showExternalImages = false)

        assertFalse("不能执行脚本", config.javaScriptEnabled)
        assertFalse("不能读本地文件", config.allowFileAccess)
        assertFalse("不能读 content:// 资源", config.allowContentAccess)
        assertTrue("默认完全不联网，追踪像素不会加载", config.blockNetworkLoads)
        assertFalse(config.allowRemoteImages)
    }

    @Test
    fun linksAreDisabledForRiskyMail() {
        val risky = mailWebViewConfig(isRisky = true, showExternalImages = false)
        assertFalse(risky.allowLinks)
        assertEquals(LinkTap.Blocked, linkTapFor("https://example.com/a", risky))

        // 放开图片也不会顺手放开风险邮件的链接
        val riskyWithImages = mailWebViewConfig(isRisky = true, showExternalImages = true)
        assertEquals(LinkTap.Blocked, linkTapFor("https://example.com/a", riskyWithImages))
    }

    @Test
    fun safeMailAsksBeforeOpeningALink() {
        val safe = mailWebViewConfig(isRisky = false, showExternalImages = false)

        assertTrue(safe.allowLinks)
        assertEquals(LinkTap.Confirm, linkTapFor("https://example.com/a", safe))
        // 非 http(s) 一律拦下，包括 javascript: 与 file:
        assertEquals(LinkTap.Blocked, linkTapFor("javascript:alert(1)", safe))
        assertEquals(LinkTap.Blocked, linkTapFor("file:///data/local/tmp/a.html", safe))
        assertEquals(LinkTap.Blocked, linkTapFor("", safe))
    }

    @Test
    fun remoteImagesOnlyAllowHttpsGet() {
        val config = mailWebViewConfig(isRisky = false, showExternalImages = true)

        assertFalse("用户点过「显示外部图片」后才允许网络加载", config.blockNetworkLoads)
        assertTrue(config.allowRemoteImages)
        assertTrue(allowsNetworkRequest("https://cdn.example.com/a.png", "GET", config))
        assertTrue(allowsNetworkRequest("https://cdn.example.com/a.png", "get", config))
        assertFalse("http 不放行", allowsNetworkRequest("http://cdn.example.com/a.png", "GET", config))
        assertFalse("非 GET 不放行", allowsNetworkRequest("https://cdn.example.com/a.png", "POST", config))
        assertFalse("其它协议不放行", allowsNetworkRequest("file:///a.png", "GET", config))
        assertFalse("ftp 不放行", allowsNetworkRequest("ftp://example.com/a.png", "GET", config))
    }

    @Test
    fun everythingIsBlockedBeforeTheUserAsksForImages() {
        val config = mailWebViewConfig(isRisky = false, showExternalImages = false)

        assertFalse(allowsNetworkRequest("https://cdn.example.com/a.png", "GET", config))
    }

    @Test
    fun confirmPanelShowsTheFullHost() {
        assertEquals("paypa1-secure.example", hostOf("https://paypa1-secure.example/verify?token=1"))
        assertEquals("mailhub.dev", hostOf("http://mailhub.dev/a/b"))
        // 解析不出主机名时原样显示地址
        assertEquals("about:blank", hostOf("about:blank"))
    }
}
