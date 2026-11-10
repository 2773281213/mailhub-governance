package com.mailhub.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

/**
 * 打包 SQLite 的冒烟测试：证明这台机器上（以及将来的手机和 CI 上）
 * 用的是同一份 SQLite，并且它的版本、WAL 和 FTS5 trigram 分词器都能用。
 *
 * 用临时目录里的真实库文件而不是内存库，因为 WAL 是文件级特性。
 */
class SqliteSmokeTest {
    /** sqliteVersion 的结果按数字逐段比较，不能按字符串比。 */
    @Test
    fun sqliteVersionIsAtLeast3340() {
        withTempDatabase { conn ->
            val version = Sqlite.sqliteVersion(conn)
            assertTrue("sqliteVersion 返回了空字符串", version.isNotBlank())
            assertTrue(
                "打包 SQLite 版本 $version 低于 3.34.0（trigram 分词器要求 3.34.0+）",
                versionAtLeast(version, 3, 34, 0),
            )
        }
    }

    /** db.py 的 get_conn() 设了 journal_mode=WAL，这里验证它真的生效。 */
    @Test
    fun journalModeIsWal() {
        withTempDatabase { conn ->
            assertEquals("wal", queryText(conn, "PRAGMA journal_mode"))
        }
    }

    /** 另外两条 PRAGMA 也要对得上 db.py 的 get_conn()。 */
    @Test
    fun otherPragmasMatchDbPy() {
        withTempDatabase { conn ->
            // synchronous=NORMAL 在 SQLite 里的编码是 1。
            assertEquals("1", queryText(conn, "PRAGMA synchronous"))
            // foreign_keys=ON 在 SQLite 里的编码是 1。
            assertEquals("1", queryText(conn, "PRAGMA foreign_keys"))
        }
    }

    /**
     * FTS5 trigram：中文按三元组做子串匹配，英文默认折叠 ASCII 大小写。
     * 建表语句的 `tokenize` 参数照抄 db.py 里 messages_fts 的写法。
     */
    @Test
    fun fts5TrigramMatchesChineseAndIsCaseInsensitive() {
        withTempDatabase { conn ->
            exec(
                conn,
                """
                CREATE VIRTUAL TABLE messages_fts USING fts5(
                    subject, sender_name, sender_addr, snippet, tokenize='trigram'
                )
                """.trimIndent(),
            )
            insertFts(
                conn,
                subject = "您的验证码 552910",
                senderName = "银行通知",
                senderAddr = "notify@bank.example",
                snippet = "您的验证码为 552910，10 分钟内有效",
            )
            insertFts(
                conn,
                subject = "Invoice Reminder",
                senderName = "Billing Center",
                senderAddr = "billing@shop.example",
                snippet = "Invoice number 202611 is ready",
            )

            // 中文三元组：只命中含「验证码」的那一行。
            assertEquals(
                "MATCH '验证码' 应只命中中文那一行",
                1L,
                countMatches(conn, "验证码"),
            )
            // 英文不小于 3 个字符的词，大小写不同也能命中。
            assertEquals("MATCH 'invoice' 应命中英文那一行", 1L, countMatches(conn, "invoice"))
            assertEquals(
                "trigram 默认折叠 ASCII 大小写，MATCH 'InVoIcE' 也应命中",
                1L,
                countMatches(conn, "InVoIcE"),
            )
            assertEquals("MATCH '账单' 不应命中这两行", 0L, countMatches(conn, "账单"))
        }
    }

    private fun withTempDatabase(block: (SQLiteConnection) -> Unit) {
        val dir: Path = Files.createTempDirectory("mailhub-core-sqlite")
        val conn = Sqlite.openDatabase(dir.resolve("mailhub.db").toString())
        try {
            block(conn)
        } finally {
            conn.close()
            dir.resolve("mailhub.db-wal").deleteIfExists()
            dir.resolve("mailhub.db-shm").deleteIfExists()
            dir.resolve("mailhub.db").deleteIfExists()
            dir.deleteIfExists()
        }
    }

    private fun insertFts(
        conn: SQLiteConnection,
        subject: String,
        senderName: String,
        senderAddr: String,
        snippet: String,
    ) {
        val stmt = conn.prepare(
            "INSERT INTO messages_fts(subject, sender_name, sender_addr, snippet) VALUES(?, ?, ?, ?)",
        )
        try {
            stmt.bindText(1, subject)
            stmt.bindText(2, senderName)
            stmt.bindText(3, senderAddr)
            stmt.bindText(4, snippet)
            stmt.step()
        } finally {
            stmt.close()
        }
    }

    private fun countMatches(conn: SQLiteConnection, keyword: String): Long {
        val stmt = conn.prepare("SELECT count(*) FROM messages_fts WHERE messages_fts MATCH ?")
        try {
            stmt.bindText(1, keyword)
            check(stmt.step()) { "SELECT count(*) 没有返回行" }
            return stmt.getLong(0)
        } finally {
            stmt.close()
        }
    }

    private fun queryText(conn: SQLiteConnection, sql: String): String {
        val stmt: SQLiteStatement = conn.prepare(sql)
        try {
            check(stmt.step()) { "$sql 没有返回行" }
            return stmt.getText(0)
        } finally {
            stmt.close()
        }
    }

    private fun exec(conn: SQLiteConnection, sql: String) {
        val stmt = conn.prepare(sql)
        try {
            stmt.step()
        } finally {
            stmt.close()
        }
    }

    /** 把 "3.51.1" 这类版本号按数字逐段比较，忽略可能的后缀。 */
    private fun versionAtLeast(version: String, vararg minimum: Int): Boolean {
        val parts = version.split(".").map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        for (index in minimum.indices) {
            val actual = parts.getOrElse(index) { 0 }
            if (actual != minimum[index]) {
                return actual > minimum[index]
            }
        }
        return true
    }
}
