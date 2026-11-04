package com.mailhub.client.ui.screens.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mailhub.client.AppContainer
import com.mailhub.client.ui.screens.common.pageMetaOf
import com.mailhub.client.ui.screens.detail.DetailScreenHost
import com.mailhub.client.ui.screens.detail.DetailViewModel
import com.mailhub.client.ui.screens.detail.DetailViewModelFactory
import com.mailhub.client.ui.screens.inbox.InboxScreenHost
import com.mailhub.client.ui.screens.inbox.InboxViewModel
import com.mailhub.client.ui.screens.inbox.InboxViewModelFactory
import com.mailhub.client.ui.screens.otp.OtpScreenHost
import com.mailhub.client.ui.screens.otp.OtpViewModel
import com.mailhub.client.ui.screens.otp.OtpViewModelFactory
import com.mailhub.client.ui.screens.overview.OverviewScreenHost
import com.mailhub.client.ui.screens.placeholder.PlaceholderScreen
import com.mailhub.client.ui.swiss.ConfirmPanel
import com.mailhub.client.ui.swiss.GridRow
import com.mailhub.client.ui.swiss.HairlineRule
import com.mailhub.client.ui.swiss.IndexLabel
import com.mailhub.client.ui.swiss.LocalSwissColors
import com.mailhub.client.ui.swiss.SText
import com.mailhub.client.ui.swiss.SwissSpace
import com.mailhub.client.ui.swiss.SwissType
import com.mailhub.client.ui.swiss.cols
import java.time.Year

/**
 * 主界面外壳：顶部索引入口 + 当前页面 + 全屏索引层 + 退出确认。
 * 页面栈由 [ShellViewModel] 持有，系统返回键先关索引层 / 退出确认，再弹栈，栈底交回系统。
 *
 * 安全区在这里一次性让出来（windowInsetsPadding 同时消费 insets），
 * 里面的 SwissScaffold 和标签栏因此不会重复留出状态栏和导航栏的高度。
 */
@Composable
fun ShellHost(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val viewModel: ShellViewModel = viewModel(
        factory = ShellViewModelFactory(
            mailRepository = container.mailRepository,
            authRepository = container.authRepository,
        ),
    )
    val stack by viewModel.stack.collectAsStateWithLifecycle()
    val indexOpen by viewModel.indexOpen.collectAsStateWithLifecycle()
    val confirmingLogout by viewModel.confirmingLogout.collectAsStateWithLifecycle()
    val meta by viewModel.meta.collectAsStateWithLifecycle()
    // 收件箱和详情共用一个收件箱 ViewModel：详情返回时那一行的已读状态和删除结果直接写回列表
    val inboxViewModel: InboxViewModel = viewModel(
        factory = InboxViewModelFactory(container.mailRepository),
    )
    val detailViewModel: DetailViewModel = viewModel(
        factory = DetailViewModelFactory(container.mailRepository),
    )
    // 验证码看板自己的 ViewModel：刷新循环跟着页面可见性开关，与收件箱互不影响
    val otpViewModel: OtpViewModel = viewModel(
        factory = OtpViewModelFactory(container.mailRepository),
    )

    LaunchedEffect(viewModel) {
        viewModel.start()
    }

    val route = stack.last()
    val header = headerFor(route)
    val pageMeta = pageMetaOf(year = Year.now().value, meta = meta)
    val colors = LocalSwissColors.current

    BackHandler(enabled = indexOpen || confirmingLogout || stack.size > 1) {
        when {
            confirmingLogout -> viewModel.dismissLogout()
            indexOpen -> viewModel.closeIndex()
            else -> viewModel.pop()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            IndexEntry(onOpen = viewModel::openIndex)
            when (route) {
                Route.Overview -> OverviewScreenHost(
                    container = container,
                    meta = pageMeta,
                    modifier = Modifier.weight(1f),
                    selectedTab = tabIndexFor(route),
                    onSelectTab = viewModel::selectTab,
                )

                Route.Inbox -> InboxScreenHost(
                    viewModel = inboxViewModel,
                    meta = pageMeta,
                    onOpenMessage = { id -> viewModel.push(Route.Detail(id)) },
                    modifier = Modifier.weight(1f),
                    selectedTab = tabIndexFor(route),
                    onSelectTab = viewModel::selectTab,
                )

                is Route.Detail -> DetailScreenHost(
                    viewModel = detailViewModel,
                    messageId = route.id,
                    meta = pageMeta,
                    onMarkedRead = inboxViewModel::markReadLocally,
                    onMarkedUnread = inboxViewModel::markUnreadLocally,
                    onDeleted = { id ->
                        inboxViewModel.removeLocally(id)
                        viewModel.pop()
                    },
                    modifier = Modifier.weight(1f),
                    selectedTab = tabIndexFor(route),
                    onSelectTab = viewModel::selectTab,
                )

                Route.Otp -> OtpScreenHost(
                    viewModel = otpViewModel,
                    meta = pageMeta,
                    modifier = Modifier.weight(1f),
                    selectedTab = tabIndexFor(route),
                    onSelectTab = viewModel::selectTab,
                )

                else -> PlaceholderScreen(
                    index = header.index,
                    section = header.section,
                    title = header.title,
                    meta = pageMeta,
                    modifier = Modifier.weight(1f),
                    selectedTab = tabIndexFor(route),
                    onSelectTab = viewModel::selectTab,
                )
            }
        }
        if (indexOpen) {
            IndexLayer(
                onOpenSecondary = viewModel::openSecondary,
                onLogout = viewModel::requestLogout,
                onClose = viewModel::closeIndex,
            )
        }
        if (confirmingLogout) {
            ConfirmPanel(
                title = "退出登录？",
                message = "退出后需要重新输入管理密码才能连回这台服务器。",
                confirmText = "退出",
                onConfirm = viewModel::confirmLogout,
                onCancel = viewModel::dismissLogout,
                danger = true,
            )
        }
    }
}

