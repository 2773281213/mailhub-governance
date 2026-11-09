package com.mailhub.core.db

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger

/**
 * `Row` 的取值语义：类型映射要与 Python `sqlite3.Row` 一致，
 * 参数绑定要支持 Python 那边会传的所有类型。
 */
class RowTest {

    /** INTEGER → Long，REAL → Double，TEXT → String，BLOB → ByteArray，NULL → null。 */
    @Test
    fun valuesUsePythonTypes() = withTempDb { db ->
        db.initDb()
        db.execute("INSERT INTO settings(k, v) VALUES(?,?)", "k值", "v值")
        val row = db.queryOne(
            "SELECT k, v, NULL AS n, 42 AS i, 1.5 AS r, x'00ff' AS b FROM settings",
        ) ?: throw AssertionError("没有结果行")

        assertEquals("TEXT 取出来是 String", "v值", row["v"])
        assertEquals("按列名取 NULL 得到 null", null, row["n"])
        assertEquals("INTEGER 取出来是 Long", 42L, row["i"])
        assertEquals("REAL 取出来是 Double", 1.5, row["r"])
        assertArrayEquals("BLOB 取出来是 ByteArray", byteArrayOf(0, -1), row.bytes("b"))
        assertEquals("列数", 6, row.size)
        assertEquals(listOf("k", "v", "n", "i", "r", "b"), row.keys())
        assertEquals("按下标取，对应 row[0]", "k值", row[0])

        // 便捷取值：NULL 返回 null，其余按需转换。
        assertEquals("k值", row.str("k"))
        assertNull(row.str("n"))
        assertEquals(42L, row.long("i"))
        assertEquals(42, row.int("i"))
        assertEquals(1.5, row.double("r")!!, 0.0)
        assertEquals("42", row.str("i"))
        assertEquals("按 SQLite 约定 0/1 转布尔", null, row.boolean("n"))
    }

    /** 列名匹配不区分大小写，与 `sqlite3.Row` 一致；也没有的列就抛异常。 */
    @Test
    fun columnNamesAreCaseInsensitive() = withTempDb { db ->
        db.initDb()
        db.execute("INSERT INTO settings(k, v) VALUES(?,?)", "k", "v")
        val row = db.queryOne("SELECT k, v FROM settings")!!

        assertEquals("大写列名也能取到", "v", row["V"])
        assertTrue(row.has("K"))
        assertFalse("没有的列", row.has("nope"))
        try {
            row["nope"]
            fail("列名不存在时应当抛异常")
        } catch (expected: NoSuchElementException) {
            assertTrue(expected.message!!.contains("nope"))
        }
    }

    /** 参数绑定支持 null、布尔、整数、浮点、文本、二进制；不支持的类型直接报错。 */
    @Test
    fun bindsSupportedParameterTypes() = withTempDb { db ->
        db.initDb()
        val row = db.queryOne(
            "SELECT ? AS flag, ? AS i, ? AS l, ? AS d, ? AS s, ? AS blob, ? AS n",
            true,
            7,
            8L,
            1.25,
            "文本",
            byteArrayOf(1, 2, 3),
            null,
        ) ?: throw AssertionError("没有结果行")

        assertEquals("Boolean 存成 1", 1L, row["flag"])
        assertEquals("Int", 7L, row["i"])
        assertEquals("Long", 8L, row["l"])
        assertEquals("Double", 1.25, row["d"])
        assertEquals("String", "文本", row["s"])
        assertArrayEquals(byteArrayOf(1, 2, 3), row.bytes("blob"))
        assertEquals("null", null, row["n"])

        try {
            db.queryOne("SELECT ?", listOf(1, 2, 3))
            fail("不支持的类型应当报错，而不是悄悄写成字符串")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("不支持的参数类型"))
        }
    }

    /** 窄整数与 `BigInteger` 也能绑；`BigInteger` 超出 64 位要报错（对应 Python 的 OverflowError）。 */
    @Test
    fun bindsNarrowIntegersAndBigInteger() = withTempDb { db ->
        db.initDb()
        val row = db.queryOne(
            "SELECT ? AS b, ? AS s, ? AS bi",
            5.toByte(),
            7.toShort(),
            BigInteger("123456789012345"),
        ) ?: throw AssertionError("没有结果行")
        assertEquals("Byte", 5L, row["b"])
        assertEquals("Short", 7L, row["s"])
        assertEquals("BigInteger", 123456789012345L, row["bi"])

        assertEquals(
            "Long.MAX_VALUE 可以",
            Long.MAX_VALUE,
            db.queryOne("SELECT ? AS m", BigInteger.valueOf(Long.MAX_VALUE))!!["m"],
        )
        assertEquals(
            "Long.MIN_VALUE 可以",
            Long.MIN_VALUE,
            db.queryOne("SELECT ? AS m", BigInteger.valueOf(Long.MIN_VALUE))!!["m"],
        )

        for (tooBig in listOf("9223372036854775808", "-9223372036854775809")) {
            try {
                db.queryOne("SELECT ?", BigInteger(tooBig))
                fail("超出 64 位整数范围时应当报错：$tooBig")
            } catch (expected: IllegalArgumentException) {
                assertTrue("错误信息要提到 64 位：${expected.message}", expected.message!!.contains("64 位"))
            }
        }
    }

    /**
     * `int()` 越界抛异常，不静默截断：IMAP 的 UID 是无符号 32 位，`uidvalidity` 也是 32 位
     * 无符号，两者都可能超过 `Int.MAX_VALUE`，这类列必须用 `long()`。
     */
    @Test
    fun intThrowsOnOverflowInsteadOfTruncating() = withTempDb { db ->
        db.initDb()
        val row = db.queryOne("SELECT 4294967295 AS uid, 7 AS n") ?: throw AssertionError("没有结果行")

        assertEquals("uid 用 long 取", 4294967295L, row.long("uid"))
        assertEquals("按下标取 Long，对应 row[0]", 4294967295L, row.long(0))
        assertEquals("没越界的列照样能用 int", 7, row.int("n"))

        try {
            row.int("uid")
            fail("超出 Int 范围时应当抛异常，而不是截断成 -1")
        } catch (expected: ArithmeticException) {
            assertTrue("越界信息：${expected.message}", expected.message!!.contains("overflow"))
        }
        assertNull("NULL 仍然是 null", db.queryOne("SELECT NULL AS x")!!.int("x"))
    }

    /** `toMap()` 对应 `dict(row)`：按列顺序、同名列后者覆盖前者。 */
    @Test
    fun toMapMatchesDictRow() = withTempDb { db ->
        db.initDb()
        val row = db.queryOne("SELECT 1 AS a, 2 AS a, 'x' AS b, NULL AS c")
            ?: throw AssertionError("没有结果行")

        val map = row.toMap()
        assertEquals("同名列后者覆盖前者", mapOf("a" to 2L, "b" to "x", "c" to null), map)
        assertEquals("按列顺序", listOf("a", "b", "c"), map.keys.toList())
    }

    /** SQLite 把 `Double.NaN` 存成 NULL，读回来是 null 而不是 NaN（KDoc 里提醒了这一点）。 */
    @Test
    fun nanIsStoredAsNull() = withTempDb { db ->
        db.initDb()
        db.execute("CREATE TABLE probe(x REAL)")
        db.execute("INSERT INTO probe(x) VALUES(?)", Double.NaN)

        val row = db.queryOne("SELECT x, typeof(x) AS ty FROM probe")
            ?: throw AssertionError("没有结果行")
        assertNull("NaN 落库即 NULL", row["x"])
        assertEquals("null", row.str("ty"))
    }
}
