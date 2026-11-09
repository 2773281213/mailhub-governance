package com.mailhub.core.sync

import com.mailhub.core.billing.Billing
import com.mailhub.core.db.Db
import com.mailhub.core.db.FakeClock
import com.mailhub.core.db.FakeSecretCipher
import com.mailhub.core.db.withTempDb
import com.mailhub.core.imap.GreenMailFixture
import com.mailhub.core.imap.ImapSession
import com.mailhub.core.imap.ImapTransport
import com.mailhub.core.imap.LARGE_MESSAGE_BYTES
import com.mailhub.core.imap.ScriptedImapServer
import com.mailhub.core.imap.TestTls
import com.mailhub.core.imap.openSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [SyncService] 对**真实 IMAP 服务器**（本地 GreenMail，IMAPS + 自签名证书）的行为，
 * 逐条对照 `sync.py` 的 `sync_account` 与其辅助函数。
 *
 * 每个用例自己建库（临时目录里的真实 SQLite 文件）、自己投递合成邮件；所有数据都是合成的，
 * 不连接任何真实邮箱。GreenMail 依赖桌面 JVM 的 TLS 与本地端口，所以放在 `src/jvmTest`。
 */
class SyncServiceGreenMailTest {

    private lateinit var fixture: GreenMailFixture

    /** 可控时钟：以夹具创建时间为基准，首次同步的 30 天窗口与投递时间对得上。 */
    private lateinit var clock: FakeClock

    private val cipher = FakeSecretCipher()

    @Before
    fun setUp() {
        fixture = GreenMailFixture()
        clock = FakeClock(fixture.now)
    }

    @After
    fun tearDown() {
        if (::fixture.isInitialized) {
            fixture.close()
        }
    }

    // ---------------- 首次 / 增量 ----------------

    /** 首次同步：导入收件箱里的邮件、写回水位与 UIDVALIDITY，并清掉 `last_error`。 */
    @Test
    fun firstSyncImportsMailAndRecordsWatermark() = withTempDb(clock) { db ->
        db.initDb()
        val accountId = insertAccount(db)
        fixture.deliver("验证码 123456", body = "您的验证码是 123456，5 分钟内有效。", messageId = "<m1@example.org>")
        fixture.deliver("第二封", messageId = "<m2@example.org>")

        assertEquals("两封都应入库", 2, syncService(db).syncAccount(accountId))

        val account = accountRow(db, accountId)
        assertTrue("水位应推进到最大 UID，实际 ${account.long("last_uid")}", (account.long("last_uid") ?: 0) > 0)
        assertTrue("UIDVALIDITY 应写回", (account.long("uidvalidity") ?: 0) > 0)
        assertEquals("last_sync 应更新", clock.now(), account.double("last_sync") ?: -1.0, 1e-6)
        assertEquals("成功的一轮要清掉 last_error", "", account.str("last_error"))
        assertEquals(2L, countMessages(db, accountId))
    }

    /** 首次同步最多导入 200 封：超出时保留**最新**的那一批。 */
    @Test
    fun firstSyncKeepsOnlyTheNewestTwoHundred() = withTempDb(clock) { db ->
        db.initDb()
        val accountId = insertAccount(db)
        for (index in 1..205) {
            fixture.deliver("第 $index 封")
        }

        assertEquals("首次同步上限 200 封", 200, syncService(db).syncAccount(accountId))

        assertEquals("库里只应有 200 封", 200L, countMessages(db, accountId))
        val minUid = db.queryOne("SELECT MIN(uid) AS u FROM messages WHERE account_id=?", accountId)
            ?.long("u") ?: 0
        assertTrue("应保留最新的 200 封（丢掉最早的几封），实际最小 UID 是 $minUid", minUid > 1)
    }

    /** 首次同步只看最近 30 天：把时钟拨到窗口之外，一封都不该导入。 */
    @Test
    fun firstSyncIgnoresMailOlderThanThirtyDays() {
        fixture.deliver("窗口外的邮件")
        val late = FakeClock(fixture.now + 40 * 86400)
        withTempDb(late) { db ->
            db.initDb()
            val accountId = insertAccount(db)
            assertEquals(0, syncService(db, late).syncAccount(accountId))
            assertEquals(0L, countMessages(db, accountId))
            assertEquals(0L, accountRow(db, accountId).long("last_uid"))
        }
    }

