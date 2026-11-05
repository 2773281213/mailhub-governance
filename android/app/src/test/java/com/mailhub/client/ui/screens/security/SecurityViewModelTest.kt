package com.mailhub.client.ui.screens.security

import com.mailhub.client.data.net.ApiError
import com.mailhub.client.data.security.AuditEntry
import com.mailhub.client.data.security.AuditPage
import com.mailhub.client.data.security.DeviceSession
import com.mailhub.client.data.security.RestoreResult
import com.mailhub.client.data.security.ReviewItem
import com.mailhub.client.data.security.RevokeAllResult
import com.mailhub.client.data.security.TrashItem
import com.mailhub.client.data.security.UndoResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 安全中心状态机：回收站多选恢复的 ids、审计撤销与分页、本机不能吊销、
 * 吊销与退出所有设备都要先确认、退出所有设备后换令牌。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecurityViewModelTest {

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

    private class FakeSecurityGateway : SecurityGateway {

        var trashItems: List<TrashItem> = TrashFixture
        var auditPages: MutableMap<Int, AuditPage> = mutableMapOf(0 to AuditPage(total = 4, items = AuditFixture))
        var devicesList: List<DeviceSession> = DevicesFixture
        var undoResult: Result<UndoResult> = Result.success(UndoResult(ok = true, restored = 3))
        var revokeAllResult: Result<RevokeAllResult> = Result.success(
            RevokeAllResult(ok = true, revoked = 3, token = "tok-new", sessionId = "sid-new", expiresTs = 200.0),
        )
        var replaceResult = true

        val restores = mutableListOf<List<Long>>()
        val undos = mutableListOf<Long>()
        val auditOffsets = mutableListOf<Int>()
        val revokes = mutableListOf<String>()
        var revokeAllCalls = 0
        val replaced = mutableListOf<Triple<String, String, Double>>()

        override suspend fun review(): Result<List<ReviewItem>> = Result.success(ReviewFixture)

        override suspend fun trash(): Result<List<TrashItem>> = Result.success(trashItems)

        override suspend fun restore(ids: List<Long>): Result<RestoreResult> {
            restores += ids
            return Result.success(RestoreResult(ok = true, count = ids.size))
        }

        override suspend fun audit(offset: Int): Result<AuditPage> {
            auditOffsets += offset
            return Result.success(auditPages[offset] ?: AuditPage(total = 0))
        }

        override suspend fun undo(auditId: Long): Result<UndoResult> {
            undos += auditId
            return undoResult
        }

        override suspend fun devices(): Result<List<DeviceSession>> = Result.success(devicesList)

        override suspend fun revoke(sessionId: String): Result<Unit> {
            revokes += sessionId
            return Result.success(Unit)
        }

        override suspend fun revokeAll(): Result<RevokeAllResult> {
            revokeAllCalls++
            return revokeAllResult
        }

        override suspend fun replaceToken(token: String, sessionId: String, expiresTs: Double): Boolean {
            replaced += Triple(token, sessionId, expiresTs)
            return replaceResult
        }
    }

    private fun TestScope.loaded(gateway: FakeSecurityGateway): SecurityViewModel {
        val viewModel = SecurityViewModel(gateway)
        viewModel.onEnter()
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun restoringSendsTheSelectedIdsInListOrder() = runTest(dispatcher) {
        val gateway = FakeSecurityGateway()
        val viewModel = loaded(gateway)

        // 先点第 3 封，再点第 1 封，再点第 2 封又取消
        viewModel.onEvent(SecurityEvent.ToggleTrash(23))
        viewModel.onEvent(SecurityEvent.ToggleTrash(21))
        viewModel.onEvent(SecurityEvent.ToggleTrash(22))
        viewModel.onEvent(SecurityEvent.ToggleTrash(22))
        viewModel.onEvent(SecurityEvent.RestoreSelected)
        advanceUntilIdle()

        assertEquals(listOf(listOf(21L, 23L)), gateway.restores)
        val state = viewModel.state.value
        assertEquals(listOf(22L), state.trash.valueOrNull()?.map { it.id })
        assertTrue(state.selectedTrash.isEmpty())
        assertEquals("已恢复 2 封", state.notices[SecuritySection.Trash])
    }

    @Test
    fun restoringWithNothingSelectedSendsNothing() = runTest(dispatcher) {
        val gateway = FakeSecurityGateway()
        val viewModel = loaded(gateway)

        viewModel.onEvent(SecurityEvent.RestoreSelected)
        advanceUntilIdle()

        assertTrue(gateway.restores.isEmpty())
    }

    @Test
    fun undoRefreshesAuditAndTrash() = runTest(dispatcher) {
        val gateway = FakeSecurityGateway()
        val viewModel = loaded(gateway)
        gateway.trashItems = TrashFixture + TrashItem(id = 24, subject = "撤销后回来的邮件")

        viewModel.onEvent(SecurityEvent.Undo(105))
        advanceUntilIdle()

        assertEquals(listOf(105L), gateway.undos)
        assertEquals("已撤销，恢复 3 封", viewModel.state.value.notices[SecuritySection.Audit])
        // 进入时拉一次，撤销后再拉一次
        assertEquals(listOf(0, 0), gateway.auditOffsets)
        assertEquals(4, viewModel.state.value.trash.valueOrNull()?.size)
    }

    @Test
    fun undoFailureShowsTheBackendDetail() = runTest(dispatcher) {
        val gateway = FakeSecurityGateway().apply {
            undoResult = Result.failure(ApiError.Server(400, "该操作已撤销过"))
        }
        val viewModel = loaded(gateway)

        viewModel.onEvent(SecurityEvent.Undo(104))
        advanceUntilIdle()

        assertEquals("该操作已撤销过", viewModel.state.value.notices[SecuritySection.Audit])
    }

    @Test
    fun loadMoreAppendsTheNextPageByOffset() = runTest(dispatcher) {
        val first = (1L..50L).map { AuditEntry(id = 1000 - it, action = "read") }
        val second = listOf(AuditEntry(id = 900, action = "read"), AuditEntry(id = 899, action = "unread"))
        val gateway = FakeSecurityGateway().apply {
            auditPages = mutableMapOf(
                0 to AuditPage(total = 52, items = first),
                50 to AuditPage(total = 52, items = second),
            )
        }
        val viewModel = loaded(gateway)
        assertTrue(viewModel.state.value.audit.valueOrNull()!!.hasMore)

        viewModel.onEvent(SecurityEvent.LoadMoreAudit)
        advanceUntilIdle()

        val list = viewModel.state.value.audit.valueOrNull()!!
        assertEquals(listOf(0, 50), gateway.auditOffsets)
        assertEquals(52, list.items.size)
        assertEquals(false, list.hasMore)
    }

    @Test
    fun theCurrentDeviceCannotBeRevoked() = runTest(dispatcher) {
        val gateway = FakeSecurityGateway()
        val viewModel = loaded(gateway)

        viewModel.onEvent(SecurityEvent.RequestRevoke("a1b2c3d4e5f60718"))
        viewModel.onEvent(SecurityEvent.Confirm)
        advanceUntilIdle()

        assertNull(viewModel.state.value.confirming)
        assertTrue(gateway.revokes.isEmpty())
    }

    @Test
    fun revokingAnotherDeviceNeedsConfirmation() = runTest(dispatcher) {
        val gateway = FakeSecurityGateway()
        val viewModel = loaded(gateway)

        viewModel.onEvent(SecurityEvent.RequestRevoke("0f1e2d3c4b5a6978"))
        advanceUntilIdle()
        // 只弹确认面板，还没发请求
        assertTrue(viewModel.state.value.confirming is SecurityConfirm.RevokeDevice)
        assertTrue(gateway.revokes.isEmpty())

        viewModel.onEvent(SecurityEvent.Confirm)
        advanceUntilIdle()

        assertEquals(listOf("0f1e2d3c4b5a6978"), gateway.revokes)
        assertEquals(2, viewModel.state.value.devices.valueOrNull()?.size)
        assertEquals("已吊销「网页」", viewModel.state.value.notices[SecuritySection.Devices])
    }

    @Test
    fun dismissingTheConfirmationSendsNothing() = runTest(dispatcher) {
        val gateway = FakeSecurityGateway()
        val viewModel = loaded(gateway)

        viewModel.onEvent(SecurityEvent.RequestRevokeAll)
        viewModel.onEvent(SecurityEvent.DismissConfirm)
        advanceUntilIdle()

        assertEquals(0, gateway.revokeAllCalls)
        assertNull(viewModel.state.value.confirming)
    }

    @Test
    fun revokeAllReplacesTheTokenWithTheNewOne() = runTest(dispatcher) {
        val gateway = FakeSecurityGateway()
        val viewModel = loaded(gateway)

        viewModel.onEvent(SecurityEvent.RequestRevokeAll)
        viewModel.onEvent(SecurityEvent.Confirm)
        advanceUntilIdle()

        assertEquals(1, gateway.revokeAllCalls)
        assertEquals(listOf(Triple("tok-new", "sid-new", 200.0)), gateway.replaced)
        // revoked 含本机原来的会话
        assertEquals("已退出 2 台设备", viewModel.state.value.notices[SecuritySection.Devices])
    }

    @Test
    fun revokeAllWithoutANewTokenAsksToLogInAgain() = runTest(dispatcher) {
        val gateway = FakeSecurityGateway().apply {
            revokeAllResult = Result.success(RevokeAllResult(ok = true, revoked = 3))
        }
        val viewModel = loaded(gateway)

        viewModel.onEvent(SecurityEvent.RequestRevokeAll)
        viewModel.onEvent(SecurityEvent.Confirm)
        advanceUntilIdle()

        assertTrue(gateway.replaced.isEmpty())
        assertEquals("已退出所有设备，请重新登录", viewModel.state.value.notices[SecuritySection.Devices])
    }

    @Test
    fun refreshFailureKeepsWhatIsAlreadyShown() = runTest(dispatcher) {
        val gateway = object : SecurityGateway by FakeSecurityGateway() {
            var fail = false

            override suspend fun trash(): Result<List<TrashItem>> =
                if (fail) Result.failure(ApiError.Network("timeout")) else Result.success(TrashFixture)
        }
        val viewModel = SecurityViewModel(gateway)
        viewModel.onEnter()
        advanceUntilIdle()

        gateway.fail = true
        viewModel.onEvent(SecurityEvent.Retry(SecuritySection.Trash))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(3, state.trash.valueOrNull()?.size)
        assertEquals("无法连接服务器，请检查网络后重试", state.notices[SecuritySection.Trash])
    }
}
