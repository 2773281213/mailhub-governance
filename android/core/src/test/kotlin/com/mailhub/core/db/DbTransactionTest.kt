package com.mailhub.core.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 事务语义：嵌套用 SAVEPOINT、回滚失败怎么处理、事务里不准写事务控制语句、
 * 以及多个线程同时用 [Db.transaction] 时共享计数器仍然对得上。
 *
 * 这些行为直接决定 L7 同步、L8 治理能不能安全地照搬 Python 的「多条写入 + 一次 commit」，
 * 所以都按审核意见补成了可执行断言，而不是写在注释里。
 */
class DbTransactionTest {

    // ---------- 嵌套事务（SAVEPOINT） ----------

    /**
     * 内层失败、外层捕获后继续提交：内层的写入不能落盘，外层前后的写入要落盘。
     *
     * 这是审核意见里最关键的一条：旧实现把嵌套当成「并入外层」，内层抛异常时什么都没回滚，
     * 外层一提交内层的半截数据就跟着落盘了。
     */
    @Test
    fun innerFailureIsRolledBackWhileOuterCommits() = withTempDb { db ->
        db.initDb()
        db.transaction {
            db.setSetting("before_inner", "1")
            try {
                db.transaction {
                    db.setSetting("inner", "1")
                    throw IllegalStateException("内层失败")
                }
                fail("内层的异常应当抛到外层")
            } catch (expected: IllegalStateException) {
                assertEquals("内层失败", expected.message)
            }
            db.setSetting("after_inner", "1")
        }

        assertEquals("内层之前的写入要提交", "1", db.getSetting("before_inner"))
        assertEquals("内层之后的写入要提交", "1", db.getSetting("after_inner"))
        assertNull(
            "内层的写入必须随 SAVEPOINT 回滚",
            db.queryOne("SELECT 1 FROM settings WHERE k='inner'"),
        )
    }

    /** 三层嵌套：中间层失败只回滚中间层及其内层，最外层的写入仍然提交。 */
    @Test
    fun middleLevelFailureOnlyRollsBackItsOwnLevel() = withTempDb { db ->
        db.initDb()
        db.transaction {
            db.setSetting("l1", "1")
            try {
                db.transaction {
                    db.setSetting("l2", "1")
                    db.transaction { db.setSetting("l3", "1") }
                    throw IllegalStateException("第二层失败")
                }
                fail("第二层的异常应当抛出去")
            } catch (expected: IllegalStateException) {
                assertEquals("第二层失败", expected.message)
            }
            db.setSetting("l1_again", "1")
        }

        assertEquals("1", db.getSetting("l1"))
        assertEquals("1", db.getSetting("l1_again"))
        assertNull(db.queryOne("SELECT 1 FROM settings WHERE k='l2'"))
        assertNull(db.queryOne("SELECT 1 FROM settings WHERE k='l3'"))
    }

    /** 内层失败后外层再开一个新的嵌套事务，仍然能正常提交（SAVEPOINT 名字没撞车）。 */
    @Test
    fun nestedTransactionWorksAgainAfterAnInnerFailure() = withTempDb { db ->
        db.initDb()
        db.transaction {
            runCatching { db.transaction { error("第一次内层失败") } }
            db.transaction { db.setSetting("inner_ok", "1") }
        }
        assertEquals("1", db.getSetting("inner_ok"))
    }

    // ---------- 回滚失败 ----------

    /**
     * SQLite 自己把事务回滚掉时（`INSERT OR ROLLBACK` 撞唯一约束），我们的 `ROLLBACK` 会失败；
     * 这个失败必须用 `addSuppressed` 挂到原异常上，不能吞掉，连接可以继续用。
     */
    @Test
    fun rollbackFailureIsAttachedAsSuppressed() = withTempDb { db ->
        db.initDb()
        db.setSetting("dup", "1")
        try {
            db.transaction {
                // OR ROLLBACK 撞到唯一约束时 SQLite 会直接回滚整个事务。
                db.execute("INSERT OR ROLLBACK INTO settings(k, v) VALUES(?, ?)", "dup", "2")
                fail("唯一约束冲突应当抛异常")
            }
            fail("事务里的异常应当抛出来")
        } catch (expected: RuntimeException) {
            assertSqliteError(expected)
            assertTrue(
                "回滚失败要挂到原异常上，不能用 catch 吞掉：${expected.suppressed.toList()}",
                expected.suppressed.isNotEmpty(),
            )
            assertTrue(
                "SQLite 已经自动回滚事务时报告的是 no transaction is active",
                expected.suppressed.any { (it.message ?: "").contains("no transaction is active") },
            )
        }

        // 这种失败不影响连接：事务已经结束，后续读写照常。
        db.setSetting("after", "1")
        assertEquals("1", db.getSetting("after"))
        assertEquals("原来的值还在", "1", db.getSetting("dup"))
    }

