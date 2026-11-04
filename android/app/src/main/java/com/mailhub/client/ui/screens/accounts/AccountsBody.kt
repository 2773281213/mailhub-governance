package com.mailhub.client.ui.screens.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.mailhub.client.data.model.Account
import com.mailhub.client.ui.screens.common.accountLabel
import com.mailhub.client.ui.swiss.ConfirmPanel
import com.mailhub.client.ui.swiss.EmptyState
import com.mailhub.client.ui.swiss.ErrorState
import com.mailhub.client.ui.swiss.GridRow
import com.mailhub.client.ui.swiss.HairlineRule
import com.mailhub.client.ui.swiss.IndexLabel
import com.mailhub.client.ui.swiss.LoadingRule
import com.mailhub.client.ui.swiss.LocalSwissColors
import com.mailhub.client.ui.swiss.SText
import com.mailhub.client.ui.swiss.SwissButton
import com.mailhub.client.ui.swiss.SwissButtonKind
import com.mailhub.client.ui.swiss.SwissSnackbar
import com.mailhub.client.ui.swiss.SwissSpace
import com.mailhub.client.ui.swiss.SwissTextButton
import com.mailhub.client.ui.swiss.SwissType
import com.mailhub.client.ui.swiss.cols

/**
 * 账户列表主体（无状态，不含页头与外壳）：
 *
 * - 顶部一行两个动作：「全部同步 →」（描边）与「添加账户 →」（黑色主按钮，本页唯一的主操作）；
 * - 每个账户一行：编号 A1 在第 1–2 栏，名称与邮箱在第 3–9 栏（邮箱用 caption），
 *   同步状态在第 10–12 栏（同步中 / 空闲 · HH:mm / 出错）；出错时下面再补一行错误说明；
 * - 点一行展开「同步 →」「测试连接 →」「删除 →」三个文字按钮；
 * - 删除先弹确认面板，确认之后才发请求。
 */
@Composable
fun AccountsBody(
    state: AccountsUiState,
    zoneId: String,
    onEvent: (AccountsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSwissColors.current
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            val notice = state.notice
            if (notice != null) {
                DismissibleSnackbar(
                    message = notice,
                    onDismiss = { onEvent(AccountsEvent.DismissNotice) },
                )
                Spacer(modifier = Modifier.height(SwissSpace.S))
            }
            GridRow {
                SwissButton(
                    text = "全部同步",
                    onClick = { onEvent(AccountsEvent.SyncAll) },
                    kind = SwissButtonKind.Outline,
                    enabled = !state.busy,
                    modifier = Modifier.cols(1, 6),
                )
                SwissButton(
                    text = "添加账户",
                    onClick = { onEvent(AccountsEvent.OpenAdd) },
                    kind = SwissButtonKind.Primary,
                    modifier = Modifier.cols(7, 6),
                )
            }
            Spacer(modifier = Modifier.height(SwissSpace.M))
            when {
                state.error != null && state.accounts.isEmpty() -> {
                    ErrorState(
                        message = state.error,
                        onRetry = { onEvent(AccountsEvent.Refresh) },
                    )
                }

                state.loading && state.accounts.isEmpty() -> {
                    Spacer(modifier = Modifier.height(SwissSpace.S))
                    LoadingRule()
                }

                state.accounts.isEmpty() -> {
                    Spacer(modifier = Modifier.height(SwissSpace.S))
                    EmptyState(
                        index = "05",
                        title = "没有账户",
                        body = "用上面的「添加账户」接入第一个邮箱。",
                    )
                }

                else -> {
                    state.accounts.forEach { account ->
                        AccountRow(
                            account = account,
                            label = accountLabel(state.accounts, account.id),
                            zoneId = zoneId,
                            expanded = state.expandedId == account.id,
                            busy = state.busy,
                            onEvent = onEvent,
                        )
                    }
                    Spacer(modifier = Modifier.height(SwissSpace.S))
                    HairlineRule(color = colors.foreground)
                }
            }
            Spacer(modifier = Modifier.height(SwissSpace.Xxl))
        }
        val confirmingId = state.confirmingDeleteId
        if (confirmingId != 0) {
            val label = accountLabel(state.accounts, confirmingId)
            ConfirmPanel(
                title = deleteTitle(label),
                message = DeleteMessage,
                confirmText = "删除",
                onConfirm = { onEvent(AccountsEvent.ConfirmDelete) },
                onCancel = { onEvent(AccountsEvent.DismissDelete) },
                danger = true,
            )
        }
    }
}

/**
 * 一个账户：上线细线、编号、名称、邮箱、同步状态；展开后追加三个文字按钮。
 * 名称和状态同处一行，状态是 caption、名称是 body，按首行基线对齐给状态补顶部内边距。
 */
@Composable
private fun AccountRow(
    account: Account,
    label: String,
    zoneId: String,
    expanded: Boolean,
    busy: Boolean,
    onEvent: (AccountsEvent) -> Unit,
) {
    val colors = LocalSwissColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val name = account.name.ifBlank { account.email }
    val status = syncStatusText(account, zoneId)
    val failure = syncErrorText(account)
    Column(modifier = Modifier.fillMaxWidth()) {
        HairlineRule(color = colors.foreground)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = { onEvent(AccountsEvent.Toggle(account.id)) },
                ),
        ) {
            Spacer(modifier = Modifier.height(SwissSpace.Xs))
            GridRow {
                IndexLabel(index = label, modifier = Modifier.cols(1, 2))
                SText(
                    text = name,
                    style = SwissType.body().copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.cols(3, 7),
                    color = colors.foreground,
                    maxLines = 1,
                )
                SText(
                    text = status,
                    style = SwissType.caption(),
                    modifier = Modifier
                        .cols(10, 3)
                        .padding(
                            top = baselineTopPadding(
                                fromStyle = SwissType.caption(),
                                fromText = status,
                                toStyle = SwissType.body(),
                                toText = name,
                            ),
                        ),
                    color = colors.foreground,
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.height(SwissSpace.Xxs))
            GridRow {
                SText(
                    text = account.email,
                    style = SwissType.caption(),
                    modifier = Modifier.cols(3, 7),
                    color = colors.foreground,
                    maxLines = 1,
                )
            }
            if (failure.isNotEmpty()) {
                Spacer(modifier = Modifier.height(SwissSpace.Xxs))
                GridRow {
                    SText(
                        text = failure,
                        style = SwissType.caption(),
                        modifier = Modifier.cols(3, 10),
                        color = colors.foreground,
                    )
                }
            }
            Spacer(modifier = Modifier.height(SwissSpace.Xs))
        }
        if (expanded) {
            GridRow {
                SwissTextButton(
                    text = "同步 →",
                    onClick = { onEvent(AccountsEvent.SyncNow(account.id)) },
                    enabled = !busy,
                    modifier = Modifier.cols(3, 3),
                )
                SwissTextButton(
                    text = "测试连接 →",
                    onClick = { onEvent(AccountsEvent.TestConnection(account.id)) },
                    enabled = !busy,
                    modifier = Modifier.cols(6, 4),
                )
                SwissTextButton(
                    text = "删除 →",
                    onClick = { onEvent(AccountsEvent.RequestDelete(account.id)) },
                    enabled = !busy,
                    modifier = Modifier.cols(10, 3),
                )
            }
            Spacer(modifier = Modifier.height(SwissSpace.Xs))
        }
    }
}

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
