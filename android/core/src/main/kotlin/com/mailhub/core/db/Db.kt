package com.mailhub.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import com.mailhub.core.Clock
import com.mailhub.core.SystemClock
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// `SQLiteStatement.getColumnType()` 的返回值就是 sqlite3_column_type 的取值。androidx.sqlite
// 把它们放在一个 Kotlin 侧不可见的类里，所以这里照抄字面值（SQLite 公开接口，长期稳定）。
private const val DATA_INTEGER = 1
private const val DATA_FLOAT = 2
private const val DATA_TEXT = 3
private const val DATA_BLOB = 4
private const val DATA_NULL = 5

/**
 * 本机模式的数据库封装，对应服务端 `db.py` 的 `get_conn()` + 各模块里直接写的 SQL。
 *
 * **线程模型与 Python 不同，行为保持一致。** Python 用每线程一个 `sqlite3.Connection`
 * （`db.py` 的 `_local`），写冲突靠 SQLite 自己的忙等待和 WAL；这里只有**一个**
 * [SQLiteConnection]，所有访问都用一把 [ReentrantLock] 串行化。这样换来的性质是：
 *
 * - 任意线程调用都安全：不会出现两个线程同时用一个连接（`androidx.sqlite` 的连接不是
 *   线程安全的），也不会出现跨连接的 `SQLITE_BUSY`；
 * - 写入顺序与 Python 一致：同一时刻只有一个写入者，语句按调用顺序落盘，
 *   `audit_log` 的自增 id、`messages.uid` 的唯一约束等结论与服务器相同；
 * - 代价是并发读也要排队。本机模式只有同步、治理和界面查询，量级远不到需要权衡的地步。
 *
 * **事务约定（后续任务必须遵守）：** Python 的 `sqlite3` 在第一条 DML 前隐式 `BEGIN`，
 * 到 `conn.commit()` 才提交，所以 `db.py` 里凡是「一次 `commit()` 之前连续执行多条写入」
 * 的地方（`init_db()`、`audit()`、`app.py` 的批量删除与撤销等），移植时必须整体包进一个
 * [transaction] 块，否则中途失败会留下半截数据。反过来，[execute] / [audit] /
 * [setSetting] 在**没有**外层事务时每条语句自己就是一次事务（SQLite 的 autocommit），
 * 与 Python 每次 `commit()` 之后的可见性相同。
 *
 * 对应关系：
 * - `get_conn()` → 构造函数（打开连接并设好 PRAGMA，见 [Sqlite.openDatabase]）；
 * - `init_db()` → [initDb]；`init_fts()` → 内部的 [initFtsLocked]；
 *   `fts_search_available()` → [ftsSearchAvailable]；
 * - `get_setting()` / `set_setting()` → [getSetting] / [setSetting]；`audit()` → [audit]；
 *   模块级的 `now()` 不需要移植，时间统一走注入的 [Clock]。
 *
 * 未移植的三处：
 * - `_pin_legacy_ai_base_url()`（把旧的隐式 AI 中转地址写进 settings）和
 *   `remove_external_api_settings()`（删掉旧「注册机取码」的访问令牌）：都是服务器老版本的
 *   迁移，本机模式的库从第一次启动就是新库，没有任何旧部署需要兼容；AI 地址由设置页写入，
 *   不走隐式默认值（`aliases` 表仍然保留，与服务器结构一致，只是不再写入）。
 * - 模块级的 `_fts_available` / `_fts_db_path` 两个全局变量：改成 [Db] 实例上的字段。
 *
 * 时间一律来自构造时注入的 [Clock]（对应 `time.time()`）；凭据加解密用
 * [com.mailhub.core.SecretCipher]，本类只存取已加密的文本，不碰密钥。
 */
class Db(val path: String, private val clock: Clock = SystemClock) {

    private val lock = ReentrantLock()
    private val conn: SQLiteConnection
    private var closed = false

