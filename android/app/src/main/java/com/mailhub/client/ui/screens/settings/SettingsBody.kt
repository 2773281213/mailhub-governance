package com.mailhub.client.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import com.mailhub.client.data.settings.SettingsDto
import com.mailhub.client.ui.swiss.ConfirmPanel
import com.mailhub.client.ui.swiss.ErrorState
import com.mailhub.client.ui.swiss.FilterOption
import com.mailhub.client.ui.swiss.GridRow
import com.mailhub.client.ui.swiss.LoadingRule
import com.mailhub.client.ui.swiss.LocalSwissColors
import com.mailhub.client.ui.swiss.SText
import com.mailhub.client.ui.swiss.SectionHeader
import com.mailhub.client.ui.swiss.SwissButton
import com.mailhub.client.ui.swiss.SwissButtonKind
import com.mailhub.client.ui.swiss.SwissSpace
import com.mailhub.client.ui.swiss.SwissTextField
import com.mailhub.client.ui.swiss.SwissToggle
import com.mailhub.client.ui.swiss.SwissType
import com.mailhub.client.ui.swiss.TableRow
import com.mailhub.client.ui.swiss.cols

/** 发送正文开关下面的说明，与网页端一致。 */
private const val SendBodyNote: String =
    "每封最多 1500 字，验证码和密码已遮蔽、链接只保留域名；关闭后只发送主题和发件人。"

private const val BackfillNote: String = "升级后重新治理旧邮件时，只把这些天内的交给 AI；0 = 不限。"

private const val WebOnlyNote: String = "这些在网页端修改。"

private const val PushCredentialNote: String = "推送地址和令牌只在网页端修改。"

/** 两句各占一行，手动断行，免得第二行只剩两三个字。 */
private const val CleanupNote: String = "天数填 0 表示不清理。\n正文超期只清正文，保留标题、摘要和验证码。"

private const val CleanServerNote: String = "删除后无法恢复；打开时会再确认一次。"

private const val SyncReadStateNote: String =
    "开启后，在其他邮箱客户端读过的邮件会同步为已读；在 MailHub 里的已读和未读也会写回邮箱服务器。"

private const val PasswordNote: String = "修改后其他设备全部退出，本机自动换用新凭据。"

/**
 * 设置页主体（无状态，不含页头与外壳）。五个编号区块从上往下读：
 * 01 治理与 AI、02 推送、03 清理与晨报、04 同步（后端没有这个字段时整块隐藏，后面的编号顺延）、
 * 05 管理密码。每块末尾有自己的「保存 →」，结果说明写在按钮下面。
 *
 * 凭据（AI Key、推送地址与令牌、Onepush 配置）只显示「已配置 / 未配置」，App 里不提供输入。
 */
@Composable
fun SettingsBody(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val saved = state.saved
    Box(modifier = modifier.fillMaxSize()) {
        when {
            saved == null && state.error != null -> ErrorState(
                message = state.error,
                onRetry = { onEvent(SettingsEvent.Retry) },
            )

            saved == null -> Column {
                Spacer(modifier = Modifier.height(SwissSpace.S))
                LoadingRule()
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                state.sections.forEachIndexed { position, section ->
                    if (position > 0) {
                        Spacer(modifier = Modifier.height(SwissSpace.Xl))
                    }
                    SectionHeader(index = state.indexOf(section), title = section.title)
                    Spacer(modifier = Modifier.height(SwissSpace.S))
                    SectionContent(section = section, saved = saved, state = state, onEvent = onEvent)
                }
                Spacer(modifier = Modifier.height(SwissSpace.Xxl))
            }
        }
        if (state.confirmingCleanServer) {
            ConfirmPanel(
                title = CleanServerConfirmTitle,
                message = CleanServerConfirmMessage,
                confirmText = "开启",
                onConfirm = { onEvent(SettingsEvent.ConfirmCleanServer) },
                onCancel = { onEvent(SettingsEvent.DismissCleanServer) },
                danger = true,
            )
        }
    }
}