    /** 增量同步：水位之后的邮件才会被拉取，水位继续前进。 */
    @Test
    fun incrementalSyncOnlyFetchesNewMail() = withTempDb(clock) { db ->
        db.initDb()
        val accountId = insertAccount(db)
        fixture.deliver("第一封", messageId = "<m1@example.org>")
        val service = syncService(db)
        assertEquals(1, service.syncAccount(accountId))
        val firstWatermark = accountRow(db, accountId).long("last_uid") ?: 0

        fixture.deliver("第二封", messageId = "<m2@example.org>")
        assertEquals("增量只应拿到新的一封", 1, service.syncAccount(accountId))
        assertEquals(2L, countMessages(db, accountId))
        assertTrue(
            "水位应继续前进",
            (accountRow(db, accountId).long("last_uid") ?: 0) > firstWatermark,
        )
    }

    // ---------------- UIDVALIDITY 与去重 ----------------

    /**
     * 服务器重建邮箱（UIDVALIDITY 变化）后，同一封信按 Message-ID 认回原来的本地记录：
     * 不重复入库，原有分类保留。
     */
    @Test
    fun uidValidityChangeRebindsByMessageIdWithoutDuplicates() = withTempDb(clock) { db ->
        db.initDb()
        val accountId = insertAccount(db)
        fixture.deliver("重建前", messageId = "<same@example.org>")
        val service = syncService(db)
        assertEquals(1, service.syncAccount(accountId))

        val before = db.queryOne("SELECT id, uid FROM messages WHERE account_id=?", accountId)!!
        val oldValidity = accountRow(db, accountId).long("uidvalidity") ?: 0
        // 打个记号，重建后如果新建了行，这个分类就丢了
        db.execute("UPDATE messages SET category='重要' WHERE id=?", before.long("id"))

        fixture.rewriteUidValidity(oldValidity + 1)
        fixture.purgeAll()
        fixture.deliver("重建后", messageId = "<same@example.org>")

        assertEquals("认回之后不该算新邮件", 0, service.syncAccount(accountId))

        assertEquals("同一封信只应有一行", 1L, countMessages(db, accountId))
        val after = db.queryOne("SELECT id, uid, category FROM messages WHERE account_id=?", accountId)!!
        assertEquals("应更新原来那一行，而不是新建", before.long("id"), after.long("id"))
        assertEquals("分类要保留", "重要", after.str("category"))
        assertTrue("UID 应重新绑定成正数，实际 ${after.long("uid")}", (after.long("uid") ?: 0) > 0)
        assertNull(
            "不应留下被解绑的旧行",
            db.queryOne("SELECT id FROM messages WHERE account_id=? AND uid<0", accountId),
        )
    }

    /** 同一个 Message-ID 从两个账户到达时只存一份（对应 `_is_duplicate`）。 */
    @Test
    fun sameMessageInTwoAccountsIsStoredOnce() {
        val second = GreenMailFixture(email = "other@example.com", password = "test-secret-2")
        try {
            withTempDb(clock) { db ->
                db.initDb()
                val firstId = insertAccount(db)
                val secondId = insertAccount(
                    db,
                    name = "第二账户",
                    email = second.email,
                    port = second.port,
                    secretEnc = cipher.encrypt(second.password),
                )
                val service = SyncService(db, cipher, clock, sessionFactory = { account, secret ->
                    val source = if (account.email == second.email) second else fixture
                    openSession(account.toImapAccount(), secret, source.transport(), clock)
                })
                fixture.deliver("转发来的信", messageId = "<shared@example.org>")
                second.deliver("转发来的信", messageId = "<shared@example.org>")

                assertEquals(1, service.syncAccount(firstId))
                assertEquals("第二个账户不该再存一份", 0, service.syncAccount(secondId))

                assertEquals("全库只应有一封", 1L, countAllMessages(db))
                val row = db.queryOne("SELECT account_id FROM messages")!!
                assertEquals(firstId.toLong(), row.long("account_id"))
            }
        } finally {
            second.close()
        }
    }

    // ---------------- 已读状态双向同步 ----------------

