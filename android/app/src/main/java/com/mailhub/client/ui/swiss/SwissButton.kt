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
 * 直角按钮，最小高度 48dp，文字左对齐、粗体、末尾加箭头。
 * 左内边距等于一个 step（栏宽 + 栏间距），所以文字左边缘落在下一条栏线上；右内边距固定 16dp。
 * Accent 永远是红底米白字（与主题无关），按下时改成黑底米白字；Primary/Outline 按下时黑白反色。
 * 禁用时改成 Outline 样式并在文字后面标注，不用变淡来表示。
 * 按钮文字用 14sp 粗体，满足「红字只用于 ≥14sp 粗体」的规则。
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
    val spec = LocalGridSpec.current
    val gridWidth = LocalGridWidth.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val effectiveKind = if (enabled) kind else SwissButtonKind.Outline
    val label = if (enabled) "$text →" else "$text（不可用） →"

    val background = when {
        !enabled -> colors.background
        effectiveKind == SwissButtonKind.Accent -> if (pressed) SwissPalette.Ink else SwissPalette.Red
        pressed -> if (effectiveKind == SwissButtonKind.Primary) colors.background else colors.foreground
        effectiveKind == SwissButtonKind.Primary -> colors.foreground
        else -> colors.background
    }
    val content = when {
        !enabled -> colors.foreground
        effectiveKind == SwissButtonKind.Accent -> SwissPalette.Paper
        pressed -> if (effectiveKind == SwissButtonKind.Primary) colors.foreground else colors.background
        effectiveKind == SwissButtonKind.Primary -> colors.background
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
        Box(
            modifier = Modifier.padding(
                start = spec.step(gridWidth),
                end = 16.dp,
                top = 14.dp,
                bottom = 14.dp,
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
                color = content,
            )
        }
    }
}
