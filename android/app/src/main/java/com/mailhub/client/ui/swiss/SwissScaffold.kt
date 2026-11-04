package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 页面骨架，下一阶段的页面全部用它开头。
 *
 * 固定区只有一行页头加一条 4dp 粗线，按栏摆放：编号第 1–2 栏，栏目名第 3–6 栏，
 * 元信息（例如 2026 — 上海）第 7–10 栏，放不下时换行、仍然左对齐；操作入口第 11–12 栏
 * （一级页面是「索引」，详情页是「← 收件」）。
 *
 * 大标题不在这里：页面把它作为滚动内容的第一项，用 [PageTitle]，向上滚动时
 * 跟着内容一起离开屏幕。这样 480×853dp 的机器上固定区只占一行的高度。
 *
 * targetSdk 36 起系统强制边到边显示，所以这里用 WindowInsets.safeDrawing 让内容避开
 * 状态栏和导航栏，背景色仍然铺满整屏。
 */
@Composable
fun SwissScaffold(
    index: String,
    section: String,
    meta: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalSwissColors.current
    // 编号是数字、栏目名和操作入口是中文，中文回退到系统字体后首行基线比数字低一两 dp，
    // 所以整行按测量结果各自补一点顶部内边距，让四个位置共用一条首行基线。
    val tops = headerBaselineTops(listOfNotNull(index, section, meta, actionLabel))
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        GridRow {
            IndexLabel(
                index = index,
                modifier = Modifier
                    .cols(1, 2)
                    .padding(top = tops[0]),
            )
            SText(
                text = section,
                style = SwissType.label(),
                modifier = Modifier
                    .cols(3, 4)
                    .padding(top = tops[1]),
                color = colors.foreground,
                maxLines = 1,
            )
            SText(
                text = meta,
                style = SwissType.label(),
                modifier = Modifier
                    .cols(7, 4)
                    .padding(top = tops[2]),
                color = colors.foreground,
                maxLines = 2,
            )
            if (actionLabel != null) {
                HeaderActionText(
                    text = actionLabel,
                    onClick = onAction,
                    modifier = Modifier.cols(11, 2),
                    textTop = tops[3],
                )
            }
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        HeavyRule(color = colors.foreground)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            content = content,
        )
        bottomBar()
    }
}

/**
 * 页头这一行各段文字的顶部内边距：以整行最低的那条首行基线为目标，
 * 其它文字各自往下补到同一条线上（只补不缩，栏位不变）。
 */
@Composable
private fun headerBaselineTops(texts: List<String>): List<Dp> {
    val style = SwissType.label()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density, style, texts) {
        val baselines = texts.map { text ->
            measurer.measure(
                text = AnnotatedString(text),
                style = style,
                maxLines = 1,
            ).firstBaseline
        }
        val target = baselines.maxOrNull() ?: 0f
        with(density) { baselines.map { (target - it).coerceAtLeast(0f).toDp() } }
    }
}

/**
 * 页头操作入口的文字按钮：只有一行 label 文字，最小触控高度 48dp。
 * 和页头里其它文字同为 label 样式，[textTop] 把它补到整行共用的那条首行基线上。
 */
@Composable
private fun HeaderActionText(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textTop: Dp = 0.dp,
) {
    val colors = LocalSwissColors.current
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .sizeIn(minHeight = SwissSpace.Xl)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.TopStart,
    ) {
        SText(
            text = text,
            style = SwissType.label(),
            modifier = Modifier.padding(top = textTop),
            color = colors.foreground,
            maxLines = 1,
        )
    }
}

/**
 * 滚动内容的第一项：大标题（默认 56sp 的 title）连同它上下的留白。
 * 放进页面自己的滚动容器（Column.verticalScroll 或 LazyColumn 的第一个 item），
 * 向上滚动时整块离开屏幕。
 *
 * @param style 详情页的主题用 headline，其它页面用默认的 title。
 * @param span 标题占几栏，默认 1–10 栏，详情页占 1–11 栏。
 */
@Composable
fun PageTitle(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = SwissType.title(),
    span: Int = 10,
) {
    val colors = LocalSwissColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        GridRow {
            SText(
                text = text,
                style = style,
                modifier = Modifier.cols(1, span),
                color = colors.foreground,
            )
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xl))
    }
}