    /**
     * SAVEPOINT 因为事务被自动回滚而不存在时，我们对事务状态的认知已经和库不一致：
     * 关掉连接并置为已关闭，后续调用抛 [IllegalStateException]，而不是继续在错的状态上写。
     */
    @Test
    fun lostSavepointClosesTheConnection() = withTempDb { db ->
        db.initDb()
        db.setSetting("dup", "1")
        try {
            db.transaction {
                db.transaction {
                    db.execute("INSERT OR ROLLBACK INTO settings(k, v) VALUES(?, ?)", "dup", "2")
                }
                fail("唯一约束冲突应当抛异常")
            }
            fail("异常应当抛到最外层")
        } catch (expected: RuntimeException) {
            assertSqliteError(expected)
            assertTrue(
                "回滚 SAVEPOINT 的失败要挂到原异常上",
                expected.suppressed.isNotEmpty(),
            )
            assertFalse(
                "这不是「事务已经结束」那种正常情况：${expected.suppressed.toList()}",
                expected.suppressed.any { (it.message ?: "").contains("no transaction is active") },
            )
        }

        try {
            db.query("SELECT 1")
            fail("连接已经不可信，应当抛 IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertTrue("错误信息要说明连接已关闭：${expected.message}", expected.message!!.contains("已关闭"))
        }
    }

    // ---------- 事务与 close ----------

    /** 事务里调用 close() 抛 IllegalStateException；事务回滚，连接还能用。 */
    @Test
    fun closeInsideTransactionIsRejected() = withTempDb { db ->
        db.initDb()
        db.setSetting("keep", "1")
        try {
            db.transaction {
                db.setSetting("rolled_back", "1")
                db.close()
            }
            fail("事务里 close() 应当抛异常")
        } catch (expected: IllegalStateException) {
            assertTrue("错误信息要提到事务：${expected.message}", expected.message!!.contains("事务"))
        }

        assertNull("异常让事务回滚了", db.queryOne("SELECT 1 FROM settings WHERE k='rolled_back'"))
        assertEquals("连接没被关掉", "1", db.getSetting("keep"))
    }

    /** close() 之后所有入口都抛 IllegalStateException；重复 close() 本身无副作用。 */
    @Test
    fun operationsAfterCloseThrow() = withTempDb { db ->
        db.initDb()
        db.close()
        db.close()

        val operations = listOf<Pair<String, () -> Any?>>(
            "query" to { db.query("SELECT 1") },
            "queryOne" to { db.queryOne("SELECT 1") },
            "execute" to { db.execute("INSERT INTO settings(k) VALUES('x')") },
            "executeMany" to { db.executeMany("INSERT INTO settings(k) VALUES(?)", listOf(arrayOf<Any?>("x"))) },
            "executeScript" to { db.executeScript("SELECT 1;") },
            "transaction" to { db.transaction { 1 } },
            "getSetting" to { db.getSetting("k") },
            "ftsSearchAvailable" to { db.ftsSearchAvailable() },
        )
        for ((name, operation) in operations) {
            try {
                operation()
                fail("close() 之后调用 $name 应当抛 IllegalStateException")
            } catch (expected: IllegalStateException) {
                assertTrue("$name 的错误信息：${expected.message}", expected.message!!.contains("已关闭"))
            }
        }
    }

    // ---------- 事务控制语句 ----------

    /**
     * 裸的 BEGIN / COMMIT（含别名 END）/ ROLLBACK / SAVEPOINT / RELEASE 一律拒绝，
     * 提示改用 transaction {}；开头的分号不能帮它们绕过关键字识别。
     */
    @Test
    fun transactionControlStatementsAreRejected() = withTempDb { db ->
        db.initDb()
        val forbidden = listOf(
            "BEGIN",
            "BEGIN IMMEDIATE",
            "  commit",
            "/* 注释 */ ROLLBACK",
            "-- 注释\nSAVEPOINT sp_1",
            "RELEASE sp_1",
            "END",
            "END TRANSACTION",
            ";COMMIT",
            ";;  /* 前置注释 */ COMMIT",
        )
        for (sql in forbidden) {
            try {
                db.execute(sql)
                fail("应当拒绝事务控制语句：$sql")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "提示要指向 transaction {}：${expected.message}",
                    expected.message!!.contains("transaction {}"),
                )
            }
            try {
                db.query(sql)
                fail("query 也应当拒绝事务控制语句：$sql")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("transaction {}"))
            }
        }
    }

    /** executeScript 拆出来的每一条语句都要过同样的检查，夹在脚本中段也不能漏。 */
    @Test
    fun executeScriptRejectsTransactionControlAtAnyStatement() = withTempDb { db ->
        db.initDb()
        val scripts = listOf(
            "COMMIT;",
            "SELECT 1; ;COMMIT;",
            "SELECT 1; BEGIN;",
            "END;",
            "SELECT 1; /* 注释 */ ROLLBACK;",
            "SELECT 1; /* 注释 */ SAVEPOINT sp_9;",
        )
        for (script in scripts) {
            try {
                db.executeScript(script)
                fail("脚本里的事务控制语句应当被拒绝：$script")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "提示要指向 transaction {}：${expected.message}",
                    expected.message!!.contains("transaction {}"),
                )
            }
        }
    }

    // ---------- 自动回滚后的连接状态 ----------

    /**
     * SQLite 自动回滚事务（这里用 `INSERT OR ROLLBACK` 撞唯一约束）后，调用方捕获异常继续
     * 执行时，不能再按 autocommit 逐条写：第一条语句就抛 [IllegalStateException] 并关连接。
     *
     * 这正是 `sync.py` 逐封捕获后继续的写法；旧实现会让后续写入悄悄落盘。
     */
    @Test
    fun writesAfterAnAutomaticRollbackAreRejected() = withTempDb { db ->
        db.initDb()
        db.setSetting("dup", "1")
        try {
            db.transaction {
                try {
                    db.execute("INSERT OR ROLLBACK INTO settings(k, v) VALUES(?, ?)", "dup", "2")
                    fail("唯一约束冲突应当抛异常")
                } catch (expected: RuntimeException) {
                    assertSqliteError(expected)
                    assertTrue(
                        "应当是唯一约束错误：${expected.message}",
                        (expected.message ?: "").uppercase().contains("UNIQUE"),
                    )
                }
                // 此时 BEGIN 已经被 SQLite 自动回滚，层数却还是 1。
                db.setSetting("after_auto_rollback", "1")
                fail("自动回滚后继续写应当抛异常")
            }
            fail("连接不可信的异常应当抛到最外层")
        } catch (expected: IllegalStateException) {
            assertTrue("错误信息要说明连接已关闭：${expected.message}", expected.message!!.contains("已关闭"))
            assertTrue("错误信息要说明事务状态：${expected.message}", expected.message!!.contains("事务"))
        }

        try {
            db.query("SELECT 1")
            fail("连接已经不可信，后续调用应当抛 IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("已关闭"))
        }

        // 重开一个连接确认：自动回滚之后的那条写入确实没有落盘。
        val reopened = Db(db.path)
        try {
            assertNull(
                "自动回滚后不能留下半截写入",
                reopened.queryOne("SELECT 1 FROM settings WHERE k='after_auto_rollback'"),
            )
            assertEquals("原来的值还在", "1", reopened.getSetting("dup"))
        } finally {
            reopened.close()
        }
    }

    // ---------- 嵌套事务与 FTS 触发器 ----------

    /**
     * 走真实路径：内层 [Db.transaction] 向 `messages` 插入（FTS 触发器在同一个 SAVEPOINT 里
     * 写索引），随后失败；外层捕获后继续提交。提交后 `MATCH` 查不到内层那封，外层那封能查到，
     * 最后用 FTS5 自己的 `integrity-check` 确认索引与 `messages` 表一致。
     */
    @Test
    fun failingInnerMessagesInsertLeavesFtsConsistent(): Unit = withTempDb { db ->
        db.initDb()
        val accountId = db.execute(
            "INSERT INTO accounts(name, provider, email, imap_host) VALUES(?,?,?,?)",
            "本机账户",
            "custom",
            "demo@example.com",
            "imap.example.com",
        ).lastInsertRowid

        db.transaction {
            try {
                db.transaction {
                    db.execute(
                        "INSERT INTO messages(account_id, uid, subject, sender_name, sender_addr, snippet) " +
                            "VALUES(?,?,?,?,?,?)",
                        accountId,
                        1L,
                        "内层的验证码 246810",
                        "银行通知",
                        "notify@bank.example",
                        "请在 10 分钟内使用",
                    )
                    throw IllegalStateException("内层失败")
                }
                fail("内层的异常应当抛到外层")
            } catch (expected: IllegalStateException) {
                assertEquals("内层失败", expected.message)
            }
            // 外层事务仍然是活的：再插一封，最终要能和内层区分开。
            db.execute(
                "INSERT INTO messages(account_id, uid, subject) VALUES(?,?,?)",
                accountId,
                2L,
                "外层的收据 invoice",
            )
        }

        assertEquals("内层的邮件不能落盘，索引里也不能有", 0L, matchCount(db, "验证码"))
        assertEquals("外层的邮件要提交并可搜索", 1L, matchCount(db, "invoice"))
        // FTS5 的完整性检查：索引里每一行都要与 messages 表对得上。
        db.execute("INSERT INTO messages_fts(messages_fts) VALUES('integrity-check')")
    }

    // ---------- 并发 ----------

    /**
     * 多线程并发跑 [Db.transaction]：读改写共享计数器的、故意抛错回滚的、只读的混在一起，
     * 最后计数器必须恰好等于成功的事务数。
     *
     * 断言线程「真的都结束了」（join 之后 `isAlive` 必须是 false），不靠超时静默放行：
     * 如果哪个线程卡在锁里，这里会直接失败而不是超时后当成通过。
     */
    @Test
    fun concurrentTransactionsKeepSharedCounterConsistent() {
        val writerCount = 4
        val writesPerThread = 40
        val failingCount = 3
        val readerCount = 3
        withTempDb { db ->
            db.initDb()
            db.setSetting("counter", "0")
            db.setSetting("sentinel", "ok")

            val start = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()

            val writers = (0 until writerCount).map { worker ->
                thread(name = "db-counter-writer-$worker") {
                    try {
                        start.await()
                        repeat(writesPerThread) {
                            // 读改写整段在一个事务里：同一把锁保证没有别的写入插进来。
                            db.transaction {
                                val current = db.getSetting("counter").toLong()
                                db.setSetting("counter", (current + 1).toString())
                            }
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }
            }
            val failers = (0 until failingCount).map { worker ->
                thread(name = "db-failing-writer-$worker") {
                    try {
                        start.await()
                        repeat(10) {
                            try {
                                db.transaction {
                                    db.setSetting("counter", "999999")
                                    db.setSetting("poison_$worker", "1")
                                    throw IllegalStateException("故意失败")
                                }
                            } catch (expected: IllegalStateException) {
                                assertEquals("故意失败", expected.message)
                            }
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }
            }
            val readers = (0 until readerCount).map { worker ->
                thread(name = "db-reader-$worker") {
                    try {
                        start.await()
                        repeat(50) {
                            assertEquals("ok", db.getSetting("sentinel"))
                            assertTrue(db.query("SELECT 1 FROM settings").isNotEmpty())
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }
            }

            start.countDown()
            (writers + failers + readers).forEach { it.join(TimeUnit.MINUTES.toMillis(2)) }
            (writers + failers + readers).forEach { worker ->
                assertFalse("线程 ${worker.name} 在 join 之后还活着", worker.isAlive)
            }
            failure.get()?.let { throw AssertionError("并发事务失败：${it.message}", it) }

            assertEquals(
                "读改写的事务不该丢也不该多",
                (writerCount * writesPerThread).toString(),
                db.getSetting("counter"),
            )
            for (worker in 0 until failingCount) {
                assertNull(
                    "回滚的事务不该留下写入",
                    db.queryOne("SELECT 1 FROM settings WHERE k=?", "poison_$worker"),
                )
            }
        }
    }

    private fun matchCount(db: Db, keyword: String): Long =
        db.queryOne("SELECT count(*) FROM messages_fts WHERE messages_fts MATCH ?", keyword)?.long(0) ?: 0L
}
