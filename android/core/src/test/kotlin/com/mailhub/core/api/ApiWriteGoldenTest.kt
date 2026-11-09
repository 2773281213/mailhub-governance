package com.mailhub.core.api

import com.mailhub.core.db.Db
import com.mailhub.core.db.FakeClock
import com.mailhub.core.db.withTempDb
import com.mailhub.core.golden.Golden
import com.mailhub.core.golden.GoldenCase
import com.mailhub.core.golden.str
import com.mailhub.core.golden.strOrNull
import com.mailhub.core.imap.ImapException
import com.mailhub.core.sync.ServerActionResult
import com.mailhub.core.sync.SyncAccount
import jakarta.mail.AuthenticationFailedException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.locks.ReentrantLock

/**
 * `LocalApi` 写接口的金标回放：`scripts/golden/api_write.py` 用 FastAPI 的 TestClient 在真服务器上
 * 跑同一份种子与同一串请求，这里逐条比较状态码、关心的响应头、响应 JSON、外部调用记录与
 * 请求之后的数据库状态。
 *
 * 回放方式与 [ApiReadGoldenTest] 相同：种子、请求序列与期望值都由脚本写进
 * `golden/api_write.json`，Kotlin 只负责按同样的顺序重放。**顺序不能变**：用例之间有依赖
 * （例如先改账户 1 的授权码，后面的「测试连接」才会解密出凭据）。
 *
 * 外部依赖在 Python 侧打桩、在 Kotlin 侧用 [RecordingSyncBackend] 顶替：
 * - 探测连接（`oauth_auth.probe_connection`）：按用例成功或抛指定异常。添加 / 修改账户的
 *   400 文案走 [com.mailhub.core.imap.connectionErrorText]，所以这一组桩抛的是文本已经等于
 *   中文说明的 [ImapException]；「测试连接」走的是按异常类型映射的那条路径，桩抛真实类型的
 *   异常（拒绝连接 / 认证失败 / 超时 / IO），两侧映射结果一致；
 * - 触发同步（`sync.engine.trigger` / `trigger_all`）、忘记账户（`sync.forget_account`）、
 *   服务器动作（`sync.apply_server_action`）：记录调用并按用例返回。
 *
 * 数据库状态（`accounts`、`messages`、`audit_log` 与相关的 `settings` 键）逐条比较；
 * 加密列只比「是否非空」，不比密文——两侧的加密实现本来就不同。
 */
class ApiWriteGoldenTest {

    @Test
    fun replaysServerWriteRoutes() {
        val seed = ApiSeed.load("write_seed")
        val cases = Golden.load("api_write")
        var requests = 0
        var dbStates = 0
        withTempDb(FakeClock(seed.now)) { db ->
            seedDatabase(db, seed)
            // 对应脚本建库之后的「网页方式登录」：服务器会把管理密码的哈希写进 settings，
            // 本机模式没有管理密码，这里只补一个非空占位值，让 db_state 的 settings 键集合一致
            db.setSetting("admin_hash", LOCAL_MODE_ADMIN_HASH_PLACEHOLDER)
            val backend = RecordingSyncBackend(db)
            val api = apiFor(db, seed, sync = backend)
            Golden.replay("api_write", cases) { case ->
                if (case.fn == "request") requests++
                if (case.fn == "db_state") dbStates++
                replay(api, db, backend, case)
            }
        }
        // 用例数变少说明脚本没有导出全，防止金标被悄悄削小
        assertTrue("request 用例太少：$requests", requests >= 107)
        assertEquals("db_state 用例要恰好五条", 5, dbStates)
    }

    /**
     * 回放器自检：数字按值比较（`3` 与 `3.0` 相等），但类型与结构必须一致。
     * 没有这条，比较器写错时会静默通过。
     */
    @Test
    fun jsonComparisonAcceptsEqualNumbersButNotTypeConfusion() {
        assertNull(compare(JsonPrimitive(3), JsonPrimitive(3.0), ""))
        assertNotNull(compare(JsonPrimitive(3), JsonPrimitive("3"), ""))
        assertNotNull(compare(JsonPrimitive(true), JsonPrimitive(1), ""))
        assertNotNull(
            compare(
                Json.parseToJsonElement("""{"a":1}"""),
                Json.parseToJsonElement("""{"a":1,"b":2}"""),
                "",
            ),
        )
    }

