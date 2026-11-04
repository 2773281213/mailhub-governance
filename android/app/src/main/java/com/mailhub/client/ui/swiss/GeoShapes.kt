package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * 需要图形时只用圆和方块。这个文件是整个工程里唯一允许出现 CircleShape 的地方。
 *
 * 边长不再写死，而是按跨栏数决定：边长 = 第 [span] 栏的宽度，
 * 所以图形本身也落在栏线上，和文字、线条共用同一套网格。
 */
@Composable
fun GeoCircle(
    span: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(geoSide(span))
            .background(color, CircleShape),
    )
}

@Composable
fun GeoSquare(
    span: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(geoSide(span))
            .background(color),
    )
}

/** 跨 [span] 栏的边长（dp）。跨度过大或过小都会在网格层面直接失败。 */
@Composable
fun geoSide(span: Int): Dp {
    val spec = LocalGridSpec.current
    val gridWidth = LocalGridWidth.current
    spec.requirePlacement(start = 1, span = span)
    return spec.spanWidth(span, gridWidth)
}
