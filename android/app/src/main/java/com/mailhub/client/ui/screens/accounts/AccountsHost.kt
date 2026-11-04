package com.mailhub.client.ui.screens.accounts

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mailhub.client.data.accounts.AccountsRepository
import com.mailhub.client.data.session.SessionManager
import com.mailhub.client.ui.screens.common.rememberSensitiveClipboard

/**
 * 账户列表的有状态外层：自己创建 [AccountsRepository] 与 ViewModel，
 * 路由与页头由外壳负责，这里只渲染主体。
 *
 * [onOpenAdd] 由外壳压栈打开添加流程。
 */
@Composable
fun AccountsScreenHost(
    sessionManager: SessionManager,
    zoneId: String,
    modifier: Modifier = Modifier,
    onOpenAdd: () -> Unit = {},
) {
    val repository = remember(sessionManager) { AccountsRepository(sessionManager) }
    val viewModel: AccountsViewModel = viewModel(
        factory = AccountsViewModelFactory(accountsGatewayOf(repository)),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.onEnter()
    }
    AccountsBody(
        state = state,
        zoneId = zoneId,
        onEvent = { event ->
            if (event is AccountsEvent.OpenAdd) onOpenAdd() else viewModel.onEvent(event)
        },
        modifier = modifier,
    )
}

/**
 * 添加账户的有状态外层：自己创建 [AccountsRepository] 与 ViewModel，
 * 授权页 / 设备码登录页 / 授权码设置页一律交给系统浏览器打开（不引 Custom Tabs）。
 *
 * [onAdded] 在添加成功后调用，外壳用它刷新账户列表。
 */
@Composable
fun AddAccountScreenHost(
    sessionManager: SessionManager,
    modifier: Modifier = Modifier,
    onAdded: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = rememberSensitiveClipboard()
    val repository = remember(sessionManager) { AccountsRepository(sessionManager) }
    val viewModel: AddAccountViewModel = viewModel(
        factory = AddAccountViewModelFactory(
            gateway = addAccountGatewayOf(repository),
            openUrl = { url -> openInBrowser(context, url) },
            copyText = { text -> clipboard.copy(text) },
            onAdded = onAdded,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.onEnter()
    }
    AddAccountBody(
        state = state,
        onEvent = viewModel::onEvent,
        modifier = modifier,
    )
}

/** 用系统浏览器打开地址；地址不合法或没有可用浏览器时安静失败，不打断流程。 */
private fun openInBrowser(context: Context, url: String) {
    if (url.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