    /** 开关打开时：本地改动推送到服务器，服务器的 `\Seen` 也拉回本地。 */
    @Test
    fun readStatePushAndPullWhenEnabled() = withTempDb(clock) { db ->
        db.initDb()
        db.setSetting("sync_read_state", "1")
        val accountId = insertAccount(db)
        fixture.deliver("待推送", messageId = "<push@example.org>")
        fixture.deliver("待拉取", messageId = "<pull@example.org>")
        val service = syncService(db)
        assertEquals(2, service.syncAccount(accountId))

        val pushRow = db.queryOne("SELECT id, uid FROM messages WHERE msg_id='<push@example.org>'")!!
        val pullRow = db.queryOne("SELECT id, uid FROM messages WHERE msg_id='<pull@example.org>'")!!
        // 本地登记「已读」待推送
        db.execute("UPDATE messages SET flag_push=1 WHERE id=?", pushRow.long("id"))
        // 服务器上把另一封标成已读，等着拉回本地
        openClient().use { session ->
            session.selectInbox()
            session.markRead(listOf(pullRow.long("uid")!!), read = true)
        }

        assertEquals("这一轮没有新邮件", 0, service.syncAccount(accountId))

        assertEquals(
            "推送成功后要清掉 flag_push",
            0L,
            db.queryOne("SELECT flag_push FROM messages WHERE id=?", pushRow.long("id"))!!.long("flag_push"),
        )
        openClient().use { session ->
            session.selectInbox()
            val marks = session.fetchFlags(listOf(pushRow.long("uid")!!)).getValue(pushRow.long("uid")!!)
            assertTrue("服务器上应已标成已读，实际 $marks", marks.any { it.equals("\\Seen", true) })
        }
        assertEquals(
            "服务器已读的邮件要拉成本地已读",
            0L,
            db.queryOne("SELECT unread FROM messages WHERE id=?", pullRow.long("id"))!!.long("unread"),
        )
    }

    /** 默认关闭：两个方向都不做，`flag_push` 与 `unread` 原样保留。 */
    @Test
    fun readStateSyncStaysOffByDefault() = withTempDb(clock) { db ->
        db.initDb()
        val accountId = insertAccount(db)
        fixture.deliver("未读的邮件", messageId = "<keep@example.org>")
        val service = syncService(db)
        assertEquals(1, service.syncAccount(accountId))

        val row = db.queryOne("SELECT id, uid FROM messages")!!
        db.execute("UPDATE messages SET flag_push=1 WHERE id=?", row.long("id"))
        openClient().use { session ->
            session.selectInbox()
            session.markRead(listOf(row.long("uid")!!), read = true)
        }

        assertEquals(0, service.syncAccount(accountId))
        assertEquals(
            "关闭时不该推送",
            1L,
            db.queryOne("SELECT flag_push FROM messages WHERE id=?", row.long("id"))!!.long("flag_push"),
        )
        assertEquals(
            "关闭时不该拉取",
            1L,
            db.queryOne("SELECT unread FROM messages WHERE id=?", row.long("id"))!!.long("unread"),
        )
    }

    /** UIDVALIDITY 刚变化的那一轮跳过已读同步：旧 UID 已作废，按它读写会误改别的邮件。 */
    @Test
    fun readStateIsSkippedOnTheRoundThatUidValidityChanges() = withTempDb(clock) { db ->
        db.initDb()
        db.setSetting("sync_read_state", "1")
        val accountId = insertAccount(db)
        fixture.deliver("重建前", messageId = "<rebuilt@example.org>")
        val service = syncService(db)
        assertEquals(1, service.syncAccount(accountId))

        val row = db.queryOne("SELECT id, uid FROM messages")!!
        val oldValidity = accountRow(db, accountId).long("uidvalidity") ?: 0
        db.execute("UPDATE messages SET flag_push=1 WHERE id=?", row.long("id"))
        fixture.rewriteUidValidity(oldValidity + 1)

        assertEquals(0, service.syncAccount(accountId))

        assertEquals(
            "重建邮箱的那一轮不能推送（UID 已作废）",
            1L,
            db.queryOne("SELECT flag_push FROM messages WHERE id=?", row.long("id"))!!.long("flag_push"),
        )
        openClient().use { session ->
            session.selectInbox()
            val uid = db.queryOne("SELECT uid FROM messages WHERE id=?", row.long("id"))!!.long("uid")!!
            val marks = session.fetchFlags(listOf(uid)).getValue(uid)
            assertFalse("服务器上不该被标成已读，实际 $marks", marks.any { it.equals("\\Seen", true) })
        }
    }

    // ---------------- 服务器动作 ----------------

