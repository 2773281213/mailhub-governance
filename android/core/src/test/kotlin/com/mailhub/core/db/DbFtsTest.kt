package com.mailhub.core.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * FTS5 三元组索引的初始化：原子性、回填时机、可用性缓存与库的一致性。
 *
 * 审核意见里的四条都在这里：`createFts` 任何一步失败都要整段回滚；`messages` 表还不存在时
 * 不能把「不可用」缓存下来；外层事务回滚后缓存要回到进入事务前的值；已有邮件时首次建表要
 * `rebuild` 回填，重开数据库时不重复回填。
 */
class DbFtsTest {

    /** 首次建 FTS 时库里已有邮件：必须 `rebuild` 回填，否则搜索永远查不到历史邮件。 */
    @Test
    fun firstInitBackfillsExistingMessages() = withTempDb { db ->
        // 只建表、不建 FTS：模拟「先写邮件，再第一次 initDb()」的升级路径。
        db.executeScript(DbSchema.SCHEMA)
        val accountId = db.execute(
            "INSERT INTO accounts(name, provider, email, imap_host) VALUES(?,?,?,?)",
            "本机账户",
            "custom",
            "demo@example.com",
            "imap.example.com",
        ).lastInsertRowid
        db.execute(
            "INSERT INTO messages(account_id, uid, subject, sender_name, sender_addr, snippet) VALUES(?,?,?,?,?,?)",
            accountId,
            1L,
            "您的验证码 246810",
            "银行通知",
            "notify@bank.example",
            "请在 10 分钟内使用",
        )
        assertEquals("建 FTS 之前没有虚表", 0L, masterCount(db, "messages_fts"))

        db.initDb()

        assertTrue("initDb 之后 FTS 可用", db.ftsSearchAvailable())
        assertEquals("已有邮件要被回填进索引", 1L, matchCount(db, "验证码"))
    }

    /**
     * 重开数据库（FTS 表已存在）时不重复 `rebuild`。
     *
     * 判断方法：先把索引清空（`delete-all` 是 FTS5 的清库命令，不动 `messages` 表），
     * 再 `initDb()`；如果又 `rebuild` 了，索引会被重新填满，命中数就不是 0。
     */
    @Test
    fun reopeningDoesNotRebuildFts() {
        val dir: Path = Files.createTempDirectory("mailhub-core-db")
        val path = dir.resolve("mailhub.db").toString()
        try {
            val first = Db(path)
            try {
                first.initDb()
                val accountId = first.execute(
                    "INSERT INTO accounts(name, provider, email, imap_host) VALUES(?,?,?,?)",
                    "本机账户",
                    "custom",
                    "demo@example.com",
                    "imap.example.com",
                ).lastInsertRowid
                first.execute(
                    "INSERT INTO messages(account_id, uid, subject) VALUES(?,?,?)",
                    accountId,
                    1L,
                    "您的验证码 246810",
                )
                assertEquals("触发器应当把新邮件写进索引", 1L, matchCount(first, "验证码"))
            } finally {
                first.close()
            }

            val second = Db(path)
            try {
                assertEquals("重开之后索引还在", 1L, matchCount(second, "验证码"))
                second.execute("INSERT INTO messages_fts(messages_fts) VALUES('delete-all')")
                assertEquals("索引已被清空", 0L, matchCount(second, "验证码"))

                second.initDb()

                assertEquals("重开数据库不应重复 rebuild", 0L, matchCount(second, "验证码"))
            } finally {
                second.close()
            }
        } finally {
            deleteDatabaseFiles(dir)
        }
    }

    /**
     * 外层事务回滚后，FTS 缓存要恢复成进入事务前的值（这里恢复成「没探测过」），否则缓存说
     * 「可用」而库里根本没有表。
     */
    @Test
    fun ftsCacheIsRestoredWhenTransactionRollsBack() = withTempDb { db ->
        // 只建表、不建 FTS：让 FTS 的第一次创建发生在事务里。
        db.executeScript(DbSchema.SCHEMA)
        assertEquals("还没建 FTS", 0L, masterCount(db, "messages_fts"))

        try {
            db.transaction {
                assertTrue("事务里建 FTS", db.ftsSearchAvailable())
                assertEquals("事务里虚表已建好", 1L, masterCount(db, "messages_fts"))
                throw IllegalStateException("回滚")
            }
            fail("异常应当抛出")
        } catch (expected: IllegalStateException) {
            assertEquals("回滚", expected.message)
        }

        assertEquals("回滚把虚表一起撤销了", 0L, masterCount(db, "messages_fts"))
        assertTrue("缓存也回到未探测，重新探测应当成功", db.ftsSearchAvailable())
        assertEquals("重新探测时把虚表建回来", 1L, masterCount(db, "messages_fts"))
    }

    /**
     * `messages` 表还不存在时 `ftsSearchAvailable()` 返回 false，但**不缓存**：
     * 建好表之后再问必须是 true，而且探测失败不能留下半截虚表。
     */
    @Test
    fun availabilityIsNotCachedBeforeMessagesTableExists() = withTempDb { db ->
        assertFalse("空库还没有 messages 表", db.ftsSearchAvailable())
        assertFalse(db.ftsSearchAvailable())
        assertEquals("探测失败不能留下半截虚表", 0L, masterCount(db, "messages_fts"))

        db.initDb()

        assertTrue(
            "建好表之后 FTS 必须可用（说明前面没把 false 缓存下来）",
            db.ftsSearchAvailable(),
        )
        assertEquals("虚表只建一张", 1L, masterCount(db, "messages_fts"))
        assertEquals(
            "三个同步触发器都要在",
            3L,
            countOf(db, "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'messages_fts_%'"),
        )
    }

    /**
     * `createFts` 整段是原子的：中间某条语句失败时，前面建好的虚表也不能留下。
     *
     * 这里把 `messages.snippet` 删掉：虚表与三个触发器都能建出来（SQLite 建触发器时不校验
     * `new.列名`），但首次建表后的 `rebuild` 会因为缺列失败。整段必须回滚干净。
     */
    @Test
    fun failedFtsCreationRollsBackEverything() = withTempDb { db ->
        db.executeScript(DbSchema.SCHEMA)
        db.execute("ALTER TABLE messages DROP COLUMN snippet")

        db.initDb()

        assertFalse("FTS 建不起来", db.ftsSearchAvailable())
        assertEquals(
            "半截虚表（含 FTS5 的内部表）要回滚掉",
            0L,
            countOf(db, "SELECT count(*) FROM sqlite_master WHERE name LIKE 'messages_fts%'"),
        )
        assertEquals(
            "触发器也不该留下",
            0L,
            countOf(db, "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'messages_fts_%'"),
        )
    }

    // ---------- 辅助 ----------

    /** `sqlite_master` 里叫 [name] 的条目数（虚表、普通表、索引、触发器都算）。 */
    private fun masterCount(db: Db, name: String): Long =
        countOf(db, "SELECT count(*) FROM sqlite_master WHERE name=?", name)

    private fun countOf(db: Db, sql: String, vararg args: Any?): Long =
        db.queryOne(sql, *args)?.long(0) ?: 0L

    private fun matchCount(db: Db, keyword: String): Long =
        countOf(db, "SELECT count(*) FROM messages_fts WHERE messages_fts MATCH ?", keyword)
}