/** 页头左侧的索引入口：文字按钮，占第 1–2 栏。 */
@Composable
internal fun IndexEntry(onOpen: () -> Unit) {
    GridRow {
        TextButton(text = "索引", onClick = onOpen, modifier = Modifier.cols(1, 2))
    }
    Spacer(modifier = Modifier.height(SwissSpace.Xs))
}

/**
 * 全屏索引层：编号用 label，名称用 headline，行间用 1dp 细线分隔。
 * 05 账户、06 设置先打开占位页，07 退出先弹二次确认。
 */
@Composable
internal fun IndexLayer(
    onOpenSecondary: (String, String) -> Unit,
    onLogout: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalSwissColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        GridRow {
            IndexLabel(index = "索引", modifier = Modifier.cols(1, 2))
            TextButton(text = "关闭", onClick = onClose, modifier = Modifier.cols(10, 3))
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xl))
        IndexRow(index = "05", title = "账户", onClick = { onOpenSecondary("05", "账户") })
        IndexRow(index = "06", title = "设置", onClick = { onOpenSecondary("06", "设置") })
        IndexRow(index = "07", title = "退出", onClick = onLogout)
    }
}

/** 索引层的一行：上方细线，编号在第 1–2 栏，名称在第 3–12 栏。 */
@Composable
private fun IndexRow(
    index: String,
    title: String,
    onClick: () -> Unit,
) {
    val colors = LocalSwissColors.current
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        HairlineRule(color = colors.foreground)
        Spacer(modifier = Modifier.height(SwissSpace.S))
        GridRow {
            IndexLabel(index = index, modifier = Modifier.cols(1, 2))
            SText(
                text = title,
                style = SwissType.headline(),
                modifier = Modifier.cols(3, 10),
                color = colors.foreground,
            )
        }
        Spacer(modifier = Modifier.height(SwissSpace.S))
    }
}

/** 文字按钮：只有一行 label 文字和一个最小触控高度，不做底色、不做胶囊。 */
@Composable
private fun TextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSwissColors.current
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .sizeIn(minHeight = SwissSpace.Xl)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.TopStart,
    ) {
        SText(
            text = text,
            style = SwissType.label(),
            color = colors.foreground,
            maxLines = 1,
        )
    }
}
