package com.mailhub.client.ui.screens.accounts

import com.mailhub.client.data.accounts.ConnectionTest
import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.SyncState
import com.mailhub.client.data.net.ApiError
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 账户列表状态机：加载、展开操作行、同步 / 测试，以及最关键的「删除必须先确认才发请求」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountsViewModelTest {

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

    private class FakeAccountsGateway : AccountsGateway {

        var listResult: Result<List<Account>> = Result.success(
            listOf(
                Account(id = 1, name = "工作邮箱", email = "work@example.com"),
                Account(id = 2, name = "私有邮箱", email = "me@example.com"),
            ),
        )

        var deleteResult: Result<Unit> = Result.success(Unit)
        var syncResult: Result<Unit> = Result.success(Unit)
        var testResult: Result<ConnectionTest> = Result.success(
            ConnectionTest(ok = true, msg = "连接正常"),
        )

        val deletes = mutableListOf<Int>()
        val syncs = mutableListOf<Int>()
        var syncAllCalls = 0
        var listCalls = 0

        override suspend fun accounts(): Result<List<Account>> {
            listCalls++
            return listResult
        }

        override suspend fun syncAll(): Result<Unit> {
            syncAllCalls++
            return Result.success(Unit)
        }

        override suspend fun sync(accountId: Int): Result<Unit> {
            syncs += accountId
            return syncResult
        }

        override suspend fun test(accountId: Int): Result<ConnectionTest> = testResult

        override suspend fun delete(accountId: Int): Result<Unit> {
            deletes += accountId
            return deleteResult
        }
    }

    @Test
    fun deleteSendsNothingBeforeConfirmation() = runTest(dispatcher) {
        val gateway = FakeAccountsGateway()
        val viewModel = AccountsViewModel(gateway)
        viewModel.onEnter()
        advanceUntilIdle()
        assertEquals(2, viewModel.state.value.accounts.size)

        viewModel.onEvent(AccountsEvent.RequestDelete(1))
        advanceUntilIdle()

        // 只弹确认面板，还没发 DELETE
        assertEquals(1, viewModel.state.value.confirmingDeleteId)
        assertTrue(gateway.deletes.isEmpty())

        viewModel.onEvent(AccountsEvent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(listOf(1), gateway.deletes)
        assertEquals(0, viewModel.state.value.confirmingDeleteId)
        assertEquals(listOf(2), viewModel.state.value.accounts.map { it.id })
        assertEquals("已删除 A1", viewModel.state.value.notice)
    }

    @Test
    fun dismissDeleteSendsNothing() = runTest(dispatcher) {
        val gateway = FakeAccountsGateway()
        val viewModel = AccountsViewModel(gateway)
        viewModel.onEnter()
        advanceUntilIdle()

        viewModel.onEvent(AccountsEvent.RequestDelete(2))
        viewModel.onEvent(AccountsEvent.DismissDelete)
        advanceUntilIdle()

        assertTrue(gateway.deletes.isEmpty())
        assertEquals(0, viewModel.state.value.confirmingDeleteId)
        assertEquals(2, viewModel.state.value.accounts.size)
    }

    @Test
    fun deleteFailureKeepsRowAndShowsDetail() = runTest(dispatcher) {
        val gateway = FakeAccountsGateway().apply {
            deleteResult = Result.failure(ApiError.Server(404, "账户不存在"))
        }
        val viewModel = AccountsViewModel(gateway)
        viewModel.onEnter()
        advanceUntilIdle()

        viewModel.onEvent(AccountsEvent.RequestDelete(1))
        viewModel.onEvent(AccountsEvent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(2, viewModel.state.value.accounts.size)
        assertEquals("账户不存在", viewModel.state.value.notice)
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun toggleExpandsOnlyOneRow() = runTest(dispatcher) {
        val gateway = FakeAccountsGateway()
        val viewModel = AccountsViewModel(gateway)
        viewModel.onEnter()
        advanceUntilIdle()

        viewModel.onEvent(AccountsEvent.Toggle(1))
        assertEquals(1, viewModel.state.value.expandedId)

        viewModel.onEvent(AccountsEvent.Toggle(2))
        assertEquals(2, viewModel.state.value.expandedId)

        viewModel.onEvent(AccountsEvent.Toggle(2))
        assertEquals(0, viewModel.state.value.expandedId)
    }

    @Test
    fun syncAllAndTestConnectionOnlyTouchTheGateway() = runTest(dispatcher) {
        val gateway = FakeAccountsGateway()
        val viewModel = AccountsViewModel(gateway)
        viewModel.onEnter()
        advanceUntilIdle()

        viewModel.onEvent(AccountsEvent.SyncAll)
        advanceUntilIdle()
        assertEquals(1, gateway.syncAllCalls)

        viewModel.onEvent(AccountsEvent.SyncNow(2))
        advanceUntilIdle()
        assertEquals(listOf(2), gateway.syncs)

        viewModel.onEvent(AccountsEvent.TestConnection(1))
        advanceUntilIdle()
        assertEquals("连接正常", viewModel.state.value.notice)
        assertTrue(gateway.deletes.isEmpty())
    }

    @Test
    fun loadFailureShowsErrorText() = runTest(dispatcher) {
        val gateway = FakeAccountsGateway().apply {
            listResult = Result.failure(ApiError.Network("断网"))
        }
        val viewModel = AccountsViewModel(gateway)
        viewModel.onEnter()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.accounts.isEmpty())
        assertEquals("无法连接服务器，请检查网络后重试", viewModel.state.value.error)
        assertFalse(viewModel.state.value.loading)
        assertNull(viewModel.state.value.notice)
    }

    @Test
    fun refreshFromAddFlowReloadsList() = runTest(dispatcher) {
        val gateway = FakeAccountsGateway()
        val viewModel = AccountsViewModel(gateway)
        viewModel.onEnter()
        advanceUntilIdle()

        gateway.listResult = Result.success(
            listOf(
                Account(id = 1, name = "工作邮箱", email = "work@example.com"),
                Account(id = 3, name = "客户邮箱", email = "hello@example.com"),
            ),
        )
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listOf(1, 3), viewModel.state.value.accounts.map { it.id })
        assertEquals(2, gateway.listCalls)
    }

    @Test
    fun syncStatusTextCoversThreeStates() {
        val idle = Account(
            id = 1,
            syncState = SyncState(state = SyncStateIdle, ts = AccountsFixtureNowTs - 12 * 60),
        )
        val syncing = Account(id = 2, syncState = SyncState(state = SyncStateSyncing, msg = "拉取 12 封"))
        val failed = Account(
            id = 3,
            syncState = SyncState(state = SyncStateError, msg = "连接测试失败"),
            lastError = "旧错误",
        )

        assertEquals("空闲 · 16:42", syncStatusText(idle, AccountsFixtureZone))
        assertEquals("同步中", syncStatusText(syncing, AccountsFixtureZone))
        assertEquals("出错", syncStatusText(failed, AccountsFixtureZone))
        // 出错时才带说明，且优先用这次同步失败的原因
        assertEquals("连接测试失败", syncErrorText(failed))
        assertEquals("", syncErrorText(idle))
        // 没有上次同步时间就只写「空闲」
        assertEquals("空闲", syncStatusText(Account(id = 4), AccountsFixtureZone))
    }
}
