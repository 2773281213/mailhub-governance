package com.mailhub.client.ui.screens.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mailhub.client.ui.screens.common.accountLabel
import com.mailhub.client.ui.screens.common.apiErrorText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 账户列表状态机：加载、展开操作行、同步 / 测试 / 删除。
 * 删除必须先经过确认面板，确认之后才发 DELETE（列表里不会有别的删除入口）。
 */
class AccountsViewModel(
    private val gateway: AccountsGateway,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountsUiState())
    val state: StateFlow<AccountsUiState> = _state.asStateFlow()

    /** 页面每次进入调用一次：重新拉列表。 */
    fun onEnter() {
        load(showLoading = _state.value.accounts.isEmpty())
    }

    /** 添加账户成功后由宿主调用：悄悄刷新列表，不清空已有内容。 */
    fun refresh() {
        load(showLoading = false)
    }

    fun onEvent(event: AccountsEvent) {
        when (event) {
            AccountsEvent.Refresh -> load(showLoading = _state.value.accounts.isEmpty())
            is AccountsEvent.Toggle -> _state.update {
                it.copy(expandedId = if (it.expandedId == event.accountId) 0 else event.accountId)
            }

            AccountsEvent.SyncAll -> syncAll()
            is AccountsEvent.SyncNow -> syncAccount(event.accountId)
            is AccountsEvent.TestConnection -> testConnection(event.accountId)
            is AccountsEvent.RequestDelete -> _state.update {
                it.copy(confirmingDeleteId = event.accountId)
            }

            AccountsEvent.ConfirmDelete -> confirmDelete()
            AccountsEvent.DismissDelete -> _state.update { it.copy(confirmingDeleteId = 0) }
            AccountsEvent.DismissNotice -> _state.update { it.copy(notice = null) }
            // 「添加账户 →」由外壳压栈，账户列表自身不发请求
            AccountsEvent.OpenAdd -> Unit
        }
    }

    private fun load(showLoading: Boolean) {
        if (showLoading) {
            _state.update { it.copy(loading = true, error = null) }
        }
        viewModelScope.launch {
            gateway.accounts().fold(
                onSuccess = { accounts ->
                    _state.update { current ->
                        current.copy(
                            accounts = accounts,
                            loading = false,
                            error = null,
                            // 账户可能已经不在列表里（例如刚被删掉），展开状态跟着收敛
                            expandedId = current.expandedId.takeIf { id -> accounts.any { it.id == id } } ?: 0,
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(loading = false, error = apiErrorText(error))
                    }
                },
            )
        }
    }

    private fun syncAll() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            gateway.syncAll().fold(
                onSuccess = {
                    _state.update { it.copy(busy = false, notice = "已开始同步全部账户") }
                    load(showLoading = false)
                },
                onFailure = { error ->
                    _state.update { it.copy(busy = false, notice = apiErrorText(error)) }
                },
            )
        }
    }

    private fun syncAccount(accountId: Int) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            gateway.sync(accountId).fold(
                onSuccess = {
                    _state.update { it.copy(busy = false, notice = "已开始同步") }
                    load(showLoading = false)
                },
                onFailure = { error ->
                    _state.update { it.copy(busy = false, notice = apiErrorText(error)) }
                },
            )
        }
    }

    private fun testConnection(accountId: Int) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            gateway.test(accountId).fold(
                onSuccess = { result ->
                    _state.update { it.copy(busy = false, notice = testResultText(result)) }
                },
                onFailure = { error ->
                    _state.update { it.copy(busy = false, notice = apiErrorText(error)) }
                },
            )
        }
    }

    /** 只有确认面板里的确认按钮会走到这里。 */
    private fun confirmDelete() {
        val accountId = _state.value.confirmingDeleteId
        if (accountId == 0 || _state.value.busy) return
        val label = accountLabel(_state.value.accounts, accountId)
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            gateway.delete(accountId).fold(
                onSuccess = {
                    _state.update { current ->
                        current.copy(
                            busy = false,
                            confirmingDeleteId = 0,
                            expandedId = 0,
                            accounts = current.accounts.filterNot { it.id == accountId },
                            notice = if (label.isEmpty()) "已删除账户" else "已删除 $label",
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(busy = false, confirmingDeleteId = 0, notice = apiErrorText(error))
                    }
                },
            )
        }
    }
}

/** 手工工厂：宿主用页面自己的 Repository 组装网关。 */
class AccountsViewModelFactory(
    private val gateway: AccountsGateway,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return AccountsViewModel(gateway) as T
    }
}
