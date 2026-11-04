package com.mailhub.client.ui.screens.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.mailhub.client.data.accounts.AuthMethod
import com.mailhub.client.data.accounts.MethodOAuthBrowser
import com.mailhub.client.data.accounts.MethodOAuthDevice
import com.mailhub.client.ui.swiss.GridRow
import com.mailhub.client.ui.swiss.HeavyRule
import com.mailhub.client.ui.swiss.IndexLabel
import com.mailhub.client.ui.swiss.LoadingRule
import com.mailhub.client.ui.swiss.LocalSwissColors
import com.mailhub.client.ui.swiss.SText
import com.mailhub.client.ui.swiss.SectionHeader
import com.mailhub.client.ui.swiss.SwissButton
import com.mailhub.client.ui.swiss.SwissButtonKind
import com.mailhub.client.ui.swiss.SwissSpace
import com.mailhub.client.ui.swiss.SwissTextButton
import com.mailhub.client.ui.swiss.SwissTextField
import com.mailhub.client.ui.swiss.SwissType
import com.mailhub.client.ui.swiss.TableRow
import com.mailhub.client.ui.swiss.cols

/** 服务商没有任何可用认证方式时的说明。 */
private const val NoMethodsText: String = "当前部署没有可用的登录方式，请联系管理员在设置页配置。"

/** 浏览器 OAuth 等待中的说明。 */
private const val BrowserWaitingText: String = "已在系统浏览器里打开授权页，完成登录后会自动回到这里。"

/** 浏览器 OAuth 的最长等待时间说明。 */
private const val BrowserTimeoutHint: String = "等待结果最长 10 分钟。"

/** 设备码页面的说明。 */
private const val DeviceCodeHint: String = "在浏览器里输入这段设备码完成登录。"

/** 授权码提交中的说明：后端要先探测 IMAP，确实要等。 */
private const val SecretSubmittingHint: String = "正在连接邮箱服务器，可能要等十几秒。"

/**
 * 添加账户主体（无状态，不含页头与外壳）。分三步，每步一个编号区块：
 * 01 邮箱地址（按域名识别服务商，识别不到就显示自定义 IMAP 并多出主机、端口）、
 * 02 选择方式（按后端 methods 顺序编号 01 02 …，unavailable / oauth_hint 原样显示）、
 * 03 执行（授权码 / 浏览器 OAuth / 设备码）。
 *
 * 走到后面的步骤时，前面的步骤收成只读的键值行，整页始终按 01 → 02 → 03 的顺序读下来。
 */
@Composable
fun AddAccountBody(
    state: AddAccountUiState,
    onEvent: (AddAccountEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val addedLine = state.addedLine
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        if (addedLine != null) {
            AddedSection(line = addedLine, onEvent = onEvent)
        } else {
            EmailSection(state = state, onEvent = onEvent)
            if (state.step != AddStep.Email) {
                Spacer(modifier = Modifier.height(SwissSpace.L))
                MethodSection(state = state, onEvent = onEvent)
            }
            if (state.step == AddStep.Execute) {
                Spacer(modifier = Modifier.height(SwissSpace.L))
                ExecuteSection(state = state, onEvent = onEvent)
            }
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xxl))
    }
}