    /** 当前线程持有的嵌套事务层数；0 表示不在事务里。 */
    private var txDepth = 0

    /**
     * FTS5 三元组索引是否可用。Python 把它缓存在模块级变量里并按库路径失效，
     * 这里按实例缓存：一个 [Db] 只对应一个库文件，语义相同又不会串库。
     * `null` 表示还没探测过（对应 Python 的 `_fts_available is None`）。
     */
    private var ftsAvailable: Boolean? = null

    init {
        if (path != ":memory:") {
            // 对应 Python 的 os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)。
            File(path).absoluteFile.parentFile?.mkdirs()
        }
        conn = Sqlite.openDatabase(path)
    }

    // ---------- 查询 ----------

    /** 执行查询，返回全部结果行，对应 `conn.execute(sql, args).fetchall()`。 */
    fun query(sql: String, vararg args: Any?): List<Row> = lock.withLock {
        checkOpen()
        queryLocked(sql, args)
    }

    /** 执行查询并返回第一行，没有结果时返回 null，对应 `...fetchone()`。 */
    fun queryOne(sql: String, vararg args: Any?): Row? = lock.withLock {
        checkOpen()
        queryLocked(sql, args, limit = 1).firstOrNull()
    }

    // ---------- 写入 ----------

    /**
     * 执行一条写语句（含 DDL），返回影响行数与自增 id，对应 `cur = conn.execute(...)` 之后读
     * `cur.rowcount` / `cur.lastrowid`。
     *
     * 不在 [transaction] 里时，这条语句自己就是一次事务（autocommit）。
     */
    fun execute(sql: String, vararg args: Any?): ExecResult = lock.withLock {
        checkOpen()
        execLocked(sql, args)
    }

    /** 逐条执行一个多语句脚本，对应 `conn.executescript(script)`。 */
    fun executeScript(script: String) = lock.withLock {
        checkOpen()
        for (statement in splitSqlScript(script)) {
            stepLocked(statement, emptyArray())
        }
    }

    /**
     * 在一个事务里执行 [block]：正常返回则提交，抛异常则回滚后原样抛出。
     *
     * 可重入：嵌套调用并入外层事务（内层不单独提交，外层回滚时内层一起回滚），
     * 与 Python 里「多条写入 + 一次 commit」的语义对齐，见类注释。
     *
     * [block] 里可以继续调用 [query] / [execute]（同一把可重入锁），但**不要**自己写
     * `BEGIN` / `COMMIT`：那会和这里的层数计数打架。
     */
    fun <T> transaction(block: () -> T): T = lock.withLock {
        checkOpen()
        if (txDepth > 0) {
            return block()
        }
        stepLocked("BEGIN", emptyArray())
        txDepth = 1
        try {
            val result = block()
            stepLocked("COMMIT", emptyArray())
            result
        } catch (throwable: Throwable) {
            // 回滚失败（例如连接已坏）不能盖住原始异常。
            try {
                stepLocked("ROLLBACK", emptyArray())
            } catch (_: Throwable) {
            }
            throw throwable
        } finally {
            txDepth = 0
        }
    }

    /** 关闭连接。重复调用无副作用。 */
    fun close() = lock.withLock {
        if (!closed) {
            closed = true
            conn.close()
        }
    }

    // ---------- db.py 的移植 ----------

    /**
     * 建表、补列、建 FTS 索引，对应 `db.py` 的 `init_db()`。
     *
     * - [DbSchema.SCHEMA] 用 [executeScript] 逐条执行（对应 `executescript`）；
     * - [DbSchema.MIGRATIONS] 只忽略「列已存在」的错，其余照常抛出：权限、损坏或写错的 SQL
     *   必须在启动时立刻暴露，不能等到治理运行时才以「缺列」的形式晚失败；
     * - 对应 Python 里「多条写入 + 一次 `conn.commit()`」，整段包在一个事务里；
     * - 幂等：重复调用不报错、不重复建 FTS、不动已有数据。
     */
    fun initDb() {
        transaction {
            executeScript(DbSchema.SCHEMA)
            for (sql in DbSchema.MIGRATIONS) {
                try {
                    stepLocked(sql, emptyArray())
                } catch (e: SQLiteException) {
                    if (!(e.message ?: "").lowercase().contains("duplicate column name")) {
                        throw e
                    }
                }
            }
            initFtsLocked()
        }
    }

