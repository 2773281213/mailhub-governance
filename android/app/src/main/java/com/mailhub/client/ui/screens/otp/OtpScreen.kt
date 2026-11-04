package com.mailhub.client.ui.screens.otp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.OtpItem
import com.mailhub.client.ui.screens.common.PageMeta
import com.mailhub.client.ui.screens.common.rememberSensitiveClipboard
import com.mailhub.client.ui.screens.shell.AppTabs
import com.mailhub.client.ui.swiss.EmptyState
import com.mailhub.client.ui.swiss.GridRow
import com.mailhub.client.ui.swiss.HairlineRule
import com.mailhub.client.ui.swiss.LoadingRule
import com.mailhub.client.ui.swiss.LocalSwissColors
import com.mailhub.client.ui.swiss.SText
import com.mailhub.client.ui.swiss.SectionHeader
import com.mailhub.client.ui.swiss.SwissButton
import com.mailhub.client.ui.swiss.SwissButtonKind
import com.mailhub.client.ui.swiss.SwissScaffold
import com.mailhub.client.ui.swiss.SwissSnackbar
import com.mailhub.client.ui.swiss.SwissSpace
import com.mailhub.client.ui.swiss.SwissTabBar
import com.mailhub.client.ui.swiss.SwissType
import com.mailhub.client.ui.swiss.cols
import kotlinx.coroutines.launch

/**
 * 验证码看板（无状态）：页头下面是列表第一项的大号验证码，
 * 收到不到 5 分钟时数字用红，下面一行 caption 说明来源与时间，再下面一个黑色复制按钮；
 * 之后是「02 更早」区块，每行一个验证码，点一行即复制。
 *
 * 整屏只有验证码数字会用到红色，其余文字一律前景色。
 */