    /** 回放一条用例；返回 null 表示与 Python 一致。 */
    private fun replay(
        api: LocalApi,
        db: Db,
        backend: RecordingSyncBackend,
        case: GoldenCase,
    ): String? = when (case.fn) {
        "request" -> replayRequest(api, backend, case)
        "settings" -> applySettings(db, case)
        "db_state" -> compare(case.expected, exportDbState(db), "")
        else -> "未知的用例类型 ${case.fn}"
    }

    private fun replayRequest(
        api: LocalApi,
        backend: RecordingSyncBackend,
        case: GoldenCase,
    ): String? {
        val stubs = case.args["stubs"]?.jsonObject
        backend.reset(
            probe = stubs?.strOrNull("probe") ?: "ok",
            serverAction = stubs?.strOrNull("server_action") ?: "all_ok",
        )
        // 请求体：`null` 表示不带 body（对应脚本里的 `body=None`）
        val raw = case.args["body"]
        val bodyText = if (raw == null || raw is JsonNull) null else raw.toString()
        val request = LocalRequest(
            method = case.args.str("method"),
            path = case.args.str("path"),
            body = bodyText,
        )
        // busy 用例：脚本里手动占住进程内的治理运行锁，这里同样把锁拿到手再发请求
        val response = if (case.id == GOVERNANCE_BUSY_CASE) {
            withGovernanceRunLock { api.handle(request) }
        } else {
            api.handle(request)
        }

        val expected = case.expected.jsonObject
        val differences = ArrayList<String>(4)
        val status = expected.getValue("status").jsonPrimitive.int
        if (status != response.status) {
            differences.add("状态码期望 $status，实际 ${response.status}（响应体 ${response.body}）")
        }
        for ((name, value) in expected["headers"]?.jsonObject ?: JsonObject(emptyMap())) {
            val wanted = value.jsonPrimitive.content
            val actual = response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            if (actual == null || actual.value != wanted) {
                differences.add("响应头 $name 期望「$wanted」，实际「${actual?.value}」")
            }
        }
        compare(expected.getValue("json"), response.body, "")?.let { differences.add(it) }
        compare(expected.getValue("calls"), jsonOf(backend.calls), "外部调用记录")
            ?.let { differences.add(it) }
        return differences.takeIf { it.isNotEmpty() }?.joinToString("；")
    }

    /** 对应脚本里的 `settings` 动作：直接写 settings 表，两侧用同一组键值。 */
    private fun applySettings(db: Db, case: GoldenCase): String? {
        val settings = case.args["settings"]?.jsonObject ?: return "用例缺少 args.settings"
        for ((key, value) in settings) {
            db.setSetting(key, value.jsonPrimitive.content)
        }
        return null
    }

