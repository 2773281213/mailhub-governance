package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 前景色底、背景色字的一条提示，文字左边缘落在第 1 条栏线上。 */
@Composable
fun SwissSnackbar(
    message: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSwissColors.current
    val spec = LocalGridSpec.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.foreground)
            .padding(start = spec.margin, end = 16.dp, top = 12.dp, bottom = 12.dp),
    ) {
        SText(
            text = message,
            style = SwissType.body(),
            color = colors.background,
        )
    }
}
