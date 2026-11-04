package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/**
 * 空状态：用简单几何形构图（一个前景色方块加一根红色横条），不对称摆放，
 * 下面接编号、标题和说明。没有装饰插画，也不用透明度。
 */
@Composable
fun EmptyState(
    index: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSwissColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        GridRow {
            GeoSquare(
                span = 4,
                color = colors.foreground,
                modifier = Modifier.cols(1, 4),
            )
            // 红色横条贴在黑方块的底边上：先空出「方块边长减去横条高度」的距离
            Column(modifier = Modifier.cols(6, 4)) {
                Spacer(modifier = Modifier.height(geoSide(4) - SwissSpace.Xs))
                GeoBar(
                    widthSpan = 4,
                    height = SwissSpace.Xs,
                    color = colors.accent,
                )
            }
        }
        Spacer(modifier = Modifier.height(SwissSpace.M))
        GridRow {
            IndexLabel(index = index, modifier = Modifier.cols(1, 2))
            SText(
                text = title,
                style = SwissType.headline(),
                modifier = Modifier.cols(3, 10),
                color = colors.foreground,
            )
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        GridRow {
            SText(
                text = body,
                style = SwissType.body(),
                modifier = Modifier.cols(3, 9),
                color = colors.foreground,
            )
        }
    }
}

/**
 * 加载指示：一个栏宽的红块，沿栏线逐栏跳动。
 * 不做平滑移动、不用透明度、不用转圈，只在整栏之间瞬移。
 */
@Composable
fun LoadingRule(
    modifier: Modifier = Modifier,
    stepMillis: Long = 140L,
) {
    val colors = LocalSwissColors.current
    val spec = LocalGridSpec.current
    var column by remember { mutableIntStateOf(1) }
    LaunchedEffect(stepMillis, spec.columns) {
        while (true) {
            delay(stepMillis)
            column = if (column >= spec.columns) 1 else column + 1
        }
    }
    GridRow(modifier = modifier) {
        Box(
            modifier = Modifier
                .cols(start = column, span = 1)
                .fillMaxWidth()
                .height(SwissSpace.Xs)
                .background(colors.accent),
        )
    }
}

/**
 * 错误状态：说明用前景色 Body 文字（不用红色小字），下面配一个重试按钮。
 * 顶部 4dp 红线只做区块标记，不是文字。
 */
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryText: String = "重试",
) {
    val colors = LocalSwissColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        HeavyRule(color = colors.accent)
        Spacer(modifier = Modifier.height(SwissSpace.S))
        GridRow {
            SText(
                text = message,
                style = SwissType.body(),
                modifier = Modifier.cols(1, 10),
                color = colors.foreground,
            )
        }
        Spacer(modifier = Modifier.height(SwissSpace.S))
        GridRow {
            SwissButton(
                text = retryText,
                onClick = onRetry,
                kind = SwissButtonKind.Primary,
                modifier = Modifier.cols(1, 6),
            )
        }
    }
}
