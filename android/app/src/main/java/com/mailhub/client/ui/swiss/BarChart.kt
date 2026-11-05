package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 最多 12 根柱子，一根一栏；超出的部分只保留最后 12 个值。 */
const val BarChartMaxBars: Int = 12

/**
 * 柱状图：每根柱子占一栏，柱宽等于栏宽，左缘落在栏线上，
 * 所以柱子的左右边缘和页面其它元素共用同一套栏线。
 * 前景色柱，[accent] 部分画成红色叠在柱底；底部 1dp 基线；标签与柱子同栏、左对齐。
 * 不画网格背景，柱子之间不留额外间距（栏间距本身就是间距）。
 */
@Composable
fun BarChart(
    values: List<Int>,
    accent: List<Int>,
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSwissColors.current
    val spec = LocalGridSpec.current
    val gridWidth = LocalGridWidth.current

    // 超过 12 个值时只取最后 12 个，柱子始终能落进 12 栏。
    val bars = values.takeLast(BarChartMaxBars)
    val accentValues = accent.takeLast(BarChartMaxBars)
    val barLabels = labels.takeLast(BarChartMaxBars)
    val maxValue = (bars.maxOrNull() ?: 0).coerceAtLeast(1)
    // 柱高基准取 8 个栏宽，360dp 下正好 160dp，不引入新的魔法数字。
    val chartHeight = spec.columnWidth(gridWidth) * 8

    Column(modifier = modifier.fillMaxWidth()) {
        GridRow(modifier = Modifier.height(chartHeight)) {
            bars.forEachIndexed { index, value ->
                ChartBar(
                    value = value,
                    accent = accentValues.getOrElse(index) { 0 },
                    maxValue = maxValue,
                    chartHeight = chartHeight,
                    modifier = Modifier.cols(index + 1, span = 1),
                )
            }
        }
        HairlineRule(color = colors.foreground)
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        GridRow {
            barLabels.forEachIndexed { index, label ->
                SText(
                    text = label,
                    style = SwissType.label(),
                    modifier = Modifier.cols(index + 1, span = 1),
                    color = colors.foreground,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 一根柱子：黑色柱身贴底，红色风险段叠在柱底。 */
@Composable
private fun ChartBar(
    value: Int,
    accent: Int,
    maxValue: Int,
    chartHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSwissColors.current
    Column(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight(value, maxValue, chartHeight))
                .background(colors.foreground),
        ) {
            val accentHeight = barHeight(accent, maxValue, chartHeight)
            if (accentHeight > 0.dp) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(accentHeight)
                        .background(colors.accent),
                )
            }
        }
    }
}

/** 柱高 = 图表高度 × 数值占比。 */
private fun barHeight(value: Int, maxValue: Int, chartHeight: Dp): Dp {
    val ratio = value.toFloat() / maxValue.toFloat()
    return chartHeight * ratio
}
