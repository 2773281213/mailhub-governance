package com.mailhub.client.ui.screens.security

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mailhub.client.data.security.SecurityRepository
import com.mailhub.client.data.session.SessionManager

/**
 * 安全中心的有状态外层：自己创建 [SecurityRepository] 与 ViewModel，
 * 路由与页头由外壳负责，这里只渲染主体。
 *
 * [onOpenMessage] 由外壳压栈打开详情。
 */
@Composable
fun SecurityScreenHost(
    sessionManager: SessionManager,
    zoneId: String,
    modifier: Modifier = Modifier,
    categories: List<String> = emptyList(),
    onOpenMessage: (Long) -> Unit = {},
) {
    val repository = remember(sessionManager) { SecurityRepository(sessionManager) }
    val viewModel: SecurityViewModel = viewModel(
        factory = SecurityViewModelFactory(securityGatewayOf(repository)),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.onEnter()
    }
    SecurityBody(
        state = state,
        zoneId = zoneId,
        categories = categories,
        onEvent = { event ->
            if (event is SecurityEvent.OpenMessage) onOpenMessage(event.messageId) else viewModel.onEvent(event)
        },
        modifier = modifier,
    )
}
