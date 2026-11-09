package com.mailhub.core.db

import androidx.sqlite.SQLiteException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * `Db` 的行为测试：建表幂等、FTS 触发器、事务回滚与嵌套、多线程读写、
 * 审计的截断与 JSON 往返、设置读写。
 *
 * 表结构本身由 [DbSchemaGoldenTest] 对着 Python 金标比较，这里只测行为。
 */
class DbTest {

    // ---------- 建表 ----------

    /** 重复执行 initDb() 不报错、不重复建 FTS、不丢数据。 */
    @Test
    fun initDbIsIdempotent() = withTempDb { db ->
        db.initDb()
        val accountId = insertAccount(db, "本机账户")
        insertMessage(db, accountId, uid = 1, subject = "您的验证码 246810")
        assertEquals(1L, count(db, "SELECT count(*) FROM messages"))

        db.initDb()
        db.initDb()

        assertEquals("重复建表不应丢数据", 1L, count(db, "SELECT count(*) FROM messages"))
        assertEquals(
            "重复建表不应多建 FTS 表",
            1L,
            count(db, "SELECT count(*) FROM sqlite_master WHERE name='messages_fts'"),
        )
        assertEquals(
            "重复建表不应多建 FTS 触发器",
            3L,
            count(db, "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'messages_fts_%'"),
        )
        assertEquals("FTS 索引应当仍然命中", 1L, matchCount(db, "验证码"))
    }

    /** 对应 db.py 的 fts_search_available()：打包的 SQLite 有 trigram，结果应为 true。 */
    @Test
    fun ftsSearchIsAvailable() = withTempDb { db ->
        db.initDb()
        assertTrue("打包 SQLite 应支持 FTS5 trigram", db.ftsSearchAvailable())
        // 缓存之后再问一次，结论不变。
        assertTrue(db.ftsSearchAvailable())
    }

    // ---------- FTS 触发器 ----------

    /** 插入、改 subject、删除之后，messages_fts MATCH 的结果都要跟着变。 */
    @Test
    fun ftsTriggersFollowMessageChanges() = withTempDb { db ->
        db.initDb()
        val accountId = insertAccount(db, "本机账户")
        val otpId = insertMessage(
            db,
            accountId,
            uid = 1,
            subject = "您的验证码 246810",
            senderName = "银行通知",
            senderAddr = "notify@bank.example",
            // 摘要里故意不再出现「验证码」，这样改 subject 之后能看出索引里旧值被删掉了。
            snippet = "请在 10 分钟内使用",
        )
        insertMessage(
            db,
            accountId,
            uid = 2,
            subject = "Invoice Reminder",
            senderName = "Billing Center",
            senderAddr = "billing@shop.example",
            snippet = "Invoice number 202611 is ready",
        )

        // 中文三字词与英文大小写。
        assertEquals("中文三元组应命中验证码那封", 1L, matchCount(db, "验证码"))
        assertEquals("英文小写应命中", 1L, matchCount(db, "invoice"))
        assertEquals("trigram 折叠 ASCII 大小写", 1L, matchCount(db, "InVoIcE"))
        assertEquals("不该命中无关词", 0L, matchCount(db, "账单"))

        // 改 subject：触发器 messages_fts_au 要先把旧值删掉再写新值。
        db.execute("UPDATE messages SET subject=? WHERE id=?", "改过的主题 gamma", otpId)
        assertEquals("旧主题不再命中", 0L, matchCount(db, "验证码"))
        assertEquals("新主题应命中", 1L, matchCount(db, "gamma"))

        // 更新不在索引里的列不会动索引。
        db.execute("UPDATE messages SET unread=0 WHERE id=?", otpId)
        assertEquals("改已读状态不应影响 FTS", 1L, matchCount(db, "gamma"))

        // 删除：触发器 messages_fts_ad 要把索引里的那一行删掉。
        db.execute("DELETE FROM messages WHERE id=?", otpId)
        assertEquals("删除后不应再命中", 0L, matchCount(db, "gamma"))
        assertEquals("另一封仍在", 1L, matchCount(db, "invoice"))
    }

    // ---------- 事务 ----------

