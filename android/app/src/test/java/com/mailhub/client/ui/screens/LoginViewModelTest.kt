package com.mailhub.client.ui.screens

import com.mailhub.client.data.model.Meta
import com.mailhub.client.data.net.ApiError
import com.mailhub.client.ui.screens.login.LastServerStore
import com.mailhub.client.ui.screens.login.LoginEvent
import com.mailhub.client.ui.screens.login.LoginField
import com.mailhub.client.ui.screens.login.LoginGateway
import com.mailhub.client.ui.screens.login.LoginViewModel
import com.mailhub.client.ui.screens.login.retryMinutes
import kotlinx.coroutines.CompletableDeferred
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

/** 登录状态机：错误映射表 + 提交中不重复发请求。 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

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

    /** 记录写入内容的假偏好存储。 */
    private class FakeLastServer(private var value: String? = null) : LastServerStore {
        val writes = mutableListOf<String>()

        override suspend fun read(): String? = value

        override suspend fun write(value: String) {
            this.value = value
            writes += value
        }
    }

    private fun viewModel(
        lastServer: LastServerStore = FakeLastServer(),
        gateway: LoginGateway,
    ) = LoginViewModel(
        login = gateway,
        lastServer = lastServer,
        deviceName = "Test Pixel",
    )

    private fun failing(error: Throwable) = LoginGateway { _, _, _ -> Result.failure(error) }

    private fun fillAndSubmit(viewModel: LoginViewModel) {
        viewModel.onEvent(LoginEvent.ServerChanged("https://mail.example.com"))
        viewModel.onEvent(LoginEvent.PasswordChanged("hunter2"))
        viewModel.onEvent(LoginEvent.Submit)
    }

    @Test
    fun invalidServerUrlKeepsServerUrlReasonAndFlagsServerField() = runTest(dispatcher) {
        val viewModel = viewModel(
            gateway = failing(ApiError.Network("服务器地址不正确：服务器地址格式不正确")),
        )

        fillAndSubmit(viewModel)
        advanceUntilIdle()

        val failure = requireNotNull(viewModel.state.value.failure)
        assertEquals(LoginField.Server, failure.field)
        assertEquals("服务器地址不正确：服务器地址格式不正确", failure.message)
        assertFalse(viewModel.state.value.submitting)
    }

    @Test
    fun unauthorizedMapsToWrongPassword() = runTest(dispatcher) {
        val viewModel = viewModel(gateway = failing(ApiError.Unauthorized()))

        fillAndSubmit(viewModel)
        advanceUntilIdle()

        val failure = requireNotNull(viewModel.state.value.failure)
        assertEquals(LoginField.Password, failure.field)
        assertEquals("密码错误", failure.message)
    }

    @Test
    fun tooManyRequestsRoundsRetryAfterUpToMinutes() = runTest(dispatcher) {
        val viewModel = viewModel(gateway = failing(ApiError.TooManyRequests(retryAfterSec = 90)))

        fillAndSubmit(viewModel)
        advanceUntilIdle()

        assertEquals("尝试过多，请 2 分钟后再试", viewModel.state.value.failure?.message)
    }

    @Test
    fun zeroRetryAfterStillSaysAtLeastOneMinute() {
        assertEquals(1, retryMinutes(0))
        assertEquals(1, retryMinutes(1))
        assertEquals(2, retryMinutes(61))
    }

    @Test
    fun networkErrorMapsToCheckAddressAndNetwork() = runTest(dispatcher) {
        val viewModel = viewModel(gateway = failing(ApiError.Network("connect timed out")))

        fillAndSubmit(viewModel)
        advanceUntilIdle()

        val failure = requireNotNull(viewModel.state.value.failure)
        assertEquals(null, failure.field)
        assertEquals("无法连接服务器，请检查地址和网络", failure.message)
    }

    @Test
    fun outdatedServerKeepsApiErrorDetail() = runTest(dispatcher) {
        val viewModel = viewModel(
            gateway = failing(ApiError.Server(0, "服务器版本过旧，请先升级 MailHub 服务端")),
        )

        fillAndSubmit(viewModel)
        advanceUntilIdle()

        assertEquals("服务器版本过旧，请先升级 MailHub 服务端", viewModel.state.value.failure?.message)
    }

    @Test
    fun decodeErrorMapsToFormatMessage() = runTest(dispatcher) {
        val viewModel = viewModel(gateway = failing(ApiError.Decode("bad json")))

        fillAndSubmit(viewModel)
        advanceUntilIdle()

        assertEquals("服务器返回的数据格式不正确", viewModel.state.value.failure?.message)
    }

    @Test
    fun repeatedClicksWhileSubmittingSendOnlyOneRequest() = runTest(dispatcher) {
        var calls = 0
        val gate = CompletableDeferred<Result<Meta>>()
        val viewModel = viewModel(
            gateway = { _, _, _ ->
                calls++
                gate.await()
            },
        )

        fillAndSubmit(viewModel)
        advanceUntilIdle()
        assertTrue("第一次提交后应处于提交中", viewModel.state.value.submitting)

        // 提交中再点两次（按钮不可用时键盘 Done 也可能连点）
        viewModel.onEvent(LoginEvent.Submit)
        viewModel.onEvent(LoginEvent.Submit)
        advanceUntilIdle()

        assertEquals(1, calls)
        gate.complete(Result.success(Meta(apiVersion = 1)))
        advanceUntilIdle()
        assertFalse(viewModel.state.value.submitting)
        assertEquals(null, viewModel.state.value.failure)
    }

    @Test
    fun successfulLoginRemembersServerUrl() = runTest(dispatcher) {
        val lastServer = FakeLastServer()
        val viewModel = viewModel(
            lastServer = lastServer,
            gateway = { _, _, _ -> Result.success(Meta(apiVersion = 1)) },
        )

        fillAndSubmit(viewModel)
        advanceUntilIdle()

        assertEquals(listOf("https://mail.example.com"), lastServer.writes)
    }

    @Test
    fun lastServerIsPrefilled() = runTest(dispatcher) {
        val viewModel = viewModel(
            lastServer = FakeLastServer("https://mail.example.com"),
            gateway = failing(ApiError.Unauthorized()),
        )

        advanceUntilIdle()

        assertEquals("https://mail.example.com", viewModel.state.value.serverUrl)
    }

    @Test
    fun editingServerFieldClearsItsOwnError() = runTest(dispatcher) {
        val viewModel = viewModel(
            gateway = failing(ApiError.Network("服务器地址不正确：服务器地址格式不正确")),
        )

        fillAndSubmit(viewModel)
        advanceUntilIdle()
        viewModel.onEvent(LoginEvent.ServerChanged("https://mail.example.org"))

        assertEquals(null, viewModel.state.value.failure)
    }
}