    /** `applyServerAction` 的已读、未读、删除三条路径。 */
    @Test
    fun applyServerActionReadsUnreadsAndDeletes() = withTempDb(clock) { db ->
        db.initDb()
        val accountId = insertAccount(db)
        fixture.deliver("甲", messageId = "<a@example.org>")
        fixture.deliver("乙", messageId = "<b@example.org>")
        val service = syncService(db)
        assertEquals(2, service.syncAccount(accountId))
        val rowA = db.queryOne("SELECT id, uid FROM messages WHERE msg_id='<a@example.org>'")!!
        val rowB = db.queryOne("SELECT id, uid FROM messages WHERE msg_id='<b@example.org>'")!!
        val idA = rowA.long("id")!!
        val uidA = rowA.long("uid")!!
        val uidB = rowB.long("uid")!!

        val read = service.applyServerAction(listOf(idA), "read")
        assertEquals("已读应成功", listOf(idA), read.succeeded)
        assertEquals(emptyList<String>(), read.errors)
        assertTrue("服务器上甲应已读", serverFlags(uidA).any { it.equals("\\Seen", true) })

        val unread = service.applyServerAction(listOf(idA), "unread")
        assertEquals(listOf(idA), unread.succeeded)
        assertFalse("服务器上甲应改回未读", serverFlags(uidA).any { it.equals("\\Seen", true) })

        val deleted = service.applyServerAction(listOf(rowB.long("id")!!), "delete")
        assertEquals("删除应成功", listOf(rowB.long("id")!!), deleted.succeeded)
        assertFalse("服务器上不该再有乙，实际 ${serverUids()}", serverUids().contains(uidB))
    }

    /** 部分失败：连不上的账户与负 UID 的记录各自报错，成功的那部分照常返回。 */
    @Test
    fun applyServerActionReportsPartialFailures() = withTempDb(clock) { db ->
        db.initDb()
        val goodId = insertAccount(db)
        fixture.deliver("正常的邮件", messageId = "<ok@example.org>")
        val service = syncService(db)
        assertEquals(1, service.syncAccount(goodId))
        val goodMessage = db.queryOne("SELECT id FROM messages WHERE msg_id='<ok@example.org>'")!!
        val goodMessageId = goodMessage.long("id")!!

        // 连不上的账户（127.0.0.1:1 必然拒绝连接）
        val badId = insertAccount(db, name = "坏账户", port = 1)
        val badMessageId = insertMessage(db, badId, uid = 5, msgId = "<bad@example.org>")
        // UIDVALIDITY 变化后解绑的旧记录（uid < 0）
        val orphanId = insertMessage(db, goodId, uid = -7, msgId = "<orphan@example.org>")

        val result = service.applyServerAction(listOf(goodMessageId, badMessageId, orphanId), "read")

        assertTrue("正常的邮件应成功：${result.succeeded}", result.succeeded.contains(goodMessageId))
        assertFalse("连不上的账户不该算成功：${result.succeeded}", result.succeeded.contains(badMessageId))
        assertFalse("负 UID 的旧记录不该算成功：${result.succeeded}", result.succeeded.contains(orphanId))
        assertTrue(
            "负 UID 要有明确错误：${result.errors}",
            result.errors.any { it.startsWith("邮件$orphanId") },
        )
        assertTrue(
            "连不上的账户要有错误：${result.errors}",
            result.errors.any { it.startsWith("账户$badId") },
        )
    }

    // ---------------- 大邮件 ----------------

    /** 超过 2MB 的邮件只取头部与正文前 1MB，入库时带 `body_truncated=1`。 */
    @Test
    fun largeMessageIsStoredTruncated() = withTempDb(clock) { db ->
        db.initDb()
        val accountId = insertAccount(db)
        fixture.deliver("大邮件", body = "a".repeat(LARGE_MESSAGE_BYTES.toInt() + 256 * 1024))

        assertEquals(1, syncService(db).syncAccount(accountId))

        val row = db.queryOne("SELECT body_truncated, body_text FROM messages")!!
        assertEquals("大邮件必须标记截断", 1L, row.long("body_truncated"))
        assertTrue("截断后仍应有正文", !row.str("body_text").isNullOrEmpty())
    }

    // ---------------- 水位 ----------------

