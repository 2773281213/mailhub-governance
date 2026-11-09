package com.mailhub.core.api

import com.mailhub.core.db.Row
import com.mailhub.core.db.isSqliteError
import com.mailhub.core.imap.connectionErrorText
import com.mailhub.core.imap.isAuthFailure
import com.mailhub.core.providers.APP_PASSWORD
import com.mailhub.core.providers.detect
import com.mailhub.core.providers.get
import com.mailhub.core.rules.Policy
import com.mailhub.core.sync.INITIAL_IMPORT_KEY_PREFIX
import com.mailhub.core.sync.SyncAccount
import com.mailhub.core.sync.resolveSecret
import com.mailhub.core.text.Py
import com.mailhub.core.text.PyRegex
import com.mailhub.core.text.cpSlice
import com.mailhub.core.text.pyStrip
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Locale
import java.util.logging.Logger

/**
 * `LocalApi` 的写接口，逐路由对照 `app.py`（函数名写在各自的 KDoc 里）。
 *
 * 与读接口一样：**服务器就是规格**，状态码、响应头、`{"detail": ...}` 文本都必须一致。
 * 本机语义不同的地方（见 `LOCAL_MODE.md` 附录 A）在文件末尾，由 `LocalApiWriteTest` 钉死。
 *
 * 线程模型：这些函数都在 OkHttp 的工作线程上**阻塞**执行。触发同步与回放服务器动作交给
 * 注入的 [SyncBackend]；[SyncBackend] 的调用一律在 `Db.transaction` 之外（同步会在另一条线程
 * 上去抢同一把数据库锁，事务里等它必然死锁）。
 */

private val log: Logger = Logger.getLogger("com.mailhub.core.api.LocalApiWrite")

/** 邮箱地址，对应 `oauth_auth._EMAIL_RE`：`^[^@\s]{1,64}@[^@\s]{1,189}$`。 */
private val EMAIL_RE = PyRegex.compile("^[^@\\s]{1,64}@[^@\\s]{1,189}$")

/** 账户颜色，对应 `oauth_auth._COLOR_RE`：`^#[0-9a-fA-F]{6}$`。 */
private val COLOR_RE = PyRegex.compile("^#[0-9a-fA-F]{6}$")

/** 添加账户时重复邮箱的提示，对应 `create_account` 里的 409。 */
private const val DUPLICATE_ACCOUNT = "这个邮箱已经添加过了，请在账户列表里编辑或重新授权"

/** 批量接口一次最多处理多少封，对应 `body.ids[:500]`。 */
private const val BATCH_MAX_IDS = 500

/** 撤销软删除时最多恢复多少封，对应 `payload.get("ids", [])[:2000]`。 */
private const val UNDO_MAX_IDS = 2000

/** `/api/accounts` 的请求体字段，对应 `AccountBody`。 */
private val ACCOUNT_FIELDS: List<BodyField> = listOf(
    BodyField("name", BodyType.STRING, default = ""),
    BodyField("provider", BodyType.STRING, default = "auto"),
    BodyField("email", BodyType.STRING, required = true),
    BodyField("secret", BodyType.STRING, default = ""),
    BodyField("imap_host", BodyType.STRING, default = ""),
    BodyField("imap_port", BodyType.INT, default = 993L),
    BodyField("poll_interval", BodyType.INT, default = 300L),
    BodyField("color", BodyType.STRING, default = "#38bdf8"),
    BodyField("enabled", BodyType.BOOL, default = true),
)

/** `/api/messages/batch` 的请求体字段，对应 `BatchBody`。 */
private val BATCH_FIELDS: List<BodyField> = listOf(
    BodyField("ids", BodyType.INT_LIST, required = true),
    BodyField("action", BodyType.STRING, required = true),
    BodyField("server", BodyType.BOOL, default = false),
    BodyField("confirmed", BodyType.BOOL, default = false),
    BodyField("override_protection", BodyType.BOOL, default = false),
)

/** `/api/trash/restore` 的请求体字段，对应 `IdsBody`。 */
private val IDS_FIELDS: List<BodyField> = listOf(
    BodyField("ids", BodyType.INT_LIST, required = true),
)

/**
 * `/api/governance/run` 的请求体字段，对应 `GovernanceRunBody`
 * （`days: int = Field(0, ge=0, le=90)`）。
 */
private val GOVERNANCE_RUN_FIELDS: List<BodyField> = listOf(
    BodyField("days", BodyType.INT, default = 0L, ge = 0L, le = 90L),
)

/**
 * `/api/settings` 的请求体字段，对应 `SettingsBody` 的全部字段（顺序与 `app.py` 相同，
 * 422 的 `detail` 顺序依赖它）。
 *
 * `SettingsBody` 的每个字段都是 `X | None = None`，所以它们**都可空**：显式传 `null` 表示
 * 「不修改这一项」，与字段缺失等价；传成别的类型才是 422。
 *
 * 凭据类字段在本机模式下一律 400（见 [putSettingsRoute] 的 KDoc），但仍然写进声明里：
 * 先按 pydantic 的规则解析类型，类型不对时回 422 而不是 400——与服务器一致。
 */
