package com.mailhub.client.ui.screens.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mailhub.client.AppContainer
import com.mailhub.client.data.model.ImportantItem
import com.mailhub.client.data.model.OverviewStats
import com.mailhub.client.ui.screens.common.MailRow
import com.mailhub.client.ui.screens.common.MailRowData
import com.mailhub.client.ui.screens.common.PageMeta
import com.mailhub.client.ui.screens.shell.AppTabs
import com.mailhub.client.ui.screens.shell.IndexActionLabel
import com.mailhub.client.ui.swiss.BarChart
import com.mailhub.client.ui.swiss.ErrorState
import com.mailhub.client.ui.swiss.GridRow
import com.mailhub.client.ui.swiss.HairlineRule
import com.mailhub.client.ui.swiss.LoadingRule
import com.mailhub.client.ui.swiss.LocalSwissColors
import com.mailhub.client.ui.swiss.PageTitle
import com.mailhub.client.ui.swiss.SText
import com.mailhub.client.ui.swiss.SectionHeader
import com.mailhub.client.ui.swiss.SwissButton
import com.mailhub.client.ui.swiss.SwissButtonKind
import com.mailhub.client.ui.swiss.SwissScaffold
import com.mailhub.client.ui.swiss.SwissSnackbar
import com.mailhub.client.ui.swiss.SwissSpace
import com.mailhub.client.ui.swiss.SwissTabBar
import com.mailhub.client.ui.swiss.SwissType
import com.mailhub.client.ui.swiss.TableRow
import com.mailhub.client.ui.swiss.cols

/**
 * 总览页（无状态）：未读数、三栏统计、三省六部、近 12 天柱状图、重要邮件、晨报、刷新。
 * 整屏只有一个红色主操作（运行治理），其余按钮都是描边或主要样式。
 *
 * 固定区只有一行页头，「总览」这个大标题是滚动内容的第一项，跟着内容一起滚出屏幕。
 */
@Composable
fun OverviewScreen(
    state: OverviewUiState,
    meta: PageMeta,
    onEvent: (OverviewEvent) -> Unit,
    modifier: Modifier = Modifier,
    selectedTab: Int = 0,
    onSelectTab: (Int) -> Unit = {},
    onOpenIndex: () -> Unit = {},
) {
    SwissScaffold(
        index = "01",
        section = "总览",
        meta = meta.line,
        modifier = modifier,
        actionLabel = IndexActionLabel,
        onAction = onOpenIndex,
        bottomBar = {
            SwissTabBar(items = AppTabs, selected = selectedTab, onSelect = onSelectTab)
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            PageTitle(text = "总览")
            when {
                state.error != null && state.overview == null -> {
                    ErrorState(
                        message = state.error,
                        onRetry = { onEvent(OverviewEvent.Refresh) },
                    )
                }

                state.overview == null -> LoadingRule()

                else -> OverviewBody(state = state, meta = meta, onEvent = onEvent)
            }
        }
    }
}

