package com.mailhub.client.ui.screens.otp

import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.OtpItem
import com.mailhub.client.data.net.ApiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
 * 看板状态机：刷新循环的节奏与停止、失败不清空、时钟自己推进红色判定、复制提示。
 * 网关换成假实现并记录每次请求的条数上限，用虚拟时间跑刷新循环。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OtpViewModelTest {

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

    /** 假网关：按 [items] 返回同一个列表，并记录每一次请求的 limit。 */
    private class FakeOtpGateway(
        var items: List<OtpItem> = emptyList(),
        private val accounts: List<Account> = OtpFixtureAccounts,
    ) : OtpGateway {

        val limits = mutableListOf<Int>()

        var accountCalls = 0

        /** 打开后所有刷新都失败。 */
        var failing = false

        override suspend fun otp(limit: Int): Result<List<OtpItem>> {
            limits += limit
            return if (failing) {
                Result.failure(ApiError.Network("断网"))
            } else {
                Result.success(items)
            }
        }

        override suspend fun accounts(): Result<List<Account>> {
            accountCalls++
            return Result.success(accounts)
        }
    }

    @Test
    fun refreshLoopRequestsThreeTimesInThirtySeconds() = runTest(dispatcher) {
        val gateway = FakeOtpGateway(items = listOf(otpFixtureItemNewest()))
        val viewModel = OtpViewModel(gateway)

        val job = launch { viewModel.refreshLoop().collect { } }
        // 0 秒一次，之后每 10 秒一次：30 秒内正好 3 次
        advanceTimeBy(OtpRefreshIntervalMillis * 3)
        assertEquals(3, gateway.limits.size)
        assertTrue("每次都要 24 条上限", gateway.limits.all { it == OtpListLimit })
        assertEquals(1, viewModel.state.value.items.size)

        // 停止收集（页面不可见 / 进后台）后不再有任何请求
        job.cancel()
        gateway.limits.clear()
        advanceTimeBy(OtpRefreshIntervalMillis * 6)
        assertTrue(gateway.limits.isEmpty())
    }

    @Test
    fun failedRefreshKeepsTheExistingBoard() = runTest(dispatcher) {
        val gateway = FakeOtpGateway(items = listOf(otpFixtureItemNewest()))
        val viewModel = OtpViewModel(gateway)

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.items.size)
        assertFalse(viewModel.state.value.refreshFailed)

        gateway.failing = true
        viewModel.refresh()
        advanceUntilIdle()
        // 失败不清空已有内容，只在底部加一行说明
        assertEquals(1, viewModel.state.value.items.size)
        assertTrue(viewModel.state.value.refreshFailed)
    }

    @Test
    fun freshnessFollowsTheInjectedClockWithoutAnyNetworkCall() = runTest(dispatcher) {
        var nowMillis = (OtpFixtureNowTs * 1000).toLong()
        val gateway = FakeOtpGateway(items = listOf(otpFixtureItemNewest()))
        val viewModel = OtpViewModel(gateway, clock = { nowMillis })

        viewModel.refresh()
        advanceUntilIdle()
        val latest = viewModel.state.value.items.first()
        assertTrue(isOtpFresh(latest.dateTs, viewModel.state.value.nowTs))

        // 时间往前 5 分 01 秒：不发请求，只靠时钟循环推进「现在」
        nowMillis += 301_000
        val job = launch { viewModel.clockLoop().collect { } }
        advanceTimeBy(OtpClockTickMillis + 1)
        assertEquals(1, gateway.limits.size)
        assertFalse(isOtpFresh(latest.dateTs, viewModel.state.value.nowTs))
        job.cancel()
    }

    @Test
    fun accountsAreRequestedOnlyOnce() = runTest(dispatcher) {
        val gateway = FakeOtpGateway(items = listOf(otpFixtureItemNewest()))
        val viewModel = OtpViewModel(gateway)

        viewModel.refresh()
        advanceUntilIdle()
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(1, gateway.accountCalls)
    }

    @Test
    fun copyingShowsTheSixtySecondNotice() = runTest(dispatcher) {
        val viewModel = OtpViewModel(FakeOtpGateway())

        viewModel.onCopied()
        assertEquals(OtpCopiedNotice, viewModel.state.value.notice)

        viewModel.onEvent(OtpEvent.DismissNotice)
        assertNull(viewModel.state.value.notice)
    }

    /** 夹具里最新的一条：收到 2 分钟前。 */
    private fun otpFixtureItemNewest(): OtpItem =
        otpFixtureItem(id = 1, code = "482913", senderName = "Cloudflare", ageSeconds = 120.0)
}
