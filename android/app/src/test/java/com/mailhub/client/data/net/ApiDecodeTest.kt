package com.mailhub.client.data.net

import com.mailhub.client.data.FakeCipher
import com.mailhub.client.data.InMemoryDataStore
import com.mailhub.client.data.session.SessionManager
import com.mailhub.client.data.session.SessionStore
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 按后端真实返回体检查解码：类型不对时是 ApiError.Decode，不会把异常抛给调用方。 */
class ApiDecodeTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse.Builder()
            .code(code)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val store = SessionStore(InMemoryDataStore(), FakeCipher())
        client = SessionManager(store).newClient(server.url("/"), null)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun messageDetailWithTextRiskLevelDecodes() = runBlocking {
        server.enqueue(
            jsonResponse(
                200,
                """{"id":11,"account_id":1,"subject":"账单","sender_addr":"a@b.c","to_addr":"me@b.c",
                   "date_ts":1700000000.0,"snippet":"","category":"账单","bill_tier":"重要",
                   "otp_code":"","importance":5,"summary":"","unread":0,"has_attach":1,
                   "account_name":"主邮箱","body_text":"正文","body_html":"<p>正文</p>",
                   "risk_level":"high","risk_reasons":"可疑链接","confidence":0.9,
                   "ai_reason":"","governance_ministry":"兵部","governance_source":"ai",
                   "governance_reason":"","needs_review":0,"deleted_ts":0}
                """.trimIndent(),
            ),
        )

        val detail = client.call { it.message(11, markRead = false) }.getOrThrow()

        assertEquals("high", detail.riskLevel)
        assertTrue(detail.isRisky)
        assertTrue(detail.hasAttachment)
        assertEquals(11L, detail.id)
    }

    @Test
    fun accountsWithIntegerEnabledDecode() = runBlocking {
        server.enqueue(
            jsonResponse(
                200,
                """[{"id":1,"name":"主邮箱","provider":"qq","email":"a@qq.com","enabled":1,
                     "last_sync":1700000000.0,"last_error":"","total":10,"unread":2,
                     "has_secret":true,"imap_host":"imap.qq.com","imap_port":993,
                     "sync_state":{"state":"idle","msg":"","ts":0,"new":0}},
                    {"id":2,"name":"备用","provider":"custom","email":"b@x.com","enabled":0,
                     "last_sync":0.0,"last_error":"认证失败","total":0,"unread":0,
                     "sync_state":{"state":"error","msg":"认证失败","ts":1.0,"new":0}}]""",
            ),
        )

        val accounts = client.call { it.accounts() }.getOrThrow()

        assertEquals(2, accounts.size)
        assertTrue(accounts[0].enabled)
        assertFalse(accounts[1].enabled)
        assertEquals("认证失败", accounts[1].lastError)
    }

    @Test
    fun typeMismatchBecomesDecodeError() = runBlocking {
        // 后端永远返回数字的字段被换成对象：解码失败但不抛异常
        server.enqueue(jsonResponse(200, """{"api_version":{"major":1},"tz":"Asia/Shanghai"}"""))

        val result = client.call { it.meta() }

        assertTrue("result=$result", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("error=$error", error is ApiError.Decode)
    }

    @Test
    fun malformedJsonBecomesDecodeError() = runBlocking {
        // 截断的 JSON：同样是解码失败，不抛异常
        server.enqueue(jsonResponse(200, """{"id":"""))

        val detail = client.call { it.message(1) }

        assertTrue("result=$detail", detail.isFailure)
        assertTrue("error=${detail.exceptionOrNull()}", detail.exceptionOrNull() is ApiError.Decode)
    }
}
