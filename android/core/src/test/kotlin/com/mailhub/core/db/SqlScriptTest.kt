package com.mailhub.core.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `executescript` 的拆分：`SCHEMA` 要拆成一条一条交给 `prepare()`，
 * 而字符串、注释和触发器体里都有分号。
 */
class SqlScriptTest {

    /** SCHEMA 里的 17 条语句要被拆成 17 条，注释不要带成单独一条。 */
    @Test
    fun splitsSchemaIntoStatements() {
        val statements = splitSqlScript(DbSchema.SCHEMA)
        assertEquals(17, statements.size)
        assertTrue("第一条是建 settings 表", statements.first().startsWith("CREATE TABLE IF NOT EXISTS settings("))
        assertTrue(
            "最后一条是 sessions 的索引",
            statements.last().endsWith("CREATE INDEX IF NOT EXISTS idx_sessions_expiry ON sessions(expires_ts);"),
        )
        for (statement in statements) {
            assertTrue("每条语句都应以分号结尾：$statement", statement.endsWith(";"))
            assertTrue("不应把纯注释拆成语句：$statement", statement.length > 1)
            assertTrue("语句不能以注释开头：$statement", !statement.startsWith("--"))
        }
    }

    /** FTS 语句里的触发器体（BEGIN … END）必须整条保留，体内的分号不能断开语句。 */
    @Test
    fun keepsTriggerBodyTogether() {
        // db.py 里这几条语句本身不带结尾分号（conn.execute 一条一条执行），拼成脚本时补上。
        val script = DbSchema.FTS_STATEMENTS.joinToString("\n") { "$it;" }
        val statements = splitSqlScript(script)
        assertEquals(4, statements.size)
        assertEquals(
            "触发器体里的分号不能断开语句",
            3,
            statements.count { it.startsWith("CREATE TRIGGER") },
        )
        for (statement in statements.filter { it.startsWith("CREATE TRIGGER") }) {
            assertTrue("触发器语句要以 END; 结尾：$statement", statement.endsWith("END;"))
        }
    }

    /** 字符串、行注释、块注释里的分号都不算语句结束；结尾的纯注释要丢掉。 */
    @Test
    fun semicolonsInStringsAndComments() {
        val script = """
            CREATE TABLE t(a TEXT DEFAULT 'x;y');
            INSERT INTO t(a) VALUES('a--b;c');
            CREATE TRIGGER tr AFTER INSERT ON t BEGIN
              /* 块注释里的分号; 不算 */
              INSERT INTO t(a) VALUES(CASE WHEN new.a IS NULL THEN 'x;y' ELSE new.a END);
              -- 行注释里的分号; 也不算
              INSERT INTO t(a) VALUES('z');
            END;
            -- 结尾的纯注释
        """.trimIndent()
        val statements = splitSqlScript(script)
        assertEquals(3, statements.size)
        assertEquals("CREATE TABLE t(a TEXT DEFAULT 'x;y');", statements[0])
        assertEquals("INSERT INTO t(a) VALUES('a--b;c');", statements[1])
        assertTrue("触发器整条保留：${statements[2]}", statements[2].startsWith("CREATE TRIGGER tr"))
        assertTrue(statements[2].endsWith("END;"))
        assertTrue("体内的块注释要留着", statements[2].contains("块注释里的分号; 不算"))
        assertTrue("体内的行注释要留着", statements[2].contains("行注释里的分号; 也不算"))
    }

    /** 双写的引号是转义，不算字符串结束；开头注释与结尾纯注释都要处理掉。 */
    @Test
    fun doubledQuotesAreEscapes() {
        val script = "-- 开头的注释\nINSERT INTO t(a) VALUES('it''s;ok');SELECT 1;-- 结尾\n"
        val statements = splitSqlScript(script)
        assertEquals(2, statements.size)
        assertEquals("INSERT INTO t(a) VALUES('it''s;ok');", statements[0])
        assertEquals("SELECT 1;", statements[1])
    }

