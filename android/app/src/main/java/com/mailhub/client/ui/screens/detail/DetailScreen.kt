package com.mailhub.client.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mailhub.client.data.model.MessageDetail
import com.mailhub.client.ui.screens.common.PageMeta
import com.mailhub.client.ui.screens.common.SensitiveClipboard
import com.mailhub.client.ui.screens.common.rememberSensitiveClipboard
import com.mailhub.client.ui.screens.shell.AppTabs
import com.mailhub.client.ui.swiss.ConfirmPanel
import com.mailhub.client.ui.swiss.ErrorState
import com.mailhub.client.ui.swiss.GridRow
import com.mailhub.client.ui.swiss.HairlineRule
import com.mailhub.client.ui.swiss.HeavyRule
import com.mailhub.client.ui.swiss.IndexLabel
import com.mailhub.client.ui.swiss.LoadingRule
import com.mailhub.client.ui.swiss.LocalGridSpec
import com.mailhub.client.ui.swiss.LocalGridWidth
import com.mailhub.client.ui.swiss.LocalSwissColors
import com.mailhub.client.ui.swiss.SText
import com.mailhub.client.ui.swiss.SwissButton
import com.mailhub.client.ui.swiss.SwissButtonKind
import com.mailhub.client.ui.swiss.SwissPalette
import com.mailhub.client.ui.swiss.SwissSnackbar
import com.mailhub.client.ui.swiss.SwissSpace
import com.mailhub.client.ui.swiss.SwissTabBar
import com.mailhub.client.ui.swiss.SwissType
import com.mailhub.client.ui.swiss.TableRow
import com.mailhub.client.ui.swiss.cols

/** 详情页页头的栏目名。 */
private const val DetailSection = "邮件"

/**
 * 邮件详情（无状态）。页头沿用外壳的编号 + 细线结构，标题位置放邮件主题（headline，
 * 占第 1–11 栏，可以换行，不用 56sp 的大标题字号）。
 *
 * 正文用 WebView 渲染 HTML、否则用 SText 显示纯文本；红色只出现在风险平涂块和
 * 唯一的红色主操作（删除）上。
 */
@Composable
fun DetailScreen(
    state: DetailUiState,
    meta: PageMeta,
    onEvent: (DetailEvent) -> Unit,
    modifier: Modifier = Modifier,
    selectedTab: Int = 1,
    onSelectTab: (Int) -> Unit = {},
) {
    val colors = LocalSwissColors.current
    val clipboard = rememberSensitiveClipboard()
    val message = state.message
    val title = message?.subject?.takeIf { it.isNotBlank() } ?: DetailSection

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            DetailHeader(title = title, meta = meta)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    state.error != null && message == null -> ErrorState(
                        message = state.error,
                        onRetry = { onEvent(DetailEvent.Reload) },
                    )

                    message == null -> LoadingRule()

                    else -> DetailBody(
                        state = state,
                        message = message,
                        meta = meta,
                        clipboard = clipboard,
                        onEvent = onEvent,
                    )
                }
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                val notice = state.notice
                if (notice != null) {
                    SwissSnackbar(message = notice)
                }
                SwissTabBar(items = AppTabs, selected = selectedTab, onSelect = onSelectTab)
            }
        }
        if (state.confirmingDelete) {
            ConfirmPanel(
                title = "删除这封邮件？",
                message = "邮件进入回收站，可以在安全中心恢复。",
                confirmText = "删除",
                onConfirm = { onEvent(DetailEvent.ConfirmDelete) },
                onCancel = { onEvent(DetailEvent.DismissDelete) },
                danger = true,
            )
        }
        if (state.confirmingProtected) {
            ConfirmPanel(
                title = "受保护邮件",
                message = "这是受保护邮件（账单、安全、重要或含附件）。确认删除这一封？",
                confirmText = "删除",
                onConfirm = { onEvent(DetailEvent.ConfirmProtectedDelete) },
                onCancel = { onEvent(DetailEvent.DismissProtected) },
                danger = true,
            )
        }
        val pendingLink = state.pendingLink
        if (pendingLink != null) {
            ConfirmPanel(
                title = "在浏览器中打开？",
                message = hostOf(pendingLink),
                confirmText = "打开",
                onConfirm = { onEvent(DetailEvent.ConfirmOpenLink) },
                onCancel = { onEvent(DetailEvent.DismissOpenLink) },
            )
        }
    }
}

