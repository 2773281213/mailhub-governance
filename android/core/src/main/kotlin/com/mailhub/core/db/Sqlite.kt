package com.mailhub.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * 打包 SQLite 的最小封装（L1 只放打开连接和版本查询，schema 与迁移在 L6）。
 *
 * 用 `androidx.sqlite` 的打包驱动而不是 Android 自带的 SQLite：Android 8–13 自带的
 * SQLite 低于 3.34，没有 FTS5 的 trigram 分词器，而本机模式的邮件搜索依赖它。
 * 打包驱动在手机和 JVM 测试里用的是同一份 SQLite。
 */
object Sqlite {
    /**
     * 打开（或创建）[path] 指向的库文件，并设置 [com.mailhub.core.db.Sqlite] 所需的 PRAGMA。
     *
     * PRAGMA 逐条对应 Python 端 `db.py` 的 `get_conn()`：
     * - `PRAGMA journal_mode=WAL` → `conn.execute("PRAGMA journal_mode=WAL")`
     * - `PRAGMA synchronous=NORMAL` → `conn.execute("PRAGMA synchronous=NORMAL")`
     * - `PRAGMA foreign_keys=ON` → `conn.execute("PRAGMA foreign_keys=ON")`
     *
     * `db.py` 的 `get_conn()` 只设了这三条，所以这里也只设这三条，不多不少。
     * `sqlite3.connect(..., timeout=30)` 的忙等待超时和 `row_factory` 是 Python 驱动的参数，
     * 在 `androidx.sqlite` 里没有对应项，不在这里模拟。
     *
     * 调用方负责关闭返回的连接。
     */
    fun openDatabase(path: String): SQLiteConnection {
        val conn = BundledSQLiteDriver().open(path)
        try {
            // journal_mode 是查询型 PRAGMA，必须 step 一次才会真正生效。
            exec(conn, "PRAGMA journal_mode=WAL")
            exec(conn, "PRAGMA synchronous=NORMAL")
            exec(conn, "PRAGMA foreign_keys=ON")
        } catch (e: Throwable) {
            // 初始化失败就不把半初始化的连接漏出去。
            conn.close()
            throw e
        }
        return conn
    }

    /** 返回连接所用 SQLite 的版本号，对应 Python 端 `sqlite3.sqlite_version`。 */
    fun sqliteVersion(conn: SQLiteConnection): String {
        val stmt = conn.prepare("SELECT sqlite_version()")
        try {
            if (!stmt.step()) {
                return ""
            }
            return stmt.getText(0)
        } finally {
            stmt.close()
        }
    }

    /** 执行一条不需要返回结果的语句（PRAGMA / DDL）。 */
    private fun exec(conn: SQLiteConnection, sql: String) {
        val stmt: SQLiteStatement = conn.prepare(sql)
        try {
            stmt.step()
        } finally {
            stmt.close()
        }
    }
}