private val SETTINGS_FIELDS: List<BodyField> = listOf(
    "ai_enabled" to BodyType.BOOL,
    "ai_base_url" to BodyType.STRING,
    "ai_key" to BodyType.STRING,
    "ai_model" to BodyType.STRING,
    "ai_send_body" to BodyType.BOOL,
    "ai_scope" to BodyType.STRING,
    "ai_backfill_days" to BodyType.INT,
    "clean_otp_days" to BodyType.INT,
    "clean_server" to BodyType.BOOL,
    "clean_bill_minor_days" to BodyType.INT,
    "sync_read_state" to BodyType.BOOL,
    "bill_amount_threshold" to BodyType.FLOAT,
    "bill_routine_count" to BodyType.INT,
    "bill_auto_renew" to BodyType.STRING,
    "bill_refund" to BodyType.STRING,
    "digest_hour" to BodyType.INT,
    "old_password" to BodyType.STRING,
    "new_password" to BodyType.STRING,
    "notify_bark_url" to BodyType.STRING,
    "clear_notify_bark" to BodyType.BOOL,
    "notify_tg_token" to BodyType.STRING,
    "clear_notify_tg_token" to BodyType.BOOL,
    "notify_tg_chat" to BodyType.STRING,
    "notify_onepush_config" to BodyType.STRING,
    "clear_notify_onepush" to BodyType.BOOL,
    "notify_important" to BodyType.BOOL,
    "notify_otp" to BodyType.BOOL,
    "body_keep_days" to BodyType.INT,
    "google_oauth_client_id" to BodyType.STRING,
    "google_oauth_client_secret" to BodyType.STRING,
    "clear_google_oauth_client_secret" to BodyType.BOOL,
    "microsoft_oauth_client_id" to BodyType.STRING,
    "microsoft_oauth_client_secret" to BodyType.STRING,
    "clear_microsoft_oauth_client_secret" to BodyType.BOOL,
).map { (name, type) -> BodyField(name, type, nullable = true) }

/** 本机模式不提供的 OAuth 登录入口的统一说明（附录 A）。 */
private const val LOCAL_OAUTH_UNSUPPORTED = "本机模式暂不支持 OAuth 登录"

// ---------------- 账户 ----------------

/** 一次账户请求的可变工作副本，对应 `_normalize_account_body` 就地改写 pydantic 模型。 */
private class AccountBody(
    var name: String,
    var provider: String,
    var email: String,
    val secret: String,
    val imapHost: String,
    val imapPort: Long,
    var pollInterval: Long,
    val color: String,
    val enabled: Boolean,
)

private fun accountBody(call: Call): AccountBody {
    val body = parseBody(call.json(), ACCOUNT_FIELDS)
    return AccountBody(
        name = body.str("name"),
        provider = body.str("provider"),
        email = body.str("email"),
        secret = body.str("secret"),
        imapHost = body.str("imap_host"),
        imapPort = body.long("imap_port"),
        pollInterval = body.long("poll_interval"),
        color = body.str("color"),
        enabled = body.bool("enabled"),
    )
}

/**
 * 对应 `_normalize_account_body` + `oauth_auth.normalize_account_identity`：
 * 识别服务商、校验邮箱格式，并把 `poll_interval` 夹到 60～86400 秒。
 *
 * 服务商与邮箱域名对不上时拒绝：用户把 QQ 邮箱填成 Gmail 时，让服务商表去猜主机名必然连不上。
 */
private fun normalizeAccountBody(body: AccountBody) {
    var requested = (if (body.provider.isEmpty()) "auto" else body.provider).pyStrip()
        .lowercase(Locale.ROOT)
    val normalizedEmail = body.email.pyStrip().lowercase(Locale.ROOT)
    val detected = detect(normalizedEmail)
    if (requested == "auto") {
        requested = detected
    } else if (detected != "custom" && requested != detected) {
        val label = get(detected)?.label ?: detected
        throw ApiException(
            400,
            "邮箱域名已识别为「$label」，请使用自动识别或选择匹配的服务商",
        )
    }
    val provider = requested.pyStrip().lowercase(Locale.ROOT)
    if (get(provider) == null) {
        throw ApiException(400, "不支持的邮箱服务商")
    }
    if (!EMAIL_RE.matcher(normalizedEmail).matches()) {
        throw ApiException(400, "邮箱地址格式不正确")
    }
    body.provider = provider
    body.email = normalizedEmail
    val poll = if (body.pollInterval == 0L) DEFAULT_POLL_INTERVAL else body.pollInterval
    body.pollInterval = minOf(86400L, maxOf(60L, poll))
}

/** 对应 `_require_secret_method`：只支持 OAuth 的服务商不能用授权码添加。 */
private fun requireSecretMethod(provider: String) {
    val meta = get(provider)
    if (meta != null && meta.secret == null) {
        throw ApiException(400, "${meta.label} 只能通过 OAuth 登录添加，不支持授权码或密码")
    }
}

/** 对应 `_resolve_host`：自定义邮箱用用户填的主机，其他用服务商表里的。 */
private fun resolveHost(body: AccountBody): Pair<String, Long> {
    val meta = get(body.provider)
    val host: String
    val port: Long
    if (body.provider == "custom" || meta == null) {
        host = body.imapHost.pyStrip()
        port = if (body.imapPort == 0L) DEFAULT_IMAP_PORT else body.imapPort
    } else {
        host = meta.hostFor("password")
        port = meta.imapPort.toLong()
    }
    if (host.isEmpty()) {
        throw ApiException(400, "缺少 IMAP 服务器地址")
    }
    return host to port
}

