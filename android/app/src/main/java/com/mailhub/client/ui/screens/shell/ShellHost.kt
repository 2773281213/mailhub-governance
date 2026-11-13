package com.mailhub.client.ui.screens.shell

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mailhub.client.AppContainer
import com.mailhub.client.data.local.isLocal
import com.mailhub.client.data.model.Capabilities
import com.mailhub.client.data.model.capabilitiesOf
import com.mailhub.client.data.session.SessionState
import com.mailhub.client.ui.screens.accounts.AccountsScreenHost
import com.mailhub.client.ui.screens.accounts.AddAccountScreenHost
import com.mailhub.client.ui.screens.common.PageMeta
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
import com.mailhub.client.ui.screens.security.SecurityScreenHost
import com.mailhub.client.ui.screens.settings.SettingsScreenHost
import com.mailhub.client.ui.swiss.ConfirmPanel
import com.mailhub.client.ui.swiss.GridRow
import com.mailhub.client.ui.swiss.HairlineRule
import com.mailhub.client.ui.swiss.IndexLabel
import com.mailhub.client.ui.swiss.LocalSwissColors
import com.mailhub.client.ui.swiss.PageTitle
import com.mailhub.client.ui.swiss.SText
import com.mailhub.client.ui.swiss.SwissScaffold
import com.mailhub.client.ui.swiss.SwissSpace
import com.mailhub.client.ui.swiss.SwissTabBar
import com.mailhub.client.ui.swiss.SwissTextButton
import com.mailhub.client.ui.swiss.SwissType
import com.mailhub.client.ui.swiss.cols
import com.mailhub.client.work.NotificationPermission
import java.time.Year
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主界面外壳：当前页面 + 全屏索引层 + 退出确认。
 * 页面栈由 [ShellViewModel] 持有，系统返回键先关索引层 / 退出确认，再弹栈，栈底交回系统。
 *
 * 页头不再单独占一行：固定区只有页面自己的一行页头，索引入口就是这一行第 11–12 栏的
 * 「索引」（二级页面换成「← 返回」），所以这里只把回调下发，不再自己画一行。
 *
 * 外壳只在已登录时组合，但两件与登录周期绑定的事都不在这里：
 * - 登录代际的 [androidx.lifecycle.ViewModelStore] 由 Activity 级的
 *   [ShellStoreHolder] 持有（见 MailHubRoot），旋转不会丢页面状态；
 * - 后台拉取的调度跟着会话状态走（见 AppContainer），退出组合不会取消周期任务。
 *
 * 这里只保留通知权限申请：进入后按需申请一次（同一进程最多一次，永久拒绝交给系统处理）。
 *
 * 安全区在这里一次性让出来（windowInsetsPadding 同时消费 insets），
 * 里面的 SwissScaffold 和标签栏因此不会重复留出状态栏和导航栏的高度。
 */
@Composable
fun ShellHost(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    LaunchedEffect(context) {
        if (NotificationPermissionRequest.claim(context)) {
            permissionLauncher.launch(NotificationPermission.Name)
        }
    }

    ShellContent(container = container, modifier = modifier)
}

/**
 * 通知权限申请：同一进程里最多弹一次。
 * [NotificationPermission.needsRequest] 已经包含 API 33+ 的判断；永久拒绝由系统处理，
 * 这里不弹自己的说明，也不把用户引到系统设置。
 */
internal object NotificationPermissionRequest {

    private val requested = AtomicBoolean(false)

    /** 需要申请、且本进程还没申请过时返回 true。 */
    fun claim(context: Context): Boolean =
        NotificationPermission.needsRequest(context) && requested.compareAndSet(false, true)
}