@Composable
fun OtpScreen(
    state: OtpUiState,
    meta: PageMeta,
    onEvent: (OtpEvent) -> Unit,
    modifier: Modifier = Modifier,
    selectedTab: Int = 2,
    onSelectTab: (Int) -> Unit = {},
) {
    SwissScaffold(
        index = "03",
        section = "验证码",
        meta = meta.line,
        title = "验证码",
        modifier = modifier,
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val notice = state.notice
                if (notice != null) {
                    DismissibleSnackbar(
                        message = notice,
                        onDismiss = { onEvent(OtpEvent.DismissNotice) },
                    )
                }
                SwissTabBar(items = AppTabs, selected = selectedTab, onSelect = onSelectTab)
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            val latest = state.latest
            when {
                latest != null -> BoardBody(
                    state = state,
                    latest = latest,
                    zoneId = meta.zoneId,
                    onEvent = onEvent,
                )

                state.loading -> {
                    Spacer(modifier = Modifier.height(SwissSpace.Xs))
                    LoadingRule()
                }

                else -> {
                    Spacer(modifier = Modifier.height(SwissSpace.S))
                    EmptyState(
                        index = "03",
                        title = OtpEmptyTitle,
                        body = OtpEmptyBody,
                    )
                }
            }
            if (state.refreshFailed) {
                Spacer(modifier = Modifier.height(SwissSpace.Xs))
                GridRow {
                    SText(
                        text = OtpRefreshFailedText,
                        style = SwissType.caption(),
                        modifier = Modifier.cols(1, 12),
                        color = LocalSwissColors.current.foreground,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 最新一条 + 「02 更早」区块。 */
@Composable
private fun BoardBody(
    state: OtpUiState,
    latest: OtpItem,
    zoneId: String,
    onEvent: (OtpEvent) -> Unit,
) {
    LatestCode(
        item = latest,
        accounts = state.accounts,
        zoneId = zoneId,
        nowTs = state.nowTs,
        onCopy = { onEvent(OtpEvent.Copy(latest.otpCode)) },
    )
    val earlier = state.earlier
    if (earlier.isEmpty()) return
    Spacer(modifier = Modifier.height(SwissSpace.Xl))
    SectionHeader(index = "02", title = "更早")
    Spacer(modifier = Modifier.height(SwissSpace.S))
    earlier.forEach { item ->
        EarlierRow(
            item = item,
            zoneId = zoneId,
            onClick = { onEvent(OtpEvent.Copy(item.otpCode)) },
        )
    }
}

/**
 * 最新一条：验证码占第 1–12 栏，6 位以上降一档字号；
 * 说明行用 caption 占第 1–12 栏；复制按钮占第 1–6 栏。
 */
@Composable
private fun LatestCode(
    item: OtpItem,
    accounts: List<Account>,
    zoneId: String,
    nowTs: Double,
    onCopy: () -> Unit,
) {
    val colors = LocalSwissColors.current
    GridRow {
        SText(
            text = item.otpCode,
            style = latestCodeStyle(item.otpCode),
            modifier = Modifier.cols(1, 12),
            color = if (isOtpFresh(item.dateTs, nowTs)) colors.accent else colors.foreground,
            maxLines = 1,
        )
    }
    Spacer(modifier = Modifier.height(SwissSpace.Xs))
    GridRow {
        SText(
            text = otpCaptionText(item = item, accounts = accounts, zoneId = zoneId, nowTs = nowTs),
            style = SwissType.caption(),
            modifier = Modifier.cols(1, 12),
            color = colors.foreground,
            maxLines = 1,
        )
    }
    Spacer(modifier = Modifier.height(SwissSpace.S))
    GridRow {
        SwissButton(
            text = "复制",
            onClick = onCopy,
            kind = SwissButtonKind.Primary,
            modifier = Modifier.cols(1, 6),
        )
    }
}

/**
 * 「更早」里的一行：验证码 headline 等宽数字占第 1–5 栏，
 * 发件人占第 6–10 栏，时间占第 11–12 栏；行间用 1dp 细线分隔，点击整行即复制。
 */
@Composable
private fun EarlierRow(
    item: OtpItem,
    zoneId: String,
    onClick: () -> Unit,
) {
    val colors = LocalSwissColors.current
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        HairlineRule(color = colors.foreground)
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        GridRow {
            SText(
                text = item.otpCode,
                style = earlierCodeStyle(),
                modifier = Modifier.cols(1, 5),
                color = colors.foreground,
                maxLines = 1,
            )
            SText(
                text = otpSenderText(item),
                style = SwissType.label(),
                modifier = Modifier.cols(6, 5),
                color = colors.foreground,
                maxLines = 1,
            )
            SText(
                text = otpClockText(item.dateTs, zoneId),
                style = SwissType.caption(),
                modifier = Modifier.cols(11, 2),
                color = colors.foreground,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
    }
}

/** 最新一条的字号：6 位及以内 displaySmall，更长换 title。 */
@Composable
private fun latestCodeStyle(code: String): TextStyle = when (otpCodeSizeFor(code)) {
    OtpCodeSize.Display -> SwissType.displaySmall()
    OtpCodeSize.Title -> SwissType.title()
}

/** 「更早」的行内验证码：headline，数字等宽，逐位对齐。 */
@Composable
private fun earlierCodeStyle(): TextStyle =
    SwissType.headline().copy(fontFeatureSettings = "tnum")

/** 点一下就关掉的提示条。 */
@Composable
private fun DismissibleSnackbar(message: String, onDismiss: () -> Unit) {
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

/**
 * 验证码看板的有状态外层：页面处于 STARTED 以上时跑刷新循环和时钟循环，
 * 离开页面或 App 进入后台时停止收集，循环随之停下；剪贴板复用 common 的
 * [rememberSensitiveClipboard]，写入后 60 秒自动清除。
 */
@Composable
fun OtpScreenHost(
    viewModel: OtpViewModel,
    meta: PageMeta,
    modifier: Modifier = Modifier,
    selectedTab: Int = 2,
    onSelectTab: (Int) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = rememberSensitiveClipboard()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(lifecycle, viewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { viewModel.clockLoop().collect { } }
            viewModel.refreshLoop().collect { }
        }
    }

    OtpScreen(
        state = state,
        meta = meta,
        onEvent = { event ->
            when (event) {
                is OtpEvent.Copy -> {
                    clipboard.copy(event.code)
                    viewModel.onCopied()
                }

                OtpEvent.DismissNotice -> viewModel.onEvent(event)
            }
        },
        modifier = modifier,
        selectedTab = selectedTab,
        onSelectTab = onSelectTab,
    )
}