/** 第 1 步：邮箱地址。输入后立刻显示识别出的服务商。 */
@Composable
private fun EmailSection(state: AddAccountUiState, onEvent: (AddAccountEvent) -> Unit) {
    val colors = LocalSwissColors.current
    val target = state.target
    SectionHeader(index = AddStep.Email.index, title = AddStep.Email.title)
    Spacer(modifier = Modifier.height(SwissSpace.S))
    if (state.step == AddStep.Email) {
        GridRow {
            SwissTextField(
                value = state.email,
                onValueChange = { onEvent(AddAccountEvent.EmailChanged(it)) },
                label = "邮箱地址",
                placeholder = "you@example.com",
                enabled = !state.submitting,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onDone = { onEvent(AddAccountEvent.ContinueEmail) }),
                modifier = Modifier.cols(1, 10),
            )
        }
        Spacer(modifier = Modifier.height(SwissSpace.M))
        when {
            state.loadingProviders -> LoadingRule()
            target != null -> TableRow(label = "服务商", value = target.label)
        }
        if (target != null && target.isCustom) {
            Spacer(modifier = Modifier.height(SwissSpace.M))
            GridRow {
                SwissTextField(
                    value = state.imapHost,
                    onValueChange = { onEvent(AddAccountEvent.HostChanged(it)) },
                    label = "IMAP 服务器地址",
                    placeholder = "imap.example.com",
                    enabled = !state.submitting,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.cols(1, 8),
                )
                SwissTextField(
                    value = state.imapPort,
                    onValueChange = { onEvent(AddAccountEvent.PortChanged(it)) },
                    label = "端口",
                    placeholder = "993",
                    enabled = !state.submitting,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.cols(9, 4),
                )
            }
        }
        val error = state.error
        if (error != null) {
            Spacer(modifier = Modifier.height(SwissSpace.M))
            GridRow {
                SText(
                    text = error,
                    style = SwissType.body(),
                    modifier = Modifier.cols(1, 10),
                    color = colors.foreground,
                )
            }
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xl))
        GridRow {
            SwissButton(
                text = "继续",
                onClick = { onEvent(AddAccountEvent.ContinueEmail) },
                kind = SwissButtonKind.Primary,
                enabled = target != null && !state.loadingProviders && !state.submitting,
                modifier = Modifier.cols(1, 6),
            )
        }
    } else {
        TableRow(label = "邮箱", value = state.email)
        TableRow(label = "服务商", value = target?.label.orEmpty())
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        GridRow {
            SwissTextButton(
                text = "改邮箱 →",
                onClick = { onEvent(AddAccountEvent.BackToEmail) },
                modifier = Modifier.cols(3, 4),
            )
        }
    }
}

/** 第 2 步：认证方式，按后端下发的顺序编号；说明文字原样显示。 */
@Composable
private fun MethodSection(state: AddAccountUiState, onEvent: (AddAccountEvent) -> Unit) {
    val colors = LocalSwissColors.current
    val rows = state.methodRows
    val notice = state.notice
    SectionHeader(index = AddStep.Method.index, title = AddStep.Method.title)
    Spacer(modifier = Modifier.height(SwissSpace.S))
    if (notice.isNotEmpty()) {
        GridRow {
            SText(
                text = notice,
                style = SwissType.body(),
                modifier = Modifier.cols(1, 10),
                color = colors.foreground,
            )
        }
        Spacer(modifier = Modifier.height(SwissSpace.M))
    }
    when {
        state.loadingProviders -> LoadingRule()
        rows.isEmpty() -> GridRow {
            SText(
                text = NoMethodsText,
                style = SwissType.body(),
                modifier = Modifier.cols(1, 10),
                color = colors.foreground,
            )
        }

        state.step == AddStep.Method -> rows.forEachIndexed { index, row ->
            MethodOption(
                index = row.index,
                label = row.method.label,
                selected = index == state.selectedMethod,
                onClick = { onEvent(AddAccountEvent.SelectMethod(index)) },
            )
        }

        else -> {
            val chosen = state.chosen
            if (chosen != null) {
                val chosenIndex = rows.getOrNull(state.selectedMethod)?.index.orEmpty()
                MethodOption(
                    index = chosenIndex,
                    label = chosen.label,
                    selected = true,
                    onClick = {},
                )
            }
        }
    }
}

/** 一个认证方式：编号在第 1–2 栏，名称在第 3–9 栏，整行可点，触控区不低于 48dp。 */
@Composable
private fun MethodOption(
    index: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalSwissColors.current
    val interactionSource = remember { MutableInteractionSource() }
    GridRow(
        modifier = Modifier
            .sizeIn(minHeight = SwissSpace.Xl)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            ),
    ) {
        IndexLabel(
            index = index,
            modifier = Modifier
                .cols(1, 2)
                .padding(
                    top = baselineTopPadding(
                        fromStyle = SwissType.label(),
                        fromText = index,
                        toStyle = SwissType.body(),
                        toText = label,
                    ),
                ),
        )
        SText(
            text = label,
            style = SwissType.body().copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            ),
            modifier = Modifier.cols(3, 9),
            color = colors.foreground,
        )
    }
}

