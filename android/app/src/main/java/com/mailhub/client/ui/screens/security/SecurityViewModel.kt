package com.mailhub.client.ui.screens.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mailhub.client.data.model.Capabilities
import com.mailhub.client.data.security.DeviceSession
import com.mailhub.client.data.security.TrashItem
import com.mailhub.client.ui.screens.common.apiErrorText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 安全中心状态机：四块各自加载；回收站多选恢复；审计撤销与分页；设备吊销。
 *
 * - 吊销单台设备、退出其他所有设备都必须先经过确认面板；
 * - 本机不提供吊销；
 * - 退出其他所有设备后用返回的新令牌原地换会话；
 * - [capabilities] 决定有没有「已登录设备」这一块，也就决定会不会去请求
 *   `/api/sessions`（见 [SecurityUiState.sections]）；它在创建时取好，运行期间不会改变。
 */
class SecurityViewModel(
    private val gateway: SecurityGateway,
    capabilities: Capabilities = Capabilities.Server,
) : ViewModel() {

    private val _state = MutableStateFlow(SecurityUiState(capabilities = capabilities))
    val state: StateFlow<SecurityUiState> = _state.asStateFlow()

    /** 页面每次进入都重新拉当前模式下可见的块：安全信息要新鲜。 */
    fun onEnter() {
        _state.value.sections.forEach { load(it) }
    }

    fun onEvent(event: SecurityEvent) {
        when (event) {
            is SecurityEvent.Retry -> load(event.section)
            // 打开详情由外壳处理
            is SecurityEvent.OpenMessage -> Unit
            is SecurityEvent.ToggleTrash -> _state.update {
                val selected = it.selectedTrash
                it.copy(
                    selectedTrash = if (event.messageId in selected) {
                        selected - event.messageId
                    } else {
                        selected + event.messageId
                    },
                    notices = it.notices - SecuritySection.Trash,
                )
            }

            SecurityEvent.ClearTrashSelection -> _state.update { it.copy(selectedTrash = emptySet()) }
            SecurityEvent.RestoreSelected -> restoreSelected()
            is SecurityEvent.Undo -> undo(event.auditId)
            SecurityEvent.LoadMoreAudit -> loadMoreAudit()
            is SecurityEvent.RequestRevoke -> requestRevoke(event.sessionId)
            SecurityEvent.RequestRevokeAll -> _state.update { it.copy(confirming = SecurityConfirm.RevokeAll) }
            SecurityEvent.Confirm -> confirm()
            SecurityEvent.DismissConfirm -> _state.update { it.copy(confirming = null) }
        }
    }

    private fun load(section: SecuritySection) {
        // 能力表里没有「已登录设备」：重试请求也不能把它拉回来
        if (section !in _state.value.sections) return
        when (section) {
            SecuritySection.Review -> refresh(section, { it.review }, { s, v -> s.copy(review = v) }) {
                gateway.review()
            }

            SecuritySection.Trash -> refresh(section, { it.trash }, ::withTrash) { gateway.trash() }
            SecuritySection.Audit -> refresh(section, { it.audit }, { s, v -> s.copy(audit = v) }) {
                gateway.audit(offset = 0).map { AuditList(items = it.items, total = it.total) }
            }

            SecuritySection.Devices -> refresh(section, { it.devices }, { s, v -> s.copy(devices = v) }) {
                gateway.devices()
            }
        }
    }

    /**
     * 刷新一块：还没有内容时显示加载态；已经有内容时保留旧内容直到新结果回来（不闪）。
     * 刷新失败时，没有内容就显示错误态，有内容就保留内容、在这一块末尾给出说明。
     */
    private fun <T> refresh(
        section: SecuritySection,
        read: (SecurityUiState) -> Loadable<T>,
        write: (SecurityUiState, Loadable<T>) -> SecurityUiState,
        fetch: suspend () -> Result<T>,
    ) {
        _state.update { if (read(it) is Loadable.Ready) it else write(it, Loadable.Loading) }
        viewModelScope.launch {
            val result = fetch()
            _state.update { state ->
                result.fold(
                    onSuccess = { write(state, Loadable.Ready(it)) },
                    onFailure = { error ->
                        val text = apiErrorText(error)
                        if (read(state) is Loadable.Ready) {
                            state.copy(notices = state.notices + (section to text))
                        } else {
                            write(state, Loadable.Failed(text))
                        }
                    },
                )
            }
        }
    }

    /** 换上新的回收站列表；已经不在回收站里的邮件不能还算选中。 */
    private fun withTrash(state: SecurityUiState, trash: Loadable<List<TrashItem>>): SecurityUiState {
        val ids = trash.valueOrNull()?.map { it.id }?.toSet() ?: return state.copy(trash = trash)
        return state.copy(trash = trash, selectedTrash = state.selectedTrash intersect ids)
    }

    private fun restoreSelected() {
        val current = _state.value
        val trash = current.trash.valueOrNull() ?: return
        // 按列表顺序发出 ids，请求内容稳定、可预期
        val ids = trash.map { it.id }.filter { it in current.selectedTrash }
        if (ids.isEmpty() || current.busy) return
        startAction(SecuritySection.Trash)
        viewModelScope.launch {
            gateway.restore(ids).fold(
                onSuccess = { result ->
                    _state.update { state ->
                        state.copy(
                            busy = false,
                            trash = Loadable.Ready(trash.filterNot { it.id in ids }),
                            selectedTrash = emptySet(),
                            notices = state.notices + (SecuritySection.Trash to "已恢复 ${result.count} 封"),
                        )
                    }
                    // 恢复会写一条审计记录
                    load(SecuritySection.Audit)
                },
                onFailure = { error -> finishWithNotice(SecuritySection.Trash, apiErrorText(error)) },
            )
        }
    }

    private fun undo(auditId: Long) {
        if (_state.value.busy) return
        startAction(SecuritySection.Audit)
        viewModelScope.launch {
            gateway.undo(auditId).fold(
                onSuccess = { result ->
                    finishWithNotice(SecuritySection.Audit, "已撤销，恢复 ${result.restored} 封")
                    // 撤销会改审计记录本身，也可能把邮件从回收站里拿回来
                    load(SecuritySection.Audit)
                    load(SecuritySection.Trash)
                },
                onFailure = { error -> finishWithNotice(SecuritySection.Audit, apiErrorText(error)) },
            )
        }
    }

    private fun loadMoreAudit() {
        val list = _state.value.audit.valueOrNull() ?: return
        if (!list.hasMore || list.loadingMore) return
        _state.update { it.copy(audit = Loadable.Ready(list.copy(loadingMore = true))) }
        viewModelScope.launch {
            gateway.audit(offset = list.items.size).fold(
                onSuccess = { page ->
                    _state.update { state ->
                        val now = state.audit.valueOrNull() ?: list
                        val known = now.items.map { it.id }.toSet()
                        // 翻页期间有新记录插到最前面时，后一页会和已有的重叠，按 id 去重
                        val merged = now.items + page.items.filterNot { it.id in known }
                        state.copy(audit = Loadable.Ready(AuditList(items = merged, total = page.total)))
                    }
                },
                onFailure = { error ->
                    _state.update { state ->
                        val now = state.audit.valueOrNull() ?: list
                        state.copy(
                            audit = Loadable.Ready(now.copy(loadingMore = false)),
                            notices = state.notices + (SecuritySection.Audit to apiErrorText(error)),
                        )
                    }
                },
            )
        }
    }

    private fun requestRevoke(sessionId: String) {
        val session = _state.value.devices.valueOrNull()?.firstOrNull { it.id == sessionId } ?: return
        // 本机不能在这里吊销：要退出本机请用「退出登录」
        if (session.current) return
        _state.update { it.copy(confirming = SecurityConfirm.RevokeDevice(session)) }
    }

    /** 只有确认面板里的确认按钮会走到这里。 */
    private fun confirm() {
        val current = _state.value
        val confirming = current.confirming ?: return
        if (current.busy) return
        _state.update { it.copy(confirming = null) }
        when (confirming) {
            is SecurityConfirm.RevokeDevice -> revoke(confirming.session)
            SecurityConfirm.RevokeAll -> revokeAll()
        }
    }

    private fun revoke(session: DeviceSession) {
        startAction(SecuritySection.Devices)
        viewModelScope.launch {
            gateway.revoke(session.id).fold(
                onSuccess = {
                    _state.update { state ->
                        val devices = state.devices.valueOrNull().orEmpty().filterNot { it.id == session.id }
                        state.copy(
                            busy = false,
                            devices = Loadable.Ready(devices),
                            notices = state.notices +
                                (SecuritySection.Devices to "已吊销「${deviceName(session)}」"),
                        )
                    }
                },
                onFailure = { error -> finishWithNotice(SecuritySection.Devices, apiErrorText(error)) },
            )
        }
    }

    private fun revokeAll() {
        startAction(SecuritySection.Devices)
        viewModelScope.launch {
            gateway.revokeAll().fold(
                onSuccess = { result ->
                    // 后端已吊销全部会话，本机靠返回的新令牌继续使用；换不上就只能重新登录
                    val replaced = result.token.isNotBlank() &&
                        result.sessionId.isNotBlank() &&
                        gateway.replaceToken(result.token, result.sessionId, result.expiresTs)
                    finishWithNotice(SecuritySection.Devices, revokeAllText(result.revoked, replaced))
                    if (replaced) {
                        load(SecuritySection.Devices)
                    }
                },
                onFailure = { error -> finishWithNotice(SecuritySection.Devices, apiErrorText(error)) },
            )
        }
    }

    private fun startAction(section: SecuritySection) {
        _state.update { it.copy(busy = true, notices = it.notices - section) }
    }

    private fun finishWithNotice(section: SecuritySection, text: String) {
        _state.update { it.copy(busy = false, notices = it.notices + (section to text)) }
    }
}

/** 手工工厂：宿主用页面自己的 Repository 组装网关。 */
class SecurityViewModelFactory(
    private val gateway: SecurityGateway,
    private val capabilities: Capabilities = Capabilities.Server,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return SecurityViewModel(gateway, capabilities) as T
    }
}
