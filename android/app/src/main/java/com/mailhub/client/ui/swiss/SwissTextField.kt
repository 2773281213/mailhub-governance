package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp

/**
 * 输入框：上方 Label 样式标签，下方 1dp 下划线；聚焦时 2dp；出错时下划线变 2dp 红色，
 * 错误说明用 Body 样式的前景色文字（不用红字，小字不用红色）。
 */
@Composable
fun SwissTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    errorText: String? = null,
) {
    val colors = LocalSwissColors.current
    var focused by remember { mutableStateOf(false) }
    val underlineHeight = if (isError || focused) 2.dp else 1.dp
    val underlineColor = if (isError) colors.accent else colors.foreground

    Column(modifier = modifier.fillMaxWidth()) {
        SText(
            text = label,
            style = SwissType.label(),
            color = colors.foreground,
        )
        Spacer(modifier = Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
            textStyle = SwissType.body().copy(color = colors.foreground),
            cursorBrush = SolidColor(colors.foreground),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(underlineHeight)
                .background(underlineColor),
        )
        if (isError && errorText != null) {
            Spacer(modifier = Modifier.height(6.dp))
            SText(
                text = errorText,
                style = SwissType.body(),
                color = colors.foreground,
            )
        }
    }
}
