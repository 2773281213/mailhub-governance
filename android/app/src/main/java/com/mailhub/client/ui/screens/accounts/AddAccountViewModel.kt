package com.mailhub.client.ui.screens.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mailhub.client.data.accounts.MethodOAuthDevice
import com.mailhub.client.data.accounts.MethodOAuthBrowser
import com.mailhub.client.data.accounts.AccountBody
import com.mailhub.client.data.accounts.DeviceCodeStart
import com.mailhub.client.data.accounts.OAuthTransaction
import com.mailhub.client.data.accounts.TransactionDone
import com.mailhub.client.data.accounts.TransactionExpired
import com.mailhub.client.data.accounts.TransactionFailed
import com.mailhub.client.ui.screens.common.apiErrorText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 添加账户状态机：域名识别服务商 → 按顺序选认证方式 → 按方式执行。
 *
 * 三种执行方式：
 * - 授权码 / 密码：POST /api/accounts，后端探测 IMAP 期间显示 LoadingRule，失败用后端 detail；
 * - 浏览器 OAuth：POST /api/oauth/start（client=android）后打开授权页，每 2 秒轮询事务，
 *   最长 10 分钟；深链带着事务 id 回来时立即再轮询一次；
 * - 设备码（Outlook）：device/start 后按后端给的 interval 秒轮询 device/poll，
 *   直到成功或超过 expires_in。
 *
 * 轮询全部走 `delay` / `withTimeoutOrNull`，单元测试用虚拟时间就能跑完整条状态机。
 */
