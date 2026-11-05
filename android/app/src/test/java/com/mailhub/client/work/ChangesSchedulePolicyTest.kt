package com.mailhub.client.work

import com.mailhub.client.data.net.ApiClient
import com.mailhub.client.data.session.SessionState
import com.mailhub.client.data.testSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 后台拉取的调度规则：按「前一状态 → 当前状态」决定调度还是取消。
 * 纯 JVM 测试，不依赖 Android，也不需要真实 JobScheduler。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangesSchedulePolicyTest {

    private fun loggedIn(token: String = "token-a"): SessionState.LoggedIn {
        val url = "https://mail.example.com".toHttpUrl()
        return SessionState.LoggedIn(
            session = testSession(serverUrl = url.toString(), token = token),
            client = ApiClient(url, token),
        )
    }

    @Test
    fun enteringLoggedInSchedulesTheBackgroundJob() {
        assertEquals(ChangesAction.Schedule, changesActionFor(SessionState.Restoring, loggedIn()))
        assertEquals(ChangesAction.Schedule, changesActionFor(SessionState.LoggedOut(), loggedIn()))
    }

    @Test
    fun enteringLoggedOutCancelsTheBackgroundJob() {
        assertEquals(ChangesAction.Cancel, changesActionFor(loggedIn(), SessionState.LoggedOut()))
        assertEquals(
            ChangesAction.Cancel,
            changesActionFor(loggedIn(), SessionState.LoggedOut(SessionState.Reason.Expired)),
        )
    }

    @Test
    fun restoringDoesNothing() {
        assertEquals(ChangesAction.None, changesActionFor(SessionState.LoggedOut(), SessionState.Restoring))
        assertEquals(ChangesAction.None, changesActionFor(loggedIn(), SessionState.Restoring))
    }

    @Test
    fun stayingOnTheSameSideDoesNothing() {
        assertEquals(ChangesAction.None, changesActionFor(loggedIn("a"), loggedIn("b")))
        assertEquals(
            ChangesAction.None,
            changesActionFor(SessionState.LoggedOut(), SessionState.LoggedOut(SessionState.Reason.UserLogout)),
        )
    }

    @Test
    fun restoringToLoggedInSchedulesOnce() = runTest {
        val actions = mutableListOf<ChangesAction>()
        val states = MutableStateFlow<SessionState>(SessionState.Restoring)
        ChangesScheduleBinding(states, scope(this), actions::add).start()

        states.value = loggedIn()
        states.value = loggedIn()

        assertEquals(listOf(ChangesAction.Schedule), actions)
    }

    @Test
    fun tokenSwapInsideTheSameLoginDoesNotScheduleAgain() = runTest {
        val actions = mutableListOf<ChangesAction>()
        val states = MutableStateFlow<SessionState>(loggedIn("old"))
        ChangesScheduleBinding(states, scope(this), actions::add).start()

        // 改密码、退出其他所有设备：只是换了令牌，周期任务不能重排（重排会把计时清零）
        states.value = loggedIn("new")

        assertEquals(listOf(ChangesAction.Schedule), actions)
    }

    @Test
    fun enteringLoggedOutCancels() = runTest {
        val actions = mutableListOf<ChangesAction>()
        val states = MutableStateFlow<SessionState>(loggedIn())
        ChangesScheduleBinding(states, scope(this), actions::add).start()

        states.value = SessionState.LoggedOut(SessionState.Reason.UserLogout)
        states.value = SessionState.LoggedOut(SessionState.Reason.Expired)

        assertEquals(listOf(ChangesAction.Schedule, ChangesAction.Cancel), actions)
    }

    @Test
    fun startingLoggedOutCancelsOnce() = runTest {
        val actions = mutableListOf<ChangesAction>()
        val states = MutableStateFlow<SessionState>(SessionState.LoggedOut())
        ChangesScheduleBinding(states, scope(this), actions::add).start()

        // 同一侧再来一次也不重复取消
        states.value = SessionState.LoggedOut(SessionState.Reason.UserLogout)

        assertEquals(listOf(ChangesAction.Cancel), actions)
    }

    /** 与生产代码一样，绑定跑在自己的 scope 里；Unconfined 让状态变化立即生效。 */
    private fun scope(testScope: TestScope): CoroutineScope =
        CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScope.testScheduler))
}