    /**
     * 导出请求之后的库状态，字段与 `api_write.py` 的 `_export_db` 逐字对应：
     * 加密列只记「是否非空」，`target_ids` / `undo_payload` 解析成 JSON 值。
     */
    private fun exportDbState(db: Db): JsonElement {
        val accounts = db.query(
            """SELECT id, name, provider, email, imap_host, imap_port, auth_type, secret_enc,
                      oauth_refresh_enc, oauth_expires, enabled, poll_interval, last_uid,
                      uidvalidity, last_error, color, created_ts
               FROM accounts ORDER BY id""",
        ).map { row ->
            linkedMapOf<String, Any?>(
                "id" to row.long("id"),
                "name" to row.str("name"),
                "provider" to row.str("provider"),
                "email" to row.str("email"),
                "imap_host" to row.str("imap_host"),
                "imap_port" to row.long("imap_port"),
                "auth_type" to row.str("auth_type"),
                "secret_set" to ((row.str("secret_enc") ?: "").isNotEmpty() ||
                    (row.str("oauth_refresh_enc") ?: "").isNotEmpty()),
                "oauth_expires" to row.double("oauth_expires"),
                "enabled" to row.long("enabled"),
                "poll_interval" to row.long("poll_interval"),
                "last_uid" to row.long("last_uid"),
                "uidvalidity" to row.long("uidvalidity"),
                "last_error" to row.str("last_error"),
                "color" to row.str("color"),
                "created_ts" to row.double("created_ts"),
            )
        }
        val messages = db.query(
            """SELECT id, account_id, uid, category, bill_tier, unread, deleted_ts, flag_push
               FROM messages ORDER BY id""",
        ).map { row ->
            linkedMapOf<String, Any?>(
                "id" to row.long("id"),
                "account_id" to row.long("account_id"),
                "uid" to row.long("uid"),
                "category" to row.str("category"),
                "bill_tier" to row.str("bill_tier"),
                "unread" to row.long("unread"),
                "deleted_ts" to row.double("deleted_ts"),
                "flag_push" to row.long("flag_push"),
            )
        }
        val audit = db.query(
            """SELECT id, ts, actor, action, tier, target_count, target_ids, reason,
                      allowed, reversible, undone, undo_payload
               FROM audit_log ORDER BY id""",
        ).map { row ->
            linkedMapOf<String, Any?>(
                "id" to row.long("id"),
                "ts" to row.double("ts"),
                "actor" to row.str("actor"),
                "action" to row.str("action"),
                "tier" to row.str("tier"),
                "target_count" to row.long("target_count"),
                "target_ids" to parseJsonOrEmptyList(row.str("target_ids")),
                "reason" to row.str("reason"),
                "allowed" to row.long("allowed"),
                "reversible" to row.long("reversible"),
                "undone" to row.long("undone"),
                "undo_payload" to parseOptionalJson(row.str("undo_payload")),
            )
        }
        val stored = LinkedHashMap<String, String>()
        for (row in db.query("SELECT k, v FROM settings")) {
            stored[row.str("k") ?: ""] = row.str("v") ?: ""
        }
        val settings = LinkedHashMap<String, Any?>()
        // 键的顺序与脚本里的 SETTINGS_KEYS 一致，只导出库里真的有值的那些
        for (key in SETTINGS_KEYS) {
            val value = stored[key] ?: continue
            settings[key] = if (key in SECRET_SETTING_KEYS) value.isNotEmpty() else value
        }
        return jsonOf(
            linkedMapOf<String, Any?>(
                "accounts" to accounts,
                "messages" to messages,
                "audit" to audit,
                "settings" to settings,
            ),
        )
    }

    /** `target_ids` 是 JSON 文本；写坏或为空时按空列表处理（脚本用 `or "[]"` 兜底）。 */
    private fun parseJsonOrEmptyList(text: String?): JsonElement =
        parseOptionalJson(text) ?: JsonArray(emptyList())

    /** 把库里的 JSON 文本解析成 JSON 值；空串与空值返回 null（对应脚本里的 `None`）。 */
    private fun parseOptionalJson(text: String?): JsonElement? {
        if (text.isNullOrEmpty()) return null
        return runCatching { Json.parseToJsonElement(text) }.getOrNull()
    }

    /**
     * 拿到 `GovernanceService` 的进程内运行锁并锁住它，模拟「另一个线程正在治理」。
     *
     * 对应脚本里直接 `governance._RUN_LOCK.acquire()`：Python 的 `threading.Lock` 不可重入，
     * 同一线程再次进入也会返回 `busy`，所以本机这边也由**发起请求的那条线程**持锁。
     * 锁是 `GovernanceService` 的私有实现细节，测试只能反射取它。
     */
    private fun <T> withGovernanceRunLock(block: () -> T): T {
        val lock = governanceRunLock()
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private fun governanceRunLock(): ReentrantLock {
        val type = Class.forName("com.mailhub.core.governance.GovernanceService")
        val field = type.getDeclaredField("RUN_LOCK")
        field.isAccessible = true
        return field.get(null) as ReentrantLock
    }

    /** 递归比较两个 JSON；[path] 只用于把差异定位到具体的字段。 */
    private fun compare(expected: JsonElement, actual: JsonElement, path: String): String? = when {
        expected is JsonNull && actual is JsonNull -> null
        expected is JsonObject && actual is JsonObject -> compareObjects(expected, actual, path)
        expected is JsonArray && actual is JsonArray -> compareArrays(expected, actual, path)
        expected is JsonPrimitive && actual is JsonPrimitive ->
            comparePrimitives(expected, actual, path)
        else -> mismatch(path, expected, actual)
    }

    private fun compareObjects(expected: JsonObject, actual: JsonObject, path: String): String? {
        val missing = expected.keys - actual.keys
        val extra = actual.keys - expected.keys
        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            return "$path 的键不一致：缺少 ${missing.sorted()}，多出 ${extra.sorted()}"
        }
        for ((key, value) in expected) {
            compare(value, actual.getValue(key), "$path.$key")?.let { return it }
        }
        return null
    }

