package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 1dp 细线，分隔区块用。默认占第 1–12 栏，也就是左右各留出页边距；
 * 需要更短的一段时用 [start]、[span] 指定栏位。
 */
@Composable
fun HairlineRule(
    color: Color = LocalSwissColors.current.foreground,
    modifier: Modifier = Modifier,
    start: Int = 1,
    span: Int = 12,
) {
    GridRule(
        thickness = 1.dp,
        color = color,
        start = start,
        span = span,
        modifier = modifier,
    )
}

/** 4dp 粗线，标示区块开头用。同样默认占第 1–12 栏。 */
@Composable
fun HeavyRule(
    color: Color = LocalSwissColors.current.foreground,
    modifier: Modifier = Modifier,
    start: Int = 1,
    span: Int = 12,
) {
    GridRule(
        thickness = SwissSpace.Xxs,
        color = color,
        start = start,
        span = span,
        modifier = modifier,
    )
}

/**
 * 通栏线：横贯整个屏幕宽度，连页边距一起盖住。
 * 只有确实需要通栏（例如贴在屏幕边缘的分隔）时才用，普通分隔一律用 [HairlineRule] / [HeavyRule]。
 */
@Composable
fun FullBleedRule(
    thickness: Dp = 1.dp,
    color: Color = LocalSwissColors.current.foreground,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .background(color),
    )
}

/** 按栏绘制的线段本体：宽度由 GridRow 的栏位决定，所以左右边缘正好落在栏线上。 */
@Composable
private fun GridRule(
    thickness: Dp,
    color: Color,
    start: Int,
    span: Int,
    modifier: Modifier,
) {
    GridRow(modifier = modifier) {
        Box(
            modifier = Modifier
                .cols(start, span)
                .fillMaxWidth()
                .height(thickness)
                .background(color),
        )
    }
}