/** 第 3 步：按下发的认证方式执行。 */
@Composable
private fun ExecuteSection(state: AddAccountUiState, onEvent: (AddAccountEvent) -> Unit) {
    val colors = LocalSwissColors.current
    SectionHeader(index = AddStep.Execute.index, title = AddStep.Execute.title)
    Spacer(modifier = Modifier.height(SwissSpace.S))
    // 失败说明用后端返回的 detail，三种方式共用同一处展示
    val error = state.error
    if (error != null) {
        GridRow {
            SText(
                text = error,
                style = SwissType.body(),
                modifier = Modifier.cols(1, 10),
                color = colors.foreground,
            )
        }
        Spacer(modifier = Modifier.height(SwissSpace.M))
    }
    val method = state.chosen
    if (method == null) {
        GridRow {
            SText(
                text = NoMethodsText,
                style = SwissType.body(),
                modifier = Modifier.cols(1, 10),
                color = colors.foreground,
            )
        }
        return
    }
    when {
        method.usesSecret -> SecretBody(state = state, method = method, onEvent = onEvent)
        method.type == MethodOAuthBrowser -> BrowserBody(state = state, onEvent = onEvent)
        method.type == MethodOAuthDevice -> DeviceBody(state = state, onEvent = onEvent)
        else -> GridRow {
            SText(
                text = NoMethodsText,
                style = SwissType.body(),
                modifier = Modifier.cols(1, 10),
                color = colors.foreground,
            )
        }
    }
}

/** 授权码 / 密码：编号步骤、setup_label 按钮、凭据输入框，提交期间显示加载条。 */
@Composable
private fun SecretBody(
    state: AddAccountUiState,
    method: AuthMethod,
    onEvent: (AddAccountEvent) -> Unit,
) {
    val colors = LocalSwissColors.current
    method.steps.forEachIndexed { index, step ->
        StepLine(
            index = (index + 1).toString().padStart(2, '0'),
            text = step,
        )
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
    }
    if (method.setupUrl.isNotEmpty()) {
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        GridRow {
            SwissTextButton(
                text = "${method.setupLabel.ifBlank { "打开服务商设置" }} →",
                onClick = { onEvent(AddAccountEvent.OpenSetup) },
                modifier = Modifier.cols(3, 7),
            )
        }
    }
    Spacer(modifier = Modifier.height(SwissSpace.M))
    GridRow {
        SwissTextField(
            value = state.secret,
            onValueChange = { onEvent(AddAccountEvent.SecretChanged(it)) },
            label = method.inputLabel.ifBlank { method.label },
            enabled = !state.submitting,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onEvent(AddAccountEvent.SubmitSecret) }),
            modifier = Modifier.cols(1, 10),
        )
    }
    Spacer(modifier = Modifier.height(SwissSpace.M))
    GridRow {
        SwissButton(
            text = "添加账户",
            onClick = { onEvent(AddAccountEvent.SubmitSecret) },
            kind = SwissButtonKind.Primary,
            enabled = !state.submitting,
            modifier = Modifier.cols(1, 6),
        )
    }
    if (state.submitting) {
        Spacer(modifier = Modifier.height(SwissSpace.S))
        LoadingRule()
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        GridRow {
            SText(
                text = SecretSubmittingHint,
                style = SwissType.caption(),
                modifier = Modifier.cols(1, 10),
                color = colors.foreground,
            )
        }
    }
}

/** 浏览器 OAuth：授权页已经在系统浏览器里打开，这里只报进度。 */
@Composable
private fun BrowserBody(state: AddAccountUiState, onEvent: (AddAccountEvent) -> Unit) {
    val colors = LocalSwissColors.current
    if (state.waitingBrowser) {
        GridRow {
            SText(
                text = BrowserWaitingText,
                style = SwissType.body(),
                modifier = Modifier.cols(1, 10),
                color = colors.foreground,
            )
        }
        Spacer(modifier = Modifier.height(SwissSpace.S))
        LoadingRule()
    } else {
        GridRow {
            SwissButton(
                text = "打开浏览器授权",
                onClick = { onEvent(AddAccountEvent.Retry) },
                kind = SwissButtonKind.Primary,
                enabled = !state.submitting,
                modifier = Modifier.cols(1, 8),
            )
        }
    }
    Spacer(modifier = Modifier.height(SwissSpace.S))
    GridRow {
        SText(
            text = BrowserTimeoutHint,
            style = SwissType.caption(),
            modifier = Modifier.cols(1, 10),
            color = colors.foreground,
        )
    }
}