    /**
     * 邮件搜索能否走 FTS5 三元组索引，对应 `fts_search_available()`。
     *
     * 还没探测过就先建一次（与 Python 一样：缓存未命中时顺手初始化）。
     * 不可用时搜索回落到 LIKE，其余功能不受影响。
     */
    fun ftsSearchAvailable(): Boolean = lock.withLock {
        checkOpen()
        ftsAvailable ?: initFtsLocked()
    }

    /** 读设置，对应 `get_setting()`；键不存在时返回 [default]。 */
    fun getSetting(key: String, default: String = ""): String = lock.withLock {
        checkOpen()
        val row = queryLocked("SELECT v FROM settings WHERE k=?", arrayOf<Any?>(key), limit = 1).firstOrNull()
        row?.str("v") ?: default
    }

    /** 写设置（存在则覆盖），对应 `set_setting()`。 */
    fun setSetting(key: String, value: String) {
        execute(
            "INSERT INTO settings(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v",
            key,
            value,
        )
    }

    /**
     * 写一条审计记录并返回它的 id，对应 `db.audit()`：任何改变邮件状态的动作都要调用
     * （包括被策略引擎拦截的）。
     *
     * 与 Python 相同的处理：
     * - 时间取 [Clock]（`time.time()`）；
     * - [reason] 按**码点**截到 500 个字符（Python 的 `reason[:500]` 按下标，emoji 只算一个）；
     * - `target_count` 记的是原始条数，但 [targetIds] 只存前 2000 个；
     * - [targetIds] 与 [undoPayload] 序列化成 JSON 文本，见 [toJsonText] 关于文本形式差异的说明。
     */
    fun audit(
        action: String,
        actor: String = "system",
        tier: String = "",
        targetIds: List<Long>? = null,
        reason: String = "",
        allowed: Boolean = true,
        reversible: Boolean = false,
        undoPayload: Any? = null,
    ): Long {
        val ids = targetIds ?: emptyList()
        val result = execute(
            """INSERT INTO audit_log(ts, actor, action, tier, target_count, target_ids,
                                     reason, allowed, reversible, undone, undo_payload)
               VALUES(?,?,?,?,?,?,?,?,?,0,?)""",
            clock.now(),
            actor,
            action,
            tier,
            ids.size,
            toJsonText(ids.take(2000)),
            truncateCodePoints(reason, MAX_REASON_CHARS),
            if (allowed) 1 else 0,
            if (reversible) 1 else 0,
            if (undoPayload != null) toJsonText(undoPayload) else "",
        )
        return result.lastInsertRowid
    }

    // ---------- 内部实现（调用方必须已持有锁） ----------

    private fun checkOpen() {
        check(!closed) { "数据库连接已关闭：$path" }
    }

    /** 建 FTS5 表与三个触发器；第一次建表后回填已有邮件（之后启动不再重复回填）。 */
    private fun initFtsLocked(): Boolean {
        ftsAvailable?.let { return it }
        return try {
            createFtsLocked()
            ftsAvailable = true
            true
        } catch (e: SQLiteException) {
            // 与 Python 一致：只记不抛。低版本 SQLite 没有 FTS5 的 trigram 分词器，
            // 建表时就会失败，搜索整体回落到 LIKE。
            ftsAvailable = false
            false
        }
    }

    private fun createFtsLocked() {
        val existed = queryLocked(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='messages_fts'",
            emptyArray(),
            limit = 1,
        ).isNotEmpty()
        for (sql in DbSchema.FTS_STATEMENTS) {
            stepLocked(sql, emptyArray())
        }
        if (!existed) {
            stepLocked("INSERT INTO messages_fts(messages_fts) VALUES('rebuild')", emptyArray())
        }
    }