    private fun compareArrays(expected: JsonArray, actual: JsonArray, path: String): String? {
        if (expected.size != actual.size) {
            return "$path 的长度期望 ${expected.size}，实际 ${actual.size}"
        }
        for (index in expected.indices) {
            compare(expected[index], actual[index], "$path[$index]")?.let { return it }
        }
        return null
    }

    private fun comparePrimitives(
        expected: JsonPrimitive,
        actual: JsonPrimitive,
        path: String,
    ): String? {
        if (expected.isString != actual.isString) return mismatch(path, expected, actual)
        if (expected.isString) {
            return if (expected.content == actual.content) null else mismatch(path, expected, actual)
        }
        val expectedBool = expected.booleanOrNull
        if (expectedBool != null) {
            return if (actual.booleanOrNull == expectedBool) null else mismatch(path, expected, actual)
        }
        val left = expected.content.toBigDecimalOrNull() ?: return mismatch(path, expected, actual)
        val right = actual.content.toBigDecimalOrNull() ?: return mismatch(path, expected, actual)
        return if (left.compareTo(right) == 0) null else mismatch(path, expected, actual)
    }

    private fun mismatch(path: String, expected: JsonElement, actual: JsonElement): String =
        "${path.ifEmpty { "响应体" }} 期望 $expected，实际 $actual"

    private companion object {

        /** 需要占住治理运行锁的用例 id（脚本里手动 acquire 的那一条）。 */
        const val GOVERNANCE_BUSY_CASE = "governance_run_busy"

        /** 只在测试库里用：对应脚本登录时写进 settings 的管理密码哈希。 */
        const val LOCAL_MODE_ADMIN_HASH_PLACEHOLDER = "local-mode-no-admin-password"

        /** 与 `api_write.py` 的 `SETTINGS_KEYS` 一致。 */
        val SETTINGS_KEYS = listOf(
            "ai_enabled", "ai_base_url", "ai_model", "ai_send_body", "ai_scope",
            "ai_backfill_days", "clean_otp_days", "clean_server", "clean_bill_minor_days",
            "sync_read_state", "digest_hour", "notify_important", "notify_otp", "body_keep_days",
            "bill_amount_threshold", "bill_routine_count", "bill_auto_renew", "bill_refund",
            "notify_tg_chat", "notify_bark_url_enc", "notify_bark_url", "notify_tg_token_enc",
            "notify_tg_token", "notify_onepush_enc", "ai_key_enc", "admin_hash",
            "google_oauth_client_id", "google_oauth_client_secret_enc",
            "microsoft_oauth_client_id", "microsoft_oauth_client_secret_enc",
        )

        /** 与 `api_write.py` 的 `SECRET_KEYS` 一致：只比较是否非空。 */
        val SECRET_SETTING_KEYS = setOf(
            "admin_hash", "ai_key_enc", "notify_bark_url_enc", "notify_bark_url",
            "notify_tg_token_enc", "notify_tg_token", "notify_onepush_enc",
            "google_oauth_client_secret_enc", "microsoft_oauth_client_secret_enc",
        )
    }
}

/**
 * 金标回放用的假 [SyncBackend]：记录每次调用（内容与 Python 侧的桩记录逐字对应），
 * 并按当前用例的桩模式成功、抛指定异常或返回部分成功。
 *
 * @param db 用来回答 `trigger_all` 需要的那句「启用中的账户 id」——与 Python 桩里的
 *   `SELECT id FROM accounts WHERE enabled=1` 相同。
 */
