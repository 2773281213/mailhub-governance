package com.mailhub.client.ui.screens.shell

import com.mailhub.client.data.session.SessionState
import com.mailhub.client.data.testSession
import com.mailhub.client.data.net.ApiClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话状态到外壳行为的两个纯函数：
 * 登录代际（换会话时重建 ViewModelStore）与后台任务的调度动作。
 */
class ShellSessionTest {

    private fun loggedIn(token: String = "token-a"): SessionState.LoggedIn {
        val url = "https://mail.example.com".toHttpUrl()
        return SessionState.LoggedIn(
            session = testSession(serverUrl = url.toString(), token = token),
            client = ApiClient(url, token),
        )
    }

    @Test
    fun loggedOutToLoggedInStartsANewGeneration() {
        assertTrue(isNewLoginGeneration(SessionState.LoggedOut(), loggedIn()))
    }

    @Test
    fun restoringToLoggedInStartsANewGeneration() {
        assertTrue(isNewLoginGeneration(SessionState.Restoring, loggedIn()))
    }

    @Test
    fun tokenSwapInsideTheSameLoginIsNotANewGeneration() {
        // 改密码、退出其他所有设备：还是同一个会话代际，设置页的「密码已修改」不能被冲掉
        assertFalse(isNewLoginGeneration(loggedIn(token = "old"), loggedIn(token = "new")))
    }

    @Test
    fun leavingTheLoggedInStateIsNotANewGeneration() {
        assertFalse(isNewLoginGeneration(loggedIn(), SessionState.LoggedOut()))
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
}
