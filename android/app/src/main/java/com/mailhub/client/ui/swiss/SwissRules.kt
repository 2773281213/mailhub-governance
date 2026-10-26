package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 1dp 细线，分隔区块用。 */
@Composable
fun HairlineRule(
    color: Color = LocalSwissColors.current.foreground,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

/** 4dp 粗线，标示区块开头用。 */
@Composable
fun HeavyRule(
    color: Color = LocalSwissColors.current.foreground,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(color),
    )
}
