package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp

/**
 * 页面骨架，下一阶段的页面全部用它开头。
 *
 * 固定区只有一行页头加一条 4dp 粗线，按栏摆放：编号第 1–2 栏，栏目名第 3–6 栏，
 * 元信息（例如 2026 — 上海）第 7–10 栏，放不下时换行、仍然左对齐；操作入口第 11–12 栏
 * （一级页面是「索引」，详情页是「← 收件」）。
 *
 * 页头这一行因为操作入口要 48dp 的触控高度而被撑高，四段文字贴在这一行的底部：
 * 多出来的高度全在文字上方，文字最后一行与粗线之间只留 [SwissSpace.Xs]。
 * 粗线与下面的滚动内容之间再留一道同样宽的背景色间隙，内容向上滚动时不会和粗线连成一块。
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
    // 四段文字贴在这一行的底部：顶部内边距由这一行的高度、各自的字面高度和首行基线算出来，
    // 首行基线仍然共用一条（中文回退到系统字体后首行基线比数字低一两 dp）。
    val tops = headerLabelTopPaddings(
        style = SwissType.label(),
        texts = listOfNotNull(index, section, meta, actionLabel),
        hasAction = actionLabel != null,
    )
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
                SwissTextButton(
                    text = actionLabel,
                    onClick = onAction,
                    modifier = Modifier
                        .cols(11, 2)
                        .fillMaxWidth(),
                    labelModifier = Modifier.padding(top = tops[3]),
                )
            }
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        HeavyRule(color = colors.foreground)
        // 粗线和滚动内容之间的背景色间隙：内容往上滚时从这道空白下方开始被裁掉，
        // 不会和粗线连成一块。
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(SwissSpace.Xs)
                .background(colors.background),
        )
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
 * 页头四段文字的顶部内边距（按 [texts] 的顺序）。
 *
 * 文字统一贴在这一行的底部：最后一行到下方粗线之间只留 [SwissSpace.Xs]，
 * 多出来的高度全部放在文字上方。
 *
 * 这一行的高度：有操作入口时由它的 48dp 最小触控高度决定（[SwissSpace.Xl]）；
 * 没有操作入口时就是这一行文字自己的高度，不留多余空白。
 *
 * 贴底的同时四段文字仍然共用一条首行基线：中文回退到系统字体后下伸比数字长，
 * 底边对齐以后首行基线就会差一两 dp，所以每段各让出「下伸差」——
 * 下伸最长的那一段贴着行底，其余各段按差值往上抬，基线于是重新落在同一条线上，
 * 底边的差别也就在一 dp 以内。
 *
 * 必须用这一行真实的文字测量：换成另一段文字，补出来的数值就可能不一样。
 */
@Composable
private fun headerLabelTopPaddings(
    style: TextStyle,
    texts: List<String>,
    hasAction: Boolean,
): List<Dp> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density, style, texts, hasAction) {
        val layouts = texts.map { text ->
            measurer.measure(
                text = AnnotatedString(text),
                style = style,
                maxLines = 1,
            )
        }
        // 下伸最长的那一段决定行底；操作入口的触控高度把行撑到 48dp。
        val deepest = layouts.maxOfOrNull { it.size.height - it.lastBaseline } ?: 0f
        val natural = deepest + (layouts.maxOfOrNull { it.firstBaseline } ?: 0f)
        with(density) {
            val rowHeight = if (hasAction) SwissSpace.Xl.toPx() else natural
            layouts.map { layout ->
                (rowHeight - deepest - layout.firstBaseline)
                    .coerceAtLeast(0f)
                    .toDp()
            }
        }
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
