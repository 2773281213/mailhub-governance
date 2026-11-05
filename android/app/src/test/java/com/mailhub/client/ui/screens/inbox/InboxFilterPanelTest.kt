package com.mailhub.client.ui.screens.inbox

import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.BatchRequest
import com.mailhub.client.data.model.BatchResult
import com.mailhub.client.data.model.MessagePage
import com.mailhub.client.data.model.Meta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 收件箱筛选区：默认收起、摘要随筛选变化、展开与收起不发请求。
 * 网关换成假实现，直接断言收起状态下第一屏只剩搜索框和一行摘要。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxFilterPanelTest {

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

    /** 假网关：只记录请求，返回一页固定的邮件。 */
    private class FakeInboxGateway : InboxGateway {

        val requests = mutableListOf<Pair<InboxFilters, String?>>()
        val batches = mutableListOf<BatchRequest>()

        override suspend fun messages(filters: InboxFilters, cursor: String?): Result<MessagePage> {
            requests += filters to cursor
            return Result.success(
                MessagePage(items = listOf(inboxSummary(1), inboxSummary(2)), nextCursor = null),
            )
        }

        override suspend fun meta(): Result<Meta> = Result.success(
            Meta(displayCategories = InboxFixtureCategories),
        )

        override suspend fun accounts(): Result<List<Account>> = Result.success(InboxFixtureAccounts)

        override suspend fun batch(request: BatchRequest): Result<BatchResult> {
            batches += request
            return Result.success(BatchResult(ok = true, count = request.ids.size))
        }
    }

    @Test
    fun filtersStartCollapsed() = runTest(dispatcher) {
        val gateway = FakeInboxGateway()
        val viewModel = InboxViewModel(gateway)

        // 收件箱一打开只显示搜索框、状态行和一行摘要
        assertFalse(viewModel.state.value.filtersExpanded)

        viewModel.onEnter()
        advanceUntilIdle()
        assertFalse("进入收件箱后仍然是收起状态", viewModel.state.value.filtersExpanded)
    }

    @Test
    fun togglingFiltersSendsNoRequest() = runTest(dispatcher) {
        val gateway = FakeInboxGateway()
        val viewModel = InboxViewModel(gateway)
        viewModel.onEnter()
        advanceUntilIdle()
        gateway.requests.clear()

        viewModel.onEvent(InboxEvent.ToggleFilters)
        assertTrue(viewModel.state.value.filtersExpanded)
        viewModel.onEvent(InboxEvent.ToggleFilters)
        assertFalse(viewModel.state.value.filtersExpanded)
        advanceUntilIdle()

        // 展开或收起只是界面状态，一次请求都不能发
        assertTrue("展开 / 收起不能发请求", gateway.requests.isEmpty())
        assertTrue(gateway.batches.isEmpty())

        // 选择筛选项后保持展开，由用户自己收起
        viewModel.onEvent(InboxEvent.ToggleFilters)
        viewModel.onEvent(InboxEvent.SelectCategory("安全"))
        advanceUntilIdle()
        assertTrue("选完筛选项仍然展开", viewModel.state.value.filtersExpanded)
        // 选筛选项本身要重新加载
        assertEquals(1, gateway.requests.size)
    }

    @Test
    fun summaryFollowsTheActiveFilters() {
        assertEquals(
            "分类：全部 · 账户：全部账户",
            filtersSummary(InboxFilters(), InboxFixtureCategories, InboxFixtureAccounts),
        )
        assertEquals(
            "分类：05 安全 · 账户：A1 工作邮箱",
            filtersSummary(
                InboxFilters(unreadOnly = true, category = "安全", accountId = 1),
                InboxFixtureCategories,
                InboxFixtureAccounts,
            ),
        )
        assertEquals(
            "分类：01 重要账单 · 账户：A2 私有邮箱",
            filtersSummary(
                InboxFilters(category = "重要账单", accountId = 2),
                InboxFixtureCategories,
                InboxFixtureAccounts,
            ),
        )
    }

    @Test
    fun summaryFallsBackWhenTheSelectionIsUnknown() {
        // 分类列表还没到：拿不到编号，只写分类名，不编造编号
        assertEquals(
            "分类：安全 · 账户：全部账户",
            filtersSummary(InboxFilters(category = "安全"), emptyList(), InboxFixtureAccounts),
        )
        // 账户对不上账户列表：退回「全部账户」，不显示空值
        assertEquals(
            "分类：安全 · 账户：全部账户",
            filtersSummary(InboxFilters(category = "安全", accountId = 9), emptyList(), emptyList()),
        )
    }
}