private class RecordingSyncBackend(private val db: Db) : SyncBackend {

    /** 当前用例的探测模式：`ok` 或一种失败模式。 */
    private var probeMode: String = "ok"

    /** 当前用例的服务器动作模式：`all_ok` / `partial` / `all_fail`。 */
    private var serverActionMode: String = "all_ok"

    /** 本次请求里外部依赖被怎么调用的（调用记录里不含凭据明文）。 */
    val calls = ArrayList<Map<String, Any?>>()

    fun reset(probe: String, serverAction: String) {
        probeMode = probe
        serverActionMode = serverAction
        calls.clear()
    }

    override fun triggerAccount(id: Int) {
        calls.add(linkedMapOf("kind" to "trigger", "id" to id))
    }

    override fun triggerAll() {
        val ids = db.query("SELECT id FROM accounts WHERE enabled=1")
            .mapNotNull { it.long("id")?.toInt() }
            .sorted()
        calls.add(linkedMapOf("kind" to "trigger_all", "ids" to ids))
    }

    override fun forgetAccount(id: Int) {
        calls.add(linkedMapOf("kind" to "forget", "id" to id))
    }

    override fun probe(account: SyncAccount, credential: String) {
        calls.add(
            linkedMapOf(
                "kind" to "probe",
                "provider" to account.provider,
                "email" to account.email,
                "host" to account.host,
                "port" to account.port,
                "auth_type" to account.authType,
                "credential_set" to credential.isNotEmpty(),
            ),
        )
        throwProbeFailure(probeMode)
    }

    override fun applyServerAction(ids: List<Long>, action: String): ServerActionResult {
        calls.add(
            linkedMapOf(
                "kind" to "apply_server_action",
                "action" to action,
                "ids" to ids,
            ),
        )
        return when (serverActionMode) {
            "all_ok" -> ServerActionResult(ids, emptyList())
            // 与脚本一样：少最后一封算成功，错误说明固定一句
            "partial" -> ServerActionResult(ids.dropLast(1), listOf(SERVER_ERROR))
            "all_fail" -> ServerActionResult(emptyList(), listOf(SERVER_ERROR))
            else -> error("未知的服务器动作模式 $serverActionMode")
        }
    }

    /**
     * 按模式抛异常。
     *
     * `text_*` 是「添加 / 修改账户」那条路径：服务器把 `str(exc)` 原样写进 400 的 detail，
     * Kotlin 换成了 [com.mailhub.core.imap.connectionErrorText] 的中文映射，所以这里抛的
     * [ImapException] 文本就是映射结果（`connectionErrorText` 对项目自己的异常原样返回）。
     * 其余模式是「测试连接」那条路径：抛真实异常类型，由 `connectionErrorText` 按类型映射。
     */
    private fun throwProbeFailure(mode: String) {
        when (mode) {
            "ok" -> Unit
            "text_refused" -> throw ImapException(TEXT_REFUSED)
            "text_auth" -> throw ImapException(TEXT_AUTH)
            "text_timeout" -> throw ImapException(TEXT_TIMEOUT)
            "refused" -> throw ConnectException("Connection refused")
            "auth" -> throw AuthenticationFailedException("登录被拒绝")
            "timeout" -> throw SocketTimeoutException("timed out")
            "io" -> throw IOException("unexpected EOF")
            else -> error("未知的探测模式 $mode")
        }
    }

    private companion object {

        /** 与 `connectionErrorText` 对拒绝连接的映射一致。 */
        const val TEXT_REFUSED = "邮箱服务器拒绝连接，请检查服务器地址和端口"

        /** 带 LOGIN 的文本让 `isAuthFailure` 为真，与 Python 侧的原始异常相同。 */
        const val TEXT_AUTH = "邮箱服务器拒绝登录（LOGIN failed），请检查授权码或密码"

        /** 与 `connectionErrorText` 对超时的映射一致。 */
        const val TEXT_TIMEOUT = "连接邮箱服务器超时"

        /** 服务器动作失败时脚本给出的固定错误说明。 */
        const val SERVER_ERROR = "账户1: 邮件服务器拒绝连接"
    }
}
