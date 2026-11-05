package com.mailhub.client.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.mailhub.client.ui.swiss.BarChart
import com.mailhub.client.ui.swiss.SwissTheme
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
 * BarChart 每根柱子占一栏：柱宽等于栏宽，左缘落在栏线上。
 * 柱子画在 Canvas/Box 上，没有语义节点，所以直接扫像素。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SwissBarChartTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun assertBarsOnColumnLines(values: List<Int>) {
        composeRule.setContent {
            SwissTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    BarChart(
                        values = values,
                        accent = List(values.size) { 0 },
                        labels = List(values.size) { index -> (index + 1).toString().padStart(2, '0') },
                    )
                }
            }
        }

        val density = composeRule.density.density
        val width = 360.dp
        val spec = gridSpecFor(width)
        val totalPx = (width.value * density).roundToInt()
        val chartHeightPx = (spec.columnWidth(width).value * 8 * density).roundToInt()

        val image = PixelProbe.capture(composeRule.onRoot(), "barchart")
        // 取靠近基线的一行：所有柱子在这一行都有柱子实体。
        val row = chartHeightPx - 4

        values.forEachIndexed { index, _ ->
            val edges = spec.edges(index + 1, 1, totalPx, density)
            assertTrue(
                "第 ${index + 1} 根柱子的左缘 ${edges.x0} 应该已经涂色",
                PixelProbe.isInk(image.getRGB(edges.x0, row)),
            )
            assertTrue(
                "第 ${index + 1} 根柱子左侧的栏间距 ${edges.x0 - 1} 应该是空白的",
                !PixelProbe.isInk(image.getRGB(edges.x0 - 1, row)),
            )
            assertTrue(
                "第 ${index + 1} 根柱子的右缘 ${edges.x1 - 1} 应该已经涂色",
                PixelProbe.isInk(image.getRGB(edges.x1 - 1, row)),
            )
            assertTrue(
                "第 ${index + 1} 根柱子右侧的栏间距 ${edges.x1} 应该是空白的",
                !PixelProbe.isInk(image.getRGB(edges.x1, row)),
            )
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun barsSitOnColumnLinesAt360() {
        assertBarsOnColumnLines(listOf(38, 52, 41, 66, 74, 58, 49, 81, 63, 55, 70, 44))
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun moreThanTwelveValuesKeepsTheLastTwelveBars() {
        // 14 个值只画最后 12 个，也就是从 03 到 14 号。
        val values = listOf(38, 52, 41, 66, 74, 58, 49, 81, 63, 55, 70, 44, 60, 92)
        composeRule.setContent {
            SwissTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    BarChart(
                        values = values,
                        accent = List(values.size) { 0 },
                        labels = List(values.size) { index -> (index + 1).toString().padStart(2, '0') },
                    )
                }
            }
        }
        val density = composeRule.density.density
        val width = 360.dp
        val spec = gridSpecFor(width)
        val totalPx = (width.value * density).roundToInt()
        val chartHeightPx = (spec.columnWidth(width).value * 8 * density).roundToInt()
        val image = PixelProbe.capture(composeRule.onRoot(), "barchart-14")
        val row = chartHeightPx - 4

        // 第 12 栏必须还有柱子，第 13 栏（不存在的栏位）以外全是空白。
        val lastBar = spec.edges(12, 1, totalPx, density)
        assertTrue(PixelProbe.isInk(image.getRGB(lastBar.x0, row)))
        // 12 栏之后到屏幕右边缘应该是纯背景。
        var paintedAfterGrid = 0
        for (x in lastBar.x1 until image.width) {
            if (PixelProbe.isInk(image.getRGB(x, row))) paintedAfterGrid++
        }
        assertEquals("图表不能画到第 12 栏之外", 0, paintedAfterGrid)
    }
}
