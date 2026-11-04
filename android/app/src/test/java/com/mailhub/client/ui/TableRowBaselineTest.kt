package com.mailhub.client.ui

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.mailhub.client.ui.swiss.SwissTheme
import com.mailhub.client.ui.swiss.TableRow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import kotlin.math.abs

/**
 * TableRow 的标签（caption，行高 16）和值（body，行高 20）必须首行基线对齐。
 *
 * 做法：分别截取两个文字节点，找出各自最下面一行「有墨」的像素（数字字形贴基线，
 * 所以这一行就是基线附近），再加上节点在根布局里的顶边，得到根坐标系下的基线像素位置，
 * 两者之差小于 1dp 即通过。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TableRowBaselineTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 图片里最后一行含墨的像素行号（自上而下）；没有墨返回 -1。 */
    private fun lastInkRow(image: BufferedImage): Int {
        for (y in image.height - 1 downTo 0) {
            for (x in 0 until image.width) {
                if (PixelProbe.isInk(image.getRGB(x, y))) return y
            }
        }
        return -1
    }

    /** 根坐标系下的基线像素位置。 */
    private fun baselineInRoot(text: String, probeName: String): Float {
        val node = composeRule.onNodeWithText(text, useUnmergedTree = true)
        val topDp = node.getBoundsInRoot().top.value
        val image = PixelProbe.capture(node, probeName)
        val inkRow = lastInkRow(image)
        assertTrue("「$text」的截图里找不到墨迹", inkRow >= 0)
        return topDp * composeRule.density.density + inkRow
    }

    private fun assertBaselineAligned(width: Int, density: Float) {
        composeRule.setContent {
            SwissTheme {
                TableRow(label = "2026", value = "1284")
            }
        }

        val labelBaseline = baselineInRoot("2026", "tablerow-label-$width")
        val valueBaseline = baselineInRoot("1284", "tablerow-value-$width")

        // 1dp 在 3x 屏上是 3px
        val limitPx = 1f * composeRule.density.density
        val delta = abs(labelBaseline - valueBaseline)
        assertTrue(
            "标签基线在 ${labelBaseline}px，值基线在 ${valueBaseline}px，差 ${delta}px " +
                "超过了 ${limitPx}px（1dp）",
            delta <= limitPx,
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun labelAndValueShareFirstBaselineAt360() {
        assertBaselineAligned(width = 360, density = 3f)
    }

    @Test
    @Config(qualifiers = "w411dp-h800dp-xxhdpi")
    fun labelAndValueShareFirstBaselineAt411() {
        assertBaselineAligned(width = 411, density = 3f)
    }
}
