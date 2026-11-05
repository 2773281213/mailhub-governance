package com.mailhub.client.ui.screens.accounts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import com.mailhub.client.ui.swiss.SwissTheme
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
 * 设备码放不下时按宽度缩小：360dp 屏上 9 位设备码必须完整可见（不溢出、不换行），
 * 8 位及以内仍然用 56sp 的 title。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AddAccountDeviceCodeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun layoutOf(code: String): TextLayoutResult {
        composeRule.setContent {
            SwissTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    AddAccountBody(
                        state = addDeviceFixtureState()
                            .copy(deviceCode = DeviceCodeUi(code = code)),
                        onEvent = {},
                    )
                }
            }
        }
        val node = composeRule.onNodeWithText(code, useUnmergedTree = true)
            .fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        val action = node.config[SemanticsActions.GetTextLayoutResult].action
        assertTrue("拿不到设备码的文本布局结果", action != null && action.invoke(layouts))
        return layouts.first()
    }

    @Test
    @Config(qualifiers = "w360dp-h1500dp-xxhdpi")
    fun nineDigitCodeIsFullyVisibleAt360dp() {
        val layout = layoutOf("QZ7XK4JN9")

        assertEquals("设备码必须保持单行", 1, layout.lineCount)
        assertFalse("9 位设备码不能被裁掉", layout.hasVisualOverflow)
        assertTrue(
            "放不下时字号要缩小到 56sp 以下",
            layout.layoutInput.style.fontSize.value < 56f,
        )
        assertTrue(
            "字号不能小于 40sp",
            layout.layoutInput.style.fontSize.value >= 40f,
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h1500dp-xxhdpi")
    fun eightDigitCodeKeepsTheTitleSize() {
        val layout = layoutOf("QZ7XK4JN")

        assertEquals(1, layout.lineCount)
        assertFalse(layout.hasVisualOverflow)
        assertEquals(
            "8 位以内的设备码仍然用 56sp",
            56f,
            layout.layoutInput.style.fontSize.value,
            0.001f,
        )
    }
}
