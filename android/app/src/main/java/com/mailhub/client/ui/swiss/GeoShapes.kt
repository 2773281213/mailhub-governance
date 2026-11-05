package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * 几何元素只用方块和条块，不用圆形，避免类似国旗的构图。
 *
 * 边长不再写死，而是按跨栏数决定：边长 = 第 [span] 栏的宽度，
 * 所以图形本身也落在栏线上，和文字、线条共用同一套网格。
 * 条块 [GeoBar] 的宽度同样按跨栏数算；高度由调用方传入，
 * 页面层只能传 [SwissSpace] 的 token，或者 [geoSide] 这样按栏宽算出来的值。
 */
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

/**
 * 跨 [widthSpan] 栏宽的条块：宽和 [GeoSquare] 用同一套网格计算，高度由调用方给定。
 * 用它来代替原来的圆形，红色只以条块的形式出现，并且紧挨黑色方块或文字摆放。
 */
@Composable
fun GeoBar(
    widthSpan: Int,
    height: Dp,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(geoSide(widthSpan))
            .height(height)
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
