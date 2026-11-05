package com.mailhub.client.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mailhub.client.data.model.BatchRequest
import com.mailhub.client.data.net.ApiError
import com.mailhub.client.data.repo.MailRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 风险邮件点链接时的固定提示。 */
const val RiskyLinkBlockedText: String = "风险邮件已禁用链接"

/**
 * 详情页状态机：打开即标已读，处理链接确认与两步删除。
 *
 * 删除先发一次 batch([id], "delete")；服务端返回 403 且带 protected 时进入二次确认，
 * 用户确认后带 override_protection 重发（只对恰好一封邮件生效，服务端会核对）。
 */
class DetailViewModel(private val gateway: DetailGateway) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private var loadedId = 0L

    /** 每次进入详情调用一次；同一封邮件重复进入不重复请求。 */
    fun onEnter(messageId: Long) {
        if (loadedId == messageId && _state.value.message != null) return
        loadedId = messageId
        _state.value = DetailUiState(messageId = messageId)
        loadMeta()
        loadMessage(messageId)
    }

    /** 失败后重试：清掉旧内容重新拉一次。 */
    fun reload() {
        val id = loadedId
        if (id <= 0L) return
        _state.update { it.copy(loading = true, error = null) }
        loadMessage(id)
    }

    private fun loadMeta() {
        viewModelScope.launch {
            gateway.meta().onSuccess { meta ->
                _state.update { it.copy(categories = meta.displayCategories) }
            }
        }
        viewModelScope.launch {
            gateway.accounts().onSuccess { accounts ->
                _state.update { it.copy(accounts = accounts) }
            }
        }
    }

    private fun loadMessage(messageId: Long) {
        viewModelScope.launch {
            gateway.message(messageId, markRead = true).fold(
                onSuccess = { detail ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = null,
                            message = detail,
                            // 服务端在响应前就地标了已读，这里同步给收件箱那一行
                            markedRead = detail.isUnread,
                        )
                    }
                },
                onFailure = { error ->
                    _state.update { it.copy(loading = false, error = detailErrorText(error)) }
                },
            )
        }
    }

    fun onEvent(event: DetailEvent) {
        when (event) {
            DetailEvent.Reload -> reload()
            DetailEvent.MarkUnread -> markUnread()
            DetailEvent.RequestDelete -> _state.update { it.copy(confirmingDelete = true) }
            DetailEvent.DismissDelete -> _state.update { it.copy(confirmingDelete = false) }
            DetailEvent.ConfirmDelete -> delete(overrideProtection = false)
            DetailEvent.ConfirmProtectedDelete -> delete(overrideProtection = true)
            DetailEvent.DismissProtected -> _state.update { it.copy(confirmingProtected = false) }
            DetailEvent.ShowExternalImages -> _state.update {
                it.copy(showExternalImages = true, notice = null)
            }

            is DetailEvent.TapLink -> onTapLink(event.url)
            DetailEvent.ConfirmOpenLink -> _state.update {
                it.copy(linkToOpen = it.pendingLink, pendingLink = null)
            }

            DetailEvent.DismissOpenLink -> _state.update { it.copy(pendingLink = null) }
            DetailEvent.DismissNotice -> _state.update { it.copy(notice = null) }
        }
    }

    /** 页面层打开浏览器后回调，把一次性信号清掉。 */
    fun clearOpenedLink() {
        _state.update { it.copy(linkToOpen = null) }
    }

    /** 链接点击：风险邮件直接提示不可用，其余弹确认框交给系统浏览器。 */
    private fun onTapLink(url: String) {
        val message = _state.value.message ?: return
        val config = mailWebViewConfig(
            isRisky = message.isRisky,
            showExternalImages = _state.value.showExternalImages,
        )
        when (linkTapFor(url, config)) {
            LinkTap.Blocked -> _state.update { current ->
                current.copy(
                    notice = if (message.isRisky) {
                        RiskyLinkBlockedText
                    } else {
                        "只允许打开 http(s) 链接"
                    },
                )
            }

            LinkTap.Confirm -> _state.update { it.copy(pendingLink = url, notice = null) }
        }
    }

    /** 标为未读：同一封邮件单发一次 batch。 */
    private fun markUnread() {
        val id = _state.value.message?.id ?: return
        if (_state.value.deleting) return
        viewModelScope.launch {
            gateway.batch(
                BatchRequest(ids = listOf(id), action = "unread", confirmed = true),
            ).fold(
                onSuccess = {
                    _state.update { current ->
                        current.copy(
                            message = current.message?.copy(unread = 1),
                            markedUnread = true,
                        )
                    }
                },
                onFailure = { error ->
                    _state.update { it.copy(notice = detailErrorText(error)) }
                },
            )
        }
    }

    /**
     * 删除这一封。第一次不带 override_protection；被安全策略拦下（403 + protected）
     * 时进入二次确认，确认后带 override_protection 重发。
     */
    private fun delete(overrideProtection: Boolean) {
        val id = _state.value.message?.id ?: return
        if (_state.value.deleting) return
        _state.update {
            it.copy(
                confirmingDelete = false,
                confirmingProtected = false,
                deleting = true,
                notice = null,
            )
        }
        viewModelScope.launch {
            gateway.batch(
                BatchRequest(
                    ids = listOf(id),
                    action = "delete",
                    confirmed = true,
                    overrideProtection = overrideProtection,
                ),
            ).fold(
                onSuccess = {
                    _state.update { current -> current.copy(deleting = false, deleted = true) }
                },
                onFailure = { error ->
                    if (error is ApiError.Forbidden && error.protected && !overrideProtection) {
                        _state.update { current ->
                            current.copy(deleting = false, confirmingProtected = true)
                        }
                    } else {
                        _state.update { current ->
                            current.copy(deleting = false, notice = detailErrorText(error))
                        }
                    }
                },
            )
        }
    }
}

/** 手工工厂：详情页只依赖邮件仓库。 */
class DetailViewModelFactory(
    private val mailRepository: MailRepository,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return DetailViewModel(detailGatewayOf(mailRepository)) as T
    }
}