/** 设备码：user_code 用 displaySmall 的等宽数字，下面给「复制 →」和「打开登录页 →」。 */
@Composable
private fun DeviceBody(state: AddAccountUiState, onEvent: (AddAccountEvent) -> Unit) {
    val colors = LocalSwissColors.current
    val device = state.deviceCode
    if (device == null) {
        GridRow {
            SwissButton(
                text = "获取设备码",
                onClick = { onEvent(AddAccountEvent.Retry) },
                kind = SwissButtonKind.Primary,
                enabled = !state.submitting,
                modifier = Modifier.cols(1, 8),
            )
        }
        return
    }
    GridRow {
        SText(
            text = "设备码",
            style = SwissType.label(),
            modifier = Modifier.cols(1, 12),
            color = colors.foreground,
            maxLines = 1,
        )
    }
    Spacer(modifier = Modifier.height(SwissSpace.Xs))
    GridRow {
        Column(modifier = Modifier.cols(1, 12)) {
            deviceCodeLines(device.code).forEach { line ->
                SText(
                    text = line,
                    style = SwissType.displaySmall(),
                    color = colors.foreground,
                    maxLines = 1,
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(SwissSpace.S))
    GridRow {
        SText(
            text = DeviceCodeHint,
            style = SwissType.caption(),
            modifier = Modifier.cols(1, 10),
            color = colors.foreground,
        )
    }
    Spacer(modifier = Modifier.height(SwissSpace.S))
    GridRow {
        SwissTextButton(
            text = "复制 →",
            onClick = { onEvent(AddAccountEvent.CopyUserCode) },
            modifier = Modifier.cols(1, 3),
        )
        SwissTextButton(
            text = "打开登录页 →",
            onClick = { onEvent(AddAccountEvent.OpenVerification) },
            modifier = Modifier.cols(5, 4),
        )
    }
    if (device.copied) {
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        GridRow {
            SText(
                text = DeviceCodeCopiedText,
                style = SwissType.caption(),
                modifier = Modifier.cols(1, 10),
                color = colors.foreground,
            )
        }
    }
    Spacer(modifier = Modifier.height(SwissSpace.M))
    LoadingRule()
}

/** 成功之后的一屏：粗线 + 一行「已添加 A? 名称」，下面是重新开始。 */
@Composable
private fun AddedSection(line: String, onEvent: (AddAccountEvent) -> Unit) {
    val colors = LocalSwissColors.current
    HeavyRule(color = colors.foreground)
    Spacer(modifier = Modifier.height(SwissSpace.M))
    GridRow {
        SText(
            text = line,
            style = SwissType.headline(),
            modifier = Modifier.cols(1, 12),
            color = colors.foreground,
        )
    }
    Spacer(modifier = Modifier.height(SwissSpace.S))
    GridRow {
        SText(
            text = "账户列表已刷新。",
            style = SwissType.caption(),
            modifier = Modifier.cols(1, 12),
            color = colors.foreground,
        )
    }
    Spacer(modifier = Modifier.height(SwissSpace.Xl))
    GridRow {
        SwissButton(
            text = "再添加一个",
            onClick = { onEvent(AddAccountEvent.StartOver) },
            kind = SwissButtonKind.Outline,
            modifier = Modifier.cols(1, 6),
        )
    }
}

/** 编号列表里的一行：编号在第 1–2 栏，说明在第 3–10 栏。 */
@Composable
private fun StepLine(index: String, text: String) {
    val colors = LocalSwissColors.current
    GridRow {
        IndexLabel(
            index = index,
            modifier = Modifier
                .cols(1, 2)
                .padding(
                    top = baselineTopPadding(
                        fromStyle = SwissType.label(),
                        fromText = index,
                        toStyle = SwissType.body(),
                        toText = text,
                    ),
                ),
        )
        SText(
            text = text,
            style = SwissType.body(),
            modifier = Modifier.cols(3, 8),
            color = colors.foreground,
        )
    }
}

