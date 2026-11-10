package com.mailhub.client.data.repo

import com.mailhub.client.data.FakeCipher
import com.mailhub.client.data.InMemoryDataStore
import com.mailhub.client.data.local.LOCAL_SERVER_URL
import com.mailhub.client.data.local.isLocal
import com.mailhub.client.data.net.ApiClient
import com.mailhub.client.data.session.SessionManager
import com.mailhub.client.data.session.SessionState
import com.mailhub.client.data.session.SessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进入本机模式的约定，见 `LOCAL_MODE.md` 3.1：
 * - 不调用 `/api/login`（一个客户端都不为匿名登录创建，也就不会有任何请求）；
 * - 不写登录页的「上次服务器」偏好：`AuthRepository` 根本没有这个依赖，写它的是服务器登录页；
 * - 先准备好本机侧（通知游标 = 本机库当前 `max(id)`），**再**激活会话；
 * - 会话 `expiresTs = 0.0`，恢复之后仍然是已登录。
 */
class AuthRepositoryLocalModeTest {

    private var dataStore = InMemoryDataStore()
    private var store = SessionStore(dataStore, FakeCipher())

    @Test
    fun prepareLocalRunsBeforeTheSessionIsActivated() = runBlocking {
        var prepared = 0
        var createdAtPrepare = -1
        val manager = SessionManager(store)
        val auth = AuthRepository(manager) {
            prepared += 1
            // 准备阶段会话必须还没激活：游标要在 /api/changes 能跑之前就写好
            createdAtPrepare = if (manager.state.value is SessionState.LoggedIn) 1 else 0
        }

        val result = auth.enterLocalMode("Pixel 8")

        assertTrue("result=$result", result.isSuccess)
        assertEquals("prepareLocal 只能调用一次", 1, prepared)
        assertEquals("prepareLocal 必须在 activate 之前完成", 0, createdAtPrepare)
        val state = manager.state.value
        assertTrue("state=$state", state is SessionState.LoggedIn)
        val session = (state as SessionState.LoggedIn).session
        assertEquals(LOCAL_SERVER_URL, session.serverUrl)
        assertTrue("会话应判定为本机会话", session.isLocal)
        assertEquals("local", session.sessionId)
        assertEquals("设备名要写进会话", "Pixel 8", session.deviceName)
    }

    @Test
    fun tokenIsFreshRandomHexAndNeverExpires() = runBlocking {
        val manager = SessionManager(store)
        val auth = AuthRepository(manager)

        auth.enterLocalMode("Pixel 8").getOrThrow()
        val first = (manager.state.value as SessionState.LoggedIn).session

        assertEquals("32 字节 = 64 个十六进制字符", 64, first.token.length)
        assertTrue("token=${first.token}", first.token.all { it in "0123456789abcdef" })
        assertEquals("0 表示永不过期", 0.0, first.expiresTs, 0.0)

        // 重新进入会换一个新令牌，但数据还在（同一个本机库）
        auth.enterLocalMode("Pixel 8").getOrThrow()
        val second = (manager.state.value as SessionState.LoggedIn).session
        assertFalse("每次进入都生成新令牌", first.token == second.token)
    }

    @Test
    fun restoringAZeroExpirySessionStaysLoggedIn() = runBlocking {
        val manager = SessionManager(store)
        AuthRepository(manager).enterLocalMode("Pixel 8").getOrThrow()

        // 新进程：同一个存储、新的 SessionManager
        val restoredManager = SessionManager(store)
        restoredManager.restore()

        val state = restoredManager.state.value
        assertTrue("expiresTs=0 不能被当成过期：$state", state is SessionState.LoggedIn)
        val session = (state as SessionState.LoggedIn).session
        assertEquals(LOCAL_SERVER_URL, session.serverUrl)
        assertTrue(session.isLocal)
        // 恢复出来的客户端同样绑在本机会话上（挂不挂拦截器由 AppContainer 决定）
        assertEquals(LOCAL_SERVER_URL, state.client.serverUrl.toString())
    }

    @Test
    fun noLoginClientIsCreatedSoNoRequestIsSent() = runBlocking {
        var clients = 0
        val manager = SessionManager(
            store = store,
            clientFactory = { url, token, onUnauthorized ->
                clients += 1
                ApiClient(url, token, onUnauthorized)
            },
        )

        AuthRepository(manager).enterLocalMode("Pixel 8").getOrThrow()

        // 登录会先建一个匿名客户端（/api/login），再建一个带令牌的客户端（/api/meta）
        assertEquals("进入本机模式只建一个带令牌的客户端", 1, clients)
    }

    @Test
    fun failureInLocalPreparationKeepsThePreviousSession() = runBlocking {
        val manager = SessionManager(store)
        manager.restore()
        val before = manager.state.value
        val auth = AuthRepository(manager) { error("打不开本机库") }

        val result = auth.enterLocalMode("Pixel 8")

        assertTrue("result=$result", result.isFailure)
        assertEquals(before, manager.state.value)
    }
}