    private fun queryLocked(sql: String, args: Array<out Any?>, limit: Int = Int.MAX_VALUE): List<Row> {
        val stmt = conn.prepare(sql)
        try {
            bindAll(stmt, args)
            val count = stmt.getColumnCount()
            val names = (0 until count).map { stmt.getColumnName(it) }
            val rows = ArrayList<Row>()
            while (stmt.step()) {
                val values = arrayOfNulls<Any?>(count)
                for (index in 0 until count) {
                    values[index] = when (stmt.getColumnType(index)) {
                        DATA_INTEGER -> stmt.getLong(index)
                        DATA_FLOAT -> stmt.getDouble(index)
                        DATA_TEXT -> stmt.getText(index)
                        DATA_BLOB -> stmt.getBlob(index)
                        DATA_NULL -> null
                        else -> null
                    }
                }
                rows.add(Row(names, values.asList()))
                if (rows.size >= limit) {
                    break
                }
            }
            return rows
        } finally {
            stmt.close()
        }
    }

    private fun execLocked(sql: String, args: Array<out Any?>): ExecResult {
        stepLocked(sql, args)
        val row = queryLocked("SELECT changes(), last_insert_rowid()", emptyArray(), limit = 1).first()
        return ExecResult(changes = row.long(0) ?: 0L, lastInsertRowid = row.long(1) ?: 0L)
    }

    /** 执行一条语句并把它跑到结束，返回结果行（DDL 与 PRAGMA 没有结果行）。 */
    private fun stepLocked(sql: String, args: Array<out Any?>) {
        val stmt = conn.prepare(sql)
        try {
            bindAll(stmt, args)
            while (stmt.step()) {
                // 结果行丢弃：需要结果请用 query()。
            }
        } finally {
            stmt.close()
        }
    }

    /**
     * 绑参数，对应 Python 的 `?` 占位符。支持 `null`、`Boolean`（存成 0/1）、
     * `Int` / `Long`、`Double`、`String`、`ByteArray`。
     */
    private fun bindAll(stmt: SQLiteStatement, args: Array<out Any?>) {
        for (index in args.indices) {
            val position = index + 1
            when (val value = args[index]) {
                null -> stmt.bindNull(position)
                is Boolean -> stmt.bindLong(position, if (value) 1L else 0L)
                is Int -> stmt.bindLong(position, value.toLong())
                is Long -> stmt.bindLong(position, value)
                is Double -> stmt.bindDouble(position, value)
                is Float -> stmt.bindDouble(position, value.toDouble())
                is String -> stmt.bindText(position, value)
                is ByteArray -> stmt.bindBlob(position, value)
                else -> throw IllegalArgumentException(
                    "不支持的参数类型：${value::class.java.name}（第 $position 个参数）",
                )
            }
        }
    }

    private companion object {
        /** `db.py` 里 `reason[:500]` 的 500。 */
        const val MAX_REASON_CHARS = 500
    }
}

/** 写语句的执行结果，对应 `cursor.rowcount` 与 `cursor.lastrowid`。 */
data class ExecResult(val changes: Long, val lastInsertRowid: Long)

/**
 * 一行查询结果，对应 Python `sqlite3.Row`。
 *
 * `row["col"]` 按列名取值，返回 `Long`（INTEGER）/ `Double`（REAL）/ `String`（TEXT）/
 * `ByteArray`（BLOB）/ `null`（NULL），与 Python 驱动给的类型一致；列名不存在时抛
 * [NoSuchElementException]（Python 抛 `IndexError`）。列名匹配不区分大小写，与
 * `sqlite3.Row` 一致：先按原样找，找不到再忽略大小写找。另提供 [str]、[long]、[int]、
 * [double]、[boolean]、[bytes] 这些便捷取值，NULL 时返回 null。
 */
