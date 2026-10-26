package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

enum class SwissButtonKind { Primary, Accent, Outline }

/**
 * 直角按钮，最小高度 48dp，左右内边距 16dp，文字左对齐、粗体、末尾加箭头。
 * 按下时黑白反色；禁用时改成 Outline 样式并在文字后面标注，不用变淡来表示。
 * 按钮文字用 14sp 粗体，这样 Accent 按下反色后出现的红字也满足「≥14sp 粗体」规则。
 */
@Composable
fun SwissButton(
    text: String,
    onClick: () -> Unit,
    kind: SwissButtonKind = SwissButtonKind.Primary,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSwissColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val effectiveKind = if (enabled) kind else SwissButtonKind.Outline
    val label = if (enabled) "$text →" else "$text（不可用） →"

    val background = when {
        !enabled -> colors.background
        pressed -> if (effectiveKind == SwissButtonKind.Primary) colors.background else colors.foreground
        effectiveKind == SwissButtonKind.Primary -> colors.foreground
        effectiveKind == SwissButtonKind.Accent -> colors.accent
        else -> colors.background
    }
    val content = when {
        !enabled -> colors.foreground
        pressed -> if (effectiveKind == SwissButtonKind.Primary) colors.foreground else colors.background
        effectiveKind == SwissButtonKind.Primary -> colors.background
        effectiveKind == SwissButtonKind.Accent -> colors.background
        else -> colors.foreground
    }
    val border = when {
        !enabled -> true
        effectiveKind == SwissButtonKind.Outline -> true
        pressed && effectiveKind == SwissButtonKind.Primary -> true
        else -> false
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(background)
            .then(if (border) Modifier.border(1.dp, colors.foreground) else Modifier)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
    ) {
        SText(
            text = label,
            style = TextStyle(
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                letterSpacing = 0.02f.em,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            color = content,
        )
    }
}