/** 按区块分派；策略表比一长串 if 好读。 */
@Composable
private fun SectionContent(
    section: SettingsSection,
    saved: SettingsDto,
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    when (section) {
        SettingsSection.Ai -> AiSection(saved = saved, state = state, onEvent = onEvent)
        SettingsSection.Push -> PushSection(saved = saved, state = state, onEvent = onEvent)
        SettingsSection.Cleanup -> CleanupSection(state = state, onEvent = onEvent)
        SettingsSection.Sync -> SyncSection(state = state, onEvent = onEvent)
        SettingsSection.Password -> PasswordSection(state = state, onEvent = onEvent)
    }
}

@Composable
private fun AiSection(saved: SettingsDto, state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    SwitchRow(field = SwitchField.AiEnabled, state = state, onEvent = onEvent)
    SwitchRow(field = SwitchField.AiSendBody, state = state, onEvent = onEvent, note = SendBodyNote)
    Spacer(modifier = Modifier.height(SwissSpace.M))
    GridRow {
        SText(
            text = "判定范围",
            style = SwissType.label(),
            modifier = Modifier.cols(1, 12),
            color = LocalSwissColors.current.foreground,
        )
    }
    GridRow {
        AiScopeOptions.forEachIndexed { position, (value, label) ->
            FilterOption(
                text = label,
                selected = state.draft.aiScope == value,
                onClick = { onEvent(SettingsEvent.ScopeChanged(value)) },
                modifier = Modifier.cols(1 + position * 4, 4),
            )
        }
    }
    Spacer(modifier = Modifier.height(SwissSpace.M))
    GridRow {
        NumberInput(field = NumberField.AiBackfillDays, state = state, onEvent = onEvent, modifier = Modifier.cols(1, 6))
    }
    Note(text = BackfillNote, start = 1)
    Spacer(modifier = Modifier.height(SwissSpace.M))
    TableRow(label = "接口地址", value = configuredText(saved.aiBaseUrl.isNotBlank()))
    TableRow(label = "模型", value = saved.aiModel.ifBlank { "未配置" })
    TableRow(label = "API Key", value = configuredText(saved.aiKeySet))
    Note(text = WebOnlyNote, start = 4)
    Spacer(modifier = Modifier.height(SwissSpace.M))
    SaveRow(section = SettingsSection.Ai, state = state, onEvent = onEvent)
}

@Composable
private fun PushSection(saved: SettingsDto, state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    SwitchRow(field = SwitchField.NotifyImportant, state = state, onEvent = onEvent)
    SwitchRow(field = SwitchField.NotifyOtp, state = state, onEvent = onEvent)
    Spacer(modifier = Modifier.height(SwissSpace.M))
    TableRow(label = "Bark", value = configuredText(saved.notifyBarkSet, saved.notifyBarkHost))
    TableRow(label = "Telegram", value = configuredText(telegramConfigured(saved)))
    TableRow(
        label = "Onepush",
        value = configuredText(saved.notifyOnepushSet, saved.notifyOnepushProvider),
    )
    Note(text = PushCredentialNote, start = 4)
    Spacer(modifier = Modifier.height(SwissSpace.M))
    SaveRow(
        section = SettingsSection.Push,
        state = state,
        onEvent = onEvent,
        secondaryText = "发送测试推送",
        onSecondary = { onEvent(SettingsEvent.TestPush) },
    )
}

@Composable
private fun CleanupSection(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    GridRow {
        NumberInput(field = NumberField.DigestHour, state = state, onEvent = onEvent, modifier = Modifier.cols(1, 6))
        NumberInput(field = NumberField.CleanOtpDays, state = state, onEvent = onEvent, modifier = Modifier.cols(7, 6))
    }
    Spacer(modifier = Modifier.height(SwissSpace.M))
    GridRow {
        NumberInput(field = NumberField.BodyKeepDays, state = state, onEvent = onEvent, modifier = Modifier.cols(1, 6))
        NumberInput(
            field = NumberField.CleanBillMinorDays,
            state = state,
            onEvent = onEvent,
            modifier = Modifier.cols(7, 6),
        )
    }
    Note(text = CleanupNote, start = 1)
    Spacer(modifier = Modifier.height(SwissSpace.S))
    SwitchRow(field = SwitchField.CleanServer, state = state, onEvent = onEvent, note = CleanServerNote)
    Spacer(modifier = Modifier.height(SwissSpace.M))
    SaveRow(section = SettingsSection.Cleanup, state = state, onEvent = onEvent)
}

