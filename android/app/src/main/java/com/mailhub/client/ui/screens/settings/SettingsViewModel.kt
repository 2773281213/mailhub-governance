package com.mailhub.client.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mailhub.client.data.model.Capabilities
import com.mailhub.client.data.settings.SettingsUpdate
import com.mailhub.client.ui.screens.common.apiErrorText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设置页状态机：加载、逐块编辑与保存、测试推送、改密码。
 *
 * - 每块的「保存 →」只提交这一块里改动过的字段；
 * - clean_server 从关到开必须先经过确认面板；
 * - 改密码先在本地校验，通过后才发请求，成功后换上新令牌；
 * - [capabilities] 决定显示哪些区块，见 [SettingsUiState.sections]；它在创建时取好，
 *   运行期间不会改变。
 */
class SettingsViewModel(
    private val gateway: SettingsGateway,
    capabilities: Capabilities = Capabilities.Server,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState(capabilities = capabilities))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** 页面每次进入调用一次。已经加载过就不重拉，免得冲掉还没保存的编辑。 */
    fun onEnter() {
        if (_state.value.saved == null) {
            load()
        }
    }

    fun onEvent(event: SettingsEvent) {
        when (event) {
            SettingsEvent.Retry -> load()
            is SettingsEvent.SwitchChanged -> onSwitch(event.field, event.checked)
            is SettingsEvent.NumberChanged -> onNumber(event.field, event.text)
            is SettingsEvent.ScopeChanged -> editDraft(SettingsSection.Ai) {
                it.copy(aiScope = event.scope)
            }

            SettingsEvent.ConfirmCleanServer -> {
                _state.update { it.copy(confirmingCleanServer = false) }
                setSwitch(SwitchField.CleanServer, true)
            }

            SettingsEvent.DismissCleanServer -> _state.update { it.copy(confirmingCleanServer = false) }
            is SettingsEvent.PasswordChanged -> _state.update {
                it.copy(
                    password = it.password.with(event.field, event.text),
                    passwordErrors = it.passwordErrors - event.field,
                    feedback = it.feedback - SettingsSection.Password,
                )
            }

            is SettingsEvent.Save -> save(event.section)
            SettingsEvent.TestPush -> testPush()
        }
    }

    private fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            gateway.load().fold(
                onSuccess = { settings ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = null,
                            saved = settings,
                            draft = settings.toDraft(),
                            numberErrors = emptyMap(),
                        )
                    }
                },
                onFailure = { error ->
                    _state.update { it.copy(loading = false, error = apiErrorText(error)) }
                },
            )
        }
    }

    private fun onSwitch(field: SwitchField, checked: Boolean) {
        val turningOnCleanServer = field == SwitchField.CleanServer &&
            checked && !_state.value.draft.switch(field)
        if (turningOnCleanServer) {
            // 删除服务器邮件不可恢复：先确认，确认之后才把开关打开
            _state.update { it.copy(confirmingCleanServer = true) }
            return
        }
        setSwitch(field, checked)
    }

    private fun setSwitch(field: SwitchField, checked: Boolean) {
        editDraft(field.section) {
            it.copy(switches = it.switches + (field to checked))
        }
    }

    private fun onNumber(field: NumberField, text: String) {
        _state.update {
            it.copy(
                draft = it.draft.copy(numbers = it.draft.numbers + (field to digitsOnly(text))),
                numberErrors = it.numberErrors - field,
                feedback = it.feedback - field.section,
            )
        }
    }

    /** 改了某块的编辑值：这块上一次的「已保存」之类的说明已经过时，一并清掉。 */
    private fun editDraft(section: SettingsSection, change: (SettingsDraft) -> SettingsDraft) {
        _state.update {
            it.copy(draft = change(it.draft), feedback = it.feedback - section)
        }
    }

    private fun save(section: SettingsSection) {
        val current = _state.value
        if (current.pending != null) return
        if (section == SettingsSection.Password) {
            changePassword()
            return
        }
        val saved = current.saved ?: return
        val sectionFields = NumberField.entries.filter { it.section == section }.toSet()
        when (val change = sectionChange(section, saved, current.draft)) {
            is SectionChange.Invalid -> _state.update {
                it.copy(
                    numberErrors = it.numberErrors - sectionFields + change.errors,
                    feedback = it.feedback - section,
                )
            }

            SectionChange.Unchanged -> _state.update {
                it.copy(
                    numberErrors = it.numberErrors - sectionFields,
                    feedback = it.feedback + (section to UnchangedText),
                )
            }

            is SectionChange.Changed -> submit(section, change.update, sectionFields)
        }
    }

    private fun submit(
        section: SettingsSection,
        update: SettingsUpdate,
        sectionFields: Set<NumberField>,
    ) {
        _state.update {
            it.copy(
                pending = section,
                numberErrors = it.numberErrors - sectionFields,
                feedback = it.feedback - section,
            )
        }
        viewModelScope.launch {
            gateway.save(update).fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            pending = null,
                            saved = it.saved?.applied(update),
                            feedback = it.feedback + (section to SavedText),
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            pending = null,
                            feedback = it.feedback + (section to apiErrorText(error)),
                        )
                    }
                },
            )
        }
    }

    private fun changePassword() {
        val password = _state.value.password
        val errors = passwordErrors(password)
        if (errors.isNotEmpty()) {
            _state.update {
                it.copy(passwordErrors = errors, feedback = it.feedback - SettingsSection.Password)
            }
            return
        }
        _state.update {
            it.copy(
                pending = SettingsSection.Password,
                passwordErrors = emptyMap(),
                feedback = it.feedback - SettingsSection.Password,
            )
        }
        viewModelScope.launch {
            val update = SettingsUpdate(oldPassword = password.old, newPassword = password.new)
            gateway.save(update).fold(
                onSuccess = { response ->
                    // 后端已吊销全部会话，本机靠返回的新令牌继续使用；换不上就只能重新登录
                    val replaced = response.token.isNotBlank() &&
                        response.sessionId.isNotBlank() &&
                        gateway.replaceToken(response.token, response.sessionId, response.expiresTs)
                    val text = if (replaced) PasswordChangedText else PasswordChangedReloginText
                    _state.update {
                        it.copy(
                            pending = null,
                            password = PasswordDraft(),
                            feedback = it.feedback + (SettingsSection.Password to text),
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            pending = null,
                            feedback = it.feedback + (SettingsSection.Password to apiErrorText(error)),
                        )
                    }
                },
            )
        }
    }

    private fun testPush() {
        if (_state.value.pending != null) return
        _state.update {
            it.copy(pending = SettingsSection.Push, feedback = it.feedback - SettingsSection.Push)
        }
        viewModelScope.launch {
            val text = gateway.testPush().fold(
                onSuccess = { result ->
                    result.msg.ifBlank { if (result.ok) "已发送" else "发送失败" }
                },
                onFailure = { error -> apiErrorText(error) },
            )
            _state.update {
                it.copy(pending = null, feedback = it.feedback + (SettingsSection.Push to text))
            }
        }
    }
}

/** 手工工厂：宿主用页面自己的 Repository 组装网关。 */
class SettingsViewModelFactory(
    private val gateway: SettingsGateway,
    private val capabilities: Capabilities = Capabilities.Server,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return SettingsViewModel(gateway, capabilities) as T
    }
}
