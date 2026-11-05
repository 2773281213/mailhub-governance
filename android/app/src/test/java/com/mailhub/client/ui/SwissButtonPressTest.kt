package com.mailhub.client.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import com.mailhub.client.ui.swiss.GridRow
import com.mailhub.client.ui.swiss.SwissButton
import com.mailhub.client.ui.swiss.SwissButtonKind
import com.mailhub.client.ui.swiss.SwissTheme
import com.mailhub.client.ui.swiss.cols
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 深色模式下按下强调按钮：必须变成「前景色（Paper）填充 + 背景色（Ink）文字」。
 * 旧实现在这里会变成黑底黑框，按钮直接看不见，所以用像素扫描守住这条。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SwissButtonPressTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "w360dp-h800dp-night-xxhdpi")
    fun accentButtonPressedInDarkThemeIsNotInk() {
        composeRule.setContent {
            SwissTheme {
                GridRow {
                    SwissButton(
                        text = "危险操作",
                        onClick = {},
                        kind = SwissButtonKind.Accent,
                        modifier = Modifier.cols(1, 12),
                    )
                }
            }
        }

        val button = composeRule.onNode(hasClickAction())
        button.performTouchInput { down(center) }
        composeRule.waitForIdle()

        val image = PixelProbe.capture(button, "accent-pressed-dark")
        var paper = 0
        var ink = 0
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val rgb = image.getRGB(x, y)
                if (PixelProbe.isPaper(rgb)) paper++
                if (PixelProbe.isInk(rgb)) ink++
            }
        }
        val total = image.width * image.height
        assertTrue("按下时背景应该是 Paper 填充，实际 Paper 像素 $paper / $total", paper * 2 > total)
        assertTrue("按下时应该能看到 Ink 文字像素，实际 $ink", ink > 0)
        assertTrue(
            "按下时背景不能是 Ink（Paper $paper，Ink $ink）",
            paper > ink * 5,
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun accentButtonReleasedInLightThemeIsRedWithPaperText() {
        composeRule.setContent {
            SwissTheme {
                GridRow {
                    SwissButton(
                        text = "危险操作",
                        onClick = {},
                        kind = SwissButtonKind.Accent,
                        modifier = Modifier.cols(1, 12),
                    )
                }
            }
        }

        val image = PixelProbe.capture(composeRule.onNode(hasClickAction()), "accent-idle-light")
        var red = 0
        var paper = 0
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val rgb = image.getRGB(x, y)
                if (PixelProbe.isRed(rgb)) red++
                if (PixelProbe.isPaper(rgb)) paper++
            }
        }
        assertTrue("强调按钮平时是红底，实际红像素 $red", red * 2 > image.width * image.height)
        assertTrue("强调按钮上的文字是米白，实际米白像素 $paper", paper > 0)
    }
}
