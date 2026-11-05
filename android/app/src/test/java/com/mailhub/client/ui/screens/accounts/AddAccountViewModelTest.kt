package com.mailhub.client.ui.screens.accounts

import com.mailhub.client.data.accounts.AccountBody
import com.mailhub.client.data.accounts.DeviceCodePoll
import com.mailhub.client.data.accounts.DeviceCodeStart
import com.mailhub.client.data.accounts.OAuthStartResponse
import com.mailhub.client.data.accounts.OAuthTransaction
import com.mailhub.client.data.accounts.ProviderInfo
import com.mailhub.client.data.model.Account
import com.mailhub.client.data.net.ApiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
 * 添加账户状态机：授权码 / 密码的成功与失败、浏览器 OAuth 的轮询（含深链立即轮询与 tx 不匹配）、
 * 设备码按 interval 轮询并在 expires_in 到点停下。全部用虚拟时间跑，不真的等待。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddAccountViewModelTest {

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

    /** 假网关：记录每一次请求，并按顺序回放预置的事务 / 设备码响应。 */
    private class FakeAddAccountGateway(
        private val providers: Map<String, ProviderInfo> = FixtureProviders,
    ) : AddAccountGateway {

        var addResult: Result<Int> = Result.success(11)
        val accountBodies = mutableListOf<AccountBody>()

        var listResult: Result<List<Account>> = Result.success(emptyList())

        var browserStart: Result<OAuthStartResponse> = Result.success(
            OAuthStartResponse(
                ok = true,
                transactionId = "tx-1",
                authorizationUrl = "https://example.com/authorize?state=abc",
                expiresIn = 600,
            ),
        )
        var browserStarts = 0
        val startedProviders = mutableListOf<Pair<String, String>>()

        /** 依次返回的事务；超出长度后一直重复最后一个。 */
        var transactions: List<OAuthTransaction> = emptyList()
        val transactionQueries = mutableListOf<String>()

        var deviceStartResult: Result<DeviceCodeStart> = Result.success(
            DeviceCodeStart(
                ok = true,
                transactionId = "dev-1",
                userCode = "QZ7XK4JN9",
                verificationUri = "https://microsoft.com/devicelogin",
                interval = 5,
                expiresIn = 20,
            ),
        )
        var deviceStarts = 0

        /** 依次返回的轮询结果；超出长度后一直重复最后一个。 */
        var devicePolls: List<DeviceCodePoll> = listOf(DeviceCodePoll(pending = true, interval = 5))
        val pollQueries = mutableListOf<String>()

        override suspend fun providers(): Result<Map<String, ProviderInfo>> =
            Result.success(providers)

        override suspend fun addAccount(body: AccountBody): Result<Int> {
            accountBodies += body
            return addResult
        }

        override suspend fun accounts(): Result<List<Account>> = listResult

        override suspend fun startBrowserOAuth(
            provider: String,
            email: String,
        ): Result<OAuthStartResponse> {
            browserStarts++
            startedProviders += provider to email
            return browserStart
        }

        override suspend fun oauthTransaction(transactionId: String): Result<OAuthTransaction> {
            transactionQueries += transactionId
            val index = transactionQueries.size - 1
            return Result.success(transactions.getOrElse(index) { transactions.last() })
        }

        override suspend fun startDeviceCode(
            provider: String,
            email: String,
        ): Result<DeviceCodeStart> {
            deviceStarts++
            return deviceStartResult
        }

        override suspend fun pollDeviceCode(transactionId: String): Result<DeviceCodePoll> {
            pollQueries += transactionId
            val index = pollQueries.size - 1
            return Result.success(devicePolls.getOrElse(index) { devicePolls.last() })
        }
    }

    /** 拉完服务商列表、填好邮箱并进入第 2 步。 */
    private fun TestScope.prepare(
        gateway: FakeAddAccountGateway,
        email: String,
        bus: OAuthReturnBus = OAuthReturnBus(),
        added: () -> Unit = {},
    ): AddAccountViewModel {
        val viewModel = AddAccountViewModel(
            gateway = gateway,
            bus = bus,
            openUrl = {},
            copyText = {},
            onAdded = added,
        )
        viewModel.onEnter()
        // 服务商列表先到位，后面的域名识别才和真机一致
        advanceUntilIdle()
        viewModel.onEvent(AddAccountEvent.EmailChanged(email))
        viewModel.onEvent(AddAccountEvent.ContinueEmail)
        return viewModel
    }

    /** 再往前一步：选中第 1 种方式，OAuth 方式会立刻开始执行。 */
    private fun TestScope.selectFirstMethod(viewModel: AddAccountViewModel): AddAccountViewModel {
        viewModel.onEvent(AddAccountEvent.SelectMethod(0))
        runCurrent()
        return viewModel
    }

    private fun pendingTransaction(transactionId: String = "tx-1") = OAuthTransaction(
        id = transactionId,
        provider = "gmail",
        status = "pending",
    )

    private fun doneTransaction(accountId: Int, transactionId: String = "tx-1") = OAuthTransaction(
        id = transactionId,
        provider = "gmail",
        status = "done",
        accountId = accountId,
        message = "邮箱登录成功",
    )

    @Test
    fun secretMethodPostsAccountBody() = runTest(dispatcher) {
        val gateway = FakeAddAccountGateway().apply {
            addResult = Result.success(21)
            listResult = Result.success(
                listOf(
                    Account(id = 20, name = "旧邮箱"),
                    Account(id = 21, name = "工作邮箱"),
                ),
            )
        }
        val viewModel = prepare(gateway, email = "work@qq.com")

        assertEquals(AddStep.Method, viewModel.state.value.step)
        viewModel.onEvent(AddAccountEvent.SelectMethod(0))
        viewModel.onEvent(AddAccountEvent.SecretChanged(" abcd1234 "))
        viewModel.onEvent(AddAccountEvent.SubmitSecret)
        advanceUntilIdle()

        val body = gateway.accountBodies.single()
        assertEquals("qq", body.provider)
        assertEquals("work@qq.com", body.email)
        assertEquals("abcd1234", body.secret)
        assertEquals("", body.imapHost)
        assertEquals(993, body.imapPort)
        // 编号取新账户在列表里的位置：第二个 → A2
        assertEquals("已添加 A2 工作邮箱", viewModel.state.value.addedLine)
        assertFalse(viewModel.state.value.submitting)
    }

    @Test
    fun customImapSendsHostAndPort() = runTest(dispatcher) {
        val gateway = FakeAddAccountGateway()
        val viewModel = AddAccountViewModel(
            gateway = gateway,
            bus = OAuthReturnBus(),
            openUrl = {},
            copyText = {},
        )
        viewModel.onEnter()
        advanceUntilIdle()
        viewModel.onEvent(AddAccountEvent.EmailChanged("hello@studio.example"))
        viewModel.onEvent(AddAccountEvent.ContinueEmail)
        // 自定义 IMAP 必须先填主机
        assertEquals("请填写 IMAP 服务器地址", viewModel.state.value.error)
        assertEquals(AddStep.Email, viewModel.state.value.step)

        viewModel.onEvent(AddAccountEvent.HostChanged("imap.studio.example"))
        viewModel.onEvent(AddAccountEvent.PortChanged("1993"))
        viewModel.onEvent(AddAccountEvent.ContinueEmail)
        assertEquals(AddStep.Method, viewModel.state.value.step)

        viewModel.onEvent(AddAccountEvent.SelectMethod(0))
        viewModel.onEvent(AddAccountEvent.SecretChanged("pw"))
        viewModel.onEvent(AddAccountEvent.SubmitSecret)
        advanceUntilIdle()

        val body = gateway.accountBodies.single()
        assertEquals("custom", body.provider)
        assertEquals("imap.studio.example", body.imapHost)
        assertEquals(1993, body.imapPort)
    }

    @Test
    fun secretFailureKeepsBackendDetail() = runTest(dispatcher) {
        val detail = "连接测试失败: AUTHENTICATE failed。请确认填写的是「QQ 邮箱授权码」，不是网页登录密码"
        val gateway = FakeAddAccountGateway().apply {
            addResult = Result.failure(ApiError.Server(400, detail))
        }
        val viewModel = prepare(gateway, email = "work@qq.com")

        viewModel.onEvent(AddAccountEvent.SelectMethod(0))
        viewModel.onEvent(AddAccountEvent.SecretChanged("wrong"))
        viewModel.onEvent(AddAccountEvent.SubmitSecret)
        advanceUntilIdle()

        assertEquals(detail, viewModel.state.value.error)
        assertNull(viewModel.state.value.addedLine)
        assertFalse(viewModel.state.value.submitting)
    }

    @Test
    fun secretConflictShowsBackendDetail() = runTest(dispatcher) {
        val detail = "这个邮箱已经添加过了，请在账户列表里编辑或重新授权"
        val gateway = FakeAddAccountGateway().apply {
            addResult = Result.failure(ApiError.Conflict(detail))
        }
        val viewModel = prepare(gateway, email = "work@qq.com")

        viewModel.onEvent(AddAccountEvent.SelectMethod(0))
        viewModel.onEvent(AddAccountEvent.SecretChanged("abcd1234"))
        viewModel.onEvent(AddAccountEvent.SubmitSecret)
        advanceUntilIdle()

        assertEquals(detail, viewModel.state.value.error)
    }

    @Test
    fun browserOauthPollsUntilDone() = runTest(dispatcher) {
        val gateway = FakeAddAccountGateway().apply {
            transactions = listOf(
                pendingTransaction(),
                pendingTransaction(),
                doneTransaction(accountId = 7),
            )
            listResult = Result.success(listOf(Account(id = 7, name = "工作邮箱")))
        }
        var added = 0
        val viewModel = prepare(gateway, email = "work@gmail.com", added = { added++ })
        selectFirstMethod(viewModel)
        advanceUntilIdle()

        assertEquals(1, gateway.browserStarts)
        assertEquals("gmail", gateway.startedProviders.single().first)
        assertEquals("work@gmail.com", gateway.startedProviders.single().second)
        // 两次 pending + 一次 done
        assertEquals(3, gateway.transactionQueries.size)
        assertEquals(listOf("tx-1", "tx-1", "tx-1"), gateway.transactionQueries)
        assertEquals("已添加 A1 工作邮箱", viewModel.state.value.addedLine)
        assertFalse(viewModel.state.value.waitingBrowser)
        assertEquals(1, added)
    }

    @Test
    fun browserOauthStopsOnFailed() = runTest(dispatcher) {
        val gateway = FakeAddAccountGateway().apply {
            transactions = listOf(
                OAuthTransaction(
                    id = "tx-1",
                    provider = "gmail",
                    status = "failed",
                    message = "OAuth 客户端配置已变化",
                ),
            )
        }
        val viewModel = prepare(gateway, email = "work@gmail.com")
        selectFirstMethod(viewModel)
        advanceUntilIdle()

        assertEquals(1, gateway.transactionQueries.size)
        assertEquals("OAuth 客户端配置已变化", viewModel.state.value.error)
        assertNull(viewModel.state.value.addedLine)
    }

    @Test
    fun browserOauthStopsOnExpired() = runTest(dispatcher) {
        val gateway = FakeAddAccountGateway().apply {
            transactions = listOf(
                OAuthTransaction(id = "tx-1", provider = "gmail", status = "expired"),
            )
        }
        val viewModel = prepare(gateway, email = "work@gmail.com")
        selectFirstMethod(viewModel)
        advanceUntilIdle()

        assertEquals(1, gateway.transactionQueries.size)
        assertEquals(BrowserExpiredText, viewModel.state.value.error)
    }

    @Test
    fun browserOauthGivesUpAfterTenMinutes() = runTest(dispatcher) {
        val gateway = FakeAddAccountGateway().apply {
            transactions = listOf(pendingTransaction())
        }
        val viewModel = prepare(gateway, email = "work@gmail.com")
        selectFirstMethod(viewModel)
        advanceUntilIdle()

        // 0 秒一次，之后每 2 秒一次，10 分钟到点后不再发请求
        val expected = (BrowserPollTimeoutMillis / BrowserPollIntervalMillis).toInt()
        assertEquals(expected, gateway.transactionQueries.size)
        assertEquals(BrowserTimeoutText, viewModel.state.value.error)
        assertFalse(viewModel.state.value.waitingBrowser)
    }

    @Test
    fun deepLinkReturnPollsImmediately() = runTest(dispatcher) {
        val bus = OAuthReturnBus()
        val gateway = FakeAddAccountGateway().apply {
            transactions = listOf(
                pendingTransaction(),
                pendingTransaction(),
                doneTransaction(accountId = 7),
            )
            listResult = Result.success(listOf(Account(id = 7, name = "工作邮箱")))
        }
        val viewModel = prepare(gateway, email = "work@gmail.com", bus = bus)
        selectFirstMethod(viewModel)
        assertEquals(1, gateway.transactionQueries.size)

        // 深链带着同一个事务 id 回来：不等 2 秒，立刻再查一次
        assertTrue(bus.publish("tx-1"))
        runCurrent()
        assertEquals(2, gateway.transactionQueries.size)

        // 第二次结果仍是 pending，再等一个间隔就拿到 done
        advanceTimeBy(BrowserPollIntervalMillis)
        runCurrent()
        assertEquals(3, gateway.transactionQueries.size)
        assertEquals("已添加 A1 工作邮箱", viewModel.state.value.addedLine)
    }

    @Test
    fun deepLinkWithOtherTransactionIsIgnored() = runTest(dispatcher) {
        val bus = OAuthReturnBus()
        val gateway = FakeAddAccountGateway().apply {
            transactions = listOf(pendingTransaction())
        }
        val viewModel = prepare(gateway, email = "work@gmail.com", bus = bus)
        selectFirstMethod(viewModel)
        assertEquals(1, gateway.transactionQueries.size)

        // 不是当前等待中的事务 id：总线直接丢掉，轮询节奏不变
        assertFalse(bus.publish("tx-other"))
        runCurrent()
        assertEquals(1, gateway.transactionQueries.size)

        advanceTimeBy(BrowserPollIntervalMillis)
        runCurrent()
        assertEquals(2, gateway.transactionQueries.size)
        assertTrue(viewModel.state.value.waitingBrowser)
    }

    @Test
    fun deviceCodePollsAtIntervalAndStopsAtExpiry() = runTest(dispatcher) {
        val gateway = FakeAddAccountGateway().apply {
            // interval 5 秒，expires_in 20 秒：0 / 5 / 10 / 15 秒各查一次，20 秒时停止
            devicePolls = listOf(DeviceCodePoll(pending = true, interval = 5))
        }
        val viewModel = prepare(gateway, email = "hello@outlook.com")
        selectFirstMethod(viewModel)

        assertEquals(1, gateway.deviceStarts)
        assertEquals(1, gateway.pollQueries.size)
        assertEquals("QZ7XK4JN9", viewModel.state.value.deviceCode?.code)

        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(1, gateway.pollQueries.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, gateway.pollQueries.size)

        advanceUntilIdle()
        assertEquals(4, gateway.pollQueries.size)
        assertEquals(DeviceExpiredText, viewModel.state.value.error)
        assertNull(viewModel.state.value.deviceCode)
    }

    @Test
    fun deviceCodeSuccessAddsAccount() = runTest(dispatcher) {
        val gateway = FakeAddAccountGateway().apply {
            devicePolls = listOf(
                DeviceCodePoll(pending = true, interval = 5),
                DeviceCodePoll(ok = true, id = 33),
            )
            listResult = Result.success(listOf(Account(id = 33, name = "客户邮箱")))
        }
        val viewModel = prepare(gateway, email = "hello@outlook.com")
        selectFirstMethod(viewModel)
        advanceUntilIdle()

        assertEquals(2, gateway.pollQueries.size)
        assertEquals("已添加 A1 客户邮箱", viewModel.state.value.addedLine)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun backToEmailClearsExecuteState() = runTest(dispatcher) {
        val gateway = FakeAddAccountGateway()
        val viewModel = prepare(gateway, email = "hello@outlook.com")
        selectFirstMethod(viewModel)

        viewModel.onEvent(AddAccountEvent.BackToEmail)

        assertEquals(AddStep.Email, viewModel.state.value.step)
        assertNull(viewModel.state.value.deviceCode)
        assertFalse(viewModel.state.value.waitingBrowser)
    }
}
