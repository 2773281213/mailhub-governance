package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 页面骨架，下一阶段的页面全部用它开头。
 *
 * 页头按栏摆放：编号标签在第 1–2 栏，栏目名在第 3–8 栏，元信息（例如 2026 — 上海）
 * 在第 9–12 栏，三处都是左对齐；页头下面一条 4dp 粗线；页面标题用 title（56sp）
 * 占第 1–10 栏，标题下留 48dp 空白；底部插槽放标签栏。
 *
 * targetSdk 36 起系统强制边到边显示，所以这里用 WindowInsets.safeDrawing 让内容避开
 * 状态栏和导航栏，背景色仍然铺满整屏。
 */
@Composable
fun SwissScaffold(
    index: String,
    section: String,
    meta: String,
    title: String,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalSwissColors.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        GridRow {
            IndexLabel(index = index, modifier = Modifier.cols(1, 2))
            SText(
                text = section,
                style = SwissType.label(),
                modifier = Modifier.cols(3, 6),
                color = colors.foreground,
                maxLines = 1,
            )
            SText(
                text = meta,
                style = SwissType.label(),
                modifier = Modifier.cols(9, 4),
                color = colors.foreground,
                maxLines = 1,
                softWrap = false,
            )
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        HeavyRule(color = colors.foreground)
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        GridRow {
            SText(
                text = title,
                style = SwissType.title(),
                modifier = Modifier.cols(1, 10),
                color = colors.foreground,
            )
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xl))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            content = content,
        )
        bottomBar()
    }
}
