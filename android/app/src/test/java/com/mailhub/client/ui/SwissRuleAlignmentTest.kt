package com.mailhub.client.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.mailhub.client.ui.swiss.HairlineRule
import com.mailhub.client.ui.swiss.SwissPalette
import com.mailhub.client.ui.swiss.SwissTheme
import com.mailhub.client.ui.swiss.gridSpecFor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * HairlineRule 默认占第 1–12 栏：左右边缘正好等于页边距和右侧栏线，
 * 不再横贯整屏。用像素扫描验证，因为线条没有自己的语义节点。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SwissRuleAlignmentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun assertHairlineEdges(widthDp: Float, qualifiers: String) {
        val width = widthDp.dp
        composeRule.setContent {
            SwissTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    HairlineRule(color = SwissPalette.Ink)
                }
            }
        }

        val density = composeRule.density.density
        val spec = gridSpecFor(width)
        val totalPx = (width.value * density).roundToInt()
        val edges = spec.edges(1, 12, totalPx, density)

        val image = PixelProbe.capture(composeRule.onRoot(), "hairline-$qualifiers")
        // 1dp 直线在 3 倍密度下正好 3px，取中间那一行扫描。
        val row = 1
        var firstInk = -1
        var lastInk = -1
        for (x in 0 until image.width) {
            if (PixelProbe.isInk(image.getRGB(x, row))) {
                if (firstInk < 0) firstInk = x
                lastInk = x
            }
        }
        assertEquals("细线左边缘必须等于页边距", edges.x0, firstInk)
        assertEquals("细线右边缘必须落在第 12 栏右栏线上", edges.x1 - 1, lastInk)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun hairlineEdgesEqualPageMarginsAt360() {
        assertHairlineEdges(360f, "w360")
    }

    @Test
    @Config(qualifiers = "w411dp-h800dp-xxhdpi")
    fun hairlineEdgesEqualPageMarginsAt411() {
        assertHairlineEdges(411f, "w411")
    }
}
