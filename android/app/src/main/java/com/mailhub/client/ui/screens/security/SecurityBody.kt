package com.mailhub.client.ui.screens.security

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.mailhub.client.data.security.AuditEntry
import com.mailhub.client.data.security.DeviceSession
import com.mailhub.client.ui.screens.common.LocalMailNow
import com.mailhub.client.ui.screens.common.MailRow
import com.mailhub.client.ui.screens.common.mailStampText
import com.mailhub.client.ui.swiss.ConfirmPanel
import com.mailhub.client.ui.swiss.EmptyState
import com.mailhub.client.ui.swiss.ErrorState
import com.mailhub.client.ui.swiss.GridRow
import com.mailhub.client.ui.swiss.HairlineRule
import com.mailhub.client.ui.swiss.LoadingRule
import com.mailhub.client.ui.swiss.LocalSwissColors
import com.mailhub.client.ui.swiss.SText
import com.mailhub.client.ui.swiss.SectionHeader
import com.mailhub.client.ui.swiss.SwissButton
import com.mailhub.client.ui.swiss.SwissButtonKind
import com.mailhub.client.ui.swiss.SwissSnackbar
import com.mailhub.client.ui.swiss.SwissSpace
import com.mailhub.client.ui.swiss.SwissTextButton
import com.mailhub.client.ui.swiss.SwissType
import com.mailhub.client.ui.swiss.baselineTopPadding
import com.mailhub.client.ui.swiss.cols

/**
 * 安全中心主体（无状态，不含页头与外壳）。服务器模式四个编号区块从上往下读：
 * 01 风险观察、02 回收站（多选恢复）、03 审计（撤销、分页）、04 已登录设备（吊销）。
 * 本机模式没有 04（`LOCAL_MODE.md` 附录 A），可见的块由 [SecurityUiState.sections] 给出。
 * 吊销单台设备与退出其他所有设备都先弹确认面板。
 *
 * @param categories /api/meta 的 display_categories，用来给分类编号，与收件箱一致。
 * @param header 前置内容插槽：外壳放页面大标题，作为滚动内容的第一项跟着一起滚走。
 */
@Composable
fun SecurityBody(
    state: SecurityUiState,
    zoneId: String,
    onEvent: (SecurityEvent) -> Unit,
    modifier: Modifier = Modifier,
    categories: List<String> = emptyList(),
    header: @Composable () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            header()
            // 本机模式没有「已登录设备」（SecurityUiState.sections 按模式给出可见的块）
            state.sections.forEachIndexed { position, section ->
                if (position > 0) {
                    Spacer(modifier = Modifier.height(SwissSpace.Xl))
                }
                SectionHeader(index = section.index, title = section.title)
                Spacer(modifier = Modifier.height(SwissSpace.S))
                when (section) {
                    SecuritySection.Review -> ReviewSection(state, zoneId, onEvent)
                    SecuritySection.Trash -> TrashSection(state, zoneId, categories, onEvent)
                    SecuritySection.Audit -> AuditSection(state, zoneId, onEvent)
                    SecuritySection.Devices -> DevicesSection(state, zoneId, onEvent)
                }
                state.notices[section]?.let { Notice(it) }
            }
            Spacer(modifier = Modifier.height(SwissSpace.Xxl))
        }
        ConfirmLayer(confirming = state.confirming, onEvent = onEvent)
    }
}

/** 一块的公共外壳：加载中是细线，失败是带「重试」的错误态，有内容才交给 [content]。 */
@Composable
private fun <T> SectionContent(
    value: Loadable<T>,
    section: SecuritySection,
    onEvent: (SecurityEvent) -> Unit,
    content: @Composable (T) -> Unit,
) {
    when (value) {
        Loadable.Loading -> LoadingRule()
        is Loadable.Failed -> ErrorState(
            message = value.message,
            onRetry = { onEvent(SecurityEvent.Retry(section)) },
        )

        is Loadable.Ready -> content(value.value)
    }
}

@Composable
private fun ReviewSection(state: SecurityUiState, zoneId: String, onEvent: (SecurityEvent) -> Unit) {
    SectionContent(state.review, SecuritySection.Review, onEvent) { items ->
        if (items.isEmpty()) {
            EmptyState(
                index = SecuritySection.Review.index,
                title = "没有风险邮件",
                body = "被标为可疑或中高风险的邮件会出现在这里。",
            )
        } else {
            items.forEach { item ->
                MailRow(
                    data = reviewRowOf(item),
                    zoneId = zoneId,
                    onClick = { onEvent(SecurityEvent.OpenMessage(item.id)) },
                )
            }
            HairlineRule(color = LocalSwissColors.current.foreground)
        }
    }
}

