package com.mailhub.client.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mailhub.client.ui.swiss.GeoBar
import com.mailhub.client.ui.swiss.GeoSquare
import com.mailhub.client.ui.swiss.GridRow
import com.mailhub.client.ui.swiss.SwissPalette
import com.mailhub.client.ui.swiss.SwissSpace
import com.mailhub.client.ui.swiss.SwissTheme
import com.mailhub.client.ui.swiss.cols
import com.mailhub.client.ui.swiss.geoSide
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
 * 红色条块 [GeoBar] 的几何校验：
 * 宽度和方块共用一套网格计算，左右边缘必须和 [com.mailhub.client.ui.swiss.GridSpec.edges] 一致；
 * 高度由调用方给定，`geoSide(6)` 的高度必须正好等于 `GeoSquare(span = 6)` 的边长。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class GeoBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 条块左右边缘落在栏线上，360dp 和 411dp 各跑一遍。 */
    private fun assertBarEdges(width: Dp) {
        composeRule.setContent {
            SwissTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    GridRow {
                        GeoBar(
                            widthSpan = 4,
                            height = SwissSpace.Xs,
                            color = SwissPalette.Red,
                            modifier = Modifier
                                .cols(1, 4)
                                .testTag("bar1"),
                        )
                        GeoBar(
                            widthSpan = 4,
                            height = SwissSpace.Xs,
                            color = SwissPalette.Red,
                            modifier = Modifier
                                .cols(6, 4)
                                .testTag("bar6"),
                        )
                    }
                }
            }
        }

        val density = composeRule.density.density
        val spec = gridSpecFor(width)
        val totalPx = (width.value * density).roundToInt()

        listOf(Triple("bar1", 1, 4), Triple("bar6", 6, 4)).forEach { (tag, start, span) ->
            val bounds = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            val left = (bounds.left.value * density).roundToInt()
            val right = (bounds.right.value * density).roundToInt()
            val edges = spec.edges(start, span, totalPx, density)
            assertEquals("$tag 左边缘必须落在第 $start 栏栏线上", edges.x0, left)
            assertEquals("$tag 右边缘必须落在第 ${start + span - 1} 栏右栏线上", edges.x1, right)
        }
    }

    /** `GeoBar(widthSpan = 1, height = geoSide(6))` 的高度等于 `GeoSquare(span = 6)` 的边长。 */
    private fun assertBarHeightMatchesSquareSix(width: Dp) {
        composeRule.setContent {
            SwissTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    GridRow {
                        GeoSquare(
                            span = 6,
                            color = SwissPalette.Ink,
                            modifier = Modifier
                                .cols(1, 6)
                                .testTag("square"),
                        )
                    }
                    GridRow {
                        GeoBar(
                            widthSpan = 1,
                            height = geoSide(6),
                            color = SwissPalette.Red,
                            modifier = Modifier
                                .cols(8, 1)
                                .testTag("bar"),
                        )
                    }
                }
            }
        }

        val density = composeRule.density.density
        val square = composeRule.onNodeWithTag("square").getUnclippedBoundsInRoot()
        val bar = composeRule.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        val squarePx = ((square.bottom - square.top).value * density).roundToInt()
        val barPx = ((bar.bottom - bar.top).value * density).roundToInt()
        assertEquals("条块高度必须等于 6 栏方块的边长（宽度 ${width.value}dp）", squarePx, barPx)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun barEdgesMatchGridAt360() {
        assertBarEdges(360.dp)
    }

    @Test
    @Config(qualifiers = "w411dp-h800dp-xxhdpi")
    fun barEdgesMatchGridAt411() {
        assertBarEdges(411.dp)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun barHeightEqualsSquareSideAt360() {
        assertBarHeightMatchesSquareSix(360.dp)
    }

    @Test
    @Config(qualifiers = "w411dp-h800dp-xxhdpi")
    fun barHeightEqualsSquareSideAt411() {
        assertBarHeightMatchesSquareSix(411.dp)
    }
}