@Composable
private fun ShellContent(
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
    // 会话状态决定按模式分支的页面：本机会话隐藏「已登录设备」、只留「同步」，退出确认也换文案
    val session by container.sessionState.collectAsStateWithLifecycle()
    val localMode = (session as? SessionState.LoggedIn)?.session?.isLocal == true
    val capabilities = capabilitiesOf(isLocal = localMode, meta = meta)
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

    // 通知点击：外壳只在已登录时组合，这里取走 id 后切到「02 收件」再压栈详情。
    // 未登录时 id 留在总线里，登录成功后由这里取走。
    val pendingMessageId by OpenMessageBus.shared.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingMessageId) {
        if (pendingMessageId == null) return@LaunchedEffect
        val id = openMessageIdToHandle(OpenMessageBus.shared, loggedIn = true) ?: return@LaunchedEffect
        viewModel.openMessage(id)
    }

    val route = stack.last()
    val header = headerFor(route)
    val pageMeta = pageMetaOf(year = Year.now().value, meta = meta)
    val colors = LocalSwissColors.current
    val backLabel = (headerActionFor(stack) as? HeaderAction.Back)?.label.orEmpty()

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
            when (route) {
                Route.Overview -> OverviewScreenHost(
                    container = container,
                    meta = pageMeta,
                    modifier = Modifier.weight(1f),
                    selectedTab = tabIndexFor(stack),
                    onSelectTab = viewModel::selectTab,
                    onOpenIndex = viewModel::openIndex,
                )

                Route.Inbox -> InboxScreenHost(
                    viewModel = inboxViewModel,
                    meta = pageMeta,
                    onOpenMessage = { id -> viewModel.push(Route.Detail(id)) },
                    modifier = Modifier.weight(1f),
                    selectedTab = tabIndexFor(stack),
                    onSelectTab = viewModel::selectTab,
                    onOpenIndex = viewModel::openIndex,
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
                    selectedTab = tabIndexFor(stack),
                    onSelectTab = viewModel::selectTab,
                    onBack = viewModel::pop,
                    // 从安全中心打开写「← 安全」，从收件箱打开写「← 收件」
                    backLabel = backLabelFor(stack) ?: "← 收件",
                )

                Route.Otp -> OtpScreenHost(
                    viewModel = otpViewModel,
                    meta = pageMeta,
                    modifier = Modifier.weight(1f),
                    selectedTab = tabIndexFor(stack),
                    onSelectTab = viewModel::selectTab,
                    onOpenIndex = viewModel::openIndex,
                )

                Route.Security -> SecurityPage(
                    container = container,
                    header = header,
                    meta = pageMeta,
                    categories = meta?.displayCategories.orEmpty(),
                    capabilities = capabilities,
                    selectedTab = tabIndexFor(stack),
                    onSelectTab = viewModel::selectTab,
                    onOpenIndex = viewModel::openIndex,
                    onOpenMessage = { id -> viewModel.push(Route.Detail(id)) },
                    modifier = Modifier.weight(1f),
                )

                Route.Accounts -> SecondaryPage(
                    header = header,
                    meta = pageMeta,
                    actionLabel = backLabel,
                    onBack = viewModel::pop,
                    modifier = Modifier.weight(1f),
                ) {
                    AccountsScreenHost(
                        sessionManager = container.sessionManager,
                        zoneId = pageMeta.zoneId,
                        onOpenAdd = viewModel::openAddAccount,
                        modifier = Modifier.fillMaxSize(),
                        header = { PageTitle(text = header.title) },
                    )
                }

                Route.AddAccount -> SecondaryPage(
                    header = header,
                    meta = pageMeta,
                    actionLabel = backLabel,
                    onBack = viewModel::pop,
                    modifier = Modifier.weight(1f),
                ) {
                    AddAccountScreenHost(
                        sessionManager = container.sessionManager,
                        onAdded = { viewModel.pop() },
                        modifier = Modifier.fillMaxSize(),
                        header = { PageTitle(text = header.title) },
                    )
                }

                Route.Settings -> SecondaryPage(
                    header = header,
                    meta = pageMeta,
                    actionLabel = backLabel,
                    onBack = viewModel::pop,
                    modifier = Modifier.weight(1f),
                ) {
                    SettingsScreenHost(
                        sessionManager = container.sessionManager,
                        modifier = Modifier.fillMaxSize(),
                        capabilities = capabilities,
                        header = { PageTitle(text = header.title) },
                    )
                }
            }
        }
        if (indexOpen) {
            IndexLayer(
                onOpenAccounts = viewModel::openAccounts,
                onOpenSettings = viewModel::openSettings,
                onLogout = viewModel::requestLogout,
                onClose = viewModel::closeIndex,
            )
        }
        if (confirmingLogout) {
            LogoutConfirmPanel(
                localMode = localMode,
                onConfirm = viewModel::confirmLogout,
                onCancel = viewModel::dismissLogout,
            )
        }
    }
}

