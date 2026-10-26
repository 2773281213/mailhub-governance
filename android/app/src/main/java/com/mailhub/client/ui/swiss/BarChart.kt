package com.mailhub.client.ui.swiss

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

/**
 * 柱状图：前景色柱，[accent] 部分从柱底向上画红色；底部 1dp 基线；标签用 Label 样式。
 * 不画网格背景。
 */
@Composable
fun BarChart(
    values: List<Int>,
    accent: List<Int>,
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSwissColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
        ) {
            val maxValue = (values.maxOrNull() ?: 0).coerceAtLeast(1)
            val gap = 6.dp.toPx()
            val barWidth = if (values.isEmpty()) {
                0f
            } else {
                (size.width - gap * (values.size - 1)) / values.size
            }
            values.forEachIndexed { index, value ->
                val left = index * (barWidth + gap)
                val barHeight = size.height * value / maxValue
                drawRect(
                    color = colors.foreground,
                    topLeft = Offset(left, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                )
                val accentValue = accent.getOrElse(index) { 0 }
                val accentHeight = size.height * accentValue / maxValue
                if (accentHeight > 0f) {
                    drawRect(
                        color = colors.accent,
                        topLeft = Offset(left, size.height - accentHeight),
                        size = Size(barWidth, accentHeight),
                    )
                }
            }
            drawLine(
                color = colors.foreground,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            labels.forEach { label ->
                SText(
                    text = label,
                    style = SwissType.label(),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            }
        }
    }
}
