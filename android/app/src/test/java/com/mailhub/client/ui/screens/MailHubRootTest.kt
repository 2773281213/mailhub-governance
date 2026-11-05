package com.mailhub.client.ui.screens

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.mailhub.client.MainActivity
import com.mailhub.client.MailHubApp
import com.mailhub.client.data.session.SessionState
import com.mailhub.client.data.testSession
import com.mailhub.client.ui.screens.shell.ShellStoreHolder
import com.mailhub.client.ui.screens.shell.ShellViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 登录代际的 ViewModelStore 要挺过配置变化：Activity 重建（旋转）之后，
 * 外壳里同一个页面 ViewModel 还是同一个实例，页面状态不会丢。
 *
 * 会话在测试里直接置为已登录，服务器地址指向本机一个关着的端口：外壳里的页面会发请求，
 * 但连不上就立刻失败，不会访问任何外部服务器。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class MailHubRootTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /** 本机关着的端口：连接立刻失败，用来让外壳里的页面请求快速收场。 */
    private val serverUrl = "http://127.0.0.1:1"

    /** 取当前 Activity（recreate 之后是新的那一个）。 */
    private fun currentActivity(): MainActivity {
        var activity: MainActivity? = null
        composeRule.activityRule.scenario.onActivity { activity = it }
        return requireNotNull(activity)
    }

    /** 外壳当前代际里的页面 ViewModel；还没建出来时返回 null。 */
    private fun shellViewModelOrNull(): ShellViewModel? {
        val holder = ViewModelProvider(currentActivity())[ShellStoreHolder::class.java]
        return try {
            ViewModelProvider(holder.owner)[ShellViewModel::class.java]
        } catch (_: RuntimeException) {
            // store 还是空的：默认工厂建不出带参数的 ShellViewModel
            null
        }
    }

    /**
     * 把容器的会话直接置为已登录。
     *
     * 不走 [com.mailhub.client.data.session.SessionManager.activate]：它会先把会话写进 DataStore，
     * 写入要用 Android Keystore 加密令牌，而 Robolectric 没有 AndroidKeyStore。
     * 这里只改内存里的状态，本用例验证的是 Activity 重建，与令牌怎么落盘无关。
     */
    @Suppress("UNCHECKED_CAST")
    private fun logIn() {
        val app = RuntimeEnvironment.getApplication() as MailHubApp
        val manager = app.container.sessionManager
        val field = manager.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        val state = field.get(manager) as MutableStateFlow<SessionState>
        val session = testSession(serverUrl = serverUrl, token = "token-a")
        state.value = SessionState.LoggedIn(
            session = session,
            client = manager.newClient(serverUrl.toHttpUrl(), "token-a"),
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun pageViewModelsSurviveActivityRecreation() {
        logIn()
        composeRule.waitUntil(timeoutMillis = 10_000) { shellViewModelOrNull() != null }
        val activityBefore = currentActivity()
        val before = shellViewModelOrNull()
        val storeBefore = ViewModelProvider(activityBefore)[ShellStoreHolder::class.java]
            .owner.viewModelStore

        composeRule.activityRule.scenario.recreate()

        val activityAfter = currentActivity()
        composeRule.waitUntil(timeoutMillis = 10_000) { shellViewModelOrNull() != null }
        val after = shellViewModelOrNull()
        val storeAfter = ViewModelProvider(activityAfter)[ShellStoreHolder::class.java]
            .owner.viewModelStore

        assertNotSame("recreate 必须真的重建 Activity", activityBefore, activityAfter)
        assertSame("重建后要沿用同一份 ViewModelStore", storeBefore, storeAfter)
        assertSame("重建后页面 ViewModel 必须是同一个实例", before, after)
    }
}