class AddAccountViewModel(
    private val gateway: AddAccountGateway,
    private val bus: OAuthReturnBus = OAuthReturnBus.shared,
    private val pollIntervalMillis: Long = BrowserPollIntervalMillis,
    private val pollTimeoutMillis: Long = BrowserPollTimeoutMillis,
    /** 用系统浏览器打开地址（授权页 / 设备码登录页 / 授权码设置页）。 */
    private val openUrl: (String) -> Unit = {},
    /** 复制文本（设备码）。 */
    private val copyText: (String) -> Unit = {},
    /** 添加成功后通知宿主刷新账户列表。 */
    private val onAdded: () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(AddAccountUiState())
    val state: StateFlow<AddAccountUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    /** 页面进入：拉一次服务商列表（失败时用黑色正文说明，可以点「重试」再拉）。 */
    fun onEnter() {
        if (_state.value.providers.isNotEmpty()) return
        loadProviders()
    }

    fun onEvent(event: AddAccountEvent) {
        when (event) {
            is AddAccountEvent.EmailChanged -> _state.update {
                it.copy(
                    email = event.value,
                    target = detectTarget(event.value, it.providers),
                    error = null,
                )
            }

            is AddAccountEvent.HostChanged -> _state.update {
                it.copy(imapHost = event.value, error = null)
            }

            is AddAccountEvent.PortChanged -> _state.update {
                it.copy(imapPort = event.value, error = null)
            }

            AddAccountEvent.ContinueEmail -> continueEmail()
            is AddAccountEvent.SelectMethod -> selectMethod(event.index)
            AddAccountEvent.BackToEmail -> {
                cancelPolling()
                _state.update { it.waitClear().copy(step = AddStep.Email) }
            }

            is AddAccountEvent.SecretChanged -> _state.update {
                it.copy(secret = event.value, error = null)
            }

            AddAccountEvent.SubmitSecret -> submitSecret()
            AddAccountEvent.Retry -> retryChosen()
            AddAccountEvent.OpenSetup -> {
                val url = _state.value.chosen?.setupUrl.orEmpty()
                if (url.isNotEmpty()) openUrl(url)
            }

            AddAccountEvent.CopyUserCode -> {
                val code = _state.value.deviceCode?.code.orEmpty()
                if (code.isNotEmpty()) {
                    copyText(code)
                    _state.update { it.copy(deviceCode = it.deviceCode?.copy(copied = true)) }
                }
            }

            AddAccountEvent.OpenVerification -> {
                val uri = _state.value.deviceCode?.verificationUri.orEmpty()
                if (uri.isNotEmpty()) openUrl(uri)
            }

            AddAccountEvent.StartOver -> {
                cancelPolling()
                _state.update {
                    AddAccountUiState(providers = it.providers, loadingProviders = false)
                }
            }
        }
    }

    override fun onCleared() {
        cancelPolling()
        super.onCleared()
    }

    private fun loadProviders() {
        if (_state.value.loadingProviders) return
        _state.update { it.copy(loadingProviders = true, error = null) }
        viewModelScope.launch {
            gateway.providers().fold(
                onSuccess = { providers ->
                    _state.update {
                        it.copy(
                            providers = providers,
                            loadingProviders = false,
                            target = detectTarget(it.email, providers),
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(loadingProviders = false, error = apiErrorText(error))
                    }
                },
            )
        }
    }

    private fun continueEmail() {
        val current = _state.value
        val email = current.email.trim()
        val target = current.target
        when {
            email.isEmpty() -> _state.update { it.copy(error = "请填写邮箱地址") }
            target == null -> _state.update { it.copy(error = "邮箱地址格式不正确") }
            target.isCustom && current.imapHost.isBlank() ->
                _state.update { it.copy(error = "请填写 IMAP 服务器地址") }

            else -> _state.update {
                it.copy(step = AddStep.Method, selectedMethod = 0, error = null)
            }
        }
    }

    /** 选中一种方式：授权码方式等用户填码，OAuth 方式立刻开始执行。 */
    private fun selectMethod(index: Int) {
        val method = _state.value.methodRows.getOrNull(index)?.method ?: return
        _state.update {
            it.copy(step = AddStep.Execute, selectedMethod = index, error = null, secret = "")
        }
        when (method.type) {
            MethodOAuthBrowser -> startBrowser()
            MethodOAuthDevice -> startDevice()
            else -> Unit
        }
    }

    private fun retryChosen() {
        when (_state.value.chosen?.type) {
            MethodOAuthBrowser -> startBrowser()
            MethodOAuthDevice -> startDevice()
            else -> Unit
        }
    }

    // ---------- 授权码 / 密码 ----------

    private fun submitSecret() {
        val current = _state.value
        if (current.submitting) return
        val target = current.target ?: return
        val method = current.chosen ?: return
        val email = current.email.trim()
        val secret = current.secret.trim()
        if (email.isEmpty()) {
            _state.update { it.copy(error = "请填写邮箱地址") }
            return
        }
        if (secret.isEmpty()) {
            val label = method.inputLabel.ifBlank { if (method.usesSecret) "授权码或密码" else "登录凭据" }
            _state.update { it.copy(error = "请填写$label") }
            return
        }
        val body = AccountBody(
            provider = target.providerKey,
            email = email,
            secret = secret,
            imapHost = if (target.isCustom) current.imapHost.trim() else "",
            imapPort = if (target.isCustom) {
                current.imapPort.trim().toIntOrNull() ?: DefaultImapPort
            } else {
                DefaultImapPort
            },
        )
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            gateway.addAccount(body).fold(
                onSuccess = { accountId -> finishAdded(accountId, email) },
                onFailure = { error ->
                    _state.update { it.copy(submitting = false, error = apiErrorText(error)) }
                },
            )
        }
    }

    // ---------- 浏览器 OAuth ----------

    private fun startBrowser() {
        val current = _state.value
        val target = current.target ?: return
        if (current.submitting) return
        cancelPolling()
        _state.update { it.copy(submitting = true, error = null, waitingBrowser = false) }
        pollJob = viewModelScope.launch {
            gateway.startBrowserOAuth(target.providerKey, current.email.trim()).fold(
                onSuccess = { start ->
                    _state.update {
                        it.copy(submitting = false, waitingBrowser = true, error = null)
                    }
                    // 先登记等待中的事务 id，深链回来时总线才会接受它
                    bus.expect(start.transactionId)
                    openUrl(start.authorizationUrl)
                    pollBrowser(start.transactionId)
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(submitting = false, waitingBrowser = false, error = apiErrorText(error))
                    }
                },
            )
        }
    }

    /**
     * 浏览器 OAuth 的轮询状态机：`pending` 继续等，`done` 结束，`failed` / `expired` 报错，
     * 最长等 [pollTimeoutMillis]；等待期间如果深链带着同一个事务 id 回来，立即再轮询一次。
     */
    private suspend fun pollBrowser(transactionId: String) {
        var elapsed = 0L
        var consumedSeq = bus.returns.value?.seq ?: 0L
        var outcome: OAuthOutcome? = null
        while (outcome == null) {
            // 到点就不再发请求，避免「等满 10 分钟还多查一次」
            if (elapsed >= pollTimeoutMillis) {
                outcome = OAuthOutcome.Failed(BrowserTimeoutText)
                break
            }
            outcome = gateway.oauthTransaction(transactionId).fold(
                onSuccess = { transaction -> browserOutcome(transaction) },
                onFailure = { error -> OAuthOutcome.Failed(apiErrorText(error)) },
            )
            if (outcome != null) break
            val returned = withTimeoutOrNull(pollIntervalMillis) {
                bus.returns.first { value ->
                    value != null && value.txId == transactionId && value.seq > consumedSeq
                }
            }
            if (returned != null) {
                // 深链：不等下一个间隔，马上再查一次
                consumedSeq = returned.seq
            } else {
                elapsed += pollIntervalMillis
            }
        }
        bus.clear()
        val final = outcome ?: return
        when (final) {
            is OAuthOutcome.Done -> finishAdded(final.accountId, _state.value.email.trim())
            is OAuthOutcome.Failed -> {
                val message = final.message
                _state.update {
                    it.copy(submitting = false, waitingBrowser = false, error = message)
                }
            }
        }
    }

    /** 事务状态换成轮询结果；pending / consumed 继续等，done / failed / expired 结束。 */
    private fun browserOutcome(transaction: OAuthTransaction): OAuthOutcome? = when (transaction.status) {
        TransactionDone -> if (transaction.accountId > 0) {
            OAuthOutcome.Done(transaction.accountId)
        } else {
            OAuthOutcome.Failed(transaction.message.ifBlank { BrowserFailedText })
        }

        TransactionFailed -> OAuthOutcome.Failed(transaction.message.ifBlank { BrowserFailedText })
        TransactionExpired -> OAuthOutcome.Failed(BrowserExpiredText)
        else -> null
    }

    // ---------- 设备码 ----------

    private fun startDevice() {
        val current = _state.value
        val target = current.target ?: return
        if (current.submitting) return
        cancelPolling()
        _state.update { it.copy(submitting = true, error = null, deviceCode = null) }
        pollJob = viewModelScope.launch {
            gateway.startDeviceCode(target.providerKey, current.email.trim()).fold(
                onSuccess = { start ->
                    _state.update {
                        it.copy(
                            submitting = false,
                            error = null,
                            deviceCode = DeviceCodeUi(
                                code = start.userCode,
                                verificationUri = start.verificationUri,
                            ),
                        )
                    }
                    pollDevice(start, current.email.trim())
                },
                onFailure = { error ->
                    _state.update { it.copy(submitting = false, error = apiErrorText(error)) }
                },
            )
        }
    }

    /** 设备码轮询：按 interval 秒查一次，成功后清掉设备码，超过 expires_in 就停下报错。 */
    private suspend fun pollDevice(start: DeviceCodeStart, fallbackName: String) {
        var intervalSeconds = start.interval.coerceAtLeast(DeviceMinIntervalSeconds)
        val limitMillis = start.expiresIn.coerceAtLeast(0).toLong() * 1000L
        var elapsed = 0L
        var outcome: OAuthOutcome? = null
        while (outcome == null) {
            // 到点就停：expires_in 之后不再多发一次请求
            if (elapsed >= limitMillis) {
                outcome = OAuthOutcome.Failed(DeviceExpiredText)
                break
            }
            outcome = gateway.pollDeviceCode(start.transactionId).fold(
                onSuccess = { poll ->
                    if (poll.interval > 0) {
                        intervalSeconds = poll.interval.coerceAtLeast(DeviceMinIntervalSeconds)
                    }
                    if (poll.ok) OAuthOutcome.Done(poll.id) else null
                },
                onFailure = { error -> OAuthOutcome.Failed(apiErrorText(error)) },
            )
            if (outcome != null) break
            delay(intervalSeconds * 1000L)
            elapsed += intervalSeconds * 1000L
        }
        val final = outcome ?: return
        when (final) {
            is OAuthOutcome.Done -> finishAdded(final.accountId, fallbackName)
            is OAuthOutcome.Failed -> {
                val message = final.message
                _state.update { it.copy(deviceCode = null, error = message) }
            }
        }
    }

    // ---------- 收尾 ----------

    /** 成功：把账户换成 A? 编号，记下「已添加 A? 名称」，并请宿主刷新列表。 */
    private suspend fun finishAdded(accountId: Int, fallbackName: String) {
        bus.clear()
        val accounts = gateway.accounts().getOrElse { emptyList() }
        val index = accounts.indexOfFirst { it.id == accountId }
        val label = if (index >= 0) "A${index + 1}" else "A?"
        val name = accounts.firstOrNull { it.id == accountId }
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?: fallbackName
        val line = if (name.isBlank()) "已添加 $label" else "已添加 $label $name"
        _state.update {
            it.copy(
                submitting = false,
                waitingBrowser = false,
                deviceCode = null,
                error = null,
                addedLine = line,
            )
        }
        onAdded()
    }

    private fun cancelPolling() {
        pollJob?.cancel()
        pollJob = null
        bus.clear()
    }

    /** 退出第 3 步或回到第 1 步时要一起清掉的状态。 */
    private fun AddAccountUiState.waitClear(): AddAccountUiState = copy(
        waitingBrowser = false,
        deviceCode = null,
        submitting = false,
        error = null,
    )

    /** 轮询结果：完成（带账户 id）或失败（带说明）。 */
    private sealed interface OAuthOutcome {
        data class Done(val accountId: Int) : OAuthOutcome
        data class Failed(val message: String) : OAuthOutcome
    }

    private companion object {
        /** 后端 AccountBody 的默认 IMAP 端口，与 app.py 一致。 */
        const val DefaultImapPort: Int = 993
    }
}

/** 手工工厂：宿主用页面自己的 Repository 组装网关，并接上浏览器、剪贴板和列表刷新。 */
class AddAccountViewModelFactory(
    private val gateway: AddAccountGateway,
    private val bus: OAuthReturnBus = OAuthReturnBus.shared,
    private val openUrl: (String) -> Unit,
    private val copyText: (String) -> Unit,
    private val onAdded: () -> Unit,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return AddAccountViewModel(
            gateway = gateway,
            bus = bus,
            openUrl = openUrl,
            copyText = copyText,
            onAdded = onAdded,
        ) as T
    }
}
