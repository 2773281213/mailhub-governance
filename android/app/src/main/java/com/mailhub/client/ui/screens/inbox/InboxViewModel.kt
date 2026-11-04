package com.mailhub.client.ui.screens.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mailhub.client.data.model.BatchRequest
import com.mailhub.client.data.model.BatchResult
import com.mailhub.client.data.model.MessagePage
import com.mailhub.client.data.repo.MailRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 收件箱状态机：游标分页、筛选与搜索防抖、多选与批量操作。
 *
 * 翻页用服务端给的不透明游标：第一页 cursor 传 null，之后传上一页的 next_cursor.token；
 * next_cursor 为 null（或这一页没有邮件）就停止加载。更换筛选条件一律回到第一页。
 */
class InboxViewModel(
    private val gateway: InboxGateway,
    private val pageSize: Int = InboxPageSize,
    private val searchDebounceMillis: Long = InboxSearchDebounceMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(InboxUiState())
    val state: StateFlow<InboxUiState> = _state.asStateFlow()

    /** 下一页的游标；null 表示没有更多。 */
    private var cursor: String? = null

    /** 每次重新加载都自增，用来丢弃过期请求的响应。 */
    private var generation = 0

    private var firstPageJob: Job? = null
    private var moreJob: Job? = null
    private var searchJob: Job? = null
    private var entered = false

    /** 每页条数，测试需要断言请求参数时读它。 */
    val requestedPageSize: Int get() = pageSize

    /**
     * 每次进入收件箱调用一次：第一次进入拉取 meta、账户和第一页。
     * 从详情返回时不再整体重载，已读状态和删除由 [markReadLocally] / [markUnreadLocally] /
     * [removeLocally] 就地更新，滚动位置不会被清掉。
     */
    fun onEnter() {
        if (entered) return
        entered = true
        loadMeta()
        reload()
    }

    fun onEvent(event: InboxEvent) {
        when (event) {
            is InboxEvent.QueryChanged -> onQueryChanged(event.text)
            is InboxEvent.SetUnreadOnly -> changeFilters { it.copy(unreadOnly = event.unreadOnly) }
            is InboxEvent.SelectCategory -> changeFilters { it.copy(category = event.category) }
            is InboxEvent.SelectAccount -> changeFilters { it.copy(accountId = event.accountId) }
            InboxEvent.Refresh -> {
                loadMeta()
                reload()
            }

            InboxEvent.LoadMore -> loadMore()
            is InboxEvent.LongPress -> longPress(event.id)
            is InboxEvent.ToggleSelected -> toggleSelected(event.id)
            InboxEvent.CancelSelect -> cancelSelect()
            is InboxEvent.SetRead -> setRead(event.read)
            InboxEvent.RequestDelete -> _state.update {
                if (it.selected.isEmpty()) it else it.copy(confirmingDelete = true)
            }

            InboxEvent.ConfirmDelete -> deleteSelected()
            InboxEvent.DismissDelete -> _state.update { it.copy(confirmingDelete = false) }
            InboxEvent.DismissNotice -> _state.update { it.copy(notice = null) }
            // 打开详情由外壳负责压栈，ViewModel 不处理
            is InboxEvent.Open -> Unit
        }
    }

    /** meta 与账户列表：分类编号和账户编号都依赖它们的顺序，失败不影响列表本身。 */
    private fun loadMeta() {
        if (_state.value.categories.isEmpty()) {
            viewModelScope.launch {
                gateway.meta().onSuccess { meta ->
                    _state.update { it.copy(categories = meta.displayCategories) }
                }
            }
        }
        viewModelScope.launch {
            gateway.accounts().onSuccess { accounts ->
                _state.update { it.copy(accounts = accounts) }
            }
        }
    }

    /** 输入即回显，防抖结束后只发一次查询。 */
    private fun onQueryChanged(text: String) {
        _state.update { it.copy(filters = it.filters.copy(query = text)) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(searchDebounceMillis)
            reload()
        }
    }

    private fun changeFilters(transform: (InboxFilters) -> InboxFilters) {
        searchJob?.cancel()
        _state.update { it.copy(filters = transform(it.filters)) }
        reload()
    }

    /** 从第一页重新加载；游标、已加载内容、多选状态一起重置。 */
    fun reload() {
        generation++
        val current = generation
        firstPageJob?.cancel()
        moreJob?.cancel()
        cursor = null
        _state.update {
            it.copy(
                loading = true,
                loadingMore = false,
                error = null,
                items = emptyList(),
                endReached = false,
                selectMode = false,
                selected = emptySet(),
            )
        }
        val filters = _state.value.filters
        firstPageJob = viewModelScope.launch {
            gateway.messages(filters, null).fold(
                onSuccess = { page -> if (current == generation) applyPage(page, append = false) },
                onFailure = { error ->
                    if (current == generation) {
                        _state.update { it.copy(loading = false, error = inboxErrorText(error)) }
                    }
                },
            )
        }
    }

    /** 滚到底部时加载下一页；没有游标、正在加载或已经到底都不再发请求。 */
    fun loadMore() {
        val current = _state.value
        if (current.loading || current.loadingMore || current.endReached) return
        val token = cursor ?: return
        val gen = generation
        val filters = current.filters
        _state.update { it.copy(loadingMore = true) }
        moreJob = viewModelScope.launch {
            gateway.messages(filters, token).fold(
                onSuccess = { page -> if (gen == generation) applyPage(page, append = true) },
                onFailure = { error ->
                    if (gen == generation) {
                        _state.update { it.copy(loadingMore = false, notice = inboxErrorText(error)) }
                    }
                },
            )
        }
    }

    /** 合并一页结果：按 id 去重，next_cursor 为 null 时标记已经到底。 */
    private fun applyPage(page: MessagePage, append: Boolean) {
        cursor = page.nextCursor?.token
        val reachedEnd = cursor == null || page.items.isEmpty()
        _state.update {
            val items = appendDistinct(if (append) it.items else emptyList(), page.items)
            it.copy(
                items = items,
                loading = false,
                loadingMore = false,
                error = null,
                endReached = reachedEnd,
            )
        }
    }

    private fun longPress(id: Long) {
        _state.update {
            it.copy(
                selectMode = true,
                selected = if (id in it.selected) it.selected else it.selected + id,
                notice = null,
            )
        }
    }

    private fun toggleSelected(id: Long) {
        _state.update { state ->
            if (!state.selectMode) return@update state
            val selected = if (id in state.selected) state.selected - id else state.selected + id
            state.copy(selected = selected, selectMode = selected.isNotEmpty())
        }
    }

    private fun cancelSelect() {
        _state.update { it.copy(selectMode = false, selected = emptySet(), confirmingDelete = false) }
    }

    /** 批量已读 / 未读：成功后就地改本地状态，退出多选。 */
    private fun setRead(read: Boolean) {
        val current = _state.value
        val ids = current.selected.toList()
        if (ids.isEmpty() || current.busy) return
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            gateway.batch(
                BatchRequest(ids = ids, action = if (read) "read" else "unread", confirmed = true),
            ).fold(
                onSuccess = { result ->
                    val done = ids.filterNot { it in blockedIds(result) }.toSet()
                    _state.update { state ->
                        state.copy(
                            busy = false,
                            selectMode = false,
                            selected = emptySet(),
                            items = state.items.map { item ->
                                if (item.id in done) item.copy(unread = if (read) 0 else 1) else item
                            },
                        )
                    }
                },
                onFailure = { error ->
                    _state.update { it.copy(busy = false, notice = inboxErrorText(error)) }
                },
            )
        }
    }

    /**
     * 删除选中的邮件：只做本地软删除（不传 server），确认后调用 batch(ids, "delete")。
     * 被策略拦截的邮件留在列表里，并用 Snackbar 说明。
     */
    private fun deleteSelected() {
        val current = _state.value
        val ids = current.selected.toList()
        if (ids.isEmpty() || current.busy) {
            _state.update { it.copy(confirmingDelete = false) }
            return
        }
        _state.update { it.copy(confirmingDelete = false, busy = true, notice = null) }
        viewModelScope.launch {
            gateway.batch(BatchRequest(ids = ids, action = "delete", confirmed = true)).fold(
                onSuccess = { result -> applyDelete(result, ids) },
                onFailure = { error ->
                    _state.update { it.copy(busy = false, notice = inboxErrorText(error)) }
                },
            )
        }
    }

    private fun applyDelete(result: BatchResult, ids: List<Long>) {
        val blocked = blockedIds(result)
        val removed = ids.filterNot { it in blocked }.toSet()
        _state.update { state ->
            state.copy(
                busy = false,
                selectMode = false,
                selected = emptySet(),
                items = state.items.filterNot { it.id in removed },
                notice = if (blocked.isEmpty()) {
                    null
                } else {
                    "${blocked.size} 封受保护邮件未删除，可在详情页逐封处理"
                },
            )
        }
    }

    private fun blockedIds(result: BatchResult): Set<Long> =
        result.blocked.mapTo(HashSet(result.blocked.size)) { it.id }

    /** 详情页打开邮件后回写已读状态，返回收件箱时这一行已经是已读。 */
    fun markReadLocally(id: Long) {
        _state.update { state ->
            state.copy(
                items = state.items.map { item ->
                    if (item.id == id && item.unread == 1) item.copy(unread = 0) else item
                },
            )
        }
    }

    /** 详情页「标为未读」后回写。 */
    fun markUnreadLocally(id: Long) {
        _state.update { state ->
            state.copy(
                items = state.items.map { item ->
                    if (item.id == id && item.unread == 0) item.copy(unread = 1) else item
                },
            )
        }
    }

    /** 详情页删除成功后把这一行从列表里移除。 */
    fun removeLocally(id: Long) {
        _state.update { state ->
            state.copy(items = state.items.filterNot { it.id == id }, selected = state.selected - id)
        }
    }
}

/** 手工工厂：收件箱只依赖邮件仓库。 */
class InboxViewModelFactory(
    private val mailRepository: MailRepository,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return InboxViewModel(inboxGatewayOf(mailRepository)) as T
    }
}
