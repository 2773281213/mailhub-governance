package com.mailhub.client.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mailhub.client.data.model.Capabilities
import com.mailhub.client.data.session.SessionManager
import com.mailhub.client.data.settings.SettingsRepository

/**
 * 设置页的有状态外层：自己创建 [SettingsRepository] 与 ViewModel，
 * 路由与页头由外壳负责，这里只渲染主体。
 * [header] 原样透传给主体，作为滚动内容的第一项。
 * [capabilities] 由外壳按会话模式与 `/api/meta` 算出；默认全部可用，页面与以前一样。
 */
@Composable
fun SettingsScreenHost(
    sessionManager: SessionManager,
    modifier: Modifier = Modifier,
    capabilities: Capabilities = Capabilities.Server,
    header: @Composable () -> Unit = {},
) {
    val repository = remember(sessionManager) { SettingsRepository(sessionManager) }
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory(settingsGatewayOf(repository), capabilities = capabilities),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.onEnter()
    }
    SettingsBody(state = state, onEvent = viewModel::onEvent, modifier = modifier, header = header)
}
