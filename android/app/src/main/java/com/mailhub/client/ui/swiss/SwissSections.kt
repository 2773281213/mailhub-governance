package com.mailhub.client.ui.swiss

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 区块标题：上方一条 1dp 细线，编号在第 1–2 栏，标题用 headline 在第 3–12 栏。
 * 全部左对齐，不居中。
 */
@Composable
fun SectionHeader(
    index: String,
    title: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSwissColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        HairlineRule(color = colors.foreground)
        Spacer(modifier = Modifier.height(SwissSpace.S))
        GridRow {
            IndexLabel(index = index, modifier = Modifier.cols(1, 2))
            SText(
                text = title,
                style = SwissType.headline(),
                modifier = Modifier.cols(3, 10),
                color = colors.foreground,
            )
        }
    }
}

/**
 * 键值行：标签用 caption 在第 1–3 栏，值用 body 在第 4–12 栏。
 * 每行上方一条 1dp 细线，连续多行就是行间分隔线。
 */
@Composable
fun TableRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSwissColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        HairlineRule(color = colors.foreground)
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        GridRow {
            SText(
                text = label,
                style = SwissType.caption(),
                modifier = Modifier.cols(1, 3),
                color = colors.foreground,
            )
            SText(
                text = value,
                style = SwissType.body(),
                modifier = Modifier.cols(4, 9),
                color = colors.foreground,
            )
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
    }
}

/**
 * 大数字统计：数字用 display，位数 ≥ 4 时自动改用 displaySmall，避免撑破版面。
 * [accent] 为真时数字用红色（大字允许用红），标签永远用 label 样式的前景色（小字不用红色）。
 */
@Composable
fun StatNumber(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val colors = LocalSwissColors.current
    val headline = value.count { it.isDigit() } >= 4
    Column(modifier = modifier.fillMaxWidth()) {
        GridRow {
            SText(
                text = value,
                style = if (headline) SwissType.displaySmall() else SwissType.display(),
                modifier = Modifier.cols(1, 12),
                color = if (accent) colors.accent else colors.foreground,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        GridRow {
            SText(
                text = label,
                style = SwissType.label(),
                modifier = Modifier.cols(1, 12),
                color = colors.foreground,
                maxLines = 1,
            )
        }
    }
}