/** 安全中心：一级页面，页头操作入口是「索引」，底部保留标签栏。 */
@Composable
private fun SecurityPage(
    container: AppContainer,
    header: PageHeader,
    meta: PageMeta,
    categories: List<String>,
    capabilities: Capabilities,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onOpenIndex: () -> Unit,
    onOpenMessage: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    SwissScaffold(
        index = header.index,
        section = header.section,
        meta = meta.line,
        modifier = modifier,
        actionLabel = IndexActionLabel,
        onAction = onOpenIndex,
        bottomBar = {
            SwissTabBar(items = AppTabs, selected = selectedTab, onSelect = onSelectTab)
        },
    ) {
        SecurityScreenHost(
            sessionManager = container.sessionManager,
            zoneId = meta.zoneId,
            categories = categories,
            capabilities = capabilities,
            onOpenMessage = onOpenMessage,
            modifier = Modifier.fillMaxSize(),
            header = { PageTitle(text = header.title) },
        )
    }
}

/** 二级页面（账户、添加账户、设置）：页头操作入口是「← 返回」，底部不带标签栏。 */
@Composable
private fun SecondaryPage(
    header: PageHeader,
    meta: PageMeta,
    actionLabel: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    SwissScaffold(
        index = header.index,
        section = header.section,
        meta = meta.line,
        modifier = modifier,
        actionLabel = actionLabel,
        onAction = onBack,
        content = content,
    )
}

/**
 * 一级页面页头操作入口「索引」的文字：占第 11–12 栏，点击打开全屏索引层。
 * 入口本身由各页面的 SwissScaffold 画（见 PageHeader），这里只放文字。
 */
const val IndexActionLabel: String = "索引"

/** 退出确认的三段文案：标题、说明、确认按钮。 */
data class LogoutConfirmTexts(val title: String, val message: String, val confirmText: String)

/**
 * 退出确认按模式给文案（`LOCAL_MODE.md` 3.1）。
 *
 * - 服务器模式：退出后要重新输入管理密码，与以前完全一致；
 * - 本机模式：只清会话，本机数据库和凭据都还在，再点一次「本机独立使用」就能回来。
 */
fun logoutConfirmTexts(localMode: Boolean): LogoutConfirmTexts = if (localMode) {
    LogoutConfirmTexts(
        title = "退出本机模式？",
        message = "本机的邮件和账户都会保留，再次选择「本机独立使用」就能回来。",
        confirmText = "退出",
    )
} else {
    LogoutConfirmTexts(
        title = "退出登录？",
        message = "退出后需要重新输入管理密码才能连回这台服务器。",
        confirmText = "退出",
    )
}

/** 退出确认面板；[localMode] 只影响文案，按钮与危险色不变。 */
@Composable
internal fun LogoutConfirmPanel(
    localMode: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val texts = logoutConfirmTexts(localMode)
    ConfirmPanel(
        title = texts.title,
        message = texts.message,
        confirmText = texts.confirmText,
        onConfirm = onConfirm,
        onCancel = onCancel,
        danger = true,
    )
}

/**
 * 全屏索引层：编号用 label，名称用 headline，行间用 1dp 细线分隔。
 * 05 账户、06 设置压栈打开对应页面，07 退出先弹二次确认。
 */
@Composable
internal fun IndexLayer(
    onOpenAccounts: () -> Unit,
    onOpenSettings: () -> Unit,
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
            SwissTextButton(text = "关闭", onClick = onClose, modifier = Modifier.cols(10, 3))
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xl))
        IndexRow(index = "05", title = "账户", onClick = onOpenAccounts)
        IndexRow(index = "06", title = "设置", onClick = onOpenSettings)
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
