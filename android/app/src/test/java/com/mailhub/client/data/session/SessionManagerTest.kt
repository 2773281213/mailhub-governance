package com.mailhub.client.data.session

import com.mailhub.client.data.FakeCipher
import com.mailhub.client.data.InMemoryDataStore
import com.mailhub.client.data.testSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话管理：启动恢复的幂等性、过期判定，以及不和登录竞态。 */
class SessionManagerTest {

    private val serverUrl = "https://mail.example.com/"

    private fun manager(
        dataStore: InMemoryDataStore,
        clock: () -> Double = { 1_000.0 },
        cipher: Cipher = FakeCipher(),
    ): Pair<SessionManager, SessionStore> {
        val store = SessionStore(dataStore, cipher)
        return SessionManager(store, clock = clock) to store
    }

    @Test
    fun startsInRestoringState() {
        val (manager, _) = manager(InMemoryDataStore())

        assertEquals(SessionState.Restoring, manager.state.value)
        assertNull(manager.currentClient())
    }

    @Test
    fun restoreReadsStorageOnlyOnceForConcurrentCalls() = runBlocking {
        val dataStore = InMemoryDataStore()
        val (manager, store) = manager(dataStore)
        store.save(testSession(serverUrl, "tok-1"))
        dataStore.readCount.set(0)

        val jobs = List(2) { index ->
            launch(Dispatchers.Default) { manager.restore() }
        }
        jobs.forEach { it.join() }

        assertEquals(1, dataStore.readCount.get())
        val state = manager.state.value
        assertTrue("state=$state", state is SessionState.LoggedIn)
        assertEquals("tok-1", (state as SessionState.LoggedIn).session.token)
        assertNotNull(manager.currentClient())
    }

    @Test
    fun restoreWithoutStoredSessionIsLoggedOut() = runBlocking {
        val (manager, _) = manager(InMemoryDataStore())

        manager.restore()

        assertEquals(SessionState.LoggedOut(SessionState.Reason.None), manager.state.value)
    }

    @Test
    fun restoreWithUndecryptableTokenIsLoggedOut() = runBlocking {
        val dataStore = InMemoryDataStore()
        val (manager, store) = manager(dataStore)
        // 先正常存一次，再用解密必失败的 Cipher 读取
        store.save(testSession(serverUrl, "tok-1"))
        val (brokenManager, _) = manager(dataStore, cipher = FakeCipher(failOnDecrypt = true))

        brokenManager.restore()

        assertEquals(SessionState.LoggedOut(SessionState.Reason.None), brokenManager.state.value)
    }

    @Test
    fun restoreWithExpiredSessionClearsStorage() = runBlocking {
        val dataStore = InMemoryDataStore()
        val (manager, store) = manager(dataStore, clock = { 2_000.0 })
        store.save(testSession(serverUrl, "tok-old", expiresTs = 1_000.0))

        manager.restore()

        assertEquals(SessionState.LoggedOut(SessionState.Reason.Expired), manager.state.value)
        assertNull(store.session.first())
        assertNull(manager.currentClient())
    }

    @Test
    fun restoreKeepsSessionWithoutExpiry() = runBlocking {
        val dataStore = InMemoryDataStore()
        val (manager, store) = manager(dataStore, clock = { 2_000.0 })
        store.save(testSession(serverUrl, "tok-1", expiresTs = 0.0))

        manager.restore()

        assertTrue(manager.state.value is SessionState.LoggedIn)
        assertEquals("tok-1", manager.state.value.let { (it as SessionState.LoggedIn).session.token })
    }

    @Test
    fun loginDuringRestoreIsNotOverwritten() = runBlocking {
        val dataStore = InMemoryDataStore(readDelayMs = 300)
        val (manager, store) = manager(dataStore)
        // 存储里是一个已经过期的旧会话：恢复过程会把状态判成过期
        store.save(testSession(serverUrl, "tok-old", expiresTs = 1.0))

        val restoring = launch(Dispatchers.Default) { manager.restore() }
        delay(100)
        val fresh = testSession(serverUrl, "tok-new", expiresTs = 9_000.0)
        manager.activate(fresh, manager.newClient(serverUrl.toHttpUrlOrNull()!!, fresh.token))
        restoring.join()

        val state = manager.state.value
        assertTrue("state=$state", state is SessionState.LoggedIn)
        assertEquals("tok-new", (state as SessionState.LoggedIn).session.token)
        assertEquals("tok-new", store.session.first()?.token)
    }

    @Test
    fun clearForLogoutMarksUserLogout() = runBlocking {
        val dataStore = InMemoryDataStore()
        val (manager, store) = manager(dataStore)
        store.save(testSession(serverUrl, "tok-1"))
        manager.restore()

        manager.clearForLogout()

        assertEquals(SessionState.LoggedOut(SessionState.Reason.UserLogout), manager.state.value)
        assertNull(store.session.first())
    }
}
