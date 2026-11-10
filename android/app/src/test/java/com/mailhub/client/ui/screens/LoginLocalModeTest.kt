package com.mailhub.client.ui.screens

import com.mailhub.client.data.model.Meta
import com.mailhub.client.data.net.ApiError
import com.mailhub.client.ui.screens.login.LastServerStore
import com.mailhub.client.ui.screens.login.LocalModeFailureText
import com.mailhub.client.ui.screens.login.LoginEvent
import com.mailhub.client.ui.screens.login.LoginGateway
import com.mailhub.client.ui.screens.login.LoginViewModel
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 登录页的本机入口：成功后不写「上次服务器」、失败只给通用说明、提交中不重复进入。
 * 会话切换本身由 AuthRepository 负责，这里只验证页面层的行为。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginLocalModeTest {

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
        enterLocal: suspend () -> Result<Unit>,
    ) = LoginViewModel(
        login = LoginGateway { _, _, _ -> Result.success(Meta(apiVersion = 1)) },
        lastServer = lastServer,
        deviceName = "Test Pixel",
        enterLocal = enterLocal,
    )

    @Test
    fun enterLocalSuccessClearsSubmittingAndWritesNoLastServer() = runTest(dispatcher) {
        val lastServer = FakeLastServer()
        var calls = 0
        val viewModel = viewModel(lastServer = lastServer, enterLocal = { calls++; Result.success(Unit) })

        viewModel.onEvent(LoginEvent.EnterLocal)
        // 请求在途时按钮不可用，页面显示加载条
        advanceUntilIdle()

        assertEquals(1, calls)
        val state = viewModel.state.value
        assertFalse(state.submitting)
        assertNull(state.failure)
        // 本机模式不是服务器登录，不该预填「上次服务器」
        assertTrue(lastServer.writes.isEmpty())
    }

    @Test
    fun enterLocalFailureShowsTheGenericMessage() = runTest(dispatcher) {
        val viewModel = viewModel(
            enterLocal = { Result.failure(ApiError.Server(0, "本机库打不开")) },
        )

        viewModel.onEvent(LoginEvent.EnterLocal)
        advanceUntilIdle()

        val failure = requireNotNull(viewModel.state.value.failure)
        // 不指向任何一个输入框：本机模式和服务器地址、密码都无关
        assertNull(failure.field)
        assertEquals(LocalModeFailureText, failure.message)
        assertFalse(viewModel.state.value.submitting)
    }

    @Test
    fun repeatedEnterLocalWhileSubmittingEntersOnlyOnce() = runTest(dispatcher) {
        var calls = 0
        val gate = CompletableDeferred<Result<Unit>>()
        val viewModel = viewModel(enterLocal = { calls++; gate.await() })

        viewModel.onEvent(LoginEvent.EnterLocal)
        advanceUntilIdle()
        assertTrue("第一次进入后应处于提交中", viewModel.state.value.submitting)

        viewModel.onEvent(LoginEvent.EnterLocal)
        viewModel.onEvent(LoginEvent.EnterLocal)
        advanceUntilIdle()

        assertEquals(1, calls)
        gate.complete(Result.success(Unit))
        advanceUntilIdle()
        assertFalse(viewModel.state.value.submitting)
        assertNull(viewModel.state.value.failure)
    }
}