@Composable
private fun TrashSection(
    state: SecurityUiState,
    zoneId: String,
    categories: List<String>,
    onEvent: (SecurityEvent) -> Unit,
) {
    SectionContent(state.trash, SecuritySection.Trash, onEvent) { items ->
        if (items.isEmpty()) {
            Caption(text = "回收站是空的。")
            return@SectionContent
        }
        Caption(text = "点选要恢复的邮件；时间是移入回收站的时间。")
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        items.forEach { item ->
            MailRow(
                data = trashRowOf(item, categories),
                zoneId = zoneId,
                selected = item.id in state.selectedTrash,
                onClick = { onEvent(SecurityEvent.ToggleTrash(item.id)) },
            )
        }
        HairlineRule(color = LocalSwissColors.current.foreground)
        val count = state.selectedTrash.size
        if (count > 0) {
            Spacer(modifier = Modifier.height(SwissSpace.S))
            GridRow {
                SwissButton(
                    text = "恢复 $count 封",
                    onClick = { onEvent(SecurityEvent.RestoreSelected) },
                    kind = SwissButtonKind.Primary,
                    enabled = !state.busy,
                    modifier = Modifier.cols(1, 6),
                )
                SwissButton(
                    text = "取消选择",
                    onClick = { onEvent(SecurityEvent.ClearTrashSelection) },
                    kind = SwissButtonKind.Outline,
                    modifier = Modifier.cols(7, 6),
                )
            }
        }
    }
}

@Composable
private fun AuditSection(state: SecurityUiState, zoneId: String, onEvent: (SecurityEvent) -> Unit) {
    SectionContent(state.audit, SecuritySection.Audit, onEvent) { list ->
        if (list.items.isEmpty()) {
            Caption(text = "暂无操作记录。")
            return@SectionContent
        }
        list.items.forEach { entry ->
            AuditRow(entry = entry, zoneId = zoneId, busy = state.busy, onEvent = onEvent)
        }
        HairlineRule(color = LocalSwissColors.current.foreground)
        when {
            list.loadingMore -> {
                Spacer(modifier = Modifier.height(SwissSpace.S))
                LoadingRule()
            }

            list.hasMore -> GridRow {
                SwissTextButton(
                    text = "更多 →",
                    onClick = { onEvent(SecurityEvent.LoadMoreAudit) },
                    modifier = Modifier.cols(2, 4),
                    labelModifier = Modifier.padding(top = SwissSpace.Xs),
                )
            }
        }
    }
}

/**
 * 一条审计记录：等级字母在第 1 栏，动作与原因在第 2–9 栏，时间在第 10–12 栏；
 * 可撤销的记录下面是「撤销 →」，已撤销的写「已撤销」。三段文字按首行基线对齐。
 */
@Composable
private fun AuditRow(entry: AuditEntry, zoneId: String, busy: Boolean, onEvent: (SecurityEvent) -> Unit) {
    val colors = LocalSwissColors.current
    val actionStyle = SwissType.body().copy(fontWeight = FontWeight.Bold)
    val action = auditActionText(entry)
    val tier = auditTierText(entry)
    val time = mailStampText(entry.ts, zoneId, LocalMailNow.current)
    val reason = auditReasonText(entry)
    Column(modifier = Modifier.fillMaxWidth()) {
        HairlineRule(color = colors.foreground)
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        GridRow {
            SText(
                text = tier,
                style = SwissType.label(),
                modifier = Modifier
                    .cols(1, 1)
                    .padding(top = baselineTopPadding(SwissType.label(), tier, actionStyle, action)),
                color = colors.foreground,
                maxLines = 1,
            )
            SText(
                text = action,
                style = actionStyle,
                modifier = Modifier.cols(2, 8),
                color = colors.foreground,
                maxLines = 1,
            )
            SText(
                text = time,
                style = SwissType.caption(),
                modifier = Modifier
                    .cols(10, 3)
                    .padding(top = baselineTopPadding(SwissType.caption(), time, actionStyle, action)),
                color = colors.foreground,
                maxLines = 1,
            )
        }
        if (reason.isNotBlank()) {
            Spacer(modifier = Modifier.height(SwissSpace.Xxs))
            GridRow {
                SText(
                    text = reason,
                    style = SwissType.caption(),
                    modifier = Modifier.cols(2, 8),
                    color = colors.foreground,
                    maxLines = 3,
                )
            }
        }
        when (auditStatusOf(entry)) {
            AuditStatus.Undoable -> GridRow {
                SwissTextButton(
                    text = "撤销 →",
                    onClick = { onEvent(SecurityEvent.Undo(entry.id)) },
                    enabled = !busy,
                    modifier = Modifier.cols(2, 3),
                    labelModifier = Modifier.padding(top = SwissSpace.Xs),
                )
            }

            AuditStatus.Undone -> {
                Spacer(modifier = Modifier.height(SwissSpace.Xs))
                GridRow {
                    SText(
                        text = "已撤销",
                        style = SwissType.label(),
                        modifier = Modifier.cols(2, 3),
                        color = colors.foreground,
                    )
                }
                Spacer(modifier = Modifier.height(SwissSpace.Xs))
            }

            AuditStatus.Blocked, AuditStatus.Final -> Spacer(modifier = Modifier.height(SwissSpace.Xs))
        }
    }
}

