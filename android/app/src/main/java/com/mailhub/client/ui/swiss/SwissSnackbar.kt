package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 前景色底、背景色字的一条提示。
 *
 * 底块占第 1–12 栏，左右边缘分别落在第 1 栏和第 12 栏的栏线上（不横贯整屏，也不吃页边距）；
 * 文字从底块内第 1 栏的栏线起排，上下各留一个紧凑间距。
 */
@Composable
fun SwissSnackbar(
    message: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSwissColors.current
    GridRow(modifier = modifier) {
        Box(
            modifier = Modifier
                .cols(start = 1, span = 12)
                .fillMaxWidth()
                .background(colors.foreground)
                .padding(vertical = SwissSpace.Xs),
        ) {
            SText(
                text = message,
                style = SwissType.body(),
                color = colors.background,
            )
        }
    }
}