@Composable
private fun SyncSection(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    SwitchRow(field = SwitchField.SyncReadState, state = state, onEvent = onEvent, note = SyncReadStateNote)
    Spacer(modifier = Modifier.height(SwissSpace.M))
    SaveRow(section = SettingsSection.Sync, state = state, onEvent = onEvent)
}

@Composable
private fun PasswordSection(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    PasswordField.entries.forEachIndexed { position, field ->
        if (position > 0) {
            Spacer(modifier = Modifier.height(SwissSpace.M))
        }
        val error = state.passwordErrors[field]
        GridRow {
            SwissTextField(
                value = state.password.value(field),
                onValueChange = { onEvent(SettingsEvent.PasswordChanged(field, it)) },
                label = field.label,
                isError = error != null,
                errorText = error,
                enabled = state.pending != SettingsSection.Password,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (field == PasswordField.Confirm) ImeAction.Done else ImeAction.Next,
                ),
                modifier = Modifier.cols(1, 8),
            )
        }
    }
    // 上面可能是输入框的错误说明，多留一点空，免得两段字读成一段
    Note(text = PasswordNote, start = 1, top = SwissSpace.M)
    Spacer(modifier = Modifier.height(SwissSpace.M))
    SaveRow(section = SettingsSection.Password, state = state, onEvent = onEvent, primaryText = "修改密码")
}

/** 一个开关；[note] 写在开关文字下面，左边缘和开关文字对齐（第 2 栏）。 */
@Composable
private fun SwitchRow(
    field: SwitchField,
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    note: String? = null,
) {
    GridRow {
        SwissToggle(
            checked = state.draft.switch(field),
            onCheckedChange = { onEvent(SettingsEvent.SwitchChanged(field, it)) },
            label = field.label,
            modifier = Modifier.cols(1, 12),
        )
    }
    if (note != null) {
        GridRow {
            SText(
                text = note,
                style = SwissType.caption(),
                modifier = Modifier.cols(2, 11),
                color = LocalSwissColors.current.foreground,
            )
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
    }
}

/** 数字输入框：只收数字；保存时没通过校验的，下划线变红、下面用黑字说明。 */
@Composable
private fun NumberInput(
    field: NumberField,
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = state.numberErrors[field]
    SwissTextField(
        value = state.draft.number(field),
        onValueChange = { onEvent(SettingsEvent.NumberChanged(field, it)) },
        label = field.label,
        isError = error != null,
        errorText = error,
        enabled = state.pending != field.section,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next,
        ),
        modifier = modifier,
    )
}

/** caption 说明，从第 [start] 栏开始一直到第 12 栏，上方留 [top]。 */
@Composable
private fun Note(text: String, start: Int, top: Dp = SwissSpace.Xs) {
    Spacer(modifier = Modifier.height(top))
    GridRow {
        SText(
            text = text,
            style = SwissType.caption(),
            modifier = Modifier.cols(start, 13 - start),
            color = LocalSwissColors.current.foreground,
        )
    }
}

/**
 * 区块末尾的按钮行：主按钮在第 1–6 栏，可选的描边按钮在第 7–12 栏；
 * 请求在途时下面是加载细线，结束后换成结果说明。
 */
@Composable
private fun SaveRow(
    section: SettingsSection,
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    primaryText: String = "保存",
    secondaryText: String? = null,
    onSecondary: () -> Unit = {},
) {
    val idle = state.pending == null
    GridRow {
        SwissButton(
            text = primaryText,
            onClick = { onEvent(SettingsEvent.Save(section)) },
            kind = SwissButtonKind.Primary,
            enabled = idle,
            modifier = Modifier.cols(1, 6),
        )
        if (secondaryText != null) {
            SwissButton(
                text = secondaryText,
                onClick = onSecondary,
                kind = SwissButtonKind.Outline,
                enabled = idle,
                modifier = Modifier.cols(7, 6),
            )
        }
    }
    val feedback = state.feedback[section]
    when {
        state.pending == section -> {
            Spacer(modifier = Modifier.height(SwissSpace.S))
            LoadingRule()
        }

        feedback != null -> {
            Spacer(modifier = Modifier.height(SwissSpace.S))
            GridRow {
                SText(
                    text = feedback,
                    style = SwissType.body(),
                    modifier = Modifier.cols(1, 12),
                    color = LocalSwissColors.current.foreground,
                )
            }
        }
    }
}