    /** 抛异常时整个事务回滚，一条都不落盘，异常原样抛出。 */
    @Test
    fun transactionRollsBackOnException() = withTempDb { db ->
        db.initDb()
        try {
            db.transaction {
                db.setSetting("a", "1")
                db.setSetting("b", "2")
                throw IllegalStateException("故意失败")
            }
            fail("事务里的异常应当原样抛出")
        } catch (e: IllegalStateException) {
            assertEquals("故意失败", e.message)
        }
        assertNull("回滚后不应留下 a", db.queryOne("SELECT 1 FROM settings WHERE k='a'"))
        assertNull("回滚后不应留下 b", db.queryOne("SELECT 1 FROM settings WHERE k='b'"))
    }

    /** 嵌套事务并入外层：外层回滚时内层的写入一起回滚。 */
    @Test
    fun nestedTransactionRollsBackWithOuter() = withTempDb { db ->
        db.initDb()
        // 全部成功：内层与外层一起提交。
        db.transaction {
            db.setSetting("outer", "1")
            db.transaction { db.setSetting("inner", "1") }
        }
        assertEquals("1", db.getSetting("outer"))
        assertEquals("1", db.getSetting("inner"))

        // 外层失败：内层已经「提交」过的写入也要回滚。
        try {
            db.transaction {
                db.setSetting("outer2", "1")
                db.transaction { db.setSetting("inner2", "1") }
                throw IllegalStateException("外层失败")
            }
            fail("外层异常应当抛出")
        } catch (expected: IllegalStateException) {
            assertEquals("外层失败", expected.message)
        }
        assertNull("内层写入应随外层回滚", db.queryOne("SELECT 1 FROM settings WHERE k='inner2'"))
        assertNull("外层写入应回滚", db.queryOne("SELECT 1 FROM settings WHERE k='outer2'"))
        assertEquals("之前提交的数据不受影响", "1", db.getSetting("inner"))
    }

    /** audit() 在事务里时随事务回滚（对应服务端「多条写入 + 一次 commit」的语义）。 */
    @Test
    fun auditInsideTransactionRollsBack() = withTempDb { db ->
        db.initDb()
        try {
            db.transaction {
                db.audit("soft_delete", actor = "user", targetIds = listOf(1L))
                throw IllegalStateException("失败")
            }
            fail("异常应当抛出")
        } catch (expected: IllegalStateException) {
            assertEquals("失败", expected.message)
        }
        assertEquals(0L, count(db, "SELECT count(*) FROM audit_log"))
    }

    /** 只有「列已存在」被忽略：其它 SQL 错误必须立刻抛出来，不能悄悄吞掉。 */
    @Test
    fun sqlErrorsAreNotSwallowed() = withTempDb { db ->
        db.initDb()
        try {
            // 重复补列：initDb() 会忽略这个错，但直接执行时要抛（对应 Python 的
            // sqlite3.OperationalError: duplicate column name）。
            db.execute("ALTER TABLE messages ADD COLUMN to_addr TEXT DEFAULT ''")
            fail("重复补列应当抛 SQLiteException")
        } catch (expected: SQLiteException) {
            assertTrue(
                "错误信息应包含 duplicate column name：${expected.message}",
                expected.message!!.lowercase().contains("duplicate column name"),
            )
        }
        try {
            db.execute("ALTER TABLE 没有这张表 ADD COLUMN x TEXT")
            fail("表不存在应当抛 SQLiteException")
        } catch (expected: SQLiteException) {
            assertTrue("不是重复列错误", !expected.message!!.lowercase().contains("duplicate column name"))
        }
    }

    // ---------- 多线程 ----------