/** 拿到数据之后的正文；各区块之间用 1dp 细线和大留白分隔。 */
@Composable
private fun OverviewBody(
    state: OverviewUiState,
    meta: PageMeta,
    onEvent: (OverviewEvent) -> Unit,
) {
    val colors = LocalSwissColors.current
    val overview = state.overview
    val stats = overview?.stats ?: OverviewStats()

    // 01 未读数：大号数字占第 1–8 栏，标签靠右占第 10–12 栏
    GridRow {
        SText(
            text = stats.unread.toString(),
            style = unreadStyle(stats.unread),
            modifier = Modifier.cols(1, 8),
            color = colors.foreground,
            maxLines = 1,
        )
        SText(
            text = "未读",
            style = SwissType.label(),
            // 大号数字的字面比节点顶边低一个区块间距，标签跟着下移才对得上数字顶部
            modifier = Modifier
                .cols(10, 3)
                .padding(top = SwissSpace.M),
            color = colors.foreground,
            maxLines = 1,
        )
    }
    Spacer(modifier = Modifier.height(SwissSpace.Xs))
    HairlineRule(color = colors.foreground)
    Spacer(modifier = Modifier.height(SwissSpace.M))

    // 三栏数字：重要未读 / 今日 / 今日验证码
    GridRow {
        MiniStat(
            value = stats.importantUnread.toString(),
            label = "重要未读",
            modifier = Modifier.cols(1, 4),
            accent = stats.importantUnread > 0,
        )
        MiniStat(
            value = stats.today.toString(),
            label = "今日",
            modifier = Modifier.cols(5, 4),
        )
        MiniStat(
            value = stats.todayOtp.toString(),
            label = "今日验证码",
            modifier = Modifier.cols(9, 4),
        )
    }

    // 02 三省六部
    Spacer(modifier = Modifier.height(SwissSpace.L))
    SectionHeader(index = "02", title = "三省六部")
    Spacer(modifier = Modifier.height(SwissSpace.S))
    val governance = state.governance
    TableRow(
        label = "待治理",
        value = governance?.pending?.toString() ?: EmptyTableValue,
    )
    TableRow(
        label = "上次运行",
        value = governanceTimeText(governance?.lastRun, meta.zoneId),
    )
    TableRow(
        label = "本次结果",
        value = governanceResultText(governance?.lastRun),
    )
    Spacer(modifier = Modifier.height(SwissSpace.S))
    GridRow {
        SwissButton(
            text = "运行",
            onClick = { onEvent(OverviewEvent.RunGovernance) },
            kind = SwissButtonKind.Accent,
            enabled = !state.governanceRunning,
            modifier = Modifier.cols(1, 6),
        )
    }
    val notice = state.notice
    if (notice != null) {
        Spacer(modifier = Modifier.height(SwissSpace.S))
        NoticeBar(
            message = notice,
            onDismiss = { onEvent(OverviewEvent.DismissNotice) },
        )
    }

    // 03 近 12 天
    Spacer(modifier = Modifier.height(SwissSpace.L))
    SectionHeader(index = "03", title = "近 12 天")
    Spacer(modifier = Modifier.height(SwissSpace.S))
    val bars = overviewBars(
        days = overview?.days.orEmpty(),
        daily = overview?.daily.orEmpty(),
    )
    if (bars.size >= 2) {
        // 柱下刻度只放得下日的两位数字，完整日期范围在这里补一行旧格式的小字
        GridRow {
            SText(
                text = "${bars.first().label} — ${bars.last().label}",
                style = SwissType.caption(),
                modifier = Modifier.cols(1, 8),
                color = colors.foreground,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
    }
    BarChart(
        values = bars.map { it.total },
        accent = bars.map { it.risk },
        labels = bars.map { it.tick },
    )

    // 04 重要邮件
    Spacer(modifier = Modifier.height(SwissSpace.L))
    SectionHeader(index = "04", title = "重要邮件")
    val important = overview?.important.orEmpty().take(ImportantLimit)
    if (important.isEmpty()) {
        Spacer(modifier = Modifier.height(SwissSpace.S))
        GridRow {
            SText(
                text = "暂无重要邮件",
                style = SwissType.caption(),
                modifier = Modifier.cols(1, 10),
                color = colors.foreground,
            )
        }
    } else {
        important.forEach { item ->
            ImportantRow(item = item, zoneId = meta.zoneId)
        }
    }

    // 05 晨报
    Spacer(modifier = Modifier.height(SwissSpace.L))
    SectionHeader(index = "05", title = "晨报")
    Spacer(modifier = Modifier.height(SwissSpace.S))
    val digest = overview?.digest
    if (digest != null && digest.content.isNotBlank()) {
        DigestBody(blocks = remember(digest.content) { parseDigest(digest.content) })
    } else {
        GridRow {
            SText(
                text = "今天还没有晨报",
                style = SwissType.caption(),
                modifier = Modifier.cols(1, 10),
                color = colors.foreground,
            )
        }
    }

    // 底部刷新
    Spacer(modifier = Modifier.height(SwissSpace.Xl))
    GridRow {
        SwissButton(
            text = "刷新",
            onClick = { onEvent(OverviewEvent.Refresh) },
            kind = SwissButtonKind.Outline,
            enabled = !state.loading,
            modifier = Modifier.cols(1, 6),
        )
    }
    Spacer(modifier = Modifier.height(SwissSpace.Xl))
}

/** 重要邮件最多显示 5 条。 */
private const val ImportantLimit = 5

/** 表里缺值时的占位符。 */
private const val EmptyTableValue = "—"

/**
 * 晨报正文：按 [parseDigest] 给出的段落逐段排版。
 *
 * 标题整段加粗；列表项的标记「—」占第 1 栏，文字从第 2 栏起排到第 10 栏，
 * 换行后仍从第 2 栏起排（悬挂缩进）；空行只留一个 [SwissSpace.S] 的段落间距。
 */
@Composable
private fun DigestBody(blocks: List<DigestBlock>) {
    val colors = LocalSwissColors.current
    blocks.forEach { block ->
        when (block) {
            DigestBlock.Gap -> Spacer(modifier = Modifier.height(SwissSpace.S))

            is DigestBlock.Heading -> GridRow {
                DigestText(
                    spans = block.spans,
                    modifier = Modifier.cols(1, 10),
                    forceBold = true,
                )
            }

            is DigestBlock.Bullet -> GridRow {
                SText(
                    text = DigestBulletMark,
                    style = SwissType.body(),
                    modifier = Modifier.cols(1, 1),
                    color = colors.foreground,
                    maxLines = 1,
                )
                DigestText(spans = block.spans, modifier = Modifier.cols(2, 9))
            }

            is DigestBlock.Paragraph -> GridRow {
                DigestText(spans = block.spans, modifier = Modifier.cols(1, 10))
            }
        }
    }
}

/**
 * 晨报里的一段文字：body 样式，行内 `**…**` 用 Bold 字重。
 *
 * 行内混排加粗只能交给 BasicText 的 AnnotatedString（[SText] 只接受纯字符串），
 * 字体、颜色和对齐仍然全部取自设计系统的 [SwissType.body] 与三色。
 */
@Composable
private fun DigestText(
    spans: List<DigestSpan>,
    modifier: Modifier = Modifier,
    forceBold: Boolean = false,
) {
    val colors = LocalSwissColors.current
    val text = buildAnnotatedString {
        spans.forEach { span ->
            withStyle(
                SpanStyle(
                    fontWeight = if (forceBold || span.bold) FontWeight.Bold else FontWeight.Normal,
                ),
            ) {
                append(span.text)
            }
        }
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = SwissType.body().copy(color = colors.foreground, textAlign = TextAlign.Start),
    )
}

/**
 * 未读大数字：用 StatNumber 的字号规则（位数 ≥ 4 时退一档），
 * 但这里要占第 1–8 栏、标签另占第 10–12 栏，所以用 GridRow + SText 直接排。
 */
@Composable
private fun unreadStyle(unread: Int) =
    if (unread.toString().count { it.isDigit() } >= 4) SwissType.displaySmall() else SwissType.display()

/** 三栏统计里的小号数字：headline 数字 + label 标签，重要未读大于 0 时数字用红。 */
@Composable
private fun MiniStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val colors = LocalSwissColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        SText(
            text = value,
            style = SwissType.headline(),
            color = if (accent) colors.accent else colors.foreground,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        SText(
            text = label,
            style = SwissType.label(),
            color = colors.foreground,
            maxLines = 1,
        )
    }
}

/**
 * 一条重要邮件：直接用共享的 [MailRow]，与收件箱保持同一种行结构
 * （第 1 栏标记、第 2–4 栏时间、第 5–12 栏主题与发件人）。
 * 总览不显示摘要和分类，所以这两项留空；未读时主题加粗、第 1 栏出现红色小方块。
 */
@Composable
private fun ImportantRow(item: ImportantItem, zoneId: String) {
    MailRow(
        data = MailRowData(
            id = item.id,
            subject = item.subject,
            sender = item.senderAddr,
            dateTs = item.dateTs,
            isUnread = item.isUnread,
        ),
        zoneId = zoneId,
    )
}

/** 提示条：点一下即关闭，不再留到下一次操作。 */
@Composable
private fun NoticeBar(message: String, onDismiss: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onDismiss,
            ),
    ) {
        SwissSnackbar(message = message)
    }
}

/** 总览页的有状态外层：取 ViewModel 并收集状态。 */
@Composable
fun OverviewScreenHost(
    container: AppContainer,
    meta: PageMeta,
    modifier: Modifier = Modifier,
    selectedTab: Int = 0,
    onSelectTab: (Int) -> Unit = {},
    onOpenIndex: () -> Unit = {},
) {
    val viewModel: OverviewViewModel = viewModel(
        factory = OverviewViewModelFactory(container.mailRepository),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 页面重新进入时 ViewModel 还是同一个实例，这里补一次加载，避免显示上一个会话的数据
    LaunchedEffect(viewModel) {
        viewModel.onEnter()
    }
    OverviewScreen(
        state = state,
        meta = meta,
        onEvent = viewModel::onEvent,
        modifier = modifier,
        selectedTab = selectedTab,
        onSelectTab = onSelectTab,
        onOpenIndex = onOpenIndex,
    )
}
