package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** 需要图形时只用圆和方块。这个文件是整个工程里唯一允许出现 CircleShape 的地方。 */
@Composable
fun GeoCircle(
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(color, CircleShape),
    )
}

@Composable
fun GeoSquare(
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(color),
    )
}