/** 页头：编号、栏目名、元信息，一条 4dp 粗线，下面是主题（headline，可换行）。 */
@Composable
private fun DetailHeader(title: String, meta: PageMeta) {
    val colors = LocalSwissColors.current
    GridRow {
        IndexLabel(index = "02", modifier = Modifier.cols(1, 2))
        SText(
            text = DetailSection,
            style = SwissType.label(),
            modifier = Modifier.cols(3, 6),
            color = colors.foreground,
            maxLines = 1,
        )
        SText(
            text = meta.line,
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
            style = SwissType.headline(),
            modifier = Modifier.cols(1, 11),
            color = colors.foreground,
        )
    }
    Spacer(modifier = Modifier.height(SwissSpace.Xl))
}

/** 拿到详情之后的正文：风险块、键值表、验证码、正文、操作。 */
@Composable
private fun DetailBody(
    state: DetailUiState,
    message: MessageDetail,
    meta: PageMeta,
    clipboard: SensitiveClipboard,
    onEvent: (DetailEvent) -> Unit,
) {
    val colors = LocalSwissColors.current
    if (message.isRisky) {
        RiskBlock(reasons = message.riskReasons.ifBlank { RiskyLinkBlockedTextBadge })
        Spacer(modifier = Modifier.height(SwissSpace.S))
    }
    TableRow(label = "发件人", value = senderText(message))
    TableRow(label = "账户", value = accountText(message, state.accounts))
    TableRow(label = "时间", value = detailTimeText(message.dateTs, meta.zoneId))
    TableRow(label = "分类", value = detailCategoryText(message, state.categories))
    TableRow(label = "置信度", value = confidenceText(message.confidence))
    TableRow(
        label = "主责部",
        value = message.governanceMinistry.ifBlank { DetailEmptyValue },
    )
    TableRow(label = "判定依据", value = judgeReasonText(message))
    Spacer(modifier = Modifier.height(SwissSpace.S))

    if (showsOtpBlock(isRisky = message.isRisky, otpCode = message.otpCode)) {
        OtpBlock(
            code = message.otpCode,
            onCopy = { clipboard.copy(message.otpCode) },
        )
        Spacer(modifier = Modifier.height(SwissSpace.S))
    }
    if (message.isBodyTruncated) {
        GridRow {
            SText(
                text = "这封邮件较大，只同步了开头部分。",
                style = SwissType.caption(),
                modifier = Modifier.cols(1, 10),
                color = colors.foreground,
            )
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
    }
    BodyFrame(
        message = message,
        showExternalImages = state.showExternalImages,
        onLink = { onEvent(DetailEvent.TapLink(it)) },
    )
    if (message.bodyHtml.isNotBlank() && !state.showExternalImages) {
        Spacer(modifier = Modifier.height(SwissSpace.S))
        GridRow {
            SwissButton(
                text = "显示外部图片",
                onClick = { onEvent(DetailEvent.ShowExternalImages) },
                kind = SwissButtonKind.Outline,
                modifier = Modifier.cols(1, 6),
            )
        }
    }
    if (message.hasAttachment) {
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        GridRow {
            SText(
                text = "含附件（客户端不提供下载，请到原邮箱查看）",
                style = SwissType.caption(),
                modifier = Modifier.cols(1, 10),
                color = colors.foreground,
            )
        }
    }
    Spacer(modifier = Modifier.height(SwissSpace.L))
    GridRow {
        SwissButton(
            text = "标为未读",
            onClick = { onEvent(DetailEvent.MarkUnread) },
            kind = SwissButtonKind.Outline,
            enabled = !state.deleting,
            modifier = Modifier.cols(1, 6),
        )
        SwissButton(
            text = "删除",
            onClick = { onEvent(DetailEvent.RequestDelete) },
            kind = SwissButtonKind.Accent,
            enabled = !state.deleting,
            modifier = Modifier.cols(7, 6),
        )
    }
    Spacer(modifier = Modifier.height(SwissSpace.Xl))
}

/** 风险邮件没有给出原因时的兜底说明。 */
private const val RiskyLinkBlockedTextBadge = "安全扫描判定这封邮件有风险，请勿点击其中的链接。"

/**
 * 风险平涂块：红底占第 1–12 栏，里面的米白粗体文字从第 2 栏的栏线起排、到第 11 栏的右缘为止，
 * 也就是 cols(2, 10)：左侧内缩一栏，右侧留出一栏加一个栏间距的空白，两边对称。
 * 文字右缘不能压到第 12 栏的右缘，那里正好是红块的边缘，长文本会贴着边缘排。
 *
 * 红底和文字是同一行里的两层：红底单独一个 GridRow，宽高都跟着整块走
 * （matchParentSize），这样它里面的栏线仍然是整页的栏线，文字才能落在真正的栏线上。
 */
@Composable
private fun RiskBlock(reasons: String) {
    val colors = LocalSwissColors.current
    Box(modifier = Modifier.fillMaxWidth()) {
        GridRow(modifier = Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .cols(1, 12)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(colors.accent),
            )
        }
        GridRow {
            SText(
                text = reasons,
                style = SwissType.body().copy(fontWeight = FontWeight.Bold),
                modifier = Modifier
                    .cols(2, 10)
                    .padding(vertical = SwissSpace.S),
                color = SwissPalette.Paper,
            )
        }
    }
}

