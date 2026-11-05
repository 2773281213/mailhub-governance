package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 搜索框：左侧 caption 标签，右侧 1dp 下划线输入，聚焦时下划线 2dp。
 * 占位文字用 caption 样式，颜色只能是前景色。
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "搜索",
    placeholder: String = "输入关键词",
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = LocalSwissColors.current
    var focused by remember { mutableStateOf(false) }
    GridRow(modifier = modifier) {
        SText(
            text = label,
            style = SwissType.caption(),
            modifier = Modifier.cols(1, 3),
            color = colors.foreground,
            maxLines = 1,
        )
        Column(modifier = Modifier.cols(4, 9)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                if (value.isEmpty()) {
                    SText(
                        text = placeholder,
                        style = SwissType.caption(),
                        color = colors.foreground,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                    textStyle = SwissType.body().copy(color = colors.foreground),
                    cursorBrush = SolidColor(colors.foreground),
                    singleLine = true,
                    enabled = enabled,
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                )
            }
            Spacer(modifier = Modifier.height(SwissSpace.Xxs))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (focused) 2.dp else 1.dp)
                    .background(colors.foreground),
            )
        }
    }
}

/**
 * 文字型筛选项：只有一个文字加一条下划线。
 * 选中时文字粗体、下划线 2dp；不做胶囊、不做圆角、没有底色块。
 *
 * 触控区最小 48dp，文字往下压到触控区底部、下划线再贴在最下面，
 * 所以同一行里每个筛选项的下划线都落在同一条线上。与之同处一行的纯文字按钮
 * 要自己按 [FilterOptionTextBottomInset] 对齐首行基线（见收件箱筛选行）。
 */
@Composable
fun FilterOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSwissColors.current
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .sizeIn(minHeight = SwissSpace.Xl)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        verticalArrangement = Arrangement.Bottom,
    ) {
        SText(
            text = text,
            style = SwissType.caption().copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            ),
            color = colors.foreground,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(SwissSpace.Xxs))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(FilterUnderlineThickness)
                .background(if (selected) colors.foreground else colors.background),
        )
    }
}

/** 筛选项下划线的粗细：选中时画前景色，未选中时画一条背景色的同高轨道把位置占住。 */
val FilterUnderlineThickness: Dp = 2.dp

/**
 * 筛选项的文字下沿到触控区底边的距离：文字与下划线之间的留白，加上下划线本身。
 * 和筛选项同处一行的纯文字按钮靠它换算首行基线，这样两边不用各自记住内部留白。
 */
val FilterOptionTextBottomInset: Dp = SwissSpace.Xxs + FilterUnderlineThickness
