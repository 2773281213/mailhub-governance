package com.mailhub.client.ui.screens.otp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mailhub.client.data.repo.MailRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 验证码看板状态机：页面可见期间每 10 秒拉一次 /api/otp，时钟每秒推进一次。
 *
 * 两个循环都写成冷流（[refreshLoop] / [clockLoop]），由页面用
 * repeatOnLifecycle(STARTED) 收集：离开页面或 App 进入后台时停止收集，
 * 循环自然停下，不再发请求。ViewModel 本身不持有常驻的定时任务。
 */
class OtpViewModel(
    private val gateway: OtpGateway,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val refreshIntervalMillis: Long = OtpRefreshIntervalMillis,
    private val clockTickMillis: Long = OtpClockTickMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(OtpUiState(nowTs = nowSeconds()))

    val state: StateFlow<OtpUiState> = _state.asStateFlow()

    private var accountsRequested = false

    /**
     * 刷新循环：先拉一次，再等 [refreshIntervalMillis]。
     * 30 秒内正好三次（0、10、20 秒），收集方取消后不再有任何请求。
     */
    fun refreshLoop(): Flow<Unit> = flow {
        while (true) {
            refresh()
            emit(Unit)
            delay(refreshIntervalMillis)
        }
    }

    /** 时钟循环：每秒把「现在」推进一格，红色判定和「N 分钟前」自己走，不等网络刷新。 */
    fun clockLoop(): Flow<Unit> = flow {
        while (true) {
            _state.update { it.copy(nowTs = nowSeconds()) }
            emit(Unit)
            delay(clockTickMillis)
        }
    }

    /** 拉一次验证码列表。失败时保留已有内容，只把底部那行说明打开。 */
    suspend fun refresh() {
        loadAccounts()
        gateway.otp(OtpListLimit).fold(
            onSuccess = { items ->
                _state.update {
                    it.copy(
                        items = items,
                        loading = false,
                        refreshFailed = false,
                        nowTs = nowSeconds(),
                    )
                }
            },
            onFailure = {
                _state.update { it.copy(loading = false, refreshFailed = true) }
            },
        )
    }

    /** 复制成功后由页面回调：弹一条「已复制，60 秒后自动清除」。 */
    fun onCopied() {
        _state.update { it.copy(notice = OtpCopiedNotice) }
    }

    fun onEvent(event: OtpEvent) {
        when (event) {
            // 剪贴板写入与 60 秒清除由页面层复用 SensitiveClipboard 完成
            is OtpEvent.Copy -> Unit
            OtpEvent.DismissNotice -> _state.update { it.copy(notice = null) }
        }
    }

    /** 账户列表只取一次：编号依赖账户顺序，失败不影响看板本身。 */
    private fun loadAccounts() {
        if (accountsRequested) return
        accountsRequested = true
        viewModelScope.launch {
            gateway.accounts().onSuccess { accounts ->
                _state.update { it.copy(accounts = accounts) }
            }
        }
    }

    private fun nowSeconds(): Double = clock() / 1000.0
}

/** 手工工厂：看板只依赖邮件仓库。 */
class OtpViewModelFactory(
    private val mailRepository: MailRepository,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return OtpViewModel(otpGatewayOf(mailRepository)) as T
    }
}
