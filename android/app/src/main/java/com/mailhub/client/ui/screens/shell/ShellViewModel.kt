package com.mailhub.client.ui.screens.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mailhub.client.data.model.Meta
import com.mailhub.client.data.repo.AuthRepository
import com.mailhub.client.data.repo.MailRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 外壳状态：手写页面栈、全屏索引层、退出确认，以及各页页头要用的 meta。
 * 导航不依赖 Navigation Compose，栈就是一个 Route 列表。
 */
class ShellViewModel(
    private val mailRepository: MailRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _stack = MutableStateFlow<List<Route>>(listOf(Route.Overview))

    /** 页面栈，最后一个元素是当前页面；栈底是 Overview。 */
    val stack: StateFlow<List<Route>> = _stack.asStateFlow()

    private val _indexOpen = MutableStateFlow(false)

    /** 全屏索引层是否打开。 */
    val indexOpen: StateFlow<Boolean> = _indexOpen.asStateFlow()

    private val _confirmingLogout = MutableStateFlow(false)

    /** 是否正在二次确认退出。 */
    val confirmingLogout: StateFlow<Boolean> = _confirmingLogout.asStateFlow()

    private val _meta = MutableStateFlow<Meta?>(null)

    /** /api/meta 的结果，供各页页头的年份与地点使用；读不到时保持 null。 */
    val meta: StateFlow<Meta?> = _meta.asStateFlow()

    private var metaRequested = false

    /**
     * 每次进入主界面调用一次：页面栈回到总览页，关掉索引层和退出确认，并重新读一次 meta。
     * 外壳的 ViewModelStore 按登录代际分开（见 ShellHost），每次重新登录都是新实例；
     * 这里仍然显式归位，保证同一个实例被再次组合时状态一致。
     */
    fun start() {
        _stack.value = listOf(Route.Overview)
        _indexOpen.value = false
        _confirmingLogout.value = false
        metaRequested = false
        loadMeta()
    }

    /** 读一次 meta。失败不重试也不打断操作，页头用兜底地点。 */
    fun loadMeta() {
        if (metaRequested) return
        metaRequested = true
        viewModelScope.launch {
            mailRepository.meta().onSuccess { _meta.value = it }
        }
    }

    fun openIndex() {
        _indexOpen.value = true
    }

    /** 关闭索引层（连同退出确认一起关掉）。 */
    fun closeIndex() {
        _confirmingLogout.value = false
        _indexOpen.value = false
    }

    /** 点底部标签：直接换掉栈，回到一级页面。 */
    fun selectTab(tabIndex: Int) {
        _stack.value = listOf(routeForTab(tabIndex))
    }

    /** 索引层「05 账户」：关掉索引层再压栈。 */
    fun openAccounts() {
        _indexOpen.value = false
        push(Route.Accounts)
    }

    /** 索引层「06 设置」：关掉索引层再压栈。 */
    fun openSettings() {
        _indexOpen.value = false
        push(Route.Settings)
    }

    /** 账户页的「添加账户」：压栈打开添加流程，成功后由页面弹栈回到账户页。 */
    fun openAddAccount() {
        push(Route.AddAccount)
    }

    /** 点通知里的邮件：切到「02 收件」标签，再压栈详情。 */
    fun openMessage(messageId: Long) {
        _stack.value = stackForOpenMessage(messageId)
    }

    /** 压栈：详情等二级页面都用它打开，返回时由 [pop] 弹栈。 */
    fun push(route: Route) {
        _stack.value = _stack.value + route
    }

    /** 弹出页面栈；已经在栈底时返回 false，让系统处理返回键。 */
    fun pop(): Boolean {
        val current = _stack.value
        if (current.size <= 1) return false
        _stack.value = current.dropLast(1)
        return true
    }

    fun requestLogout() {
        _confirmingLogout.value = true
    }

    fun dismissLogout() {
        _confirmingLogout.value = false
    }

    /** 二次确认后真正退出：清会话后 sessionState 会切到 LoggedOut，外壳随之切回登录页。 */
    fun confirmLogout() {
        _confirmingLogout.value = false
        _indexOpen.value = false
        viewModelScope.launch { authRepository.logout() }
    }
}

/** 手工工厂：外壳只依赖两个 Repository，不用注解注入。 */
class ShellViewModelFactory(
    private val mailRepository: MailRepository,
    private val authRepository: AuthRepository,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ShellViewModel(mailRepository, authRepository) as T
    }
}