    /** 抓取失败时水位不越过失败的 UID（`_advance_watermark` 的直接单测）。 */
    @Test
    fun advanceWatermarkStopsAtTheFirstFailedUid() {
        assertEquals("第一个 UID 就失败：水位不动", 0L, advanceWatermark(0, listOf(1, 2, 3), setOf(1)))
        assertEquals("中间失败：停在前一个", 2L, advanceWatermark(0, listOf(1, 2, 3), setOf(3)))
        assertEquals("没有失败：推进到最大", 3L, advanceWatermark(0, listOf(1, 2, 3), emptySet()))
        assertEquals("空批次：保持原水位", 5L, advanceWatermark(5, emptyList(), emptySet()))
        assertEquals("全都失败：保持原水位", 3L, advanceWatermark(3, listOf(4, 5), setOf(4, 5)))
    }

    /** 端到端：服务器拒绝 FETCH 时水位留在 0，`last_error` 说明有几封没拉下来。 */
    @Test
    fun fetchFailureKeepsWatermarkAndRecordsError() {
        ScriptedImapServer(capabilities = "IMAP4rev1").use { server ->
            withTempDb(clock) { db ->
                db.initDb()
                val accountId = insertAccount(db, host = "127.0.0.1", port = server.port)
                server.searchUids = " 3 4"
                server.failFetch = true

                val service = SyncService(db, cipher, clock, sessionFactory = { account, secret ->
                    openSession(
                        account.toImapAccount(),
                        secret,
                        ImapTransport(TestTls.trustAllFactory, 5_000, 5_000),
                        clock,
                    )
                })
                assertEquals("一封都没入库", 0, service.syncAccount(accountId))

                val account = accountRow(db, accountId)
                assertEquals("水位不能越过失败的 UID", 0L, account.long("last_uid"))
                assertEquals(
                    "2 封邮件拉取或入库失败，下次同步重试",
                    account.str("last_error"),
                )
                assertEquals(0L, countMessages(db, accountId))
            }
        }
    }

    // ---------------- 首次导入标记 ----------------

    /** 首次导入标记只为第一轮写一次；之后的同步不会把它改掉。 */
    @Test
    fun initialImportMarkerIsWrittenOnce() = withTempDb(clock) { db ->
        db.initDb()
        val accountId = insertAccount(db)
        fixture.deliver("第一封", messageId = "<i1@example.org>")
        fixture.deliver("第二封", messageId = "<i2@example.org>")
        val service = syncService(db)
        assertEquals(2, service.syncAccount(accountId))

        val key = "$INITIAL_IMPORT_KEY_PREFIX$accountId"
        val marker = db.getSetting(key).toLongOrNull() ?: 0L
        val maxId = db.queryOne("SELECT MAX(id) AS m FROM messages")!!.long("m")!!
        assertTrue("应记下首次导入的最大 id", marker > 0)
        assertEquals("标记应等于本轮最大 id", maxId, marker)

        fixture.deliver("第三封", messageId = "<i3@example.org>")
        assertEquals(1, service.syncAccount(accountId))
        assertEquals("第二次同步不该改标记", marker, db.getSetting(key).toLongOrNull() ?: 0L)
    }

    // ---------------- 错误文案 ----------------

    /** 认证失败：`last_error` 是中文说明，状态是 error。 */
    @Test
    fun authFailureStoresChineseErrorText() = withTempDb(clock) { db ->
        db.initDb()
        val accountId = insertAccount(db, secretEnc = cipher.encrypt("wrong-password"))
        val service = syncService(db)

        assertEquals(0, service.syncAccount(accountId))

        assertEquals(
            "邮箱服务器拒绝登录，请检查授权码或密码",
            accountRow(db, accountId).str("last_error"),
        )
        assertEquals("error", service.getStatus()["$accountId"]?.state)
    }

    /** OAuth 账户：本机模式 P0 还没有令牌刷新，`last_error` 直接说明不支持。 */
    @Test
    fun oauthAccountIsReportedAsUnsupported() = withTempDb(clock) { db ->
        db.initDb()
        val accountId = insertAccount(db, authType = "oauth")

        assertEquals(0, syncService(db).syncAccount(accountId))

        assertEquals(OAUTH_UNSUPPORTED_TEXT, accountRow(db, accountId).str("last_error"))
    }

    // ---------------- 入库的列 ----------------

