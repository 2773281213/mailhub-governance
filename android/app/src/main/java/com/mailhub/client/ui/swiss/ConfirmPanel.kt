package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 确认面板的内容部分：顶部 4dp 前景色粗线，背景色底，Headline 标题、Body 正文，
 * 下方两个按钮（取消 Outline，确认 Primary，危险操作可选 Accent）。
 * 样张里直接渲染这个内容，不弹出。
 */
@Composable
fun ConfirmPanelContent(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    cancelText: String = "取消",
    danger: Boolean = false,
) {
    val colors = LocalSwissColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background),
    ) {
        HeavyRule(color = colors.foreground)
        Spacer(modifier = Modifier.height(20.dp))
        GridRow {
            SText(
                text = title,
                style = SwissType.headline(),
                modifier = Modifier.cols(1, 12),
                color = colors.foreground,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        GridRow {
            SText(
                text = message,
                style = SwissType.body(),
                modifier = Modifier.cols(1, 10),
                color = colors.foreground,
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        GridRow {
            SwissButton(
                text = cancelText,
                onClick = onCancel,
                kind = SwissButtonKind.Outline,
                modifier = Modifier.cols(1, 6),
            )
            SwissButton(
                text = confirmText,
                onClick = onConfirm,
                kind = if (danger) SwissButtonKind.Accent else SwissButtonKind.Primary,
                modifier = Modifier.cols(7, 6),
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

/**
 * 从底部出现的整宽确认面板。背后是一层完全透明的点击层（不加半透明遮罩），
 * 点空白处等于取消。
 */
@Composable
fun ConfirmPanel(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    cancelText: String = "取消",
    danger: Boolean = false,
) {
    val dismissInteraction = remember { MutableInteractionSource() }
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = dismissInteraction,
                    indication = null,
                    onClick = onCancel,
                ),
        )
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.weight(1f))
            ConfirmPanelContent(
                title = title,
                message = message,
                confirmText = confirmText,
                onConfirm = onConfirm,
                onCancel = onCancel,
                cancelText = cancelText,
                danger = danger,
            )
        }
    }
}
