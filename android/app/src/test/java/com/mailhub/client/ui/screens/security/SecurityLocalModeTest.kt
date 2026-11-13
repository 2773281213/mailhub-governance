package com.mailhub.client.ui.screens.security

import com.mailhub.client.data.model.Capabilities
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 安全中心按模式取区块：本机模式没有「已登录设备」，也一次都不请求 `/api/sessions`；
 * 服务器模式仍是四块，进入页面就拉一次设备列表。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecurityLocalModeTest {

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

    /** 只关心设备列表有没有被请求；其余各块返回固定夹具。 */
    private class FakeSecurityGateway : SecurityGateway {

        var deviceCalls = 0
        var auditCalls = 0

        override suspend fun review(): Result<List<ReviewItem>> = Result.success(ReviewFixture)

        override suspend fun trash(): Result<List<TrashItem>> = Result.success(TrashFixture)

        override suspend fun restore(ids: List<Long>): Result<RestoreResult> =
            Result.success(RestoreResult(ok = true, count = ids.size))

        override suspend fun audit(offset: Int): Result<AuditPage> {
            auditCalls++
            return Result.success(AuditPage(total = AuditFixture.size, items = AuditFixture))
        }

        override suspend fun undo(auditId: Long): Result<UndoResult> =
            Result.success(UndoResult(ok = true, restored = 1))

        override suspend fun devices(): Result<List<DeviceSession>> {
            deviceCalls++
            return Result.success(DevicesFixture)
        }

        override suspend fun revoke(sessionId: String): Result<Unit> = Result.success(Unit)

        override suspend fun revokeAll(): Result<RevokeAllResult> = Result.success(RevokeAllResult(ok = true))

        override suspend fun replaceToken(token: String, sessionId: String, expiresTs: Double): Boolean = true
    }

    private fun TestScope.loaded(
        gateway: FakeSecurityGateway = FakeSecurityGateway(),
        localMode: Boolean = false,
    ): SecurityViewModel {
        val viewModel = SecurityViewModel(
            gateway,
            capabilities = if (localMode) Capabilities.Local else Capabilities.Server,
        )
        viewModel.onEnter()
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun localModeHasNoDevicesSection() = runTest(dispatcher) {
        val state = loaded(localMode = true).state.value

        assertEquals(
            listOf(SecuritySection.Review, SecuritySection.Trash, SecuritySection.Audit),
            state.sections,
        )
        assertFalse(SecuritySection.Devices in state.sections)
    }

    @Test
    fun localModeNeverRequestsSessions() = runTest(dispatcher) {
        val gateway = FakeSecurityGateway()
        val viewModel = loaded(gateway = gateway, localMode = true)

        // 进入时三块各拉一次，设备列表一次都不拉
        assertEquals(0, gateway.deviceCalls)
        assertEquals(1, gateway.auditCalls)
        assertTrue(viewModel.state.value.review.valueOrNull() != null)

        // 重试请求也不能把设备列表拉回来
        viewModel.onEvent(SecurityEvent.Retry(SecuritySection.Devices))
        advanceUntilIdle()
        assertEquals(0, gateway.deviceCalls)
    }

    @Test
    fun serverModeStillLoadsTheDevicesSection() = runTest(dispatcher) {
        val gateway = FakeSecurityGateway()
        val viewModel = loaded(gateway = gateway, localMode = false)

        assertEquals(SecuritySection.entries.toList(), viewModel.state.value.sections)
        assertEquals(1, gateway.deviceCalls)
        assertEquals(DevicesFixture, viewModel.state.value.devices.valueOrNull())
    }
}
