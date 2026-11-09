package com.mailhub.core.imap

import com.mailhub.core.db.FakeClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ImapSession] 对**真实 IMAP 服务器**（本地 GreenMail，IMAPS + 自签名证书）的行为，
 * 逐条对照 `mail_client.py`。这些用例只能在桌面 JVM 上跑（GreenMail 依赖 JVM 的 TLS
 * 与本地端口），所以放在 `src/jvmTest`。
 */
class ImapSessionGreenMailTest {

    private lateinit var fixture: GreenMailFixture

    /** 可控时钟：以夹具创建时间为基准，`SINCE` 窗口与真实投递时间对得上。 */
    private val clock: FakeClock get() = FakeClock(fixture.now)

    @Before
    fun setUp() {
        fixture = GreenMailFixture()
    }

    @After
    fun tearDown() {
        if (::fixture.isInitialized) {
            fixture.close()
        }
    }

    private fun open(): ImapSession = openSession(fixture.account(), fixture.password, fixture.transport(), clock)

    /** 登录、读能力、选中收件箱（对应 `select_inbox`），UIDVALIDITY 要能读出来。 */
    @Test
    fun loginAndSelectInboxReadsUidValidity() {
        open().use { session ->
            val uidValidity = session.selectInbox()
            assertTrue("UIDVALIDITY 应该大于 0，实际 $uidValidity", uidValidity > 0)
            assertTrue(
                "GreenMail 通告 UIDPLUS，登录后重新读取能力列表应能看到：${session.capabilities}",
                session.capabilities.contains("UIDPLUS"),
            )
        }
    }

    /** 首次同步：`last_uid == 0` 时按 SINCE 搜索，返回升序 UID。 */
    @Test
    fun searchUidsReturnsDeliveredMessagesInAscendingOrder() {
        fixture.deliver("第一封")
        fixture.deliver("第二封")
        fixture.deliver("第三封")
        open().use { session ->
            session.selectInbox()
            val uids = session.searchUids(lastUid = 0)
            assertEquals("应该搜到 3 封", 3, uids.size)
            assertEquals("UID 必须升序", uids.sorted(), uids)
        }
    }

    /**
     * 首次搜索的 30 天窗口与「最多 200 封」：把时钟往后拨 40 天，SINCE 落在未来，
     * 一封都搜不到；把 `firstLimit` 调小则只保留**最新**的那几封。
     */
    @Test
    fun firstSearchHonoursThirtyDayWindowAndLimit() {
        fixture.deliver("甲")
        fixture.deliver("乙")
        fixture.deliver("丙")
        open().use { session ->
            session.selectInbox()
            val newest = session.searchUids(lastUid = 0, sinceDays = 30, firstLimit = 2)
            assertEquals("上限 2 封时应保留最新的两封", 2, newest.size)

            val laterClock = FakeClock(fixture.now + 40 * 86400)
            val future = openSession(
                fixture.account(),
                fixture.password,
                fixture.transport(),
                laterClock,
            )
            future.use { other ->
                other.selectInbox()
                assertEquals(
                    "30 天窗口之外的邮件不应该被首次同步取到",
                    emptyList<Long>(),
                    other.searchUids(lastUid = 0),
                )
            }
        }
    }

    /** 增量同步：`last_uid` 之后的邮件才会返回。 */
    @Test
    fun searchUidsIsIncrementalAfterFirstSync() {
        fixture.deliver("旧邮件")
        open().use { session ->
            session.selectInbox()
            val first = session.searchUids(lastUid = 0)
            assertEquals(1, first.size)
            val lastUid = first.max()
            fixture.deliver("新邮件")
            val next = session.searchUids(lastUid = lastUid)
            assertEquals("增量只应拿到 UID 大于水位的邮件", 1, next.size)
            assertTrue(next.single() > lastUid)
        }
    }

