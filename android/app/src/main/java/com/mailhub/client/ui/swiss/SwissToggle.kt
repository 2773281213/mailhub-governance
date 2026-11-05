package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 方块开关：开为实心前景色，关为 1dp 描边；右侧 Body 标签；整行可点（toggleable 语义，
 * 触控高度不小于 48dp）。
 * 方块从当前栏线开始，边长取 min(24dp, 栏宽)；标签从下一个 step 处开始，
 * 也就是落在方块所在栏的下一条栏线上。
 */
@Composable
fun SwissToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSwissColors.current
    val spec = LocalGridSpec.current
    val gridWidth = LocalGridWidth.current
    val step = spec.step(gridWidth)
    val square = minOf(SwissSpace.M, spec.columnWidth(gridWidth))
    val gap = if (step > square) step - square else 0.dp
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .sizeIn(minHeight = SwissSpace.Xl)
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = SwissSpace.Xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(square)
                .then(
                    if (checked) {
                        Modifier.background(colors.foreground)
                    } else {
                        Modifier.border(1.dp, colors.foreground)
                    },
                ),
        )
        Spacer(modifier = Modifier.width(gap))
        SText(
            text = label,
            style = SwissType.body(),
            color = colors.foreground,
        )
    }
}