    /** 8 个线程各写 200 条，同时有人读：不报错、不丢数据。 */
    @Test
    fun concurrentReadsAndWritesAreSafe() {
        val threadCount = 8
        val perThread = 200
        withTempDb { db ->
            db.initDb()
            val start = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            val workers = (0 until threadCount).map { worker ->
                thread(name = "db-writer-$worker") {
                    try {
                        start.await()
                        repeat(perThread) { index ->
                            val key = "row_w${worker}_$index"
                            db.execute("INSERT INTO settings(k, v) VALUES(?,?)", key, "v$index")
                            if (index % 20 == 0) {
                                // 读写交错，验证同一连接上的排队。
                                db.query("SELECT count(*) FROM settings WHERE k LIKE 'row%'")
                                db.setSetting("worker_$worker", index.toString())
                            }
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }
            }
            start.countDown()
            workers.forEach { it.join(TimeUnit.MINUTES.toMillis(2)) }
            failure.get()?.let { throw AssertionError("并发写入失败：${it.message}", it) }

            assertEquals(
                "并发写入不应丢数据",
                (threadCount * perThread).toLong(),
                count(db, "SELECT count(*) FROM settings WHERE k LIKE 'row%'"),
            )
            val lastTick = (perThread - 1) / 20 * 20
            for (worker in 0 until threadCount) {
                assertEquals(
                    "每个线程的最后一次写入都要在",
                    lastTick.toString(),
                    db.getSetting("worker_$worker"),
                )
            }
        }
    }

    // ---------- 审计 ----------

    /** reason 按码点截到 500；target_ids 只存前 2000 个；JSON 文本解析回来要相等。 */
    @Test
    fun auditTruncatesAndRoundTripsJson() {
        val clock = FakeClock(1_700_000_000.5)
        withTempDb(clock) { db ->
            db.initDb()
            // 第一个码点是 emoji（UTF-16 里占两个 char），后面再补 600 个汉字：共 601 个码点。
            val reason = "\uD83C\uDF0F" + "原".repeat(600)
            val auditId = db.audit(
                action = "soft_delete",
                actor = "user",
                tier = "A",
                targetIds = (1L..2050L).toList(),
                reason = reason,
                allowed = false,
                reversible = true,
                undoPayload = mapOf("ids" to listOf(1L, 2L), "unread" to mapOf("1" to 1L)),
            )

            val row = db.queryOne("SELECT * FROM audit_log WHERE id=?", auditId)
                ?: throw AssertionError("审计记录没写进去")
            assertEquals("时间应来自 Clock", 1_700_000_000.5, row.double("ts")!!, 0.0)
            assertEquals("user", row.str("actor"))
            assertEquals("soft_delete", row.str("action"))
            assertEquals("A", row.str("tier"))
            assertEquals(0L, row.long("allowed"))
            assertEquals(1L, row.long("reversible"))
            assertEquals(0L, row.long("undone"))
            assertEquals("target_count 记原始条数", 2050L, row.long("target_count"))

            val storedReason = row.str("reason")!!
            assertEquals(
                "reason 按码点截到 500",
                500,
                storedReason.codePointCount(0, storedReason.length),
            )
            assertEquals("\uD83C\uDF0F" + "原".repeat(499), storedReason)

            val ids = Json.parseToJsonElement(row.str("target_ids")!!).jsonArray
                .map { it.jsonPrimitive.content.toLong() }
            assertEquals("target_ids 只存前 2000 个", 2000, ids.size)
            assertEquals(1L, ids.first())
            assertEquals(2000L, ids.last())

            val undo = Json.parseToJsonElement(row.str("undo_payload")!!)
            assertEquals(
                "undo_payload 解析回来要与写入的值相同",
                Json.parseToJsonElement("""{"ids":[1,2],"unread":{"1":1}}"""),
                undo,
            )
        }
    }

    /** 不传 undo_payload 时按 Python 语义存空串（不是 JSON 的 null）。 */
    @Test
    fun auditWithoutUndoPayloadStoresEmptyString() = withTempDb { db ->
        db.initDb()
        val id = db.audit("read", actor = "rule", targetIds = listOf(5L), reason = "测试")
        val row = db.queryOne("SELECT * FROM audit_log WHERE id=?", id)!!
        assertEquals("", row.str("undo_payload"))
        assertEquals("[5]", row.str("target_ids"))
        assertEquals(1L, row.long("target_count"))
    }

    // ---------- 设置 ----------

    /** getSetting 的默认值、setSetting 的覆盖。 */
    @Test
    fun settingDefaultAndOverride() = withTempDb { db ->
        db.initDb()
        assertEquals("缺省值是空串", "", db.getSetting("missing"))
        assertEquals("可以给别的默认值", "8", db.getSetting("digest_hour", "8"))

        db.setSetting("digest_hour", "9")
        assertEquals("9", db.getSetting("digest_hour"))
        db.setSetting("digest_hour", "10")
        assertEquals("覆盖写只留一条", 1L, count(db, "SELECT count(*) FROM settings WHERE k='digest_hour'"))
        assertEquals("10", db.getSetting("digest_hour"))
    }

    /**
     * 凭据列的存取：`Db` 只存密文文本，加解密交给注入的 `com.mailhub.core.SecretCipher`。
     * 这里用 [FakeSecretCipher] 走一遍，证明设置读写能装下凭据（L10 换成 Keystore 实现）。
     */
    @Test
    fun credentialsRoundTripThroughSecretCipher() = withTempDb { db ->
        db.initDb()
        val cipher = FakeSecretCipher()
        val plain = "示例授权码-0123456789"
        val encrypted = cipher.encrypt(plain)
        assertTrue("假实现也要真的改过内容", encrypted != plain)
        db.setSetting("bark_url_enc", encrypted)
        assertEquals(plain, cipher.decrypt(db.getSetting("bark_url_enc")))
        assertEquals("空串进空串出", "", cipher.encrypt(""))
        assertEquals("解不开返回空串", "", cipher.decrypt("不是我们的格式"))
    }

    // ---------- execute 的返回值 ----------

    /**
     * `changes` 与 `lastInsertRowid`：插入一行是 1 与自增 id；
     * `INSERT OR IGNORE` 被忽略时两者都是 0（不能返回上一次插入留下的 rowid）。
     */
    @Test
    fun insertOrIgnoreReportsZeroRowidWhenIgnored() = withTempDb { db ->
        db.initDb()
        val inserted = db.execute("INSERT INTO digests(day, content) VALUES(?, ?)", "2026-11-07", "内容")
        assertEquals("插入一行", 1L, inserted.changes)
        assertTrue("自增 id 应当大于 0：${inserted.lastInsertRowid}", inserted.lastInsertRowid > 0)

        db.setSetting("k", "旧值")
        // 先插一条审计，让 last_insert_rowid() 指向别的表，专门验「不能串到别的行」。
        val auditId = db.audit("read")
        assertTrue("审计行有自增 id", auditId > 0)

        val ignored = db.execute("INSERT OR IGNORE INTO settings(k, v) VALUES(?, ?)", "k", "新值")
        assertEquals("被忽略的行不计入 changes", 0L, ignored.changes)
        assertEquals("不能返回别的表上一次插入的 rowid", 0L, ignored.lastInsertRowid)
        assertEquals("旧值不变", "旧值", db.getSetting("k"))
    }

    /** `executeMany` 对应 `executemany`：返回总影响行数，不在事务里时自动包一个事务。 */
    @Test
    fun executeManyReturnsTotalChangesAndWrapsInTransaction() = withTempDb { db ->
        db.initDb()
        val total = db.executeMany(
            "INSERT INTO settings(k, v) VALUES(?, ?)",
            (1..5).map { arrayOf<Any?>("k$it", "v$it") },
        )
        assertEquals("5 行都写进去", 5L, total)
        assertEquals(5L, count(db, "SELECT count(*) FROM settings"))

        val ignored = db.executeMany(
            "INSERT INTO settings(k, v) VALUES(?, ?) ON CONFLICT(k) DO NOTHING",
            (1..5).map { arrayOf<Any?>("k$it", "覆盖") },
        )
        assertEquals("全部被忽略时是 0", 0L, ignored)

        // 中途失败整批回滚（自动包的事务），不留下前几行。
        try {
            db.executeMany(
                "INSERT INTO settings(k, v) VALUES(?, ?)",
                listOf(arrayOf<Any?>("dup", "1"), arrayOf<Any?>("dup", "2")),
            )
            fail("主键冲突应当抛出")
        } catch (expected: SQLiteException) {
            assertNull("整批回滚，不留半截", db.queryOne("SELECT 1 FROM settings WHERE k='dup'"))
        }

        // 已经在事务里时不再单开事务：外层回滚会一起撤销。
        try {
            db.transaction {
                db.executeMany(
                    "INSERT INTO settings(k, v) VALUES(?, ?)",
                    listOf(arrayOf<Any?>("batch", "1")),
                )
                throw IllegalStateException("外层失败")
            }
        } catch (expected: IllegalStateException) {
            assertEquals("外层失败", expected.message)
        }
        assertNull("外层回滚带走 executeMany 的写入", db.queryOne("SELECT 1 FROM settings WHERE k='batch'"))
    }

    // ---------- OAuth 表结构 ----------

    /**
     * `initDb()` 也要建出 `oauth_auth.init_oauth_db()` 的东西：`oauth_transactions` 表与
     * `accounts` 的三列。`app.py` 的账户 SQL 依赖这几列，缺了会整片查询失败。
     */
    @Test
    fun initDbCreatesOAuthSchema() = withTempDb { db ->
        db.initDb()
        assertEquals(
            "oauth_transactions 表要在",
            1L,
            count(db, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='oauth_transactions'"),
        )
        assertEquals(
            "两个索引也要在",
            2L,
            count(db, "SELECT count(*) FROM sqlite_master WHERE type='index' AND name LIKE 'idx_oauth_%'"),
        )
        val columns = db.query("PRAGMA table_info(accounts)").mapNotNull { it.str("name") }
        for (column in listOf("oauth_client_id", "oauth_scope", "oauth_reauth_required")) {
            assertTrue("accounts 要有 $column 列，现在只有 $columns", column in columns)
        }

        // 建出来的表能直接用（列名与 init_oauth_db() 一致）。
        db.execute(
            "INSERT INTO oauth_transactions(id, provider, flow, email, expires_ts, created_ts) VALUES(?,?,?,?,?,?)",
            "tx-1",
            "outlook",
            "browser",
            "demo@example.com",
            0.0,
            0.0,
        )
        assertEquals(
            "默认值要按 init_oauth_db() 的建表语句",
            "pending",
            db.queryOne("SELECT status FROM oauth_transactions WHERE id='tx-1'")?.str("status"),
        )

        // 幂等：重复 initDb() 不再报错、不多建。
        db.initDb()
        assertEquals(
            "重复 initDb 不多建表",
            1L,
            count(db, "SELECT count(*) FROM sqlite_master WHERE name='oauth_transactions'"),
        )
    }

    // ---------- 设置（NULL 值） ----------

    /** settings 的值是 NULL 时，`getSetting` 返回默认值（`db.py` 会把 NULL 原样返回）。 */
    @Test
    fun getSettingFallsBackWhenValueIsNull() = withTempDb { db ->
        db.initDb()
        db.execute("INSERT INTO settings(k, v) VALUES(?, NULL)", "nulled")
        assertEquals("给默认值时用默认值", "默认值", db.getSetting("nulled", "默认值"))
        assertEquals("默认值缺省是空串", "", db.getSetting("nulled"))
    }

    // ---------- 辅助 ----------

    private fun insertAccount(db: Db, name: String): Long = db.execute(
        "INSERT INTO accounts(name, provider, email, imap_host) VALUES(?,?,?,?)",
        name,
        "custom",
        "demo@example.com",
        "imap.example.com",
    ).lastInsertRowid

    private fun insertMessage(
        db: Db,
        accountId: Long,
        uid: Long,
        subject: String,
        senderName: String = "示例发件人",
        senderAddr: String = "demo@example.com",
        snippet: String = "示例摘要",
    ): Long = db.execute(
        "INSERT INTO messages(account_id, uid, subject, sender_name, sender_addr, snippet, date_ts, created_ts) " +
            "VALUES(?,?,?,?,?,?,?,?)",
        accountId,
        uid,
        subject,
        senderName,
        senderAddr,
        snippet,
        1_700_000_000.0,
        1_700_000_000.0,
    ).lastInsertRowid

    /** 只取第一列第一行的整数，用于 `SELECT count(*)`。 */
    private fun count(db: Db, sql: String, vararg args: Any?): Long =
        db.queryOne(sql, *args)?.long(0) ?: 0L

    private fun matchCount(db: Db, keyword: String): Long =
        count(db, "SELECT count(*) FROM messages_fts WHERE messages_fts MATCH ?", keyword)
}