    /**
     * 以 `_`、`$`、`:`、`@` 开头的标识符整段是一个标识符：`_end` 不能被当成 `END`。
     *
     * 旧实现只把字母当标识符开头，`_end` 里的 `end` 会命中 `END`、把触发器体的层数减到 0，
     * 结果体内的分号提前断句，拆出来的 SQL 直接被截断。
     */
    @Test
    fun underscorePrefixedIdentifiersAreNotKeywords() {
        val script = """
            CREATE TRIGGER tr AFTER INSERT ON t BEGIN
              INSERT INTO t(_end) VALUES(new.begin);
            END;
            SELECT 1;
        """.trimIndent()
        val statements = splitSqlScript(script)
        assertEquals("触发器整条保留，后面才是另一条：$statements", 2, statements.size)
        assertTrue(statements[0].startsWith("CREATE TRIGGER tr"))
        assertTrue("触发器要以 END; 收尾：${statements[0]}", statements[0].endsWith("END;"))
        assertTrue("体内的 _end 要原样保留", statements[0].contains("INSERT INTO t(_end)"))
        assertEquals("SELECT 1;", statements[1])
    }

    /** `.` 后面的限定列名（`new.end`）不是关键字，触发器体的层数不能被它减掉。 */
    @Test
    fun qualifiedColumnNamesAreNotKeywords() {
        val script = """
            CREATE TRIGGER tr AFTER UPDATE ON t BEGIN
              INSERT INTO t(a) VALUES(new.end);
            END;
            SELECT 1;
        """.trimIndent()
        val statements = splitSqlScript(script)
        assertEquals("触发器整条保留，后面才是另一条：$statements", 2, statements.size)
        assertTrue(statements[0].endsWith("END;"))
        assertTrue(statements[0].contains("VALUES(new.end)"))
        assertEquals("SELECT 1;", statements[1])
    }

    /**
     * 只有语句开头是 `CREATE [TEMP|TEMPORARY] TRIGGER` 才进触发器模式。
     *
     * `governance_runs` 有一列就叫 `trigger`，`begin` 也可能是列名；见到这两个词就进触发器
     * 模式会把后面的两条语句粘成一条。
     */
    @Test
    fun triggerColumnDoesNotStartTriggerMode() {
        val statements = splitSqlScript("SELECT trigger, begin FROM t; SELECT 1;")
        assertEquals(2, statements.size)
        assertEquals("SELECT trigger, begin FROM t;", statements[0])
        assertEquals("SELECT 1;", statements[1])
    }

    /** `CREATE TEMP TRIGGER` / `CREATE TEMPORARY TRIGGER` 同样要认出来。 */
    @Test
    fun tempTriggersAreRecognised() {
        for (prefix in listOf("CREATE TEMP TRIGGER", "CREATE TEMPORARY TRIGGER")) {
            val script = """
                $prefix tr AFTER INSERT ON t BEGIN
                  INSERT INTO t(a) VALUES(1);
                END;
                SELECT 1;
            """.trimIndent()
            val statements = splitSqlScript(script)
            assertEquals("$prefix 应当按触发器拆：$statements", 2, statements.size)
            assertTrue(statements[0].endsWith("END;"))
            assertEquals("SELECT 1;", statements[1])
        }
    }

    /**
     * 只含分号的片段是空语句（`executescript` 里是空操作），不能拆成一条送去 prepare；
     * 否则 `;COMMIT;` 会先拿 `;` 去执行，轮不到事务控制检查。
     */
    @Test
    fun emptyStatementsAreDropped() {
        assertEquals(listOf("SELECT 1;"), splitSqlScript(";; SELECT 1; ; -- 注释\n ;;;"))
    }

    /** 触发器之外出现 `BEGIN`（例如列名或字符串里的）不能影响断句。 */
    @Test
    fun beginOutsideTriggerDoesNotOpenABlock() {
        val statements = splitSqlScript("UPDATE t SET begin=1 WHERE a='begin'; SELECT 2;")
        assertEquals(2, statements.size)
        assertEquals("UPDATE t SET begin=1 WHERE a='begin';", statements[0])
        assertEquals("SELECT 2;", statements[1])
    }
}
