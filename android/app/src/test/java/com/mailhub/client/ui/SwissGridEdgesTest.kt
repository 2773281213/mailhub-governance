package com.mailhub.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mailhub.client.ui.swiss.GridRow
import com.mailhub.client.ui.swiss.SwissTheme
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
import kotlin.math.roundToInt

/**
 * GridRow 的边缘必须和 [com.mailhub.client.ui.swiss.GridSpec.edges] 完全一致：
 * 相邻两栏元素的左右边缘在像素层面零误差，360dp 和 411dp 各跑一遍
 * （411dp 的栏宽是 24.25dp，最容易暴露「分别取整栏宽和左边缘」带来的 1px 误差）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SwissGridEdgesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun assertGridEdges(width: Dp) {
        composeRule.setContent {
            SwissTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    GridRow {
                        Box(
                            modifier = Modifier
                                .cols(1, 3)
                                .height(24.dp)
                                .background(com.mailhub.client.ui.swiss.SwissPalette.Ink)
                                .testTag("a"),
                        )
                        Box(
                            modifier = Modifier
                                .cols(4, 3)
                                .height(24.dp)
                                .background(com.mailhub.client.ui.swiss.SwissPalette.Ink)
                                .testTag("b"),
                        )
                        Box(
                            modifier = Modifier
                                .cols(10, 3)
                                .height(24.dp)
                                .background(com.mailhub.client.ui.swiss.SwissPalette.Ink)
                                .testTag("c"),
                        )
                    }
                }
            }
        }

        val density = composeRule.density.density
        val spec = gridSpecFor(width)
        val totalPx = (width.value * density).roundToInt()

        val placements = listOf(
            Triple("a", 1, 3),
            Triple("b", 4, 3),
            Triple("c", 10, 3),
        )
        placements.forEach { (tag, start, span) ->
            val bounds = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            val left = (bounds.left.value * density).roundToInt()
            val right = (bounds.right.value * density).roundToInt()
            val edges = spec.edges(start, span, totalPx, density)
            assertEquals("$tag 左边缘必须落在第 $start 栏栏线上", edges.x0, left)
            assertEquals("$tag 右边缘必须落在第 ${start + span - 1} 栏右栏线上", edges.x1, right)
        }

        // 相邻两栏之间只隔一个栏间距，不能有累积误差。
        val a = spec.edges(1, 3, totalPx, density)
        val b = spec.edges(4, 3, totalPx, density)
        assertEquals("相邻元素之间的空隙必须正好是一个栏间距", (spec.gutter.value * density).roundToInt(), b.x0 - a.x1)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun edgesMatchGridAt360() {
        assertGridEdges(360.dp)
    }

    @Test
    @Config(qualifiers = "w411dp-h800dp-xxhdpi")
    fun edgesMatchGridAt411() {
        assertGridEdges(411.dp)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun unboundedWidthFailsWithChineseMessage() {
        val failure = runCatching {
            composeRule.setContent {
                SwissTheme {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        GridRow {
                            Box(
                                modifier = Modifier
                                    .cols(1, 1)
                                    .height(24.dp)
                                    .background(com.mailhub.client.ui.swiss.SwissPalette.Ink),
                            )
                        }
                    }
                }
            }
        }.exceptionOrNull()

        val messages = generateSequence(failure) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")
        assertTrue("无界宽度必须以中文错误信息失败，实际：$messages", messages.contains("GridRow 需要确定宽度"))
    }
}
