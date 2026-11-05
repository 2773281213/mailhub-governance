package com.mailhub.client.ui.screens.detail

import android.webkit.WebView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.mailhub.client.ui.swiss.SwissPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 正文 WebView 的纸面底色与包装样式。
 *
 * 真机验收（截图 13、14）里正文是纯白底、不在三色体系内，首次打开还短暂盖住了
 * 上面的风险块和键值表。这里钉住两件事：底色取自 Paper（深色模式下也一样），
 * 以及包装出来的 HTML 只有基础样式和原始正文，不引入任何脚本。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MailBodyHtmlTest {

    /** 与 SwissType.body() 相同的一套字号：13/20。 */
    private val bodyStyle = TextStyle(fontSize = 13.sp, lineHeight = 20.sp)

    private val bodyHtml = "<p>账户存在异常登录，请立即验证。</p>"

    @Test
    fun wrapperKeepsTheOriginalBodyAndAddsTheBaseStyle() {
        val document = wrapMailBodyHtml(bodyHtml, bodyStyle)

        assertTrue("包装后的 HTML 必须保留原始正文", document.contains(bodyHtml))
        assertTrue("基础样式只加在 <head> 里", document.contains("<style>"))
        // 底色和文字色都从 SwissPalette 生成
        assertTrue(document.contains("background:${cssHex(SwissPalette.Paper)}"))
        assertTrue(document.contains("color:${cssHex(SwissPalette.Ink)}"))
        assertTrue(document.contains("margin:0; padding:0"))
        // 正文的字母号和行高与 SwissType.body() 一致（以 px 计）
        assertTrue(document.contains("font-size:13px"))
        assertTrue(document.contains("line-height:20px"))
        // 图片不超出正文宽度
        assertTrue(document.contains("img { max-width:100%; height:auto; }"))
    }

    @Test
    fun wrapperIntroducesNoScript() {
        val document = wrapMailBodyHtml(bodyHtml, bodyStyle)

        assertFalse("不能引入脚本", document.contains("<script", ignoreCase = true))
        assertFalse("不能有 javascript: 伪协议", document.contains("javascript:", ignoreCase = true))
        assertFalse("不能有内联事件", document.contains("onerror", ignoreCase = true))
    }

    @Test
    fun cssHexIsGeneratedFromThePalette() {
        assertEquals("#F2EDE4", cssHex(SwissPalette.Paper))
        assertEquals("#000000", cssHex(SwissPalette.Ink))
        assertEquals("#DA291C", cssHex(SwissPalette.Red))
    }

    @Test
    fun webViewBackgroundIsPaperNotWhite() {
        val context = RuntimeEnvironment.getApplication()
        val view = createMailBodyView(
            context = context,
            config = mailWebViewConfig(isRisky = false, showExternalImages = false),
            onLink = {},
        )

        // Robolectric 的 WebView shadow 记录 setBackgroundColor 的值（View.getBackground 读不到）
        val background = shadowOf(view as WebView).backgroundColor
        assertEquals(SwissPalette.Paper.toArgb(), background)
        // 纯白不在三色体系内
        assertNotEquals(android.graphics.Color.WHITE, background)
        assertEquals(SwissPalette.Paper.toArgb(), mailBodyBackgroundArgb())
    }
}