/** 验证码：displaySmall 的等宽数字，右上角一个「复制 →」描边按钮。 */
@Composable
private fun OtpBlock(code: String, onCopy: () -> Unit) {
    val colors = LocalSwissColors.current
    GridRow {
        SText(
            text = "验证码",
            style = SwissType.label(),
            modifier = Modifier.cols(1, 4),
            color = colors.foreground,
            maxLines = 1,
        )
        SwissButton(
            text = "复制",
            onClick = onCopy,
            kind = SwissButtonKind.Outline,
            modifier = Modifier.cols(9, 4),
        )
    }
    Spacer(modifier = Modifier.height(SwissSpace.Xs))
    GridRow {
        SText(
            text = code,
            style = SwissType.displaySmall(),
            modifier = Modifier.cols(1, 12),
            color = colors.foreground,
            maxLines = 1,
        )
    }
}

/**
 * 正文框：外加 1dp 前景色描边，内侧文字从下一条栏线起排。
 * HTML 走 WebView，纯文本走 SText；两种情况下框和留白都一样。
 */
@Composable
private fun BodyFrame(
    message: MessageDetail,
    showExternalImages: Boolean,
    onLink: (String) -> Unit,
) {
    val colors = LocalSwissColors.current
    val spec = LocalGridSpec.current
    val gridWidth = LocalGridWidth.current
    val innerStart = spec.step(gridWidth)
    val html = message.bodyHtml
    GridRow {
        Box(
            modifier = Modifier
                .cols(1, 12)
                .fillMaxWidth()
                .drawBehind {
                    // 线宽取当前密度，正好是 1dp
                    drawRect(color = colors.foreground, style = Stroke(width = density))
                }
                .padding(
                    start = innerStart,
                    end = SwissSpace.S,
                    top = SwissSpace.S,
                    bottom = SwissSpace.S,
                ),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (html.isNotBlank()) {
                    MailBodyWebView(
                        html = html,
                        config = mailWebViewConfig(
                            isRisky = message.isRisky,
                            showExternalImages = showExternalImages,
                        ),
                        onLink = onLink,
                    )
                } else {
                    SText(
                        text = message.bodyText.ifBlank { "（无正文）" },
                        style = SwissType.body(),
                        color = colors.foreground,
                    )
                }
            }
        }
    }
}

/**
 * 详情页的有状态外层。ViewModel 由外壳持有，messageId 变化时重新加载；
 * 已读 / 未读 / 删除的结果通过回调写回收件箱那一行，然后由外壳弹栈。
 */
@Composable
fun DetailScreenHost(
    viewModel: DetailViewModel,
    messageId: Long,
    meta: PageMeta,
    onMarkedRead: (Long) -> Unit,
    onMarkedUnread: (Long) -> Unit,
    onDeleted: (Long) -> Unit,
    modifier: Modifier = Modifier,
    selectedTab: Int = 1,
    onSelectTab: (Int) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel, messageId) {
        viewModel.onEnter(messageId)
    }
    LaunchedEffect(state.markedRead) {
        if (state.markedRead) onMarkedRead(messageId)
    }
    LaunchedEffect(state.markedUnread) {
        if (state.markedUnread) onMarkedUnread(messageId)
    }
    LaunchedEffect(state.deleted) {
        if (state.deleted) onDeleted(messageId)
    }
    LaunchedEffect(state.linkToOpen) {
        val url = state.linkToOpen ?: return@LaunchedEffect
        openInBrowser(context, url)
        viewModel.clearOpenedLink()
    }
    DetailScreen(
        state = state,
        meta = meta,
        onEvent = viewModel::onEvent,
        modifier = modifier,
        selectedTab = selectedTab,
        onSelectTab = onSelectTab,
    )
}
