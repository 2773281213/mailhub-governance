package com.mailhub.client.ui.swiss

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/**
 * 文字按钮：只有一行 label 文字和 48dp 的最小触控高度，不做底色、不做胶囊、没有按下态。
 * 文字贴在触控区的左上角，左边缘就是所在栏的栏线。
 *
 * [labelModifier] 加在文字上，用来和同一行的其它文字对齐首行基线（默认不缩进）。
 */
@Composable
fun SwissTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    labelModifier: Modifier = Modifier,
) {
    val colors = LocalSwissColors.current
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .sizeIn(minHeight = SwissSpace.Xl)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        SText(
            text = text,
            style = SwissType.label(),
            modifier = labelModifier,
            color = colors.foreground,
            maxLines = 1,
        )
    }
}
