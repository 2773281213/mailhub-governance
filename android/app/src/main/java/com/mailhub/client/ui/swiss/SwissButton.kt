package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class SwissButtonKind { Primary, Accent, Outline }

/**
 * 直角按钮，最小触控高度 48dp（视觉尺寸由内边距决定，不靠 Material 的
 * minimumInteractiveComponentSize），文字左对齐、粗体、末尾加箭头。
 * 左内边距等于一个 step（栏宽 + 栏间距），所以文字左边缘落在下一条栏线上；右内边距固定 16dp。
 *
 * 按下时所有变体统一变成「前景色填充 + 背景色文字」：这样深色模式下强调按钮
 * 也不会变成黑底黑框而消失。
 * Accent 平时永远是红底米白字（与主题无关）。
 * 禁用时改成 Outline 样式并在文字后面标注，不用变淡来表示。
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
        pressed -> colors.foreground
        effectiveKind == SwissButtonKind.Accent -> SwissPalette.Red
        effectiveKind == SwissButtonKind.Primary -> colors.foreground
        else -> colors.background
    }
    val content = when {
        !enabled -> colors.foreground
        pressed -> colors.background
        effectiveKind == SwissButtonKind.Accent -> SwissPalette.Paper
        effectiveKind == SwissButtonKind.Primary -> colors.background
        else -> colors.foreground
    }
    val border = when {
        !enabled -> true
        pressed -> false
        effectiveKind == SwissButtonKind.Outline -> true
        else -> false
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .sizeIn(minHeight = SwissSpace.Xl)
            .background(background)
            .then(if (border) Modifier.border(1.dp, colors.foreground) else Modifier)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier.padding(
                start = spec.step(gridWidth),
                end = SwissSpace.S,
                top = SwissSpace.S,
                bottom = SwissSpace.S,
            ),
        ) {
            SText(
                text = label,
                style = SwissType.body().copy(fontWeight = FontWeight.Bold),
                color = content,
                maxLines = 1,
            )
        }
    }
}
