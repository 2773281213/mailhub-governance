package com.mailhub.client

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.mailhub.client.ui.swiss.GridRow
import com.mailhub.client.ui.swiss.MetaLine
import com.mailhub.client.ui.swiss.SwissButton
import com.mailhub.client.ui.swiss.SwissButtonKind
import com.mailhub.client.ui.swiss.SwissTabBar
import com.mailhub.client.ui.swiss.SwissTheme
import com.mailhub.client.ui.swiss.SwissToggle
import com.mailhub.client.ui.swiss.cols
import com.mailhub.client.ui.swiss.gridSpecFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * 文字左边缘对齐栏线的测试。不靠截图，直接取文字节点的边界和 gridSpecFor(width).x(n) 比较，
 * 误差不超过 1dp。360dp 和 411dp 各跑一遍。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SwissAlignmentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun assertTextLeft(text: String, expected: Dp) {
        val bounds = composeRule.onNodeWithText(text, substring = true, useUnmergedTree = true).getBoundsInRoot()
        val delta = abs((bounds.left - expected).value)
        assertTrue(
            "「$text」左边缘是 ${bounds.left}，应该落在栏线 $expected 上（误差 ${delta}dp）",
            delta <= 1f,
        )
    }

    @Composable
    private fun AlignSample() {
        Column {
            GridRow {
                SwissButton(
                    text = "主要操作",
                    onClick = {},
                    kind = SwissButtonKind.Primary,
                    modifier = Modifier.cols(1, 12),
                )
            }
            SwissTabBar(
                items = listOf("01" to "总览", "02" to "收件箱", "03" to "规则", "04" to "设置"),
                selected = 1,
                onSelect = {},
            )
            GridRow {
                SwissToggle(
                    checked = true,
                    onCheckedChange = {},
                    label = "同步通知",
                    modifier = Modifier.cols(1, 6),
                )
                SwissToggle(
                    checked = false,
                    onCheckedChange = {},
                    label = "仅 Wi-Fi",
                    modifier = Modifier.cols(7, 6),
                )
            }
            GridRow {
                MetaLine(
                    year = 2026,
                    place = "上海",
                    modifier = Modifier.cols(9, 4),
                )
            }
        }
    }

    private fun assertSample(width: Dp) {
        val spec = gridSpecFor(width)
        composeRule.setContent {
            SwissTheme {
                AlignSample()
            }
        }

        assertTextLeft("主要操作", spec.x(2, width))
        assertTextLeft("01 总览", spec.x(1, width))
        assertTextLeft("02 收件箱", spec.x(4, width))
        assertTextLeft("03 规则", spec.x(7, width))
        assertTextLeft("04 设置", spec.x(10, width))
        assertTextLeft("同步通知", spec.x(2, width))
        assertTextLeft("仅 Wi-Fi", spec.x(8, width))

        val metaBounds = composeRule.onNodeWithText("2026 — 上海", substring = true, useUnmergedTree = true).getBoundsInRoot()
        val metaHeight = metaBounds.bottom - metaBounds.top
        // Compose 的单行节点高度取字体度量高度（10sp 在 3x 下约 15dp），lineHeight 只影响行距，
        // 不会把节点裁到 12dp。这里断言「一行」，折行会是 30dp 左右。
        assertTrue(
            "MetaLine 应该只有一行，实际高度 $metaHeight",
            metaHeight <= 16.dp,
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun textEdgesAlignToGridAt360() {
        assertSample(360.dp)
    }

    @Test
    @Config(qualifiers = "w411dp-h800dp-xxhdpi")
    fun textEdgesAlignToGridAt411() {
        assertSample(411.dp)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-night-xxhdpi")
    fun accentButtonUsesPaperTextInDarkTheme() {
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

        val imageFile = File("build/alignment/accent-dark.png")
        imageFile.parentFile?.mkdirs()
        val previousRecord = System.getProperty("roborazzi.test.record")
        System.setProperty("roborazzi.test.record", "true")
        try {
            composeRule
                .onNodeWithText("危险操作", substring = true, useUnmergedTree = true)
                .captureRoboImage(imageFile.path)
        } finally {
            if (previousRecord == null) {
                System.clearProperty("roborazzi.test.record")
            } else {
                System.setProperty("roborazzi.test.record", previousRecord)
            }
        }

        val image = requireNotNull(ImageIO.read(imageFile)) { "读取截图失败：${imageFile.path}" }
        var paperPixels = 0
        var blackPixels = 0
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val rgb = image.getRGB(x, y)
                val red = (rgb shr 16 and 0xFF) / 255f
                val green = (rgb shr 8 and 0xFF) / 255f
                val blue = (rgb and 0xFF) / 255f
                if (red > 0.85f && green > 0.85f && blue > 0.8f) {
                    paperPixels++
                }
                if (red < 0.05f && green < 0.05f && blue < 0.05f) {
                    blackPixels++
                }
            }
        }
        assertTrue("红底按钮上应该找到 Paper 文字像素", paperPixels > 0)
        assertEquals("红底按钮上不应该出现纯黑像素", 0, blackPixels)
    }
}
