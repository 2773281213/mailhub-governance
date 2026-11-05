package com.mailhub.client.data.settings

import com.mailhub.client.data.FakeCipher
import com.mailhub.client.data.InMemoryDataStore
import com.mailhub.client.data.net.ApiError
import com.mailhub.client.data.net.ApiJson
import com.mailhub.client.data.session.SessionManager
import com.mailhub.client.data.session.SessionState
import com.mailhub.client.data.session.SessionStore
import com.mailhub.client.data.testSession
import com.mailhub.client.ui.screens.common.apiErrorText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 设置接口走真实的 Retrofit + ApiJson：请求体里只能有改动过的字段，
 * GET 的字段与 app.py 逐个核对，400 的 detail 原样带到界面文案。
 */
class SettingsRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var store: SessionStore
    private lateinit var manager: SessionManager
    private lateinit var repository: SettingsRepository

    private fun json(code: Int, body: String): MockResponse =
        MockResponse.Builder()
            .code(code)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    @Before
    fun setUp() = runBlocking {
        server = MockWebServer()
        server.start()
        store = SessionStore(InMemoryDataStore(), FakeCipher())
        manager = SessionManager(store)
        val url = server.url("/")
        manager.activate(
            testSession(url.toString(), "tok-old", sessionId = "sid-old"),
            manager.newClient(url, "tok-old"),
        )
        repository = SettingsRepository(manager)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun updateSendsOnlyTheChangedField() = runBlocking {
        server.enqueue(json(200, """{"ok":true}"""))

        val result = repository.update(SettingsUpdate(notifyOtp = true))

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/settings", request.target)
        // 其他字段都是 null，不出现在 JSON 里，后端也就不会动它们
        assertEquals("""{"notify_otp":true}""", request.body?.utf8())
    }

    @Test
    fun decodesSettingsWithAndWithoutSyncReadState() {
        val withField = ApiJson.decodeFromString<SettingsDto>(
            """
            {"ai_enabled":true,"ai_base_url":"https://ai.example.com/v1","ai_key_set":true,
             "ai_model":"gemini-2.5-flash","ai_send_body":false,"ai_scope":"uncertain",
             "ai_backfill_days":30,"clean_otp_days":7,"clean_server":false,"sync_read_state":true,
             "clean_bill_minor_days":0,"bill_amount_threshold":100.0,"bill_routine_count":3,
             "bill_auto_renew":"一般","bill_refund":"重要","digest_hour":8,
             "notify_bark_set":true,"notify_bark_host":"bark.example.com",
             "notify_tg_token_set":false,"notify_tg_chat":"","notify_onepush_set":true,
             "notify_onepush_provider":"serverchanturbo","notify_important":true,"notify_otp":false,
             "body_keep_days":90,"google_oauth_configured":false,"google_oauth_source":"",
             "microsoft_oauth_configured":false,"microsoft_oauth_device_configured":true}
            """.trimIndent(),
        )
        assertTrue(withField.aiEnabled)
        assertTrue(withField.aiKeySet)
        assertFalse(withField.aiSendBody)
        assertEquals(AiScopeUncertain, withField.aiScope)
        assertEquals(7, withField.cleanOtpDays)
        assertEquals(true, withField.syncReadState)
        assertEquals("bark.example.com", withField.notifyBarkHost)
        assertEquals("serverchanturbo", withField.notifyOnepushProvider)

        val withoutField = ApiJson.decodeFromString<SettingsDto>(
            """{"ai_enabled":false,"digest_hour":7}""",
        )
        assertNull(withoutField.syncReadState)
        assertEquals(7, withoutField.digestHour)
    }

    @Test
    fun badRequestDetailIsShownAsIs() = runBlocking {
        server.enqueue(json(400, """{"detail":"原密码错误"}"""))

        val error = repository.update(
            SettingsUpdate(oldPassword = "wrong-pass", newPassword = "new-pass-123"),
        ).exceptionOrNull()

        assertTrue("error=$error", error is ApiError.Server)
        assertEquals("原密码错误", apiErrorText(error!!))
        // 400 不是会话失效，不能把人踢下线
        assertTrue(manager.state.value is SessionState.LoggedIn)
    }

    @Test
    fun passwordChangeReturnsANewTokenThatReplacesTheSession() = runBlocking {
        server.enqueue(
            json(200, """{"ok":true,"token":"tok-new","session_id":"sid-new","expires_ts":200.0}"""),
        )

        val response = repository.update(
            SettingsUpdate(oldPassword = "old-pass-123", newPassword = "new-pass-123"),
        ).getOrThrow()
        val body = server.takeRequest().body?.utf8().orEmpty()

        assertEquals("""{"old_password":"old-pass-123","new_password":"new-pass-123"}""", body)
        assertTrue(repository.replaceToken(response.token, response.sessionId, response.expiresTs))
        val state = manager.state.value as SessionState.LoggedIn
        assertEquals("tok-new", state.session.token)
        assertEquals("sid-new", state.session.sessionId)
        assertEquals("tok-new", store.refresh()?.token)
    }

    @Test
    fun testPushPostsWithoutUnsavedOnepushConfig() = runBlocking {
        server.enqueue(json(200, """{"ok":false,"msg":"未配置任何推送通道"}"""))

        val result = repository.testPush().getOrThrow()

        assertFalse(result.ok)
        assertEquals("未配置任何推送通道", result.msg)
        val request = server.takeRequest()
        assertEquals("/api/notify/test", request.target)
        assertEquals("""{"onepush_config":""}""", request.body?.utf8())
    }

    @Test
    fun toStringNeverContainsPasswords() {
        val text = SettingsUpdate(
            oldPassword = "old-secret-1",
            newPassword = "new-secret-2",
            notifyOtp = true,
        ).toString()

        assertFalse(text, text.contains("old-secret-1"))
        assertFalse(text, text.contains("new-secret-2"))
        assertTrue(text, text.contains("notify_otp"))
    }
}
