package com.mailhub.client.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mailhub.client.AppContainer
import com.mailhub.client.data.session.SessionState
import com.mailhub.client.ui.screens.login.LoginScreenHost
import com.mailhub.client.ui.screens.shell.ShellHost
import com.mailhub.client.ui.screens.shell.ShellStoreHolder
import com.mailhub.client.ui.swiss.LocalSwissColors

/**
 * 应用根：进来自先恢复会话，再按会话状态换整棵界面。
 * 恢复期只铺背景色；未登录显示登录页；已登录进主界面（外壳 + 手写页面栈）。
 *
 * 登录代际的 ViewModelStore 由 Activity 级的 [ShellStoreHolder] 持有，所以旋转等配置变化不会
 * 清掉页面状态；这里只按当前会话状态取这一代际的 owner 交给外壳。
 */
@Composable
fun MailHubRoot(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val session by container.sessionState.collectAsStateWithLifecycle()
    val storeHolder: ShellStoreHolder = viewModel()

    LaunchedEffect(container) {
        container.sessionManager.restore()
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (val current = session) {
            SessionState.Restoring -> RestoringStage()

            is SessionState.LoggedOut -> LoginScreenHost(
                container = container,
                expired = current.reason == SessionState.Reason.Expired,
            )

            is SessionState.LoggedIn -> CompositionLocalProvider(
                LocalViewModelStoreOwner provides storeHolder.ownerFor(current),
            ) {
                ShellHost(container = container)
            }
        }
    }
}

/** 会话恢复期：只铺满背景色，不画任何文字、转圈或图形。 */
@Composable
private fun RestoringStage() {
    val colors = LocalSwissColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    )
}
