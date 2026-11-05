package com.mailhub.client.ui.screens.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.mailhub.client.ui.swiss.SwissTheme
import com.mailhub.client.ui.swiss.gridSpecFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 长发件人地址不能被截断：有分类时默认只占第 5–8 栏，
 * 放不下就独占第 5–12 栏一行，完整地址可见且左边缘仍落在第 5 栏栏线上。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class MailRowLongSenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 钓鱼邮件的真实地址：截成 `notice@paypa1` 正好把最要紧的域名藏起来。 */
    private val longSender = "notice@paypa1-secure.example"

    private fun render() {
        composeRule.setContent {
            CompositionLocalProvider(LocalMailNow provides 1793782440.0) {
                SwissTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        MailRow(
                            data = MailRowData(
                                id = 1,
                                subject = "账户存在异常登录，请立即验证你的身份",
                                sender = longSender,
                                dateTs = 1793782440.0 - 1800,
                                isUnread = true,
                                snippet = "这是一段示例摘要。",
                                categoryIndex = 6,
                                categoryName = "可疑",
                            ),
                            zoneId = "Asia/Shanghai",
                        )
                    }
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun longSenderIsFullyVisibleFromColumnFive() {
        render()

        val node = composeRule.onNodeWithText(longSender, useUnmergedTree = true)
            .fetchSemanticsNode()
        val density = composeRule.density.density
        val spec = gridSpecFor(360.dp)
        val expectedLeft = with(composeRule.density) { spec.x(5, 360.dp).toPx() }
        val expectedWidth = with(composeRule.density) { spec.spanWidth(8, 360.dp).toPx() }

        assertEquals("发件人左边缘应该落在第 5 栏栏线上", expectedLeft, node.boundsInRoot.left, density)
        assertEquals("发件人应该独占第 5–12 栏", expectedWidth, node.boundsInRoot.width, density)

        val layouts = mutableListOf<TextLayoutResult>()
        val action = node.config[SemanticsActions.GetTextLayoutResult].action
        assertTrue("拿不到发件人的文本布局结果", action != null && action.invoke(layouts))
        val layout = layouts.first()
        assertEquals(longSender, layout.layoutInput.text.text)
        assertFalse("发件人地址被截断了", layout.hasVisualOverflow)
    }
}
