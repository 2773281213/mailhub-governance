package com.mailhub.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import com.mailhub.core.Clock
import com.mailhub.core.SystemClock
import com.mailhub.core.text.cpSlice
import java.io.File
import java.math.BigInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Logger
import kotlin.concurrent.withLock

// `SQLiteStatement.getColumnType()` 的返回值就是 sqlite3_column_type 的取值。androidx.sqlite
// 把它们放在一个 Kotlin 侧不可见的类里，所以这里照抄字面值（SQLite 公开接口，长期稳定）。
private const val DATA_INTEGER = 1
private const val DATA_FLOAT = 2
private const val DATA_TEXT = 3
private const val DATA_BLOB = 4
private const val DATA_NULL = 5

/** SQLite 说「当前没有活动事务」时的错误信息片段，见 `transaction` 的回滚失败处理。 */
private const val NO_TRANSACTION_ACTIVE = "no transaction is active"

/** 嵌套事务的 SAVEPOINT 名字前缀：第 N 层用 `sp_N`。 */
private const val SAVEPOINT_PREFIX = "sp_"

/** 对应 `db.py` 的 `log = logging.getLogger("mailhub.db")`；这里只有一条 FTS 的 warning。 */
private val LOG: Logger = Logger.getLogger("com.mailhub.core.db.Db")

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
 * - 代价是并发读也要排队。本机模式只有同步、治理和界面查询，量级远不到需要权衡的地步，
 *   但**锁内不能等 IO**，见 [transaction] 的「锁的约束」。
 *
 * **全应用只能有一个 [Db] 实例。** 事务层数、FTS 可用性缓存和那把锁都挂在实例上；再开一个
 * 实例就是第二条连接，两个实例的锁互不相干，同一时刻可能同时写同一个库文件，SQLite 只能靠
 * `SQLITE_BUSY` 报错（本类没有设置忙等待，见 [Sqlite.openDatabase]）。需要访问库的地方都从
 * 同一个入口拿这个实例。
 *
 * **首次调用 [initDb] / [ftsSearchAvailable] 不要放在 [transaction] 里。** 两者都可能创建
 * FTS5 虚表；外层已经有事务时，它们内部的「独立事务」会退化成 `SAVEPOINT`，而 FTS5 的虚表
 * 在 SAVEPOINT 里被撤销、外层再 `COMMIT` 时 SQLite 会报 `SQL logic error`（这就是 [initDb]
 * 把 FTS 单独放在一个事务里的原因）。启动时先在任何事务之外调用 [initDb]，之后
 * [ftsSearchAvailable] 就只读缓存了。
 *
 * **事务约定（后续任务必须遵守）：** Python 的 `sqlite3` 在第一条 DML 前隐式 `BEGIN`，
 * 到 `conn.commit()` 才提交，所以 `db.py` 里凡是「一次 `commit()` 之前连续执行多条写入」
 * 的地方（`init_db()`、`audit()`、`app.py` 的批量删除与撤销等），移植时必须整体包进一个
 * [transaction] 块，否则中途失败会留下半截数据。反过来，[execute] / [audit] /
 * [setSetting] 在**没有**外层事务时每条语句自己就是一次事务（SQLite 的 autocommit），
 * 与 Python 每次 `commit()` 之后的可见性相同。`governance.py` 的 `BEGIN IMMEDIATE`
 * （`_claim_run`）移植时同样用 [transaction]，不要自己写事务控制语句。
 *
 * 对应关系：
 * - `get_conn()` → 构造函数（打开连接并设好 PRAGMA，见 [Sqlite.openDatabase]）；
 * - `init_db()` + `oauth_auth.init_oauth_db()` → [initDb]（服务器启动时依次调用这两个，
 *   `app.py`）；`init_fts()` → 内部的 [initFtsLocked]；`fts_search_available()` →
 *   [ftsSearchAvailable]；
 * - `get_setting()` / `set_setting()` → [getSetting] / [setSetting]；`audit()` → [audit]；
 *   `conn.executemany(...)` → [executeMany]；模块级的 `now()` 不需要移植，时间统一走注入的
 *   [Clock]。
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

    /**
     * 执行查询，返回全部结果行，对应 `conn.execute(sql, args).fetchall()`。
     *
     * 事务控制语句（`BEGIN` / `COMMIT` / `END` / `ROLLBACK` / `SAVEPOINT` / `RELEASE`）要抛
     * [IllegalArgumentException]：那是 [transaction] 的职责。开头的分号不影响识别
     * （`;COMMIT` 同样被拒绝）。
     *
     * `?` 占位符的个数必须与 [args] 的个数一致，否则抛 [IllegalArgumentException]；
     * 字符串和注释里的 `?` 不计数。
     */
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
     *
     * [ExecResult.changes] 对非 DML 语句（DDL、PRAGMA、`CREATE TABLE` 等）**没有意义**：
     * SQLite 的 `changes()` 指的是「上一条 INSERT/UPDATE/DELETE 改了几行」，DDL 不改它。
     * Python 那边 `cursor.rowcount` 对这类语句是 -1，这里不做特殊处理，调用方也不要依赖。
     *
     * [ExecResult.lastInsertRowid] 见 [ExecResult] 的说明：`changes == 0` 时一律是 0。
     *
     * 事务控制语句（含 `COMMIT` 的别名 `END`）要抛 [IllegalArgumentException]：事务请用
     * [transaction]。`?` 占位符的个数必须与 [args] 一致，否则抛 [IllegalArgumentException]：
     * `androidx.sqlite` 不会像 Python 的 `sqlite3` 那样报 `ProgrammingError`，漏传的参数会
     * 静默绑成 NULL。
     */
    fun execute(sql: String, vararg args: Any?): ExecResult = lock.withLock {
        checkOpen()
        execLocked(sql, args)
    }

    /**
     * 用同一条预编译语句执行多行参数，返回总共影响的行数，对应 `conn.executemany(sql, rows)`。
     *
     * - 参数是「一行一个 `Array`」，顺序与 SQL 里的 `?` 一致（对应 Python 的元组）；
     * - 复用同一个预编译语句，只为减少反复解析 SQL 的开销，语义与逐条 [execute] 相同；
     * - 返回值是每条语句 `changes()` 的和（Python 的 `rowcount` 也是这么累加的），
     *   `SELECT changes()` 只预编译一次，每行复用；
     * - 每一行的参数个数都必须与 SQL 里的 `?` 一致（`?` 的扫描只做一次），否则抛
     *   [IllegalArgumentException]，不会把缺的参数静默绑成 NULL；
     * - 不在 [transaction] 里时自动包进一个事务：中途失败不会留下前几行，与
     *   `governance.py` / `sync.py` 里「`executemany` 之后紧跟 `commit()`」一致。
     */
    fun executeMany(sql: String, rows: Iterable<Array<out Any?>>): Long = lock.withLock {
        checkOpen()
        rejectTransactionControl(sql)
        if (txDepth > 0) {
            executeManyLocked(sql, rows)
        } else {
            transaction { executeManyLocked(sql, rows) }
        }
    }

    /**
     * 逐条执行一个多语句脚本，对应 `conn.executescript(script)`。
     *
     * 与 Python 的 `executescript` 有一处**不同**：Python 会先隐式 `COMMIT` 掉外层没提交的
     * 事务再执行脚本，这里不会——脚本里的语句就落在当前事务里（没有外层事务时每条自己提交）。
     * 因此脚本里**不准自带** `BEGIN` / `COMMIT`（含别名 `END`）/ `ROLLBACK`：那会和外层的
     * 层数计数打架。**每一条**拆出来的语句都会做与 [execute] 相同的检查（事务控制、参数
     * 个数），不会因为藏在脚本中段就漏过去。需要事务语义时，把 [executeScript] 包进
     * [transaction]。[executeScript] 不接受参数，脚本里出现 `?` 占位符会报错。
     */
    fun executeScript(script: String) = lock.withLock {
        checkOpen()
        for (statement in splitSqlScript(script)) {
            rejectTransactionControl(statement)
            stepLocked(statement, emptyArray())
        }
    }

    /**
     * 在一个事务里执行 [block]：正常返回则提交，抛异常则回滚后原样抛出。
     *
     * ## 锁的约束（后续任务必须照这条执行）
     *
     * 整个 [block] 都在 [Db] 那**唯一**一把锁里跑（和 [query] / [execute] 是同一把锁）。
     * 锁内一阻塞，界面的全部查询就跟着排队，本机模式会直接卡住。所以 [block] 里：
     * - **不准**做 IMAP 或其他网络请求（收信、发信、等 AI 接口返回都不行）；
     * - **不准**做耗时计算（MIME 解析、正文清洗、规则/AI 结果的组装）；
     * - **不准**获取别的锁，也**不准** `runBlocking` / `join` 之类的等待。
     *
     * 正确做法：在锁外把网络请求和解析全部做完，只把最后几条 SQL（写库、改状态、写审计）
     * 包进一个**短**事务。后续任务的验收按这条看。
     *
     * ## 事务语义
     *
     * - 可重入：嵌套调用用 `SAVEPOINT` 实现（第 N 层是 `sp_N`）。内层正常结束只 `RELEASE`，
     *   外层回滚时内层一起回滚；内层抛异常时 `ROLLBACK TO` 到自己的 SAVEPOINT 再 `RELEASE`，
     *   所以外层捕获这个异常后继续提交时，**内层的写入不会落盘**。与 Python 里
     *   「多条写入 + 一次 commit」的语义对齐，见类注释。
     * - **SQLite 自动回滚**（磁盘满、中断、`ON CONFLICT ROLLBACK` 等）后，连接已经不在事务
     *   里，而本类的层数计数还是 1。每条语句执行前都会用 `SQLiteConnection.inTransaction()`
     *   确认连接仍在事务中（见 [ensureUsableTransactionLocked]）；不符合就抛
     *   [IllegalStateException] 并关闭连接，不会让后续写入按 autocommit 逐条落盘。调用方在
     *   [block] 里捕获异常后继续写（`sync.py` 的逐封处理）也会被这里挡住。
     * - 回滚本身失败时不会吞掉：回滚的异常用 `addSuppressed` 附到原异常上。如果连接已经
     *   不可信（错误信息不是「no transaction is active」，例如 SAVEPOINT 已经被自动回滚
     *   掉），立即关闭连接并把它置为已关闭，后续调用抛 [IllegalStateException]。事务里
     *   调用 [close] 同样抛 [IllegalStateException]。
     * - [block] 里可以继续调用 [query] / [execute] / [executeMany]（同一把可重入锁），
     *   但**不要**自己写 `BEGIN` / `COMMIT` / `SAVEPOINT`：[execute] / [query] 会直接拒绝。
     * - 不要在 [block] 里首次调用 [initDb] 或 [ftsSearchAvailable]（会创建 FTS5 虚表），
     *   原因见类注释。
     */
    fun <T> transaction(block: () -> T): T = lock.withLock {
        checkOpen()
        ensureUsableTransactionLocked()
        if (txDepth > 0) nestedTransactionLocked(block) else outerTransactionLocked(block)
    }

    /**
     * 关闭连接。重复调用无副作用；事务里调用抛 [IllegalStateException]（先结束事务再关）。
     */
    fun close() = lock.withLock {
        check(txDepth == 0) { "不能在事务里关闭数据库连接：$path" }
        closeLocked()
    }

    // ---------- db.py 的移植 ----------

    /**
     * 建表、补列、建 FTS 索引，对应服务器启动时的 `db.init_db()` + `oauth_auth.init_oauth_db()`
     * （`app.py` 里就是这两句，先后顺序也一样，见 [DbSchema] 的类注释）。
     *
     * 依次执行：
     * 1. [DbSchema.SCHEMA]（对应 `init_db()` 的 `executescript(SCHEMA)`）；
     * 2. [DbSchema.MIGRATIONS]（对应 `init_db()` 的 `MIGRATIONS` 补列）；
     * 3. [DbSchema.OAUTH_SCHEMA]（对应 `init_oauth_db()` 建 `oauth_transactions` 与两个索引）；
     * 4. [DbSchema.OAUTH_ACCOUNT_COLUMNS] / [DbSchema.OAUTH_TRANSACTION_COLUMNS]
     *    （对应 `init_oauth_db()` 的补列），再跑一次 [DbSchema.OAUTH_REAUTH_SQL]；
     * 5. FTS5 表与触发器（对应 `init_db()` 末尾的 `init_fts(conn)`）。
     *
     * 2、4 里只忽略「列已存在」的错，其余照常抛出：权限、损坏或写错的 SQL 必须在启动时
     * 立刻暴露，不能等到治理运行时才以「缺列」的形式晚失败。
     *
     * 对应 Python 里「多条写入 + 一次 `conn.commit()`」，表结构那几步整段包在一个事务里；
     * **FTS 单独用一个事务**（Python 是同一个事务，这里故意不同）：FTS5 的虚表如果在
     * SAVEPOINT 里被撤销、之后外层再 `COMMIT`，SQLite 会报 `SQL logic error`，那样「建不出
     * FTS 就回落到 LIKE」这条降级路径会把整个启动带崩。用独立事务则失败时是完整 `ROLLBACK`，
     * 一行都不留，也不会影响已经提交的表结构。
     *
     * 幂等：重复调用不报错、不重复建 FTS、不动已有数据。
     */
    fun initDb() = lock.withLock {
        checkOpen()
        transaction {
            executeScript(DbSchema.SCHEMA)
            for (sql in DbSchema.MIGRATIONS) {
                stepIgnoringDuplicateColumn(sql)
            }
            executeScript(DbSchema.OAUTH_SCHEMA)
            for (sql in DbSchema.OAUTH_ACCOUNT_COLUMNS) {
                stepIgnoringDuplicateColumn(sql)
            }
            for (sql in DbSchema.OAUTH_TRANSACTION_COLUMNS) {
                stepIgnoringDuplicateColumn(sql)
            }
            stepLocked(
                DbSchema.OAUTH_REAUTH_SQL,
                arrayOf<Any?>(DbSchema.OAUTH_REAUTH_MESSAGE, DbSchema.OAUTH_BORROWED_CLIENT_ID),
            )
        }
        // FTS 自己的事务，见上面 KDoc 的说明。
        initFtsLocked()
    }

    /**
     * 邮件搜索能否走 FTS5 三元组索引，对应 `fts_search_available()`。
     *
     * 还没探测过就先建一次（与 Python 一样：缓存未命中时顺手初始化）。
     * 不可用时搜索回落到 LIKE，其余功能不受影响。
     *
     * `messages` 表还不存在时（还没执行过 [initDb]）返回 false 但**不缓存**：那是「库还没
     * 建好」，不是「SQLite 不支持 FTS5」，缓存下来会让建好表之后也永远拿不到索引。
     */
    fun ftsSearchAvailable(): Boolean = lock.withLock {
        checkOpen()
        initFtsLocked()
    }

    /**
     * 读设置，对应 `get_setting()`。
     *
     * 键不存在时返回 [default]；键存在但值为 NULL 时也返回 [default]（Python 的
     * `row["v"] if row else default` 会把 NULL 原样返回，本机模式没有写 NULL 的路径，
     * 这里统一按「没设置」处理，避免调用方拿到 null 再崩在 `!!` 上）。
     */
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
     * - [reason] 按**码点**截到 500 个字符（Python 的 `reason[:500]` 按下标，emoji 只算一个，
     *   所以用 [cpSlice] 而不是 `String.take`）；
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
            reason.cpSlice(0, MAX_REASON_CHARS),
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

    private fun closeLocked() {
        if (closed) {
            return
        }
        closed = true
        txDepth = 0
        conn.close()
    }

    /**
     * 在 `txDepth > 0` 时确认连接真的还在事务里，否则按「连接不可信」处理。
     *
     * 为什么需要：SQLite 遇到磁盘满、`sqlite3_interrupt`、`ON CONFLICT ROLLBACK` 等情况会
     * **自动回滚整个事务**。如果调用方在 [transaction] 里捕获了这个异常继续执行，[txDepth]
     * 还是 1，后面的每条写入都会按 autocommit 逐条落盘，嵌套的 `SAVEPOINT` 还会另开一个
     * 事务——`sync.py` 就是逐封捕获后继续的写法，L7 之前必须在这里挡住。
     *
     * 用 `SQLiteConnection.inTransaction()`：androidx.sqlite 2.7.1 的打包驱动实现了它
     * （底层是 `sqlite3_get_autocommit`），可以直接问「连接当前是否在事务里」，不必靠捕获
     * `cannot rollback` / `no transaction is active` 之类的错误信息去猜，也不需要在每条
     * 语句后面额外试写一次。只要它说不在事务里，就关闭连接并抛
     * [IllegalStateException]；后续调用全部按「已关闭」失败。
     */
    private fun ensureUsableTransactionLocked() {
        if (txDepth == 0 || closed) {
            return
        }
        val inTransaction = try {
            conn.inTransaction()
        } catch (error: Throwable) {
            throw untrustedConnectionLocked("无法确认连接的事务状态（${error.message}）", error)
        }
        if (!inTransaction) {
            throw untrustedConnectionLocked(
                "SQLite 已经自动回滚了当前事务（例如磁盘满或 ON CONFLICT ROLLBACK）",
            )
        }
    }

    /** 关闭连接并按「连接不可信」抛出 [IllegalStateException]，附带关闭时的异常。 */
    private fun untrustedConnectionLocked(reason: String, cause: Throwable? = null): IllegalStateException {
        val error = IllegalStateException("$reason，连接状态不可信，已关闭：$path", cause)
        try {
            closeLocked()
        } catch (closeError: Throwable) {
            error.addSuppressed(closeError)
        }
        return error
    }

    /**
     * 外层事务：真的 `BEGIN` / `COMMIT`。回滚会一并撤销事务里建的 FTS 表与触发器，
     * 所以进入事务前的 FTS 缓存要在回滚时恢复（或置空重新探测），否则缓存会和库不一致。
     */
    private fun <T> outerTransactionLocked(block: () -> T): T {
        val ftsBefore = ftsAvailable
        stepLocked("BEGIN", emptyArray(), transactionControl = true)
        txDepth = 1
        try {
            val result = block()
            stepLocked("COMMIT", emptyArray(), transactionControl = true)
            return result
        } catch (throwable: Throwable) {
            ftsAvailable = ftsBefore
            rollbackOuterLocked(throwable)
            throw throwable
        } finally {
            txDepth = 0
        }
    }

    /**
     * 嵌套事务：用 `SAVEPOINT sp_N`。正常结束 `RELEASE`；抛异常时先 `ROLLBACK TO` 再
     * `RELEASE`，然后原样抛出——外层捕获后继续提交时，内层的写入已经被丢弃。
     */
    private fun <T> nestedTransactionLocked(block: () -> T): T {
        val depth = txDepth
        val ftsBefore = ftsAvailable
        val savepoint = SAVEPOINT_PREFIX + depth
        stepLocked("SAVEPOINT $savepoint", emptyArray(), transactionControl = true)
        txDepth = depth + 1
        try {
            val result = block()
            stepLocked("RELEASE $savepoint", emptyArray(), transactionControl = true)
            return result
        } catch (throwable: Throwable) {
            // 内层也可能建了 FTS，回滚内层同样要恢复缓存。
            ftsAvailable = ftsBefore
            rollbackToSavepointLocked(savepoint, throwable)
            throw throwable
        } finally {
            // 回滚失败时连接已经关了，层数没有意义，直接归零。
            txDepth = if (closed) 0 else depth
        }
    }

    private fun rollbackOuterLocked(original: Throwable) {
        if (closed) {
            // 连接已经被上一步关掉了（见 onRollbackFailure），没有可回滚的东西。
            return
        }
        try {
            stepLocked("ROLLBACK", emptyArray(), transactionControl = true)
        } catch (error: Throwable) {
            onRollbackFailure(original, error)
        }
    }

    private fun rollbackToSavepointLocked(savepoint: String, original: Throwable) {
        if (closed) {
            return
        }
        try {
            stepLocked("ROLLBACK TO $savepoint", emptyArray(), transactionControl = true)
        } catch (error: Throwable) {
            // 连 SAVEPOINT 都没了，RELEASE 也不会成功，别再做无用的尝试。
            onRollbackFailure(original, error)
            return
        }
        try {
            stepLocked("RELEASE $savepoint", emptyArray(), transactionControl = true)
        } catch (error: Throwable) {
            onRollbackFailure(original, error)
        }
    }

    /**
     * 回滚失败：把异常挂到原异常上（不吞掉），再判断连接还能不能信。
     *
     * 「no transaction is active」是 SQLite 已经自动回滚掉事务时的正常报告（例如
     * `INSERT OR ROLLBACK` 触发约束错误），层数计数照样会归零，连接可以继续用；其它错误
     * （例如 SAVEPOINT 已经不存在）说明我们对事务状态的认知和库不一致，必须立刻关连接，
     * 否则后续写入可能落在意料之外的事务里。
     */
    private fun onRollbackFailure(original: Throwable, error: Throwable) {
        original.addSuppressed(error)
        if (!(error.message ?: "").lowercase().contains(NO_TRANSACTION_ACTIVE)) {
            try {
                closeLocked()
            } catch (closeError: Throwable) {
                original.addSuppressed(closeError)
            }
        }
    }

    /** 建 FTS5 表与三个触发器；第一次建表后回填已有邮件（之后启动不再重复回填）。 */
    private fun initFtsLocked(): Boolean {
        ftsAvailable?.let { return it }
        if (!messagesTableExistsLocked()) {
            // 还没 initDb：不是「不支持 FTS5」，不能缓存。
            return false
        }
        return try {
            // 整段放进自己的事务／SAVEPOINT：任何一步失败都回滚，不留半截虚表或触发器。
            transaction { createFtsLocked() }
            ftsAvailable = true
            true
        } catch (e: RuntimeException) {
            if (!isSqliteError(e)) throw e
            // 与 Python 一致：只记一条 warning，不抛。低版本 SQLite 没有 FTS5 的 trigram
            // 分词器，建表时就会失败，搜索整体回落到 LIKE，其余功能不受影响。
            LOG.warning("SQLite 不支持 FTS5 trigram 索引，邮件搜索回落到 LIKE：${e.message}")
            ftsAvailable = false
            false
        }
    }

    private fun messagesTableExistsLocked(): Boolean = queryLocked(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='messages'",
        emptyArray(),
        limit = 1,
    ).isNotEmpty()

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
            // 首次建表：把已经在库里的邮件回填进索引。重开数据库时表已存在，不重复回填。
            stepLocked("INSERT INTO messages_fts(messages_fts) VALUES('rebuild')", emptyArray())
        }
    }

    private fun queryLocked(sql: String, args: Array<out Any?>, limit: Int = Int.MAX_VALUE): List<Row> {
        rejectTransactionControl(sql)
        ensureUsableTransactionLocked()
        checkParameterCount(sql, args)
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
        rejectTransactionControl(sql)
        stepLocked(sql, args)
        val row = queryLocked("SELECT changes(), last_insert_rowid()", emptyArray(), limit = 1).first()
        val changes = row.long(0) ?: 0L
        // changes == 0（例如 INSERT OR IGNORE 被忽略、UPDATE 没命中）时 last_insert_rowid()
        // 还是上一次插入留下的值，读出来会被当成「这条语句插入了 id=N」，一律返回 0。
        val lastInsertRowid = if (changes == 0L) 0L else (row.long(1) ?: 0L)
        return ExecResult(changes = changes, lastInsertRowid = lastInsertRowid)
    }

    /** 复用同一个预编译语句执行多行参数；调用方负责事务（见 [executeMany]）。 */
    private fun executeManyLocked(sql: String, rows: Iterable<Array<out Any?>>): Long {
        ensureUsableTransactionLocked()
        // 同一条 SQL 的占位符个数不变，只扫描一次；每行只比个数。
        val expected = countPlaceholders(sql)
        val stmt = conn.prepare(sql)
        try {
            // changes() 也只预编译一次：每行重新 prepare 一条一模一样的 SELECT 没有必要。
            val changesStmt = conn.prepare("SELECT changes()")
            try {
                var total = 0L
                for (args in rows) {
                    checkParameterCount(sql, expected, args.size)
                    bindAll(stmt, args)
                    while (stmt.step()) {
                        // executemany 只用于写语句，结果行丢弃：需要结果请用 query()。
                    }
                    check(changesStmt.step()) { "SELECT changes() 没有返回行" }
                    total += changesStmt.getLong(0)
                    changesStmt.reset()
                    stmt.reset()
                    stmt.clearBindings()
                }
                return total
            } finally {
                changesStmt.close()
            }
        } finally {
            stmt.close()
        }
    }

    /**
     * 执行一条语句并把它跑到结束，结果行丢弃（DDL 与 PRAGMA 没有结果行）。
     *
     * [transactionControl] 只给 [transaction] 自己的 `BEGIN` / `COMMIT` / `ROLLBACK` /
     * `SAVEPOINT` / `RELEASE` 用：这些语句本来就是改事务状态的，执行前不能要求「连接还在
     * 事务里」（`BEGIN` 之前就不在，自动回滚之后的 `ROLLBACK` 也不在）。
     */
    private fun stepLocked(
        sql: String,
        args: Array<out Any?>,
        transactionControl: Boolean = false,
    ) {
        if (!transactionControl) {
            ensureUsableTransactionLocked()
        }
        checkParameterCount(sql, args)
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

    /** 补列语句：只忽略「列已存在」，其余错误照常抛出（与 `init_db()` 的处理一致）。 */
    private fun stepIgnoringDuplicateColumn(sql: String) {
        try {
            stepLocked(sql, emptyArray())
        } catch (e: RuntimeException) {
            if (!isSqliteError(e) || !(e.message ?: "").lowercase().contains("duplicate column name")) {
                throw e
            }
        }
    }

    /**
     * 拦下裸的事务控制语句：层数计数由 [transaction] 维护，外面再写 `BEGIN` 会把状态搞乱
     * （`governance.py` 的 `BEGIN IMMEDIATE` 移植过来要用 [transaction]）。
     */
    private fun rejectTransactionControl(sql: String) {
        val keyword = leadingKeyword(sql) ?: return
        if (keyword.uppercase() in TRANSACTION_CONTROL) {
            throw IllegalArgumentException(
                "不要在 execute()/query() 里直接写 ${keyword.uppercase()}：请用 transaction {} 包起来，" +
                    "嵌套事务会自动用 SAVEPOINT。",
            )
        }
    }

    /**
     * 取 SQL 的第一个关键字（跳过开头空白、注释与空语句的分号）；第一条不是标识符时返回 null。
     *
     * 开头的 `;` 是空语句分隔符，不是关键字：`;COMMIT` 里的 `COMMIT` 照样要被识别出来，
     * 否则只靠 `prepare()` 报错就漏过了统一拦截。
     */
    private fun leadingKeyword(sql: String): String? {
        var index = skipTrivia(sql, 0)
        while (index < sql.length && sql[index] == ';') {
            index = skipTrivia(sql, index + 1)
        }
        if (index >= sql.length || !isIdentifierStart(sql[index])) {
            return null
        }
        val start = index
        while (index < sql.length && isIdentifierPart(sql[index])) {
            index++
        }
        return sql.substring(start, index)
    }

    /** 核对 `?` 占位符个数：Python 的 `sqlite3` 会抛 `ProgrammingError`，这里抛参数异常。 */
    private fun checkParameterCount(sql: String, args: Array<out Any?>) {
        checkParameterCount(sql, countPlaceholders(sql), args.size)
    }

    /** 同上，占位符个数已经由调用方扫描过（[executeMany] 每行复用同一个结果）。 */
    private fun checkParameterCount(sql: String, expected: Int, actual: Int) {
        if (expected != actual) {
            throw IllegalArgumentException(
                "SQL 需要 $expected 个 ? 占位符，实际传了 $actual 个参数（Python 的 sqlite3 会抛 " +
                    "ProgrammingError）：${sql.take(MAX_SQL_IN_ERROR)}",
            )
        }
    }

    /**
     * 绑参数，对应 Python 的 `?` 占位符。支持 `null`、`Boolean`（存成 0/1）、
     * `Byte` / `Short` / `Int` / `Long` / `BigInteger`、`Double`、`String`、`ByteArray`。
     *
     * 两个坑：
     * - **不要传 `Float`**：SQLite 只存 64 位浮点，Python 里也没有单精度浮点。这里为了宽容
     *   而接受 `Float` 并转成 `Double`，但 `0.1f.toDouble()` 是 0.10000000149011612，
     *   需要精确值时请直接传 `Double`。
     * - `Double.NaN` 会被 SQLite 存成 NULL（`sqlite3_bind_double` 的 NaN 落库即 NULL），
     *   读回来是 null 而不是 NaN。
     */
    private fun bindAll(stmt: SQLiteStatement, args: Array<out Any?>) {
        for (index in args.indices) {
            val position = index + 1
            when (val value = args[index]) {
                null -> stmt.bindNull(position)
                is Boolean -> stmt.bindLong(position, if (value) 1L else 0L)
                is Byte -> stmt.bindLong(position, value.toLong())
                is Short -> stmt.bindLong(position, value.toLong())
                is Int -> stmt.bindLong(position, value.toLong())
                is Long -> stmt.bindLong(position, value)
                is BigInteger -> stmt.bindLong(position, bigIntegerToLong(value, position))
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

    /** `BigInteger` 转 `Long`；超出 64 位整数范围时报错，对应 Python 的 `OverflowError`。 */
    private fun bigIntegerToLong(value: BigInteger, position: Int): Long {
        if (value.bitLength() > MAX_INTEGER_BITS) {
            throw IllegalArgumentException("参数超出 64 位整数范围（第 $position 个参数）：$value")
        }
        return value.toLong()
    }

    private companion object {
        /** `db.py` 里 `reason[:500]` 的 500。 */
        const val MAX_REASON_CHARS = 500

        /** SQLite INTEGER 是带符号 64 位：`bitLength() > 63` 就已经溢出。 */
        const val MAX_INTEGER_BITS = 63

        /** 参数个数不符的错误信息里最多带多长的 SQL（避免把整段脚本塞进日志）。 */
        const val MAX_SQL_IN_ERROR = 200

        /** 不准出现在 [execute] / [query] 里的事务控制关键字；`END` 是 `COMMIT` 的别名。 */
        val TRANSACTION_CONTROL = setOf("BEGIN", "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE")
    }
}

/** androidx.sqlite 报错时可能抛的类：桌面、Android 各一个。 */
private val SQLITE_ERROR_CLASSES = setOf("androidx.sqlite.SQLiteException", "android.database.SQLException")

/**
 * 这个异常是不是 SQLite 报的错。
 *
 * androidx.sqlite 在两个平台抛的类不同。`:core` 按 JVM 编译，字节码里的 `catch` 只认桌面那个类，
 * 在手机上接不住（`initDb` 的补列语句会直接崩），所以按类名判断，子类也算。
 * 都是 `RuntimeException`，调用方先接 `RuntimeException` 再用这里判断。
 */
internal fun isSqliteError(e: Throwable): Boolean =
    generateSequence<Class<*>>(e.javaClass) { it.superclass }.any { it.name in SQLITE_ERROR_CLASSES }

/** 写语句的执行结果，对应 `cursor.rowcount` 与 `cursor.lastrowid`。 */
data class ExecResult(
    /**
     * 影响的行数。非 DML 语句（DDL、PRAGMA）没有意义：SQLite 的 `changes()` 不改，
     * Python 的 `cursor.rowcount` 是 -1。
     */
    val changes: Long,
    /**
     * 自增 id。`changes == 0` 时一律是 0：SQLite 的 `last_insert_rowid()` 此时返回的是
     * **上一次**插入的 id（`INSERT OR IGNORE` 被忽略时尤其容易被误读成「刚插入了 id=N」）。
     */
    val lastInsertRowid: Long,
)

/**
 * 一行查询结果，对应 Python `sqlite3.Row`。
 *
 * `row["col"]` 按列名取值，返回 `Long`（INTEGER）/ `Double`（REAL）/ `String`（TEXT）/
 * `ByteArray`（BLOB）/ `null`（NULL），与 Python 驱动给的类型一致；列名不存在时抛
 * [NoSuchElementException]（Python 抛 `IndexError`）。列名匹配不区分大小写，与
 * `sqlite3.Row` 一致：先按原样找，找不到再忽略大小写找。另提供 [str]、[long]、[int]、
 * [double]、[boolean]、[bytes] 这些便捷取值，NULL 时返回 null，以及对应 `dict(row)` 的 [toMap]。
 *
 * **整数列一律用 [long]。** IMAP 的 UID 是无符号 32 位（最大 4294967295），`uidvalidity`
 * 也是 32 位无符号，两者都可能超过 [Int.MAX_VALUE]；`messages.id`、`accounts.id`、
 * `audit_log.id` 是 SQLite 的 64 位自增 id。所以 `id`、`uid`、`uidvalidity` 这些列请用
 * [long] 取，[int] 只用于确定不会越界的计数类列，越界时它抛异常而不是静默截断。
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

    /**
     * 转成 `Map`，对应 Python 的 `dict(row)`：按**列顺序**插入。
     *
     * `dict(row)` 走的是 `keys()` 加 `row[key]`，重复的列名每次取到的都是**第一列**
     * （`row["a"]` 也如此），所以同名列以第一次出现的值为准，后面的不覆盖。
     */
    fun toMap(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>(names.size * 2)
        for (index in names.indices) {
            val name = names[index]
            if (!map.containsKey(name)) {
                map[name] = values[index]
            }
        }
        return map
    }

    /** 取值并转成字符串；NULL 返回 null。 */
    fun str(column: String): String? = get(column)?.toString()

    /** 取值并转成 Long；NULL 返回 null。整数列（`id`、`uid`、`uidvalidity`）一律用它。 */
    fun long(column: String): Long? = asLong(column, get(column))

    /** 按列下标取 Long，对应 Python 的 `row[0]` 之后自己转（`SELECT changes()` 用得上）。 */
    fun long(index: Int): Long? = asLong("列 $index", values[index])

    /** 取值并转成 Int；NULL 返回 null，超出 Int 范围时抛 [ArithmeticException]。 */
    fun int(column: String): Int? = long(column)?.let { Math.toIntExact(it) }

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

/** SQLite 里标识符可以以字母、`_`、`$`、`:`、`@` 开头（后三个是 TCL 风格的参数前缀）。 */
private fun isIdentifierStart(c: Char): Boolean =
    c.isLetter() || c == '_' || c == '$' || c == ':' || c == '@'

private fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || isIdentifierStart(c)