/**
 * 对应 `_resolve_update_host`：OAuth 账户不许直接改服务商与邮箱；改登录信息必须同时给凭据。
 */
private fun resolveUpdateHost(row: Row, body: AccountBody): Pair<String, Long> {
    val rowAuthType = row.str("auth_type") ?: ""
    val rowEmail = (row.str("email") ?: "").pyStrip().lowercase(Locale.ROOT)
    if (rowAuthType == "oauth" && body.secret.isEmpty()) {
        val sameIdentity = body.provider == (row.str("provider") ?: "") && body.email == rowEmail
        if (!sameIdentity) {
            throw ApiException(400, "OAuth 账户的服务商和邮箱地址不能直接修改，请重新登录")
        }
        return (row.str("imap_host") ?: "") to (row.long("imap_port") ?: DEFAULT_IMAP_PORT)
    }
    val (host, port) = resolveHost(body)
    val changedLogin = body.provider != (row.str("provider") ?: "") ||
        body.email != rowEmail ||
        host != (row.str("imap_host") ?: "") ||
        port != (row.long("imap_port") ?: 0L)
    if (changedLogin && body.secret.isEmpty()) {
        throw ApiException(400, "修改邮箱登录信息时必须重新填写凭据")
    }
    return host to port
}

/**
 * 对应 `_probe_password_account`：填了凭据就先连一次，连不上直接 400。
 *
 * 说明（与任务描述的偏差，见总结）：服务器这里把 `str(exc)` 原样拼进 detail，
 * 本机模式改用 [connectionErrorText] 的中文映射——Android 上拿到的 `e.message`
 * 是 Java / Angus 的英文原文，直接给用户看既看不懂也会暴露平台细节。
 * 认证失败时补一句「请确认填写的是…」，与 `_probe_password_account` 相同。
 */
private fun LocalApi.probePasswordAccount(body: AccountBody, host: String, port: Long) {
    if (body.secret.isEmpty()) {
        return
    }
    val account = SyncAccount(
        id = 0,
        provider = body.provider,
        email = body.email,
        host = host,
        port = port.toInt(),
        authType = "password",
    )
    try {
        sync.probe(account, body.secret)
    } catch (error: Exception) {
        var detail = "连接测试失败: " + connectionErrorText(error).cpSlice(0, PROBE_ERROR_CHARS)
        val meta = get(body.provider)
        if (meta?.secret != null && meta.secret.kind == APP_PASSWORD && isAuthFailure(error)) {
            detail += "。请确认填写的是「${meta.secret.inputLabel}」，不是网页登录密码"
        }
        throw ApiException(400, detail)
    }
}

/**
 * 对应 `create_account`：规范化 → 校验登录方式 → 查重 → 解析主机 → 探测 → 入库 → 触发同步。
 *
 * 唯一约束兜底：账户表没有唯一索引，重名只能靠上面的 SELECT 拦；万一并发插入撞上约束，
 * 这里按 SQLite 错误转成同样的 409，而不是 500。**不能** `catch (SQLiteException)`：
 * 手机上抛的是别的异常类，要用 [isSqliteError] 判断（`LOCAL_MODE.md` 第四节规则 10）。
 */
