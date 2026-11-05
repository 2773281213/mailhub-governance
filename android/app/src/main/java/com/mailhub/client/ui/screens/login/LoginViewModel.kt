package com.mailhub.client.ui.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mailhub.client.data.model.Meta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 登录动作。真实实现就是 AuthRepository.login，测试里换成假实现。 */
fun interface LoginGateway {
    suspend fun login(serverUrl: String, password: String, deviceName: String): Result<Meta>
}

/**
 * 登录页状态：两个输入、提交中标记、失败说明（含哪个输入框标红），以及过期提示。
 * 只做页面层的事，会话落盘与状态切换仍由 AuthRepository 负责。
 */
class LoginViewModel(
    private val login: LoginGateway,
    private val lastServer: LastServerStore,
    private val deviceName: String,
    expiredNotice: Boolean = false,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState(expiredNotice = expiredNotice))
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    /** 上次成功登录的服务器地址预填到输入框。 */
    init {
        viewModelScope.launch {
            val last = lastServer.read()
            if (!last.isNullOrBlank()) {
                _state.update { if (it.serverUrl.isBlank()) it.copy(serverUrl = last) else it }
            }
        }
    }

    /**
     * 每次进入登录页调用一次：同步过期提示并清掉上次的密码与错误。
     * ViewModel 挂在 Activity 上，退出再登录会复用同一个实例。
     */
    fun onEnter(expiredNotice: Boolean) {
        _state.update {
            it.copy(expiredNotice = expiredNotice, failure = null, submitting = false, password = "")
        }
    }

    fun onEvent(event: LoginEvent) {
        when (event) {
            is LoginEvent.ServerChanged -> _state.update {
                it.copy(serverUrl = event.value, failure = it.failure?.takeIf { f -> f.field != LoginField.Server })
            }

            is LoginEvent.PasswordChanged -> _state.update {
                it.copy(password = event.value, failure = it.failure?.takeIf { f -> f.field != LoginField.Password })
            }

            LoginEvent.Submit -> submit()
        }
    }

    /**
     * 提交登录。提交中（按钮不可用之外键盘 Done 也可能连点）直接返回，不会发出第二次请求。
     */
    private fun submit() {
        val current = _state.value
        if (current.submitting) return
        _state.update { it.copy(submitting = true, failure = null) }
        viewModelScope.launch {
            login.login(current.serverUrl, current.password, deviceName).fold(
                onSuccess = {
                    lastServer.write(current.serverUrl.trim())
                    _state.update { it.copy(submitting = false) }
                },
                onFailure = { error ->
                    _state.update { it.copy(submitting = false, failure = loginFailureOf(error)) }
                },
            )
        }
    }
}

/** 手工工厂：外壳在登录页里组装真实的 Gateway 与偏好存储。 */
class LoginViewModelFactory(
    private val login: LoginGateway,
    private val lastServer: LastServerStore,
    private val deviceName: String,
    private val expiredNotice: Boolean,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return LoginViewModel(login, lastServer, deviceName, expiredNotice) as T
    }
}