class Row internal constructor(
    private val names: List<String>,
    private val values: List<Any?>,
) {
    /** 列数，对应 `len(row)`。 */
    val size: Int get() = names.size

    /** 列名，对应 `row.keys()`。 */
    fun keys(): List<String> = names

    /** 按列名取值；列名不存在时抛异常。 */
    operator fun get(column: String): Any? = values[indexOf(column)]

    /** 按列下标取值，对应 Python 的 `row[0]`。 */
    operator fun get(index: Int): Any? = values[index]

    /** 有没有这一列（不区分大小写）。 */
    fun has(column: String): Boolean = names.any { it.equals(column, ignoreCase = true) }

    /** 取值并转成字符串；NULL 返回 null。 */
    fun str(column: String): String? = get(column)?.toString()

    /** 取值并转成 Long；NULL 返回 null。 */
    fun long(column: String): Long? = asLong(column, get(column))

    /** 按列下标取 Long，给内部读 `changes()` / `last_insert_rowid()` 用。 */
    internal fun long(index: Int): Long? = asLong("列 $index", values[index])

    /** 取值并转成 Int；NULL 返回 null。 */
    fun int(column: String): Int? = long(column)?.toInt()

    /** 取值并转成 Double；NULL 返回 null。 */
    fun double(column: String): Double? = when (val value = get(column)) {
        null -> null
        is Double -> value
        is Float -> value.toDouble()
        is Long -> value.toDouble()
        is Int -> value.toDouble()
        is String -> value.toDoubleOrNull()
            ?: throw IllegalStateException("列 $column 的文本无法转成 Double：$value")
        else -> throw IllegalStateException("列 $column 的类型无法转成 Double：${value::class.java.name}")
    }

    /** 取值并按 SQLite 的布尔约定转成 Boolean（非 0 为真）；NULL 返回 null。 */
    fun boolean(column: String): Boolean? = long(column)?.let { it != 0L }

    /** 取 BLOB；NULL 返回 null。 */
    fun bytes(column: String): ByteArray? = when (val value = get(column)) {
        null -> null
        is ByteArray -> value
        else -> throw IllegalStateException("列 $column 不是 BLOB：${value::class.java.name}")
    }

    /** 列名定位：先按原样找，找不到再忽略大小写（`sqlite3.Row` 也忽略大小写）。 */
    private fun indexOf(column: String): Int {
        val exact = names.indexOf(column)
        if (exact >= 0) {
            return exact
        }
        val folded = names.indexOfFirst { it.equals(column, ignoreCase = true) }
        if (folded < 0) {
            throw NoSuchElementException("结果里没有列 $column，只有 $names")
        }
        return folded
    }

    private fun asLong(column: String, value: Any?): Long? = when (value) {
        null -> null
        is Long -> value
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Double -> value.toLong()
        is Float -> value.toLong()
        is Boolean -> if (value) 1L else 0L
        is String -> value.toLongOrNull()
            ?: throw IllegalStateException("列 $column 的文本无法转成 Long：$value")
        else -> throw IllegalStateException("列 $column 的类型无法转成 Long：${value::class.java.name}")
    }

    override fun toString(): String = "Row(" + names.indices.joinToString(", ") { "${names[it]}=${values[it]}" } + ")"
}

/**
 * 按**码点**截断，对应 Python 的 `s[:max]`（Python 的下标按码点算，
 * Kotlin 的 `String` 是 UTF-16，emoji 这类增补平面字符占两个 char）。
 */
internal fun truncateCodePoints(text: String, max: Int): String {
    if (text.length <= max) {
        // 每个 UTF-16 单元最多对应一个码点，长度不超过 max 时码点数也不会超过 max。
        return text
    }
    var count = 0
    var index = 0
    while (index < text.length && count < max) {
        index += Character.charCount(Character.codePointAt(text, index))
        count++
    }
    return text.substring(0, index)
}