    /** 入库时分类、风险、`template_key`、`flag_push` 等列都要写对。 */
    @Test
    fun insertRowCarriesClassificationAndRiskColumns() = withTempDb(clock) { db ->
        db.initDb()
        val accountId = insertAccount(db)
        fixture.deliver(
            subject = "验证码 123456",
            body = "您的验证码是 123456，5 分钟内有效。",
            messageId = "<m1@example.org>",
            from = "notice@bank.example",
        )

        assertEquals(1, syncService(db).syncAccount(accountId))

        val row = db.queryOne("SELECT * FROM messages")!!
        assertEquals("<m1@example.org>", row.str("msg_id"))
        assertEquals("验证码", row.str("category"))
        assertEquals("123456", row.str("otp_code"))
        assertEquals("notice@bank.example", row.str("sender_addr"))
        assertTrue("风险等级应写进库里，实际 ${row.str("risk_level")}", (row.str("risk_level") ?: "").isNotEmpty())
        val expectedKey = Billing.templateKey("notice@bank.example", "验证码 123456")
        assertEquals("template_key 应与 billing.template_key 一致", expectedKey, row.str("template_key"))
        assertTrue("template_key 应带上发件域名，实际 $expectedKey", expectedKey.startsWith("bank.example|"))
        assertEquals("新邮件默认没有待推送动作", 0L, row.long("flag_push"))
        assertEquals("整封抓取不该标记截断", 0L, row.long("body_truncated"))
        assertEquals("服务器上的未读状态要写进来", 1L, row.long("unread"))
        assertEquals("规则结论不需要人工复核", 0L, row.long("needs_review"))
    }

    // ---------------- 夹具与辅助 ----------------

    /** 建一个指向夹具的 [SyncService]，会话走信任自签名证书的传输设置。 */
    private fun syncService(db: Db, at: FakeClock = clock): SyncService = SyncService(
        db = db,
        cipher = cipher,
        clock = at,
        sessionFactory = { account, secret ->
            openSession(account.toImapAccount(), secret, fixture.transport(), at)
        },
    )

    /** 直接连夹具的会话，用来检查/修改服务器端的状态。 */
    private fun openClient(): ImapSession =
        openSession(fixture.account(), fixture.password, fixture.transport(), clock)

    /** 服务器上某个 UID 的标记集合。 */
    private fun serverFlags(uid: Long): Set<String> = openClient().use { session ->
        session.selectInbox()
        session.fetchFlags(listOf(uid))[uid] ?: emptySet()
    }

    /** 服务器收件箱里现存的 UID。 */
    private fun serverUids(): List<Long> = openClient().use { session ->
        session.selectInbox()
        session.searchUids(lastUid = 0)
    }

    /** 插入一个账户，默认指向夹具。 */
    private fun insertAccount(
        db: Db,
        name: String = "测试账户",
        email: String = fixture.email,
        host: String = "127.0.0.1",
        port: Int = fixture.port,
        authType: String = "password",
        secretEnc: String = cipher.encrypt(fixture.password),
        provider: String = "custom",
        lastUid: Long = 0,
        uidValidity: Long = 0,
        pollInterval: Long = 300,
        enabled: Int = 1,
    ): Int = Math.toIntExact(
        db.execute(
            """INSERT INTO accounts
               (name, provider, email, imap_host, imap_port, auth_type, secret_enc,
                enabled, poll_interval, last_uid, uidvalidity, last_sync, created_ts)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            name,
            provider,
            email,
            host,
            port,
            authType,
            secretEnc,
            enabled,
            pollInterval,
            lastUid,
            uidValidity,
            0.0,
            clock.now(),
        ).lastInsertRowid,
    )

    /** 直接插一条邮件记录（用来造「连不上的账户」与「已解绑的旧记录」）。 */
    private fun insertMessage(db: Db, accountId: Int, uid: Long, msgId: String): Long = db.execute(
        """INSERT INTO messages(account_id, uid, msg_id, subject, date_ts, created_ts)
           VALUES (?,?,?,?,?,?)""",
        accountId,
        uid,
        msgId,
        "合成邮件",
        clock.now(),
        clock.now(),
    ).lastInsertRowid

    private fun accountRow(db: Db, accountId: Int) = requireNotNull(
        db.queryOne("SELECT * FROM accounts WHERE id=?", accountId),
    ) { "账户 $accountId 应该存在" }

    private fun countMessages(db: Db, accountId: Int): Long =
        db.queryOne("SELECT COUNT(*) AS c FROM messages WHERE account_id=?", accountId)!!.long("c") ?: 0L

    private fun countAllMessages(db: Db): Long =
        db.queryOne("SELECT COUNT(*) AS c FROM messages")!!.long("c") ?: 0L
}
