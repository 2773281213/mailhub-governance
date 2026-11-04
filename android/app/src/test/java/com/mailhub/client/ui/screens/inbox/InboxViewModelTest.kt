package com.mailhub.client.ui.screens.inbox

import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.BatchRequest
import com.mailhub.client.data.model.BatchResult
import com.mailhub.client.data.model.BlockedItem
import com.mailhub.client.data.model.Cursor
import com.mailhub.client.data.model.MessagePage
import com.mailhub.client.data.model.Meta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 收件箱状态机：游标分页与去重、筛选重置、搜索防抖、多选与批量删除。
 * 网关换成假实现，直接断言发出的游标、筛选条件和 batch 请求参数。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 假网关：按 (筛选, 游标) 返回预置的一页，并记录每一次请求。 */
    private class FakeInboxGateway(
        private val pages: (InboxFilters, String?) -> Result<MessagePage> = { _, _ ->
            Result.success(MessagePage())
        },
    ) : InboxGateway {

        val requests = mutableListOf<Pair<InboxFilters, String?>>()
        val batches = mutableListOf<BatchRequest>()

        /** 批量接口的返回；默认认为全部成功。 */
        var batchResult: (BatchRequest) -> Result<BatchResult> = { request ->
            Result.success(BatchResult(ok = true, count = request.ids.size))
        }

        override suspend fun messages(filters: InboxFilters, cursor: String?): Result<MessagePage> {
            requests += filters to cursor
            return pages(filters, cursor)
        }

        override suspend fun meta(): Result<Meta> = Result.success(
            Meta(displayCategories = InboxFixtureCategories),
        )

        override suspend fun accounts(): Result<List<Account>> = Result.success(InboxFixtureAccounts)

        override suspend fun batch(request: BatchRequest): Result<BatchResult> {
            batches += request
            return batchResult(request)
        }

        /** 记录到的游标序列。 */
        fun cursors(): List<String?> = requests.map { it.second }
    }

    private fun ids(viewModel: InboxViewModel): List<Long> = viewModel.state.value.items.map { it.id }

    private fun onePage(vararg ids: Long): Result<MessagePage> = Result.success(
        MessagePage(items = ids.map { inboxSummary(it) }, nextCursor = null),
    )

    @Test
    fun cursorPaginationDropsDuplicateAndStopsAtEnd() = runTest(dispatcher) {
        val first = MessagePage(
            items = listOf(inboxSummary(1), inboxSummary(2), inboxSummary(3)),
            nextCursor = Cursor(token = "c2"),
        )
        // 服务端翻页边界会把上一页最后一条再下发一次
        val second = MessagePage(
            items = listOf(inboxSummary(3), inboxSummary(4), inboxSummary(5)),
            nextCursor = Cursor(token = "c3"),
        )
        val third = MessagePage(items = listOf(inboxSummary(6), inboxSummary(7)), nextCursor = null)
        val gateway = FakeInboxGateway { _, cursor ->
            Result.success(
                when (cursor) {
                    null -> first
                    "c2" -> second
                    "c3" -> third
                    else -> MessagePage()
                },
            )
        }
        val viewModel = InboxViewModel(gateway)

        viewModel.onEnter()
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L), ids(viewModel))

        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids(viewModel))

        viewModel.loadMore()
        advanceUntilIdle()
        // 三页合起来无重复、无遗漏
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L), ids(viewModel))
        assertEquals(7, ids(viewModel).distinct().size)

        // next_cursor 为 null 之后不再请求
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(null, "c2", "c3"), gateway.cursors())
        assertTrue(viewModel.state.value.endReached)
        assertFalse(viewModel.state.value.loadingMore)
    }

    @Test
    fun changingFilterRestartsFromTheFirstPage() = runTest(dispatcher) {
        val gateway = FakeInboxGateway { filters, cursor ->
            val base = if (filters.category == "安全") 100L else 1L
            Result.success(
                if (cursor == null) {
                    MessagePage(items = listOf(inboxSummary(base)), nextCursor = Cursor(token = "t-$base"))
                } else {
                    MessagePage(items = listOf(inboxSummary(base + 1)), nextCursor = null)
                },
            )
        }
        val viewModel = InboxViewModel(gateway)

        viewModel.onEnter()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), ids(viewModel))

        gateway.requests.clear()
        viewModel.onEvent(InboxEvent.SelectCategory("安全"))
        advanceUntilIdle()

        // 换筛选条件只发一次请求，且从第一页重新开始
        assertEquals(1, gateway.requests.size)
        assertNull(gateway.requests.single().second)
        assertEquals("安全", gateway.requests.single().first.category)
        assertEquals(listOf(100L), ids(viewModel))
        assertFalse(viewModel.state.value.endReached)
    }

    @Test
    fun searchDebounceSendsOnlyTheLastQuery() = runTest(dispatcher) {
        val gateway = FakeInboxGateway { _, _ -> onePage(1) }
        val viewModel = InboxViewModel(gateway)

        viewModel.onEnter()
        advanceUntilIdle()
        gateway.requests.clear()

        viewModel.onEvent(InboxEvent.QueryChanged("账"))
        viewModel.onEvent(InboxEvent.QueryChanged("账单"))
        viewModel.onEvent(InboxEvent.QueryChanged("账单 11"))
        // 输入立刻回显，但查询要等防抖
        assertEquals("账单 11", viewModel.state.value.filters.query)

        advanceTimeBy(InboxSearchDebounceMillis - 1)
        assertTrue("防抖期间不应该发请求", gateway.requests.isEmpty())

        advanceUntilIdle()
        assertEquals(1, gateway.requests.size)
        assertEquals("账单 11", gateway.requests.single().first.query)
    }

    @Test
    fun longPressEntersSelectModeAndCancelLeaves() = runTest(dispatcher) {
        val gateway = FakeInboxGateway { _, _ -> onePage(1, 2, 3) }
        val viewModel = InboxViewModel(gateway)
        viewModel.onEnter()
        advanceUntilIdle()

        viewModel.onEvent(InboxEvent.LongPress(1))
        assertTrue(viewModel.state.value.selectMode)
        assertEquals(setOf(1L), viewModel.state.value.selected)

        viewModel.onEvent(InboxEvent.ToggleSelected(2))
        assertEquals(setOf(1L, 2L), viewModel.state.value.selected)

        viewModel.onEvent(InboxEvent.ToggleSelected(2))
        assertEquals(setOf(1L), viewModel.state.value.selected)

        viewModel.onEvent(InboxEvent.CancelSelect)
        assertFalse(viewModel.state.value.selectMode)
        assertTrue(viewModel.state.value.selected.isEmpty())
        // 取消多选不发任何批量请求
        assertTrue(gateway.batches.isEmpty())
    }

    @Test
    fun deleteConfirmationSendsTheSelectedIdsAsSoftDelete() = runTest(dispatcher) {
        val gateway = FakeInboxGateway { _, _ -> onePage(1, 2, 3) }
        val viewModel = InboxViewModel(gateway)
        viewModel.onEnter()
        advanceUntilIdle()

        viewModel.onEvent(InboxEvent.LongPress(1))
        viewModel.onEvent(InboxEvent.ToggleSelected(3))
        viewModel.onEvent(InboxEvent.RequestDelete)
        assertTrue(viewModel.state.value.confirmingDelete)
        assertTrue("确认前不能发请求", gateway.batches.isEmpty())

        viewModel.onEvent(InboxEvent.ConfirmDelete)
        advanceUntilIdle()

        val request = gateway.batches.single()
        assertEquals(listOf(1L, 3L), request.ids.sorted())
        assertEquals("delete", request.action)
        assertTrue("用户确认过", request.confirmed)
        assertFalse("删除只做本地软删除，不能传 server=true", request.server)
        assertFalse(request.overrideProtection)
        // 删除成功的行从列表里消失，多选状态退出
        assertEquals(listOf(2L), ids(viewModel))
        assertFalse(viewModel.state.value.selectMode)
        assertFalse(viewModel.state.value.busy)
        assertNull(viewModel.state.value.notice)
    }

    @Test
    fun blockedMailsStayInTheListWithNotice() = runTest(dispatcher) {
        val gateway = FakeInboxGateway { _, _ -> onePage(1, 2) }
        gateway.batchResult = {
            Result.success(
                BatchResult(
                    ok = true,
                    count = 1,
                    blocked = listOf(BlockedItem(id = 1, reason = "受保护邮件", protected = true)),
                ),
            )
        }
        val viewModel = InboxViewModel(gateway)
        viewModel.onEnter()
        advanceUntilIdle()

        viewModel.onEvent(InboxEvent.LongPress(1))
        viewModel.onEvent(InboxEvent.ToggleSelected(2))
        viewModel.onEvent(InboxEvent.RequestDelete)
        viewModel.onEvent(InboxEvent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(listOf(1L), ids(viewModel))
        assertEquals("1 封受保护邮件未删除，可在详情页逐封处理", viewModel.state.value.notice)
    }

    @Test
    fun markingReadUpdatesOnlyTheDoneRows() = runTest(dispatcher) {
        val gateway = FakeInboxGateway { _, _ -> onePage(1, 2) }
        val viewModel = InboxViewModel(gateway)
        viewModel.onEnter()
        advanceUntilIdle()

        viewModel.onEvent(InboxEvent.LongPress(1))
        viewModel.onEvent(InboxEvent.SetRead(true))
        advanceUntilIdle()

        val request = gateway.batches.single()
        assertEquals(listOf(1L), request.ids)
        assertEquals("read", request.action)
        assertEquals(0, viewModel.state.value.items.first { it.id == 1L }.unread)
        assertFalse(viewModel.state.value.selectMode)
    }
}
