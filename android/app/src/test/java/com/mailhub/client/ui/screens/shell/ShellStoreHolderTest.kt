package com.mailhub.client.ui.screens.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.mailhub.client.data.net.ApiClient
import com.mailhub.client.data.session.SessionState
import com.mailhub.client.data.testSession
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录代际的 ViewModelStore 持有者：新代际换 store 并清掉旧的，换令牌沿用同一份，
 * 退出登录清空，同一个状态重复调用幂等。
 */
class ShellStoreHolderTest {

    /** 记录自己被清理过的 ViewModel；放在 store 里就能看出 store 有没有被清。 */
    class TrackedViewModel : ViewModel() {

        var cleared: Boolean = false

        override fun onCleared() {
            cleared = true
        }
    }

    private fun loggedIn(token: String = "token-a"): SessionState.LoggedIn {
        val url = "https://mail.example.com".toHttpUrl()
        return SessionState.LoggedIn(
            session = testSession(serverUrl = url.toString(), token = token),
            client = ApiClient(url, token),
        )
    }

    private fun viewModelIn(owner: ViewModelStoreOwner): TrackedViewModel =
        ViewModelProvider(owner)[TrackedViewModel::class.java]

    @Test
    fun aNewGenerationSwapsTheStoreAndClearsTheOldOne() {
        val holder = ShellStoreHolder()
        val loggedOut = holder.ownerFor(SessionState.LoggedOut())
        val oldViewModel = viewModelIn(loggedOut)

        val loggedIn = holder.ownerFor(loggedIn())

        assertNotSame(loggedOut, loggedIn)
        assertTrue("旧代际的 ViewModel 必须被清理", oldViewModel.cleared)
        // 新 store 是空的，不会先看到上一台服务器的数据
        assertNotSame(oldViewModel, viewModelIn(loggedIn))
    }

    @Test
    fun aTokenSwapKeepsTheSameStore() {
        val holder = ShellStoreHolder()
        val first = holder.ownerFor(loggedIn("old"))
        val pageViewModel = viewModelIn(first)

        val second = holder.ownerFor(loggedIn("new"))

        assertSame(first, second)
        assertSame(pageViewModel, viewModelIn(second))
        assertFalse("换令牌不能清掉页面状态", pageViewModel.cleared)
    }

    @Test
    fun loggingOutClearsTheStore() {
        val holder = ShellStoreHolder()
        val loggedIn = holder.ownerFor(loggedIn())
        val pageViewModel = viewModelIn(loggedIn)

        val loggedOut = holder.ownerFor(SessionState.LoggedOut(SessionState.Reason.UserLogout))

        // 退出登录不换 owner，只清空里面的 ViewModel
        assertSame(loggedIn, loggedOut)
        assertTrue(pageViewModel.cleared)
    }

    @Test
    fun callingWithTheSameStateAgainIsIdempotent() {
        val holder = ShellStoreHolder()
        val state = loggedIn()
        val first = holder.ownerFor(state)
        val pageViewModel = viewModelIn(first)

        val second = holder.ownerFor(state)

        assertSame(first, second)
        assertSame(pageViewModel, viewModelIn(second))
        assertFalse(pageViewModel.cleared)
    }

    @Test
    fun enteringLoggedOutTwiceDoesNotSwapTheStore() {
        val holder = ShellStoreHolder()
        val first = holder.ownerFor(SessionState.LoggedOut())

        val second = holder.ownerFor(SessionState.LoggedOut(SessionState.Reason.Expired))

        assertSame(first, second)
    }
}
