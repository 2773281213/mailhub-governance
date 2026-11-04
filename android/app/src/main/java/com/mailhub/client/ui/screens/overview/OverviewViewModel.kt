package com.mailhub.client.ui.screens.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mailhub.client.data.net.ApiError
import com.mailhub.client.data.repo.MailRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 总览页状态机：进入页面自动加载一次，之后由刷新按钮和治理按钮驱动。 */
class OverviewViewModel(
    private val mailRepository: MailRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OverviewUiState())
    val state: StateFlow<OverviewUiState> = _state.asStateFlow()

    /** 是否已经进入过一次页面；第一次由 init 负责加载，之后每次进入都要重新拉。 */
    private var entered = false

    init {
        refresh()
    }

    /**
     * 有状态外层每次进入总览页调用一次。
     * ViewModel 挂在 Activity 上，退出再登录、或者从别的标签回到总览时都是同一个实例，
     * 状态里还是上一个会话的数据，所以不是第一次进入就重新加载。
     */
    fun onEnter() {
        if (entered) refresh() else entered = true
    }

    fun onEvent(event: OverviewEvent) {
        when (event) {
            OverviewEvent.Refresh -> refresh()
            OverviewEvent.RunGovernance -> runGovernance()
            OverviewEvent.DismissNotice -> _state.update { it.copy(notice = null) }
        }
    }

    /** 重新拉总览与治理状态；已经渲染出内容时不退回整页加载态，避免闪烁。 */
    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val overview = mailRepository.overview()
            val governance = mailRepository.governanceStatus()
            overview.fold(
                onSuccess = { data ->
                    _state.update { current ->
                        current.copy(
                            loading = false,
                            error = null,
                            overview = data,
                            governance = governance.getOrNull() ?: current.governance,
                        )
                    }
                },
                onFailure = { error ->
                    _state.update { it.copy(loading = false, error = overviewErrorText(error)) }
                },
            )
        }
    }

    /** 运行一次治理（近 0 天）。409 表示已经在跑，用 Snackbar 说明。 */
    private fun runGovernance() {
        if (_state.value.governanceRunning) return
        _state.update { it.copy(governanceRunning = true, notice = null) }
        viewModelScope.launch {
            mailRepository.runGovernance(0).fold(
                onSuccess = { status ->
                    val refreshed = mailRepository.governanceStatus().getOrNull() ?: status
                    _state.update { it.copy(governanceRunning = false, governance = refreshed) }
                },
                onFailure = { error ->
                    val notice = if (error is ApiError.Conflict) {
                        "治理正在进行中"
                    } else {
                        overviewErrorText(error)
                    }
                    _state.update { it.copy(governanceRunning = false, notice = notice) }
                },
            )
        }
    }
}

/** 手工工厂：总览页只依赖 MailRepository。 */
class OverviewViewModelFactory(
    private val mailRepository: MailRepository,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return OverviewViewModel(mailRepository) as T
    }
}
