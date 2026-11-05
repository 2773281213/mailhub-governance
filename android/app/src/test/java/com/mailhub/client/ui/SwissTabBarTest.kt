package com.mailhub.client.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.mailhub.client.ui.swiss.SwissTabBar
import com.mailhub.client.ui.swiss.SwissTheme
import com.mailhub.client.ui.swiss.gridSpecFor
import com.mailhub.client.ui.swiss.tabSpanFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 标签栏按 12 / n 分栏：3 项各占 4 栏，2/4/6 项同理；其他项数直接以中文错误信息失败。
 * 位置用文字左边缘验证——每项没有水平内边距，所以文字左边缘就是该项起始栏线。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SwissTabBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun supportedCountsMapToWholeColumns() {
        assertEquals(6, tabSpanFor(2))
        assertEquals(4, tabSpanFor(3))
        assertEquals(3, tabSpanFor(4))
        assertEquals(2, tabSpanFor(6))
    }

    @Test
    fun unsupportedCountFailsInChinese() {
        val error = assertThrows(IllegalArgumentException::class.java) { tabSpanFor(5) }
        val message = error.message.orEmpty()
        assertTrue("错误信息应该是中文说明，实际：$message", message.contains("标签栏只支持"))
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun threeItemsTakeFourColumnsEach() {
        val width = 360.dp
        composeRule.setContent {
            SwissTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    SwissTabBar(
                        items = listOf("01" to "总览", "02" to "收件箱", "03" to "规则"),
                        selected = 0,
                        onSelect = {},
                    )
                }
            }
        }

        val density = composeRule.density.density
        val spec = gridSpecFor(width)
        val totalPx = (width.value * density).roundToInt()
        val expectations = listOf("01 总览" to 1, "02 收件箱" to 5, "03 规则" to 9)
        expectations.forEach { (text, startColumn) ->
            val bounds = composeRule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()
            val leftPx = (bounds.left.value * density).roundToInt()
            val expected = spec.edges(startColumn, 1, totalPx, density).x0
            assertTrue(
                "「$text」左边缘应该是第 $startColumn 栏栏线 $expected，实际 $leftPx",
                abs(leftPx - expected) <= 1,
            )
        }
    }
}