    /** 整封抓取：BODY.PEEK 不改变已读状态，解析出的字段与邮件内容一致。 */
    @Test
    fun fetchMessagesDoesNotMarkSeenAndParsesFields() {
        fixture.deliver(
            subject = "验证码 123456",
            body = "您的验证码是 123456，5 分钟内有效。",
            messageId = "<m1@example.org>",
            from = "notice@bank.example",
        )
        open().use { session ->
            session.selectInbox()
            val uids = session.searchUids(0)
            val result = session.fetchMessages(uids)
            assertEquals("不应该有失败的 UID：${result.failed}", emptySet<Long>(), result.failed)
            val message = result.messages.single()
            assertEquals(uids.single(), message.uid)
            assertEquals(1, message.unread)
            assertEquals(0, message.bodyTruncated)
            assertEquals("<m1@example.org>", message.parsed.msgId)
            assertEquals("验证码 123456", message.parsed.subject)
            assertEquals("notice@bank.example", message.parsed.senderAddr)
            assertTrue(
                "正文应解析出来，实际：${message.parsed.bodyText}",
                message.parsed.bodyText.contains("123456"),
            )
            // BODY.PEEK 不该改已读状态
            val flags = session.fetchFlags(uids)
            assertFalse("BODY.PEEK 不应该把邮件标成已读", flags.getValue(uids.single()).any {
                it.equals("\\Seen", ignoreCase = true)
            })
        }
    }

    /** `fetch_sizes` 只取元数据，大小与整封抓取到的字节数一致。 */
    @Test
    fun fetchSizesReportsMessageSize() {
        fixture.deliver("大小测试", body = "x".repeat(4096))
        open().use { session ->
            session.selectInbox()
            val uid = session.searchUids(0).single()
            val sizes = session.fetchSizes(listOf(uid))
            assertNotNull("应该拿到大小：$sizes", sizes[uid])
            assertTrue("大小应大于 4KB，实际 ${sizes[uid]}", (sizes[uid] ?: 0) > 4096)
        }
    }

    /** `mark_read` / `store_flags` 能把已读标记写上去，也能撤回。 */
    @Test
    fun markReadWritesAndClearsSeenFlag() {
        fixture.deliver("标记测试")
        open().use { session ->
            session.selectInbox()
            val uid = session.searchUids(0).single()
            session.markRead(listOf(uid), read = true)
            assertTrue(
                "标记已读后应该有 \\Seen：${session.fetchFlags(listOf(uid))}",
                session.fetchFlags(listOf(uid)).getValue(uid).any { it.equals("\\Seen", true) },
            )
            session.markRead(listOf(uid), read = false)
            assertFalse(
                "撤回后不该有 \\Seen",
                session.fetchFlags(listOf(uid)).getValue(uid).any { it.equals("\\Seen", true) },
            )
        }
    }

    /** 有 UIDPLUS 时 `delete` 用 `UID EXPUNGE`，只清列出的 UID，返回 expunged。 */
    @Test
    fun deleteUsesUidExpungeWhenUidPlusIsAdvertised() {
        fixture.deliver("要删的")
        fixture.deliver("保留的")
        open().use { session ->
            session.selectInbox()
            assertTrue("GreenMail 应该通告 UIDPLUS", session.capabilities.contains("UIDPLUS"))
            val uids = session.searchUids(0)
            val target = uids.first()
            assertEquals("expunged", session.delete(listOf(target)))
            val remaining = session.searchUids(0)
            assertEquals("只应删掉指定的那一封", listOf(uids.last()), remaining)
        }
    }

    /** 大邮件（超过 LARGE_MESSAGE_BYTES）只取头部和正文前 1MB，并标记 body_truncated。 */
    @Test
    fun largeMessageIsTruncated() {
        // 2MB 是 Python 那边的阈值（LARGE_MESSAGE_BYTES），这里造一封明显更大的邮件
        val huge = "a".repeat(LARGE_MESSAGE_BYTES.toInt() + 256 * 1024)
        fixture.deliver("大邮件", body = huge)
        open().use { session ->
            session.selectInbox()
            val uid = session.searchUids(0).single()
            val sizes = session.fetchSizes(listOf(uid))
            assertTrue(
                "这封邮件的 RFC822.SIZE 应该超过 2MB，实际 ${sizes[uid]}",
                (sizes[uid] ?: 0) > LARGE_MESSAGE_BYTES,
            )
            val result = session.fetchMessages(listOf(uid))
            val message = result.messages.single()
            assertEquals("大邮件必须标记 body_truncated", 1, message.bodyTruncated)
            assertTrue(
                "截断后的正文不能超过 1MB 太多，实际 ${message.parsed.bodyText.length}",
                message.parsed.bodyText.length <= TRUNCATED_BODY_BYTES + 1024,
            )
            assertTrue("截断后仍应有正文", message.parsed.bodyText.isNotEmpty())
        }
    }

    private companion object {
        /** 30 天换算成秒：窗口测试里把时钟往后拨 40 天用得上。 */
        @Suppress("unused")
        const val THIRTY_DAYS_SECONDS: Int = 30 * 86400
    }
}
