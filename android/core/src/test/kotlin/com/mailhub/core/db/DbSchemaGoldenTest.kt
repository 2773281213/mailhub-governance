package com.mailhub.core.db

import com.mailhub.core.golden.Golden
import com.mailhub.core.golden.Golden.diff
import com.mailhub.core.golden.GoldenCase
import com.mailhub.core.golden.str
import com.mailhub.core.golden.toPlain
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * 新库的表结构必须与 Python `db.init_db()` 建出来的一模一样。
 *
 * 金标由 `scripts/golden/db_schema.py` 生成，包含：
 * - 一条 `sqlite_master` 用例：`type, name, tbl_name, sql` 的完整清单（按 type、name 排序），
 *   多一张表、少一个索引都会失败；
 * - 每张表、每个索引、每个触发器一条 `object` 用例：`sqlite_master` 的那一行，
 *   表另外附上 `PRAGMA table_info`。
 *
 * `sql` 文本逐字比较：`MIGRATIONS` 里 `ALTER TABLE` 补的列会追加到 `CREATE TABLE` 的原文后面，
 * 两边都由 SQLite 自己生成，所以只要建表语句照抄就应该相同。两者都对得上，L7–L9
 * 才能把 `sync.py`、`governance.py`、`app.py` 里的 SQL 原样搬过来。
 */
class DbSchemaGoldenTest {

    @Test
    fun newDatabaseMatchesPython() = withTempDb { db ->
        db.initDb()
        Golden.replay("db_schema") { case ->
            when (case.fn) {
                "sqlite_master" -> checkMasterListing(db, case)
                "object" -> checkObject(db, case)
                else -> "未知的 fn：${case.fn}"
            }
        }
    }

    /** 完整清单：数量、顺序、每一项的四个字段都要与 Python 一致。 */
    private fun checkMasterListing(db: Db, case: GoldenCase): String? {
        val actual = db.query("SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name")
            .map { rowToMap(it) }
        return diff(case.expected.toPlain(), actual)
    }

    /** 单个对象：`sqlite_master` 的那一行，表再比一遍 `PRAGMA table_info`。 */
    private fun checkObject(db: Db, case: GoldenCase): String? {
        val type = case.args.str("type")
        val name = case.args.str("name")
        val expected = case.expected.jsonObject
        val row = db.queryOne(
            "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE type=? AND name=?",
            type,
            name,
        ) ?: return "sqlite_master 里没有 $type/$name"

        val problems = ArrayList<String>()
        for (key in listOf("type", "name", "tbl_name", "sql")) {
            val want = expected[key]?.toPlain()
            val actual = row[key]
            if (want != actual) {
                problems.add("$key 期望 $want，实际 $actual")
            }
        }

        val actualColumns = db.query("PRAGMA table_info(${quoteIdentifier(name)})").map { rowToMap(it) }
        val wantColumns = expected["table_info"]?.toPlain() ?: emptyList<Any?>()
        if (wantColumns != actualColumns) {
            problems.add("table_info 期望 $wantColumns，实际 $actualColumns")
        }
        return problems.takeIf { it.isNotEmpty() }?.joinToString("；")
    }

    private fun rowToMap(row: Row): Map<String, Any?> = row.keys().associateWith { row[it] }

    /** PRAGMA 的语法里不能绑参数，标识符只能拼进 SQL；双引号里的双引号要写成两个。 */
    private fun quoteIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""
}
