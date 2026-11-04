package com.mailhub.client.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mailhub.client.data.session.SessionManager
import com.mailhub.client.data.settings.SettingsRepository

/**
 * 设置页的有状态外层：自己创建 [SettingsRepository] 与 ViewModel，
 * 路由与页头由外壳负责，这里只渲染主体。
 */
@Composable
fun SettingsScreenHost(
    sessionManager: SessionManager,
    modifier: Modifier = Modifier,
) {
    val repository = remember(sessionManager) { SettingsRepository(sessionManager) }
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory(settingsGatewayOf(repository)),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.onEnter()
    }
    SettingsBody(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}
