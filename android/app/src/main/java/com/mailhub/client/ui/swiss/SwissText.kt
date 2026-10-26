package com.mailhub.client.ui.swiss

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/**
 * 设计系统里唯一的文字组件。强制左对齐，不暴露 textAlign 参数，
 * 也不接受任何居中或两端对齐的样式。
 */
@Composable
fun SText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = LocalSwissColors.current.foreground,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color, textAlign = TextAlign.Start),
        maxLines = maxLines,
        overflow = overflow,
        softWrap = softWrap,
    )
}