internal fun LocalApi.createAccountRoute(call: Call): JsonElement {
    val body = accountBody(call)
    normalizeAccountBody(body)
    requireSecretMethod(body.provider)
    if (body.secret.isEmpty()) {
        throw ApiException(400, "缺少授权码/密码")
    }
    val existing = db.queryOne(
        "SELECT id FROM accounts WHERE provider=? AND lower(email)=?",
        body.provider,
        body.email,
    )
    if (existing != null) {
        throw ApiException(409, DUPLICATE_ACCOUNT)
    }
    val (host, port) = resolveHost(body)
    probePasswordAccount(body, host, port)
    val color = normalizeColor(body.color)
    val inserted = try {
        db.execute(
            """INSERT INTO accounts(name, provider, email, imap_host, imap_port, auth_type,
                                    secret_enc, poll_interval, color, enabled, created_ts)
               VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
            body.name.ifEmpty { body.email },
            body.provider,
            body.email,
            host,
            port,
            AUTH_TYPE_PASSWORD,
            cipher.encrypt(body.secret),
            body.pollInterval,
            color,
            if (body.enabled) 1L else 0L,
            clock.now(),
        )
    } catch (error: RuntimeException) {
        if (!isSqliteError(error)) throw error
        throw ApiException(409, DUPLICATE_ACCOUNT)
    }
    sync.triggerAccount(Math.toIntExact(inserted.lastInsertRowid))
    return jsonOf(linkedMapOf<String, Any?>("ok" to true, "id" to inserted.lastInsertRowid))
}

/**
 * 对应 `update_account`：先按原记录裁决主机与凭据，再探测，最后在一个事务里写库。
 *
 * 换了凭据就顺手清掉 OAuth 的一整套列，与 `app.py` 的第二条 UPDATE 相同：账户从此走授权码登录。
 */
internal fun LocalApi.updateAccountRoute(call: Call): JsonElement {
    val accId = call.pathId("acc_id")
    val row = db.queryOne("SELECT * FROM accounts WHERE id=?", accId)
        ?: throw ApiException(404, "账户不存在")
    val body = accountBody(call)
    normalizeAccountBody(body)
    if (body.secret.isNotEmpty()) {
        requireSecretMethod(body.provider)
    }
    val (host, port) = resolveUpdateHost(row, body)
    probePasswordAccount(body, host, port)
    val color = normalizeColor(body.color)
    db.transaction {
        db.execute(
            """UPDATE accounts SET name=?, provider=?, email=?, imap_host=?, imap_port=?,
               poll_interval=?, color=?, enabled=? WHERE id=?""",
            body.name.ifEmpty { body.email },
            body.provider,
            body.email,
            host,
            port,
            body.pollInterval,
            color,
            if (body.enabled) 1L else 0L,
            accId,
        )
        if (body.secret.isNotEmpty()) {
            db.execute(
                """UPDATE accounts SET auth_type='password', secret_enc=?,
                   oauth_refresh_enc='', oauth_access_enc='', oauth_expires=0,
                   oauth_client_id='', oauth_scope='', oauth_reauth_required=0, last_error=''
                   WHERE id=?""",
                cipher.encrypt(body.secret),
                accId,
            )
        }
    }
    return jsonOf(linkedMapOf<String, Any?>("ok" to true))
}

/**
 * 对应 `delete_account` + `_delete_account_sync`：删账户（邮件由外键级联删除）、写审计。
 *
 * 本机独有的两步收尾（服务器没有）：
 * - 清掉 `settings.local_initial_import_<id>`：这是本机判断「首次导入不通知」的标记，
 *   账户没了它就没有意义，留着还会误伤重建后恰好同 id 的账户；
 * - 通知协调器忘掉该账户（[SyncBackend.forgetAccount]），否则它的同步状态、互斥锁和
 *   排队中的同步会一直留在内存里。这一步**必须在事务外**。
 */
internal fun LocalApi.deleteAccountRoute(call: Call): JsonElement {
    val accId = call.pathId("acc_id")
    val row = db.queryOne("SELECT email FROM accounts WHERE id=?", accId)
        ?: throw ApiException(404, "账户不存在")
    val email = row.str("email") ?: ""
    db.transaction {
        db.execute("DELETE FROM accounts WHERE id=?", accId)
        db.execute("DELETE FROM settings WHERE k=?", "$INITIAL_IMPORT_KEY_PREFIX$accId")
    }
    sync.forgetAccount(Math.toIntExact(accId))
    db.audit(
        "delete_account",
        actor = "user",
        tier = "C",
        targetIds = listOf(accId),
        reason = "删除账户 $email，本地邮件一并删除",
    )
    return jsonOf(linkedMapOf<String, Any?>("ok" to true))
}

/**
 * 对应 `sync_now`：只把账户塞进同步队列，不检查账户是否存在（与服务器一致）。
 */
internal fun LocalApi.syncNowRoute(call: Call): JsonElement {
    sync.triggerAccount(Math.toIntExact(call.pathId("acc_id")))
    return jsonOf(linkedMapOf<String, Any?>("ok" to true))
}

/**
 * 对应 `test_account`：连接失败也是 200 + `ok=false`，`msg` 用 [connectionErrorText]。
 *
 * 原始异常只记类型：异常文本里可能带凭据，也会暴露服务器平台。
 */
internal fun LocalApi.testAccountRoute(call: Call): JsonElement {
    val accId = call.pathId("acc_id")
    val row = db.queryOne("SELECT * FROM accounts WHERE id=?", accId)
        ?: throw ApiException(404, "账户不存在")
    val account = SyncAccount.fromRow(row)
    return try {
        val secret = resolveSecret(account, cipher)
        sync.probe(account, secret)
        jsonOf(linkedMapOf<String, Any?>("ok" to true, "msg" to "连接正常"))
    } catch (error: Exception) {
        log.warning("测试连接失败：account=$accId type=${error::class.java.simpleName}")
        jsonOf(linkedMapOf<String, Any?>("ok" to false, "msg" to connectionErrorText(error)))
    }
}

/** 对应 `sync_all`：把启用中的账户全部塞进同步队列。 */
internal fun LocalApi.syncAllRoute(call: Call): JsonElement {
    sync.triggerAll()
    return jsonOf(linkedMapOf<String, Any?>("ok" to true))
}

// ---------------- 批量操作 ----------------

/** 对应 `_load_for_policy`：只取策略引擎要看的列，回收站里的邮件不算。 */
private fun LocalApi.loadForPolicy(ids: List<Long>): List<Map<String, Any?>> {
    if (ids.isEmpty()) {
        return emptyList()
    }
    val placeholders = ids.joinToString(",") { "?" }
    return db.query(
        """SELECT id, category, bill_tier, has_attach, importance, sender_addr, unread, deleted_ts
           FROM messages WHERE deleted_ts=0 AND id IN ($placeholders)""",
        *ids.toTypedArray(),
    ).map { it.toMap() }
}

/**
 * 对应 `batch_messages`：批量已读 / 未读 / 删除。
 *
 * 安全语义与服务器逐条相同：
 * - 受保护邮件的二次确认只对**单封**请求生效（`len(set(ids)) == 1`），批量请求带了也无效；
 * - 全部被拦截时 403 + `X-MailHub-Protected: 1`，并写一条 `allowed=0` 的审计；
 * - `server=true` 时先让 IMAP 执行（[SyncBackend.applyServerAction]），**只有服务器确认成功的
 *   那些**才改动本地状态；全部失败时本地一封都不动；
 * - 审计内容（动作名、等级、原因、可撤销与撤销载荷）与服务器逐字相同。
 */
internal fun LocalApi.batchMessagesRoute(call: Call): JsonElement {
    val body = parseBody(call.json(), BATCH_FIELDS)
    val requestedIds = body.longList("ids")
    if (requestedIds.isEmpty()) {
        return jsonOf(linkedMapOf<String, Any?>("ok" to true, "count" to 0L))
    }
    // 与服务器一样按 500 截断；非受保护邮件的动作名映射也照抄 `app.py`
    val ids = requestedIds.take(BATCH_MAX_IDS)
    val action = when (body.str("action")) {
        "delete" -> if (body.bool("server")) "purge" else "soft_delete"
        "read" -> "read"
        "unread" -> "unread"
        else -> throw ApiException(400, "不支持的动作 ${body.str("action")}")
    }
    val confirmed = body.bool("confirmed")
    val server = body.bool("server")
    val override = body.bool("override_protection") && ids.toSet().size == 1
    val messages = loadForPolicy(ids)
    val blocked = ArrayList<Map<String, Any?>>(messages.size)
    var allowedIds = ArrayList<Long>(messages.size)
    for (message in messages) {
        val decision = Policy.evaluate(
            action,
            message,
            actor = "user",
            confirmed = confirmed,
            overrideProtection = override,
        )
        val id = (message["id"] as? Number)?.toLong() ?: continue
        if (decision.allowed) {
            allowedIds += id
        } else {
            blocked += linkedMapOf<String, Any?>(
                "id" to id,
                "reason" to decision.reason,
                "protected" to (action in Policy.DESTRUCTIVE_ACTIONS && Policy.isProtected(message).protected),
            )
        }
    }
    if (allowedIds.isEmpty()) {
        val first = blocked.firstOrNull()?.get("reason") as? String ?: "无可执行目标"
        db.audit(action, actor = "user", targetIds = ids, allowed = false, reason = first)
        val headers = if (blocked.isNotEmpty() && blocked[0]["protected"] == true) {
            mapOf("X-MailHub-Protected" to "1")
        } else {
            emptyMap()
        }
        throw ApiException(403, "操作被安全策略拦截：$first", headers)
    }

    var errors: List<String> = emptyList()
    var undoPayload: Any? = null
    var pushAccounts: List<Int> = emptyList()
    val readStateOn = readStateSyncEnabled()
    // 不可逆的服务器动作在事务**之外**先做：只有服务器确认成功的那些才允许改动本地状态，
    // 而且这里会去连 IMAP（[SyncBackend.applyServerAction] 是阻塞的），绝不能占着数据库事务。
    val serverAction = when {
        action == "purge" -> "delete"
        server -> body.str("action")
        else -> null
    }
    if (serverAction != null) {
        val result = sync.applyServerAction(allowedIds, serverAction)
        errors = result.errors
        allowedIds = ArrayList(result.succeeded)
    }
    db.transaction {
        when (action) {
            "purge" -> {
                // 不可逆：从本地移除，审计标注不可撤销
                if (allowedIds.isNotEmpty()) {
                    db.execute(
                        "DELETE FROM messages WHERE id IN (${allowedIds.joinToString(",") { "?" }})",
                        *allowedIds.toTypedArray(),
                    )
                }
            }
            "soft_delete" -> {
                db.execute(
                    "UPDATE messages SET deleted_ts=? WHERE id IN (${allowedIds.joinToString(",") { "?" }})",
                    *listOf<Any?>(clock.now()).plus(allowedIds).toTypedArray(),
                )
                undoPayload = linkedMapOf<String, Any?>("ids" to allowedIds)
            }
            else -> {
                if (allowedIds.isNotEmpty()) {
                    val want = if (action == "read") 0L else 1L
                    val placeholders = allowedIds.joinToString(",") { "?" }
                    db.execute(
                        "UPDATE messages SET unread=? WHERE id IN ($placeholders)",
                        *listOf<Any?>(want).plus(allowedIds).toTypedArray(),
                    )
                    if (readStateOn) {
                        if (server) {
                            // 已经直接写到了邮箱服务器，这些邮件不再有待推送的标记
                            db.execute(
                                "UPDATE messages SET flag_push=0 WHERE id IN ($placeholders)",
                                *allowedIds.toTypedArray(),
                            )
                        } else {
                            // 纯本地改动：只给状态真的变了的邮件登记待推送
                            val allowedSet = allowedIds.toSet()
                            val changed = messages.mapNotNull { message ->
                                val id = (message["id"] as? Number)?.toLong() ?: return@mapNotNull null
                                if (id !in allowedSet) return@mapNotNull null
                                val unread = (message["unread"] as? Number)?.toLong() ?: 0L
                                if (unread != want) id else null
                            }
                            pushAccounts = registerFlagPush(changed, if (want == 0L) 1 else -1)
                        }
                    }
                    // server=true 时已读状态已经写到邮箱服务器，撤销只能改回本地字段、
                    // 两边从此不一致，所以只给纯本地的已读/未读记录撤销载荷
                    if (!server) {
                        val prev = LinkedHashMap<String, Any?>()
                        val allowedSet = allowedIds.toSet()
                        for (message in messages) {
                            val id = (message["id"] as? Number)?.toLong() ?: continue
                            if (id in allowedSet) {
                                prev[id.toString()] = (message["unread"] as? Number)?.toLong() ?: 0L
                            }
                        }
                        undoPayload = linkedMapOf<String, Any?>("unread" to prev)
                    }
                }
            }
        }
    }
    for (accountId in pushAccounts) {
        sync.triggerAccount(accountId)
    }

    val protectedHit = override && action in Policy.DESTRUCTIVE_ACTIONS && messages.any { message ->
        val id = (message["id"] as? Number)?.toLong() ?: return@any false
        id in allowedIds.toSet() && Policy.isProtected(message).protected
    }
    var reason = if (protectedHit) "用户对受保护邮件单封二次确认" else "用户批量操作"
    if (errors.isNotEmpty()) {
        reason += "；${errors.size} 个账户执行失败"
    } else if (blocked.isNotEmpty()) {
        reason += "，${blocked.size} 封被策略拦截"
    }
    if (action != "purge" && action != "soft_delete" && server && allowedIds.isNotEmpty()) {
        reason += "；已同步到邮件服务器，撤销只能恢复本地状态，因此不提供撤销"
    }
    val auditId = db.audit(
        action,
        actor = "user",
        tier = Policy.ACTION_TIER[action] ?: Policy.TIER_CONFIRM,
        targetIds = allowedIds,
        allowed = allowedIds.isNotEmpty(),
        reason = reason,
        reversible = undoPayload != null,
        undoPayload = undoPayload,
    )
    return jsonOf(
        linkedMapOf<String, Any?>(
            "ok" to errors.isEmpty(),
            "count" to allowedIds.size,
            "blocked" to blocked,
            "errors" to errors,
            "audit_id" to auditId,
            "reversible" to (undoPayload != null),
        ),
    )
}

// ---------------- 回收站与撤销 ----------------

/** 对应 `restore_trash`：把回收站里的邮件恢复，返回实际恢复条数。 */
internal fun LocalApi.restoreTrashRoute(call: Call): JsonElement {
    val requestedIds = parseBody(call.json(), IDS_FIELDS).longList("ids")
    if (requestedIds.isEmpty()) {
        return jsonOf(linkedMapOf<String, Any?>("ok" to true, "count" to 0L))
    }
    val ids = requestedIds.take(BATCH_MAX_IDS)
    val result = db.execute(
        "UPDATE messages SET deleted_ts=0 WHERE id IN (${ids.joinToString(",") { "?" }})",
        *ids.toTypedArray(),
    )
    db.audit("restore", actor = "user", tier = "A", targetIds = ids, reason = "从回收站恢复")
    return jsonOf(linkedMapOf<String, Any?>("ok" to true, "count" to result.changes))
}

/**
 * 对应 `undo_action`：撤销一条可逆的审计记录。不可逆动作（purge / 发送类）一律拒绝。
 *
 * 撤销已读状态时，如果「已读状态双向同步」开着，按恢复后的状态登记待推送
 * （恢复成未读标 -1，恢复成已读标 1），与 `app.py` 的分组方式相同。
 */
internal fun LocalApi.undoActionRoute(call: Call): JsonElement {
    val auditId = call.pathId("audit_id")
    val row = db.queryOne("SELECT * FROM audit_log WHERE id=?", auditId)
        ?: throw ApiException(404, "审计记录不存在")
    if ((row.long("undone") ?: 0L) != 0L) {
        throw ApiException(400, "该操作已撤销过")
    }
    val payloadText = row.str("undo_payload") ?: ""
    if ((row.long("reversible") ?: 0L) == 0L || payloadText.isEmpty()) {
        throw ApiException(400, "动作「${row.str("action") ?: ""}」不可撤销（已在邮箱服务器上永久生效）")
    }
    val payload = try {
        Json.parseToJsonElement(payloadText).jsonObject
    } catch (error: IllegalArgumentException) {
        throw ApiException(500, "撤销载荷无法解析")
    }
    var restored = 0L
    var pushAccounts: List<Int> = emptyList()
    db.transaction {
        when (row.str("action")) {
            "soft_delete" -> {
                val ids = (payload["ids"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content?.toLongOrNull() }
                    ?.take(UNDO_MAX_IDS)
                    ?: emptyList()
                if (ids.isNotEmpty()) {
                    restored += db.execute(
                        "UPDATE messages SET deleted_ts=0 WHERE id IN (${ids.joinToString(",") { "?" }})",
                        *ids.toTypedArray(),
                    ).changes
                }
            }
            "read", "unread" -> {
                val pairs = ArrayList<Pair<Long, Long>>()
                val unread = payload["unread"] as? JsonObject
                if (unread != null) {
                    for ((key, value) in unread) {
                        val id = Py.int(key)?.toLong() ?: continue
                        val prev = (value as? JsonPrimitive)?.content?.let { Py.int(it)?.toLong() } ?: continue
                        pairs += id to prev
                    }
                }
                for ((id, prev) in pairs) {
                    restored += db.execute(
                        "UPDATE messages SET unread=? WHERE id=?",
                        prev,
                        id,
                    ).changes
                }
                if (pairs.isNotEmpty() && readStateSyncEnabled()) {
                    // 撤销把已读状态改了回来：按恢复后的状态登记待推送
                    val toUnread = pairs.filter { it.second == 0L }.map { it.first }
                    val toRead = pairs.filter { it.second != 0L }.map { it.first }
                    pushAccounts = registerFlagPush(toUnread, 1) + registerFlagPush(toRead, -1)
                }
            }
        }
        db.execute("UPDATE audit_log SET undone=1 WHERE id=?", auditId)
    }
    for (accountId in pushAccounts.distinct().sorted()) {
        sync.triggerAccount(accountId)
    }
    db.audit(
        "undo",
        actor = "user",
        tier = "A",
        targetIds = listOf(auditId),
        reason = "撤销审计 #$auditId（${row.str("action") ?: ""}），恢复 $restored 封",
    )
    return jsonOf(linkedMapOf<String, Any?>("ok" to true, "restored" to restored))
}

// ---------------- 治理 ----------------

/**
 * 对应 `governance_run` + `_run_governance_api`：一键执行完整自动治理。
 *
 * 与服务器的三处对应关系：
 * - 进程内已有运行（`busy`）→ **409**「已有自动分拣任务正在运行」，不是 200；
 * - 响应**不带** `alerts`：服务器用 `result.pop("alerts")` 把它取走交给推送；本机模式的
 *   通知由 App 在同步后统一处理（`LOCAL_MODE.md` 第五节），这里同样把它丢掉；
 * - 运行本身抛异常 → 500「自动分拣失败：…」。
 */
internal fun LocalApi.governanceRunRoute(call: Call): JsonElement {
    val element = call.json()
    val days = if (element == null) 0L else parseBody(element, GOVERNANCE_RUN_FIELDS).long("days")
    val result = try {
        governance.runGovernance(
            limit = 0,
            trigger = if (days != 0L) "user-resort" else "user-one-click",
            sinceDays = days.toInt(),
        )
    } catch (error: ApiException) {
        throw error
    } catch (error: Exception) {
        throw ApiException(500, "自动分拣失败：${(error.message ?: "").cpSlice(0, 200)}")
    }
    if (result.busy) {
        throw ApiException(409, "已有自动分拣任务正在运行")
    }
    return jsonOf(result.toMap())
}

// ---------------- 设置 ----------------

/**
 * 对应 `put_settings`，按本机语义裁剪（附录 A）。
 *
 * 与服务器相同的部分：先校验再写（校验不过时一条设置都不写），字段的取值范围与默认值、
 * 账单策略变化后重新定级，全部照 `app.py`。
 *
 * 本机模式不同的部分：
 * - 改管理密码（`new_password` 不为 null）→ 400「本机模式没有管理密码」；
 * - 凭据类字段（AI Key、Bark、Telegram 令牌、Onepush、OAuth 客户端）→ 400，**不静默忽略**：
 *   静默忽略会让用户以为保存成功；
 * - App 的 `SettingsUpdate` 只发送其余 14 个字段，这些字段的行为与服务器完全一致。
 *
 * 校验顺序也照 `app.py`：密码 → `ai_scope` → 账单等级 → 凭据 → 写库。
 */
internal fun LocalApi.putSettingsRoute(call: Call): JsonElement {
    val body = parseBody(call.json(), SETTINGS_FIELDS)
    if (body.optStr("new_password") != null) {
        throw ApiException(400, "本机模式没有管理密码")
    }
    val aiScope = body.optStr("ai_scope")
    if (aiScope != null && aiScope !in AI_SCOPES_ALL) {
        throw ApiException(400, "AI 判定范围必须是 all / uncertain / other")
    }
    for (value in listOf(body.optStr("bill_auto_renew"), body.optStr("bill_refund"))) {
        if (value != null && value !in BILL_TIERS) {
            throw ApiException(400, "账单等级只能是「重要」或「一般」")
        }
    }
    requireNoCredentialSettings(body)

    body.optBool("ai_enabled")?.let { db.setSetting("ai_enabled", if (it) "1" else "0") }
    body.optStr("ai_base_url")?.let { db.setSetting("ai_base_url", it.pyStrip()) }
    body.optStr("ai_model")?.let { db.setSetting("ai_model", it.pyStrip()) }
    body.optBool("ai_send_body")?.let { db.setSetting("ai_send_body", if (it) "1" else "0") }
    aiScope?.let { db.setSetting("ai_scope", it) }
    body.optLong("ai_backfill_days")?.let {
        db.setSetting("ai_backfill_days", minOf(365L, maxOf(0L, it)).toString())
    }
    body.optLong("clean_otp_days")?.let { db.setSetting("clean_otp_days", maxOf(0L, it).toString()) }
    body.optBool("clean_server")?.let { db.setSetting("clean_server", if (it) "1" else "0") }
    body.optBool("sync_read_state")?.let { db.setSetting("sync_read_state", if (it) "1" else "0") }
    body.optLong("digest_hour")?.let {
        db.setSetting("digest_hour", minOf(23L, maxOf(0L, it)).toString())
    }
    body.optStr("notify_tg_chat")?.let { db.setSetting("notify_tg_chat", it.pyStrip()) }
    body.optBool("notify_important")?.let { db.setSetting("notify_important", if (it) "1" else "0") }
    body.optBool("notify_otp")?.let { db.setSetting("notify_otp", if (it) "1" else "0") }
    body.optLong("body_keep_days")?.let {
        db.setSetting("body_keep_days", maxOf(0L, it).toString())
    }
    body.optLong("clean_bill_minor_days")?.let {
        db.setSetting("clean_bill_minor_days", maxOf(0L, it).toString())
    }
    var billChanged = false
    val billUpdates = listOf(
        "bill_amount_threshold" to body.optDouble("bill_amount_threshold")?.let {
            Py.formatG(minOf(1_000_000.0, maxOf(0.0, it)))
        },
        "bill_routine_count" to body.optLong("bill_routine_count")?.let {
            minOf(1000L, maxOf(0L, it)).toString()
        },
        "bill_auto_renew" to body.optStr("bill_auto_renew"),
        "bill_refund" to body.optStr("bill_refund"),
    )
    for ((key, value) in billUpdates) {
        if (value != null && value != db.getSetting(key, "")) {
            db.setSetting(key, value)
            billChanged = true
        }
    }
    if (billChanged) {
        // 策略变了：按已存的交易画像重新定级（不调用 AI，用户手动设定的不动）
        governance.retierBills()
    }
    return jsonOf(linkedMapOf<String, Any?>("ok" to true))
}

/**
 * 本机模式拒绝修改凭据类字段（附录 A）：返回 400，而不是静默忽略。
 *
 * 判定条件与 `put_settings` 里「这条改动会不会真的写凭据」一致：空的 Key / 空地址本来就是
 * 「不修改」，照服务器语义放行；`clear_*` 为真表示要清空，同样属于修改。
 */
private fun requireNoCredentialSettings(body: Body) {
    val touching = listOf(
        (body.optStr("ai_key") ?: "").isNotEmpty(),
        (body.optStr("notify_bark_url") ?: "").pyStrip().isNotEmpty() || body.bool("clear_notify_bark"),
        (body.optStr("notify_tg_token") ?: "").pyStrip().isNotEmpty() ||
            body.bool("clear_notify_tg_token"),
        (body.optStr("notify_onepush_config") ?: "").pyStrip().isNotEmpty() ||
            body.bool("clear_notify_onepush"),
        body.optStr("google_oauth_client_id") != null,
        (body.optStr("google_oauth_client_secret") ?: "").isNotEmpty() ||
            body.bool("clear_google_oauth_client_secret"),
        body.optStr("microsoft_oauth_client_id") != null,
        (body.optStr("microsoft_oauth_client_secret") ?: "").isNotEmpty() ||
            body.bool("clear_microsoft_oauth_client_secret"),
    )
    if (touching.any { it }) {
        throw ApiException(400, "本机模式暂不支持修改此项")
    }
}

// ---------------- 本机语义（附录 A） ----------------

/** 对应 `login` 的位置，但本机模式不需要登录：400「本机模式不需要登录」。 */
internal fun LocalApi.localLoginRoute(call: Call): JsonElement =
    throw ApiException(400, "本机模式不需要登录")

/** 对应 `logout`：什么都不用做，数据留在本机。 */
internal fun LocalApi.localLogoutRoute(call: Call): JsonElement =
    jsonOf(linkedMapOf<String, Any?>("ok" to true))

/** 对应 `delete_session` / `revoke_all_sessions`：本机只有这一个设备，没有可吊销的会话。 */
internal fun LocalApi.localNoOtherDevicesRoute(call: Call): JsonElement =
    throw ApiException(400, "本机模式没有其他设备")

/** 对应 `notify_test`：本机模式的通知走系统通知，没有可测试的推送通道。 */
internal fun LocalApi.localNotifyTestRoute(call: Call): JsonElement =
    throw ApiException(400, "本机模式使用系统通知")

/** 对应 OAuth 登录的四个入口（`/api/oauth/start` 等）：P0 一律不支持。 */
internal fun LocalApi.localOauthUnsupportedRoute(call: Call): JsonElement =
    throw ApiException(400, LOCAL_OAUTH_UNSUPPORTED)

// ---------------- 小工具 ----------------

/** 对应 `oauth_auth.normalize_color`：不合法（含 CSS 注入尝试）时退回默认的浅蓝。 */
private fun normalizeColor(value: String): String =
    if (COLOR_RE.matcher(value).matches()) value else DEFAULT_ACCOUNT_COLOR

/** 对应 `ai.AI_SCOPES`。 */
private val AI_SCOPES_ALL = listOf("all", "uncertain", "other")

/** 对应 `billing.TIERS`；写死在接口层，避免把账单模块的内部细节带进请求校验。 */
private val BILL_TIERS = listOf("重要", "一般")

/** `accounts.poll_interval` 的默认值，对应 `AccountBody.poll_interval = 300`。 */
private const val DEFAULT_POLL_INTERVAL = 300L

/** IMAP 默认端口，对应 `AccountBody.imap_port = 993`。 */
private const val DEFAULT_IMAP_PORT = 993L

/** 账户默认颜色，对应 `AccountBody.color` 与 `oauth_auth.normalize_color` 的兜底。 */
private const val DEFAULT_ACCOUNT_COLOR = "#38bdf8"

/** `accounts.auth_type` 的授权码分支。 */
private const val AUTH_TYPE_PASSWORD = "password"

/** 「连接测试失败」里原始说明最多保留多少字，对应 `str(exc)[:150]`。 */
private const val PROBE_ERROR_CHARS = 150
