package com.mailhub.client.work

import com.mailhub.client.data.InMemoryDataStore
import com.mailhub.client.data.net.ApiClient
import com.mailhub.client.data.session.Session
import com.mailhub.client.data.testSession
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 后台增量同步的一轮：游标、reset、渠道挑选、去重、401 与网络错误、按服务器分开的游标。
 * 走真实的 ApiClient + MockWebServer，通知出口换成记录调用的假实现。
 */
class ChangesSyncTest {

    private lateinit var server: MockWebServer
    private lateinit var cursors: ChangesCursorStore
    private val notified = mutableListOf<Pair<Long, NotifyChannel>>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cursors = ChangesCursorStore(InMemoryDataStore())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun sessionOf(target: MockWebServer): Session = testSession(target.url("/").toString(), "tok-1")

    private fun syncFor(session: Session?): ChangesSync = ChangesSync(
        session = { session },
        clientFor = { ApiClient(it.serverUrl.toHttpUrl(), it.token) },
        cursors = cursors,
        notifier = { item, channel -> notified += item.id to channel },
    )

    private fun sync(): ChangesSync = syncFor(sessionOf(server))

    private val origin: String get() = originOf(server.url("/").toString())!!

    private fun json(code: Int, body: String): MockResponse =
        MockResponse.Builder()
            .code(code)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun item(
        id: Long,
        category: String,
        importance: Int = 0,
        otp: String = "",
        unread: Int = 1,
    ): String =
        """{"id":$id,"account_id":1,"account_name":"工作邮箱","subject":"主题 $id",
           "sender_name":"发件人","sender_addr":"a@example.com","date_ts":1793782440.0,
           "category":"$category","bill_tier":"","importance":$importance,"otp_code":"$otp",
           "risk_level":"none","unread":$unread,"display_category":"$category"}"""

    private fun page(cursor: Long, vararg items: String, reset: Boolean = false): String =
        """{"items":[${items.joinToString(",")}],"cursor":$cursor,"reset":$reset}"""

    @Test
    fun firstRunOnlyStoresTheCursor() = runBlocking {
        server.enqueue(json(200, page(120)))

        val outcome = sync().runOnce()

        assertEquals(ChangesOutcome.Done(notified = 0), outcome)
        assertEquals(120L, cursors.get(origin))
        assertTrue(notified.isEmpty())
        assertEquals("/api/changes?after_id=0&limit=$ChangesPageSize", server.takeRequest().target)
    }

    @Test
    fun resetOnlyMovesTheCursor() = runBlocking {
        cursors.set(origin, 500)
        server.enqueue(json(200, page(120, item(101, "重要"), reset = true)))

        val outcome = sync().runOnce()

        assertEquals(ChangesOutcome.Done(notified = 0), outcome)
        assertEquals(120L, cursors.get(origin))
        assertTrue(notified.isEmpty())
    }

    @Test
    fun notifiesOnlyEligibleMailAndAdvancesTheCursor() = runBlocking {
        cursors.set(origin, 100)
        server.enqueue(
            json(
                200,
                page(
                    107,
                    item(101, "重要"),
                    item(102, "推广"),
                    item(103, "验证码", otp = "482913"),
                    item(104, "可疑", importance = 5),
                    item(105, "账单", importance = 4),
                    item(106, "验证码"),
                    item(107, "重要", unread = 0),
                ),
            ),
        )

        val outcome = sync().runOnce()

        assertEquals(ChangesOutcome.Done(notified = 4), outcome)
        assertEquals(
            listOf(
                101L to NotifyChannel.Important,
                103L to NotifyChannel.Otp,
                104L to NotifyChannel.Security,
                105L to NotifyChannel.Important,
            ),
            notified,
        )
        assertEquals(107L, cursors.get(origin))
        assertEquals("/api/changes?after_id=100&limit=$ChangesPageSize", server.takeRequest().target)
    }

    @Test
    fun theSameMailIsNotifiedOnlyOnce() = runBlocking {
        cursors.set(origin, 100)
        server.enqueue(json(200, page(101, item(101, "重要"))))
        server.enqueue(json(200, page(101)))

        sync().runOnce()
        sync().runOnce()

        assertEquals(listOf(101L to NotifyChannel.Important), notified)
        server.takeRequest()
        // 第二轮从新游标往后拉
        assertEquals("/api/changes?after_id=101&limit=$ChangesPageSize", server.takeRequest().target)
    }

    @Test
    fun notificationsAreCappedAndKeepTheNewest() = runBlocking {
        cursors.set(origin, 100)
        val items = (101L..108L).map { item(it, "重要") }.toTypedArray()
        server.enqueue(json(200, page(108, *items)))

        sync().runOnce()

        assertEquals((104L..108L).toList(), notified.map { it.first })
        assertEquals(108L, cursors.get(origin))
    }

    @Test
    fun fullPagesAreFollowedUntilAShortPage() = runBlocking {
        cursors.set(origin, 100)
        val first = (101L until 101L + ChangesPageSize).map { item(it, "推广") }.toTypedArray()
        val firstCursor = 100L + ChangesPageSize
        server.enqueue(json(200, page(firstCursor, *first)))
        server.enqueue(json(200, page(firstCursor + 1, item(firstCursor + 1, "安全"))))

        sync().runOnce()

        assertEquals(listOf(firstCursor + 1 to NotifyChannel.Security), notified)
        assertEquals(firstCursor + 1, cursors.get(origin))
        assertEquals("/api/changes?after_id=100&limit=$ChangesPageSize", server.takeRequest().target)
        assertEquals("/api/changes?after_id=$firstCursor&limit=$ChangesPageSize", server.takeRequest().target)
    }

    @Test
    fun unauthorizedDoesNothing() = runBlocking {
        cursors.set(origin, 100)
        server.enqueue(json(401, """{"detail":"会话已失效"}"""))

        val outcome = sync().runOnce()

        assertEquals(ChangesOutcome.Unauthorized, outcome)
        assertEquals(100L, cursors.get(origin))
        assertTrue(notified.isEmpty())
    }

    @Test
    fun serverErrorsAskForARetryAndKeepTheCursor() = runBlocking {
        cursors.set(origin, 100)
        server.enqueue(json(503, """{"detail":"维护中"}"""))

        assertEquals(ChangesOutcome.Retry, sync().runOnce())
        assertEquals(100L, cursors.get(origin))
    }

    @Test
    fun malformedResponsesDoNotRetry() = runBlocking {
        cursors.set(origin, 100)
        server.enqueue(json(200, """{"items":"不是数组"}"""))

        assertEquals(ChangesOutcome.Failed, sync().runOnce())
        assertEquals(100L, cursors.get(origin))
    }

    @Test
    fun cursorsAreKeptPerServer() = runBlocking {
        val other = MockWebServer()
        other.start()
        try {
            cursors.set(origin, 100)
            other.enqueue(json(200, page(7)))

            syncFor(sessionOf(other)).runOnce()

            assertEquals(7L, cursors.get(originOf(other.url("/").toString())!!))
            assertEquals(100L, cursors.get(origin))
            assertEquals(0, server.requestCount)
        } finally {
            other.close()
        }
    }

    @Test
    fun noSessionDoesNothing() = runBlocking {
        assertEquals(ChangesOutcome.NoSession, syncFor(null).runOnce())
        assertEquals(0, server.requestCount)
        assertNull(cursors.get(origin))
    }

    @Test
    fun originIgnoresTrailingSlashAndDefaultPort() {
        assertEquals(originOf("https://mail.example.com"), originOf("https://mail.example.com:443/"))
        assertNull(originOf("不是地址"))
    }
}