@Composable
private fun DevicesSection(state: SecurityUiState, zoneId: String, onEvent: (SecurityEvent) -> Unit) {
    SectionContent(state.devices, SecuritySection.Devices, onEvent) { devices ->
        devices.forEach { session ->
            DeviceRow(session = session, zoneId = zoneId, busy = state.busy, onEvent = onEvent)
        }
        HairlineRule(color = LocalSwissColors.current.foreground)
        // 只有本机时没有可退出的设备，按钮不出现
        if (devices.any { !it.current }) {
            Spacer(modifier = Modifier.height(SwissSpace.M))
            GridRow {
                SwissButton(
                    text = "退出其他所有设备",
                    onClick = { onEvent(SecurityEvent.RequestRevokeAll) },
                    kind = SwissButtonKind.Outline,
                    enabled = !state.busy,
                    modifier = Modifier.cols(1, 8),
                )
            }
        }
    }
}

/**
 * 一台设备：设备名第 1–8 栏、最近活动时间第 9–12 栏；
 * 下一行是客户端类型（本机再标「本机」），其他设备在第 9–12 栏给出「吊销 →」。
 */
@Composable
private fun DeviceRow(session: DeviceSession, zoneId: String, busy: Boolean, onEvent: (SecurityEvent) -> Unit) {
    val colors = LocalSwissColors.current
    val nameStyle = SwissType.body().copy(fontWeight = FontWeight.Bold)
    val name = deviceName(session)
    val seen = mailStampText(deviceSeenTs(session), zoneId, LocalMailNow.current)
    val meta = deviceMetaText(session)
    Column(modifier = Modifier.fillMaxWidth()) {
        HairlineRule(color = colors.foreground)
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        GridRow {
            SText(
                text = name,
                style = nameStyle,
                modifier = Modifier.cols(1, 8),
                color = colors.foreground,
                maxLines = 1,
            )
            SText(
                text = seen,
                style = SwissType.caption(),
                modifier = Modifier
                    .cols(9, 4)
                    .padding(top = baselineTopPadding(SwissType.caption(), seen, nameStyle, name)),
                color = colors.foreground,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xxs))
        GridRow {
            SText(
                text = meta,
                style = SwissType.caption(),
                modifier = Modifier.cols(1, 8),
                color = colors.foreground,
                maxLines = 1,
            )
            if (!session.current) {
                SwissTextButton(
                    text = "吊销 →",
                    onClick = { onEvent(SecurityEvent.RequestRevoke(session.id)) },
                    enabled = !busy,
                    modifier = Modifier.cols(9, 4),
                    labelModifier = Modifier.padding(
                        top = baselineTopPadding(SwissType.label(), "吊销 →", SwissType.caption(), meta),
                    ),
                )
            }
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
    }
}

/** 单行 caption 说明，占第 1–12 栏。 */
@Composable
private fun Caption(text: String) {
    GridRow {
        SText(
            text = text,
            style = SwissType.caption(),
            modifier = Modifier.cols(1, 12),
            color = LocalSwissColors.current.foreground,
        )
    }
}

/** 一块末尾的操作结果。 */
@Composable
private fun Notice(text: String) {
    Spacer(modifier = Modifier.height(SwissSpace.S))
    SwissSnackbar(message = text)
}

/** 吊销单台设备 / 退出其他所有设备的确认面板。 */
@Composable
private fun ConfirmLayer(confirming: SecurityConfirm?, onEvent: (SecurityEvent) -> Unit) {
    val (title, message, confirmText) = when (confirming) {
        is SecurityConfirm.RevokeDevice -> Triple(revokeDeviceTitle(confirming.session), RevokeDeviceMessage, "吊销")
        SecurityConfirm.RevokeAll -> Triple(RevokeAllTitle, RevokeAllMessage, "全部退出")
        null -> return
    }
    ConfirmPanel(
        title = title,
        message = message,
        confirmText = confirmText,
        onConfirm = { onEvent(SecurityEvent.Confirm) },
        onCancel = { onEvent(SecurityEvent.DismissConfirm) },
        danger = true,
    )
}
