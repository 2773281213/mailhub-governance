package com.mailhub.core.api

import com.mailhub.core.Clock
import com.mailhub.core.SecretCipher
import com.mailhub.core.SystemClock
import com.mailhub.core.billing.Billing
import com.mailhub.core.db.Db
import com.mailhub.core.db.Row
import com.mailhub.core.db.SettingsSchema
import com.mailhub.core.governance.Governance
import com.mailhub.core.governance.GovernanceService
import com.mailhub.core.providers.APP_PASSWORD
import com.mailhub.core.providers.PROVIDERS_ALL
import com.mailhub.core.providers.Provider
import com.mailhub.core.rules.Rules
import com.mailhub.core.text.Py
import com.mailhub.core.text.pyStrip
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigInteger
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.logging.Logger

/**
 * 一次进程内的接口请求，由 OkHttp 拦截器（L10 的 `LocalApiInterceptor`）从真实请求里
 * 拆出来：方法、路径、查询参数、请求头与 JSON 请求体。
 *
 * [path] 只含路径（不带查询串）；万一带了 `?` 也会被 [LocalApi.handle] 拆开。
 */
data class LocalRequest(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
) {

    /**
     * 只显示方法、路径与 body 长度。
     *
     * [body] 里可能有授权码、密码和 AI Key，[headers] 里有 `Authorization`，所以 data class
     * 自动生成的 `toString()`（会把全部字段拼出来）不能直接用——日志与崩溃报告都会带上它。
     */
    override fun toString(): String =
        "LocalRequest(method=$method, path=$path, body=${body?.length ?: 0} 字符)"
}

/**
 * 一次进程内的响应：状态码、响应头与 JSON 体，语义与服务器返回的 HTTP 响应相同。
 *
 * [headers] 按 HTTP 约定不区分大小写；[body] 永远是 JSON——服务器所有接口都返回 JSON，
 * 未捕获异常也统一成 `{"detail": "Internal Server Error"}`（见 [LocalApi.handle]）。
 */
data class LocalResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: JsonElement,
)

/**
 * 业务错误，对应 FastAPI 的 `HTTPException`：转成 `{"detail": ...}` 与给定状态码，
 * [headers] 原样附到响应上（例如受保护邮件 403 的 `X-MailHub-Protected: 1`）。
 */
class ApiException(
    val status: Int,
    val detail: JsonElement,
    val headers: Map<String, String> = emptyMap(),
) : Exception("HTTP $status") {

    /** 最常见的形态：`detail` 是一句中文说明。 */
    constructor(status: Int, detail: String, headers: Map<String, String> = emptyMap()) :
        this(status, JsonPrimitive(detail), headers)
}

/**
 * `/api/status` 与账户列表里 `sync_state` 的数据来源，对应 Python 的 `sync.get_status()`。
 *
 * 返回 `{"账户 id": {"state": idle|syncing|error, "msg": str, "ts": float, "new": int}}`。
 * L7 的同步协调器实现它；测试里用假实现。
 */
fun interface SyncStatusSource {

    fun status(): Map<String, Map<String, Any?>>

    companion object {
        /** 没有任何同步状态（测试与刚建库时的行为）。 */
        val EMPTY: SyncStatusSource = SyncStatusSource { emptyMap() }
    }
}

/**
 * 本机模式的 API 路由，在进程内回答 App 的请求，逐路由对照服务端 `app.py`
 * （`/api/oauth/providers` 对照 `oauth_auth.public_providers()`）。
 *
 * **服务器就是规格**：状态码、响应头、JSON 的键名与值类型（布尔 / 整数 / 浮点不能混）
 * 都必须与服务器一致，App 的 DTO、错误映射和测试因此不用改。
 *
 * 与服务器的关系：
 * - 按方法和路径路由，路径参数按 FastAPI 的规则转换，类型不对返回 422；
 * - 未知路由 404 + `{"detail": "Not Found"}`，方法不对 405（带 `Allow` 头）；
 * - [ApiException] 对应 `HTTPException`，其余异常一律 500，且不把内部信息写进响应；
 * - **不校验令牌，也从不返回 401**（见 `LOCAL_MODE.md` 3.1）：它只在进程内被调用，没有外部入口。
 *
 * 本机语义与服务器不同的地方（见 `LOCAL_MODE.md` 第五节与附录 A）：
 * - `/api/changes`：`after_id == 0` 是从头列出（服务器是「只返回水位」），`after_id < 0` 才是
 *   初始化；并且跳过 `settings` 里 `local_initial_import_<账户 id>` 及其之前的邮件，游标照常前进；
 * - `/api/sessions` 返回空列表；`/api/oauth/providers` 里所有服务商的 OAuth 都不可用。
 *
 * **线程模型**：[handle] 是阻塞函数，会在 OkHttp 的工作线程上调用。它不做任何网络请求，
 * 也不用协程／`runBlocking`，只查本机数据库（[Db] 自己串行化）。列表查询只取需要的列，
 * 不会把 `body_html` 这类大字段带进结果。
 *
 * @param db 本机数据库，全应用唯一实例。
 * @param clock 当前时间，对应 `time.time()`。
 * @param zone 本地时区，对应 `sync.TZ`；`/api/meta` 的 `tz` 用它的 id（对应 `sync.TZ_NAME`）。
 *   **必须显式传入**：默认值会让「漏注入」变成静默的错误时区。
 * @param status `sync.get_status()` 的来源。**必须显式传入**；只读接口可以传
 *   [SyncStatusSource.EMPTY]，写接口必须接真实的同步状态。
 * @param governance 三省六部治理，`/api/governance/status`、`/api/governance/run` 与
 *   `/api/changes` 的屏障用它。
 * @param cipher 读写设置里加密字段（AI Key、账户授权码等）用，对应
 *   `security.encrypt` / `security.decrypt`。
 * @param sync 同步子系统的入口（触发同步、探测连接、回放服务器动作），见 [SyncBackend]。
 *   **必须显式传入**：写接口要接真实实现（L10 用 [CoordinatorSyncBackend]）。测试里的空实现
 *   [SyncBackend.NoOp] 只允许用于只读路径——它的 `probe` / `applyServerAction` 会直接抛异常，
 *   漏注入时不会出现「探测总是通过、`server=true` 的删除只删本地」这种假成功。
 * @param publicBaseUrl 本机会话的保留地址，用来拼 OAuth 回调地址（对应 `MAILHUB_PUBLIC_URL`）。
 * @param onReadStateChanged 已读状态有需要写回服务器的改动时回调（对应 `_trigger_read_state_sync`），
 *   参数是去重升序的账户 id。**必须显式传入**；不需要时由调用方传空实现。写接口走
 *   [SyncBackend.triggerAccount]。
 */
class LocalApi(
    internal val db: Db,
    internal val clock: Clock = SystemClock,
    internal val zone: ZoneId,
    internal val status: SyncStatusSource,
    internal val governance: GovernanceService = GovernanceService(db, clock),
    internal val cipher: SecretCipher,
    internal val sync: SyncBackend,
    internal val publicBaseUrl: String = LOCAL_BASE_URL,
    private val onReadStateChanged: (List<Int>) -> Unit,
) {

    private val log: Logger = Logger.getLogger("com.mailhub.core.api.LocalApi")

    /**
     * 处理一次请求，对应 FastAPI 的中间件 + 路由。
     *
     * 永不抛异常：所有错误都转成 [LocalResponse]，拦截器只要照抄状态码、响应头与响应体。
     */
    fun handle(request: LocalRequest): LocalResponse = try {
        val (path, query) = splitTarget(request)
        LocalResponse(
            200,
            JSON_HEADERS,
            dispatch(request.method.uppercase(Locale.ROOT), path, Call(query, rawBody = request.body)),
        )
    } catch (error: ApiException) {
        val headers = LinkedHashMap<String, String>(JSON_HEADERS)
        headers.putAll(error.headers)
        LocalResponse(error.status, headers, detailBody(error.detail))
    } catch (error: Exception) {
        // 原始异常只写本地日志，而且只记类型：异常文本里可能有邮件内容或凭据
        log.warning("本机接口异常：${error::class.java.simpleName}")
        LocalResponse(500, JSON_HEADERS, detailBody(JsonPrimitive("Internal Server Error")))
    }

    // ---------------- 路由 ----------------

    /**
     * 一条路由：方法 + 路径模式 + 处理函数。
     *
     * 模式里的 `{name}` 是路径参数，对应 FastAPI 的 `{name}` / `{name: int}`；其余段落必须
     * 逐字相同。段数不同、或参数位上出现空段（`//`）时都不匹配——与 FastAPI 一致。
     */
    private class Route(
        val method: String,
        val pattern: List<String>,
        val handler: (Call) -> JsonElement,
    ) {

        /** 匹配成功时返回路径参数（可能为空表），不匹配返回 null。 */
        fun match(segments: List<String>): Map<String, String>? {
            if (segments.size != pattern.size) return null
            var params: LinkedHashMap<String, String>? = null
            for (index in pattern.indices) {
                val expected = pattern[index]
                val actual = segments[index]
                if (expected.length > 2 && expected.startsWith("{") && expected.endsWith("}")) {
                    if (actual.isEmpty()) return null
                    val map = params ?: LinkedHashMap<String, String>().also { params = it }
                    map[expected.substring(1, expected.length - 1)] = actual
                } else if (expected != actual) {
                    return null
                }
            }
            return params ?: emptyMap()
        }
    }

    /**
     * 路由表。顺序与 `app.py` 的声明顺序大致相同：静态路径放在带参数的同前缀路径之前，
     * 这样 `POST /api/messages/batch` 不会被 `GET /api/messages/{msg_id}` 抢走。
     */
    private val routes: List<Route> = listOf(
        // —— 读接口（L9a） ——
        get("/api/meta", ::metaRoute),
        get("/api/overview", ::overviewRoute),
        get("/api/governance/status", ::governanceStatusRoute),
        get("/api/accounts", ::accountsRoute),
        get("/api/status", ::syncStatusRoute),
        get("/api/messages", ::messagesRoute),
        getCall("/api/messages/{msg_id}", ::messageDetailCall),
        get("/api/changes", ::changesRoute),
        get("/api/otp", ::otpRoute),
        get("/api/review", ::reviewRoute),
        get("/api/trash", ::trashRoute),
        get("/api/audit", ::auditRoute),
        get("/api/settings", ::settingsRoute),
        get("/api/sessions", ::sessionsRoute),
        get("/api/oauth/providers", ::oauthProvidersRoute),
        // —— 写接口（L9b） ——
        post("/api/accounts", ::createAccountRoute),
        put("/api/accounts/{acc_id}", ::updateAccountRoute),
        delete("/api/accounts/{acc_id}", ::deleteAccountRoute),
        post("/api/accounts/{acc_id}/sync", ::syncNowRoute),
        post("/api/accounts/{acc_id}/test", ::testAccountRoute),
        post("/api/sync-all", ::syncAllRoute),
        post("/api/messages/batch", ::batchMessagesRoute),
        post("/api/trash/restore", ::restoreTrashRoute),
        post("/api/audit/{audit_id}/undo", ::undoActionRoute),
        post("/api/governance/run", ::governanceRunRoute),
        put("/api/settings", ::putSettingsRoute),
        // —— 本机语义（附录 A）：App 会调用，但本机模式一律不执行 ——
        post("/api/login", ::localLoginRoute),
        post("/api/logout", ::localLogoutRoute),
        post("/api/sessions/revoke-all", ::localNoOtherDevicesRoute),
        delete("/api/sessions/{sid}", ::localNoOtherDevicesRoute),
        post("/api/notify/test", ::localNotifyTestRoute),
        post("/api/oauth/start", ::localOauthUnsupportedRoute),
        getCall("/api/oauth/transactions/{transaction_id}", ::localOauthUnsupportedRoute),
        post("/api/oauth/outlook/device/start", ::localOauthUnsupportedRoute),
        post("/api/oauth/outlook/device/poll", ::localOauthUnsupportedRoute),
    )

    private fun get(path: String, handler: (Params) -> JsonElement): Route =
        Route("GET", pathSegments(path)) { call -> handler(call.query) }

    /** 需要拿到路径参数或请求体的 GET 路由（`/api/messages/{msg_id}` 这类）。 */
    private fun getCall(path: String, handler: (Call) -> JsonElement): Route =
        Route("GET", pathSegments(path), handler)

    private fun post(path: String, handler: (Call) -> JsonElement): Route =
        Route("POST", pathSegments(path), handler)

    private fun put(path: String, handler: (Call) -> JsonElement): Route =
        Route("PUT", pathSegments(path), handler)

    private fun delete(path: String, handler: (Call) -> JsonElement): Route =
        Route("DELETE", pathSegments(path), handler)

    /**
     * 对应 FastAPI 的路由匹配：先找「路径 + 方法」都匹配的路由；只有路径匹配而方法不符时
     * 回 405（`Allow` 列出该路径的全部方法），都不匹配回 404。
     */
    private fun dispatch(method: String, path: String, call: Call): JsonElement {
        val segments = pathSegments(path)
        var allowed: MutableList<String>? = null
        for (route in routes) {
            val params = route.match(segments) ?: continue
            if (route.method != method) {
                (allowed ?: ArrayList<String>(2).also { allowed = it }).add(route.method)
                continue
            }
            return route.handler(Call(call.query, params, call.rawBody))
        }
        allowed?.let { methods ->
            throw ApiException(
                405,
                "Method Not Allowed",
                mapOf("Allow" to methods.distinct().joinToString(", ")),
            )
        }
        throw ApiException(404, "Not Found")
    }

    /** `/api/messages/{msg_id}`：把路径参数转成整数后交给原来的处理函数。 */
    private fun messageDetailCall(call: Call): JsonElement =
        messageDetailRoute(call.query, parsePathId("msg_id", call.pathText("msg_id")))

    /** 拆出路径与查询参数；`path` 里带 `?` 时一并解析（拦截器正常情况下不会这样传）。 */
    private fun splitTarget(request: LocalRequest): Pair<String, Params> {
        val target = request.path
        val mark = target.indexOf('?')
        val path = if (mark < 0) target else target.substring(0, mark)
        val inline = if (mark < 0) emptyMap() else parseQueryString(target.substring(mark + 1))
        return (path.ifEmpty { "/" }) to Params(inline + request.query)
    }

    // ---------------- /api/meta 与状态 ----------------

    /** 对应 `meta`：App 启动时读一次，版本、能力开关、时区和各类枚举。 */
    private fun metaRoute(query: Params): JsonElement = jsonOf(
        linkedMapOf<String, Any?>(
            "ok" to true,
            "api_version" to API_VERSION,
            "server_time" to clock.now(),
            "tz" to zone.id,
            "categories" to Rules.CATEGORIES,
            "display_categories" to DISPLAY_CATEGORIES,
            "bill_tiers" to Billing.TIERS,
            "ministries" to Governance.MINISTRIES,
            "governance_version" to Governance.VERSION,
            "features" to linkedMapOf(
                "token_auth" to true,
                "cursor" to true,
                "changes" to true,
                "sessions" to true,
            ),
        ),
    )

    /** 对应 `sync_status`：`sync.get_status()` 原样透出。 */
    private fun syncStatusRoute(query: Params): JsonElement = jsonOf(status.status())

    /** 对应 `governance_status`。 */
    private fun governanceStatusRoute(query: Params): JsonElement =
        jsonOf(governance.governanceStatus().toMap())

    // ---------------- 账户 ----------------

    /** 对应 `list_accounts` → `_list_accounts_sync` + `_acc_public`。 */
    private fun accountsRoute(query: Params): JsonElement {
        // 一条聚合查询代替每个账户两条 COUNT；回收站里的邮件不计入
        val counts = LinkedHashMap<Long, Pair<Long, Long>>()
        for (row in db.query(
            """SELECT account_id, COUNT(*) AS total, SUM(unread) AS unread
               FROM messages WHERE deleted_ts=0 GROUP BY account_id""",
        )) {
            val id = row.long("account_id") ?: continue
            counts[id] = (row.long("total") ?: 0L) to (row.long("unread") ?: 0L)
        }
        val syncStatus = status.status()
        val items = db.query("SELECT $ACCOUNT_SELECT FROM accounts ORDER BY id").map { row ->
            val account = LinkedHashMap<String, Any?>()
            for (column in ACCOUNT_OUTPUT_COLUMNS) {
                account[column] = row[column]
            }
            // `_acc_public` 会去掉全部密文列，只回显是否已配置凭据
            account["has_secret"] = Py.truthy(row["secret_enc"]) || Py.truthy(row["oauth_refresh_enc"])
            account["sync_state"] = syncStatus[row.long("id").toString()] ?: idleSyncState()
            val count = counts[row.long("id")]
            account["total"] = count?.first ?: 0L
            account["unread"] = count?.second ?: 0L
            account
        }
        return jsonOf(items)
    }

    // ---------------- 总览 ----------------

    /** 对应 `_overview_sync`：今日统计、分类分布、近 14 天、重要邮件与最新晨报。 */
    private fun overviewRoute(query: Params): JsonElement {
        // Python 是 datetime.fromtimestamp(now, TZ).replace(hour=0, ...).timestamp()
        val dayStart = Instant.ofEpochSecond(clock.now().toLong()).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toEpochSecond().toDouble()

        // 5 个统计数合成一条查询；空表时 SUM 返回 NULL，统一按 0 处理
        val row = db.queryOne(
            """SELECT SUM(CASE WHEN deleted_ts=0 THEN 1 ELSE 0 END) AS total,
                      SUM(CASE WHEN unread=1 AND deleted_ts=0 THEN 1 ELSE 0 END) AS unread,
                      SUM(CASE WHEN date_ts >= ? AND deleted_ts=0 THEN 1 ELSE 0 END) AS today,
                      SUM(CASE WHEN date_ts >= ? AND category='验证码' AND deleted_ts=0
                               THEN 1 ELSE 0 END) AS today_otp,
                      SUM(CASE WHEN unread=1 AND deleted_ts=0
                                AND (category IN ('重要','安全','可疑') OR importance >= 4)
                               THEN 1 ELSE 0 END) AS important_unread
               FROM messages""",
            dayStart,
            dayStart,
        )
        val stats = linkedMapOf<String, Any?>(
            "total" to (row?.long("total") ?: 0L),
            "unread" to (row?.long("unread") ?: 0L),
            "today" to (row?.long("today") ?: 0L),
            "today_otp" to (row?.long("today_otp") ?: 0L),
            "important_unread" to (row?.long("important_unread") ?: 0L),
        )

        // 分类分布：账单按等级拆开后再合并同名分类
        val byCategory = LinkedHashMap<String, Long>()
        for (cat in db.query(
            """SELECT category, bill_tier, COUNT(*) c FROM messages WHERE deleted_ts=0
               GROUP BY category, bill_tier""",
        )) {
            val name = displayCategory(cat.strOrEmpty("category"), cat.strOrEmpty("bill_tier"))
            byCategory[name] = (byCategory[name] ?: 0L) + (cat.long("c") ?: 0L)
        }
        stats["by_category"] = byCategory

        // 近 14 天每日邮件量（按分类堆叠）：一条查询取出全部日期，再在这里分桶
        val start = dayStart - 13 * 86400.0
        val end = dayStart + 86400.0
        val buckets = Array(14) { LinkedHashMap<String, Long>() }
        for (bucket in db.query(
            """SELECT CAST((date_ts - ?) / 86400 AS INTEGER) AS d, category, bill_tier, COUNT(*) AS c
               FROM messages
               WHERE date_ts >= ? AND date_ts < ? AND deleted_ts = 0
               GROUP BY d, category, bill_tier""",
            start,
            start,
            end,
        )) {
            val day = bucket.long("d")?.toInt() ?: continue
            if (day < 0 || day >= 14) continue
            val name = displayCategory(bucket.strOrEmpty("category"), bucket.strOrEmpty("bill_tier"))
            buckets[day][name] = (buckets[day][name] ?: 0L) + (bucket.long("c") ?: 0L)
        }
        val days = ArrayList<String>(14)
        val daily = ArrayList<Map<String, Any?>>(14)
        for (i in 13 downTo 0) {
            val label = DAY_LABEL.format(
                Instant.ofEpochSecond(dayStart.toLong() - i * 86400L).atZone(zone),
            )
            days.add(label)
            // 只出现当天有邮件的分类，不补 0
            val entry = LinkedHashMap<String, Any?>()
            entry["day"] = label
            entry.putAll(buckets[13 - i])
            daily.add(entry)
        }

        // 重要邮件（未读优先）
        val important = db.query(
            """SELECT m.id, m.subject, m.sender_addr, m.date_ts, m.category, m.bill_tier, m.importance,
                      m.summary, m.unread, a.name AS account_name, a.color AS account_color
               FROM messages m JOIN accounts a ON a.id=m.account_id
               WHERE m.deleted_ts=0 AND (m.category IN ('重要','安全','可疑') OR m.importance >= 4)
               ORDER BY m.unread DESC, m.date_ts DESC LIMIT 8""",
        ).map { it.toMap() }

        // 最新晨报
        val digest = db.queryOne(
            "SELECT day, content, created_ts FROM digests ORDER BY day DESC LIMIT 1",
        )?.toMap()

        return jsonOf(
            linkedMapOf<String, Any?>(
                "stats" to stats,
                "days" to days,
                "daily" to daily,
                "categories" to DISPLAY_CATEGORIES,
                "important" to important,
                "digest" to digest,
                "ai_enabled" to aiEnabled(),
            ),
        )
    }

    // ---------------- 邮件列表与详情 ----------------

    /** 对应 `list_messages` → `_list_messages_sync`。 */
    private fun messagesRoute(query: Params): JsonElement {
        var beforeTs = query.float("before_ts", 0.0)
        var beforeId = query.long("before_id", 0L)
        val cursor = query.str("cursor")
        if (cursor.isNotEmpty()) {
            // 不透明游标优先于旧的 before_ts / before_id
            val (ts, id) = decodeCursor(cursor)
            beforeTs = ts
            beforeId = id
        }
        val text = query.str("q")
        val filter = messageFilter(
            category = query.str("category"),
            account = query.long("account", 0L),
            unread = query.long("unread", -1L),
            query = text,
            billTier = query.str("bill_tier"),
            // 没有关键词时不碰 FTS 可用性探测（与 Python 的 `if q:` 一致）
            ftsAvailable = text.isNotEmpty() && db.ftsSearchAvailable(),
        )
        val page = query.long("page", 1L)
        val size = query.long("page_size", 40L)
            .coerceIn(MIN_PAGE_SIZE.toLong(), MAX_PAGE_SIZE.toLong()).toInt()
        if (beforeId > 0) {
            // 游标分页：按 (date_ts, id) 取下一页，不用 OFFSET，也不统计总数
            val condition = filter.sql + " AND (m.date_ts < ? OR (m.date_ts = ? AND m.id < ?))"
            val args = filter.args + listOf(beforeTs, beforeTs, beforeId) + listOf(size)
            return jsonOf(queryMessages(condition, args, "LIMIT ?", page, null, size))
        }
        val total = db.queryOne(
            "SELECT COUNT(*) c FROM messages m WHERE ${filter.sql}",
            *filter.args.toTypedArray(),
        )?.long("c") ?: 0L
        val args = filter.args + listOf(size, (maxOf(1L, page) - 1) * size)
        return jsonOf(queryMessages(filter.sql, args, "LIMIT ? OFFSET ?", page, total, size))
    }

    /**
     * 取一页邮件并拼出 `next_cursor`。
     *
     * 列表只取界面需要的列：`body_html` / `body_text` 可能很大，全读进来会长时间占着
     * [Db] 那唯一一把锁。
     */
    private fun queryMessages(
        condition: String,
        args: List<Any?>,
        tail: String,
        page: Long,
        total: Long?,
        size: Int,
    ): Map<String, Any?> {
        val items = db.query(
            """SELECT m.id, m.account_id, m.uid, m.subject, m.sender_name, m.sender_addr,
                      m.date_ts, m.snippet, m.category, m.otp_code, m.importance, m.summary,
                      m.unread, m.has_attach, m.bill_tier, m.risk_level,
                      a.name AS account_name, a.color AS account_color
               FROM messages m JOIN accounts a ON a.id=m.account_id
               WHERE $condition ORDER BY m.date_ts DESC, m.id DESC $tail""",
            *args.toTypedArray(),
        ).map { row ->
            // 与 /api/changes 一致地补上展示分类；网页端会忽略多出来的字段
            val item = LinkedHashMap<String, Any?>(row.toMap())
            item["display_category"] =
                displayCategory(row.strOrEmpty("category"), row.strOrEmpty("bill_tier"))
            item
        }
        val nextCursor = if (items.size == size) {
            val last = items.last()
            val ts = last["date_ts"] as Double
            val id = last["id"] as Long
            linkedMapOf<String, Any?>(
                "before_ts" to ts,
                "before_id" to id,
                "token" to encodeCursor(ts, id),
            )
        } else {
            null
        }
        return linkedMapOf(
            "total" to total,
            "page" to page,
            "items" to items,
            "next_cursor" to nextCursor,
        )
    }

    /** 对应 `get_message`：含 `mark_read` 的写入与 `_register_flag_push`。 */
    private fun messageDetailRoute(query: Params, msgId: Long): JsonElement {
        val markRead = query.bool("mark_read", true)
        val row = db.queryOne(
            """SELECT m.*, a.name AS account_name, a.color AS account_color
               FROM messages m JOIN accounts a ON a.id=m.account_id
               WHERE m.id=? AND m.deleted_ts=0""",
            msgId,
        ) ?: throw ApiException(404, "邮件不存在")
        // 打开即本地标记已读（服务器端标记由前端显式调用）；
        // App 预览用 mark_read=false 只看内容，不改未读状态
        if (markRead && Py.truthy(row["unread"])) {
            val push = readStateSyncEnabled()
            val accounts = if (push) {
                // 开关开启：本地标已读的同时登记待推送，下一次同步写回服务器 \Seen
                db.transaction { registerFlagPush(listOf(msgId), 1) }
            } else {
                db.transaction { db.execute("UPDATE messages SET unread=0 WHERE id=?", msgId) }
                emptyList()
            }
            if (push) {
                onReadStateChanged(accounts)
            }
        }
        // 返回改动之前的快照：Python 也是先 `d = dict(row)` 再写库
        return jsonOf(row.toMap())
    }

    // ---------------- 增量变更 ----------------

    /** 对应 `_changes_sync`，含本机的两处不同（见类注释）。 */
    private fun changesRoute(query: Params): JsonElement {
        val afterId = query.long("after_id", 0L)
        val limit = query.long("limit", 50L).coerceIn(1L, CHANGES_MAX_LIMIT.toLong()).toInt()
        val now = clock.now()
        val maxId = db.queryOne("SELECT COALESCE(MAX(id), 0) AS m FROM messages")?.long("m") ?: 0L
        if (afterId < 0) {
            // 初始化调用：只告诉客户端当前水位，不补发历史邮件
            return jsonOf(changesWithoutItems(maxId, reset = false))
        }
        if (afterId > maxId) {
            // 数据库被恢复成旧备份时 max_id 会倒退，客户端游标比它大就永远卡住；
            // 让客户端从头重新拉一遍（reset 信号）
            return jsonOf(changesWithoutItems(maxId, reset = true))
        }
        // 本机差异：账户首次同步导入的历史邮件不通知，但游标照常前进。
        // 这个条件同时用在屏障查询里：否则账户 B 首次导入的待治理邮件会把账户 A 的
        // 新验证码一起挡住，而它本来就不会出现在结果里。
        val skips = initialImportSkips()
        // 屏障：还没治理完的新邮件先挡住它和它之后的邮件，避免客户端看到待定级的旧标签。
        // 超过 1 小时仍未治理的邮件不再挡路，治理任务挂掉也不会让通知永久卡住。
        val (pendingSql, pendingArgs) = Governance.pendingClause(now)
        var barrierSql =
            """SELECT MIN(m.id) AS b FROM messages m
               WHERE m.id > ? AND m.deleted_ts=0 AND m.created_ts >= ?
                 AND $pendingSql"""
        val barrierArgs = mutableListOf<Any?>(
            afterId,
            now - BARRIER_STALE_SECONDS,
        )
        barrierArgs.addAll(pendingArgs)
        if (skips.isNotEmpty()) {
            barrierSql += skipCondition("m", skips)
            for ((accountId, maxSkippedId) in skips) {
                barrierArgs.add(accountId)
                barrierArgs.add(maxSkippedId)
            }
        }
        val barrier = db.queryOne(barrierSql, *barrierArgs.toTypedArray())?.long("b") ?: 0L

        var condition = "m.id > ? AND m.deleted_ts=0"
        val args = mutableListOf<Any?>(afterId)
        if (barrier > 0) {
            condition += " AND m.id < ?"
            args.add(barrier)
        }
        if (skips.isNotEmpty()) {
            condition += skipCondition("m", skips)
            for ((accountId, maxSkippedId) in skips) {
                args.add(accountId)
                args.add(maxSkippedId)
            }
        }
        args.add(limit)
        val items = db.query(
            """SELECT m.id, m.account_id, a.name AS account_name, m.subject, m.sender_name,
                      m.sender_addr, m.date_ts, m.category, m.bill_tier, m.importance,
                      m.otp_code, m.risk_level, m.unread
               FROM messages m LEFT JOIN accounts a ON a.id=m.account_id
               WHERE $condition ORDER BY m.id LIMIT ?""",
            *args.toTypedArray(),
        ).map { row ->
            val item = LinkedHashMap<String, Any?>(row.toMap())
            item["display_category"] =
                displayCategory(row.strOrEmpty("category"), row.strOrEmpty("bill_tier"))
            item
        }
        val cursor = when {
            items.isNotEmpty() -> items.last()["id"] as Long
            barrier > 0 -> barrier - 1
            else -> maxId
        }
        return jsonOf(
            linkedMapOf<String, Any?>(
                "items" to items,
                "cursor" to maxOf(cursor, afterId),
                "reset" to false,
            ),
        )
    }

    private fun changesWithoutItems(cursor: Long, reset: Boolean): Map<String, Any?> = linkedMapOf(
        "items" to emptyList<Any?>(),
        "cursor" to cursor,
        "reset" to reset,
    )

    /**
     * `settings` 里 `local_initial_import_<账户 id>` 给出的跳过上限，按账户 id 升序。
     *
     * 值不是整数的键直接忽略：那是本机自己写的标记，写坏了只该少跳几封，不该让接口 500。
     */
    private fun initialImportSkips(): List<Pair<Long, Long>> = db.query("SELECT k, v FROM settings")
        .mapNotNull { row ->
            val key = row.str("k") ?: return@mapNotNull null
            if (!key.startsWith(INITIAL_IMPORT_PREFIX)) return@mapNotNull null
            val accountId = sqliteLong(key.removePrefix(INITIAL_IMPORT_PREFIX)) ?: return@mapNotNull null
            val maxId = sqliteLong(row.str("v") ?: "") ?: return@mapNotNull null
            accountId to maxId
        }
        .sortedBy { it.first }

    /**
     * 「跳过首次导入邮件」的 SQL 片段，[alias] 是 `messages` 的别名；每个账户两个参数，
     * 顺序与 [skips] 相同。屏障查询与结果查询必须用同一个条件，否则被跳过的邮件仍会挡住别人。
     */
    private fun skipCondition(alias: String, skips: List<Pair<Long, Long>>): String =
        " AND NOT (" +
            skips.joinToString(" OR ") { "($alias.account_id=? AND $alias.id <= ?)" } + ")"

    // ---------------- 验证码、风险观察、回收站与审计 ----------------

    /** 对应 `otp_list`。 */
    private fun otpRoute(query: Params): JsonElement {
        val limit = query.long("limit", 24L).coerceIn(1L, 100L)
        return jsonOf(
            db.query(
                """SELECT m.id, m.subject, m.sender_addr, m.sender_name, m.date_ts, m.otp_code,
                          m.risk_level, m.unread, a.name AS account_name, a.color AS account_color,
                          a.email AS account_email
                   FROM messages m JOIN accounts a ON a.id=m.account_id
                   WHERE m.category='验证码' AND m.otp_code != '' AND m.deleted_ts=0
                   ORDER BY m.date_ts DESC LIMIT ?""",
                limit,
            ).map { it.toMap() },
        )
    }

    /** 对应 `review_queue`：只读观察，不要求逐封确认。 */
    private fun reviewRoute(query: Params): JsonElement {
        val limit = query.long("limit", 50L).coerceIn(1L, 200L)
        return jsonOf(
            db.query(
                """SELECT m.id, m.subject, m.sender_addr, m.sender_name, m.date_ts, m.category,
                          m.confidence, m.ai_reason, m.risk_level, m.risk_reasons, m.needs_review,
                          m.governance_ministry, m.governance_source, m.governance_reason,
                          a.name AS account_name, a.color AS account_color
                   FROM messages m JOIN accounts a ON a.id=m.account_id
                   WHERE m.deleted_ts=0 AND (m.category='可疑' OR m.risk_level IN ('medium','high'))
                   ORDER BY (m.risk_level='high') DESC, m.date_ts DESC LIMIT ?""",
                limit,
            ).map { it.toMap() },
        )
    }

    /** 对应 `list_trash`：负数 LIMIT 在 SQLite 里等于不限制条数，先夹到 1..500。 */
    private fun trashRoute(query: Params): JsonElement {
        val limit = query.long("limit", 100L).coerceIn(1L, 500L)
        return jsonOf(
            db.query(
                """SELECT m.id, m.subject, m.sender_addr, m.date_ts, m.category, m.deleted_ts,
                          a.name AS account_name, a.color AS account_color
                   FROM messages m JOIN accounts a ON a.id=m.account_id
                   WHERE m.deleted_ts > 0 ORDER BY m.deleted_ts DESC LIMIT ?""",
                limit,
            ).map { it.toMap() },
        )
    }

    /** 对应 `list_audit`。 */
    private fun auditRoute(query: Params): JsonElement {
        val limit = query.long("limit", 50L).coerceIn(1L, 200L)
        val offset = maxOf(0L, query.long("offset", 0L))
        val items = db.query(
            """SELECT id, ts, actor, action, tier, target_count, reason,
                      allowed, reversible, undone
               FROM audit_log ORDER BY ts DESC LIMIT ? OFFSET ?""",
            limit,
            offset,
        ).map { it.toMap() }
        val total = db.queryOne("SELECT COUNT(*) c FROM audit_log")?.long("c") ?: 0L
        return jsonOf(linkedMapOf<String, Any?>("total" to total, "items" to items))
    }

    // ---------------- 设置 ----------------

    /** 对应 `get_settings`：凭据只回显「是否已设置」。 */
    private fun settingsRoute(query: Params): JsonElement {
        val aiScope = db.getSetting("ai_scope", "all")
        val google = oauthConfigInfo("google", "gmail")
        val microsoft = oauthConfigInfo("microsoft", "outlook")
        // Bark 地址里带设备 key，只回显是否已设置和主机名
        val barkUrl = secretSetting(BARK_SETTING, BARK_LEGACY_SETTING).trimEnd('/')
        return jsonOf(
            linkedMapOf<String, Any?>(
                "ai_enabled" to aiEnabled(),
                "ai_base_url" to db.getSetting("ai_base_url", "").pyStrip().trimEnd('/'),
                "ai_key_set" to cipher.decrypt(db.getSetting("ai_key_enc", "")).isNotEmpty(),
                "ai_model" to db.getSetting("ai_model", "gemini-2.5-flash"),
                "ai_send_body" to (db.getSetting("ai_send_body", "1") == "1"),
                "ai_scope" to if (aiScope in AI_SCOPES) aiScope else "all",
                "ai_backfill_days" to intSetting("ai_backfill_days", DEFAULT_BACKFILL_DAYS),
                "clean_otp_days" to SettingsSchema.getInt(db, "clean_otp_days"),
                "clean_server" to SettingsSchema.getBool(db, "clean_server"),
                "sync_read_state" to readStateSyncEnabled(),
                "clean_bill_minor_days" to SettingsSchema.getInt(db, "clean_bill_minor_days"),
                "bill_amount_threshold" to floatSetting(
                    "bill_amount_threshold",
                    Billing.DEFAULTS["amount_threshold"] as Double,
                ),
                "bill_routine_count" to intSetting(
                    "bill_routine_count",
                    Billing.DEFAULTS["routine_count"] as Int,
                ),
                "bill_auto_renew" to defaultedSetting(
                    "bill_auto_renew",
                    Billing.DEFAULTS["auto_renew"] as String,
                ),
                "bill_refund" to defaultedSetting("bill_refund", Billing.DEFAULTS["refund"] as String),
                "digest_hour" to SettingsSchema.getInt(db, "digest_hour"),
                "notify_bark_set" to barkUrl.isNotEmpty(),
                "notify_bark_host" to urlHost(barkUrl),
                "notify_tg_token_set" to
                    secretSetting(TG_TOKEN_SETTING, TG_TOKEN_LEGACY_SETTING).isNotEmpty(),
                "notify_tg_chat" to SettingsSchema.getStr(db, "notify_tg_chat"),
                // Onepush 配置含令牌，只返回是否已设置和服务商名（本机不解析 YAML，见 KDoc）
                "notify_onepush_set" to db.getSetting(ONEPUSH_SETTING, "").isNotEmpty(),
                "notify_onepush_provider" to "",
                "notify_important" to SettingsSchema.getBool(db, "notify_important"),
                "notify_otp" to SettingsSchema.getBool(db, "notify_otp"),
                "body_keep_days" to SettingsSchema.getInt(db, "body_keep_days"),
                "google_oauth_configured" to google["configured"],
                "google_oauth_source" to google["source"],
                "google_oauth_client_id" to google["client_id"],
                "google_oauth_client_secret_set" to google["secret_set"],
                "google_oauth_callback_url" to google["callback_url"],
                "google_oauth_configuration_error" to google["configuration_error"],
                "microsoft_oauth_configured" to microsoft["configured"],
                "microsoft_oauth_device_configured" to
                    db.getSetting("microsoft_oauth_client_id", "").pyStrip().isNotEmpty(),
                "microsoft_oauth_source" to microsoft["source"],
                "microsoft_oauth_client_id" to microsoft["client_id"],
                "microsoft_oauth_client_secret_set" to microsoft["secret_set"],
                "microsoft_oauth_callback_url" to microsoft["callback_url"],
            ),
        )
    }

    /** 对应 `oauth_auth.browser_config_info` 的其余字段；本机没有环境变量，只看设置表。 */
    private fun oauthConfigInfo(prefix: String, provider: String): Map<String, Any?> {
        val clientId = db.getSetting("${prefix}_oauth_client_id", "").pyStrip()
        val secretSet =
            cipher.decrypt(db.getSetting("${prefix}_oauth_client_secret_enc", "")).isNotEmpty()
        val error = oauthClientIdentityError(provider, clientId)
        return linkedMapOf(
            "configured" to (clientId.isNotEmpty() && secretSet && error.isEmpty()),
            "source" to (if (clientId.isNotEmpty() || secretSet) "settings" else ""),
            "client_id" to clientId,
            "secret_set" to secretSet,
            "callback_url" to redirectUri(provider),
            "configuration_error" to error,
        )
    }

    /** 对应 `oauth_auth._redirect_uri`：本机会话的保留地址 + 固定回调路径。 */
    private fun redirectUri(provider: String): String =
        "${publicBaseUrl.trimEnd('/')}/api/oauth/$provider/callback"

    /** 对应 `oauth_auth.oauth_client_identity_error`：拒绝 Outlook 的私有注册身份。 */
    private fun oauthClientIdentityError(provider: String, clientId: String): String {
        if (provider == "gmail" && clientId.trim().lowercase(Locale.ROOT) == OUTLOOK_GOOGLE_CLIENT_ID) {
            return "不能使用 Outlook 私有 Google Client ID，请创建本项目自己的 Google OAuth Web 客户端"
        }
        if (urlHost(redirectUri(provider)) == OUTLOOK_OAUTH_HOST) {
            return "不能使用 Outlook 的 OAuth 中转回调，请将回调地址登记为本项目域名"
        }
        return ""
    }

    // ---------------- 会话与服务商（本机语义） ----------------

    /** 对应 `get_sessions`，本机语义：没有其他设备，恒为空列表（附录 A）。 */
    private fun sessionsRoute(query: Params): JsonElement =
        jsonOf(linkedMapOf<String, Any?>("items" to emptyList<Any?>()))

    /**
     * 对应 `oauth_auth.list_providers` → `public_providers()`，本机语义见附录 A：
     * P0 所有服务商的 `oauth_browser` / `oauth_device` 都不可用，只剩授权码与密码方式；
     * 只支持 OAuth 的服务商（Outlook）标注「本机模式暂不支持，P2 提供」。
     */
    private fun oauthProvidersRoute(query: Params): JsonElement {
        val providers = LinkedHashMap<String, Any?>()
        for (provider in PROVIDERS_ALL) {
            val methods = localAuthMethods(provider)
            providers[provider.key] = linkedMapOf<String, Any?>(
                "label" to provider.label,
                "domains" to provider.domains,
                "host" to provider.imapHost,
                "port" to provider.imapPort,
                "note" to provider.note,
                "methods" to methods,
                "oauth" to linkedMapOf("browser" to false, "device" to false),
                // 本机没有「到设置页配置 OAuth 客户端」这条路，所以不给这条提示
                "oauth_hint" to "",
                "unavailable" to if (methods.isEmpty()) LOCAL_OAUTH_UNAVAILABLE else "",
            )
        }
        return jsonOf(linkedMapOf<String, Any?>("providers" to providers))
    }

    /** 对应 `oauth_auth.auth_methods` 的本机版本：OAuth 方式一概不提供。 */
    private fun localAuthMethods(provider: Provider): List<Map<String, Any?>> {
        val secret = provider.secret ?: return emptyList()
        val links = secret.prereqLinks.map { (label, url) ->
            linkedMapOf<String, Any?>("label" to label, "url" to url)
        } + if (secret.setupUrl.isNotEmpty()) {
            listOf(
                linkedMapOf<String, Any?>(
                    "label" to secret.setupLabel.ifEmpty { "打开服务商设置" },
                    "url" to secret.setupUrl,
                ),
            )
        } else {
            emptyList()
        }
        return listOf(
            linkedMapOf(
                "type" to secret.kind,
                "label" to if (secret.kind == APP_PASSWORD) "使用授权码" else "使用密码",
                "input_label" to secret.inputLabel,
                "setup_url" to secret.setupUrl,
                "setup_label" to secret.setupLabel,
                "steps" to secret.steps,
                "links" to links,
                "troubleshoot" to secret.troubleshoot,
            ),
        )
    }

    // ---------------- 内部实现 ----------------

    /** 对应 `_display_category`：账单按等级拆成「重要账单」「一般账单」，未定级按重要展示。 */
    private fun displayCategory(category: String, billTier: String): String {
        val name = governance.displayCategory(category, billTier)
        return if (name == "账单") IMPORTANT_TIER_BILL else name
    }

    /** 对应 `sync.read_state_sync_enabled`：已读状态双向同步开关。 */
    internal fun readStateSyncEnabled(): Boolean = SettingsSchema.getBool(db, "sync_read_state")

    /** 对应 `ai.ai_config()["enabled"]`。 */
    private fun aiEnabled(): Boolean = db.getSetting("ai_enabled", "0") == "1"

    /**
     * 对应 `_register_flag_push`（Python 已改成 `message_ops.register_flag_push`）：
     * 登记「待写回服务器的已读状态」，返回涉及的账户 id（去重、升序）。
     *
     * flag：1 = 下次同步要把这些邮件在服务器上标为已读（本地同时置已读），-1 = 标为未读。
     * 调用方负责只传入状态确实发生变化的邮件，并在事务里调用（与 Python 的一次 `commit()` 对应）。
     * 实现见 [MessageOps.registerFlagPush]（按 [MessageOps.SQL_CHUNK] 分块）。
     */
    internal fun registerFlagPush(ids: List<Long>, flag: Int): List<Int> =
        MessageOps.registerFlagPush(db, ids, flag)

    /** 对应 `get_setting(key, "") or 默认值`。 */
    private fun defaultedSetting(key: String, fallback: String): String =
        db.getSetting(key, "").ifEmpty { fallback }

    /** 对应 `int(get_setting(key, "") or 默认值)`；解析失败按默认值处理（Python 会抛异常）。 */
    private fun intSetting(key: String, fallback: Int): Int {
        val value = Py.int(db.getSetting(key, "").ifEmpty { fallback.toString() }) ?: return fallback
        return if (value.bitLength() > MAX_INT_BITS) fallback else value.toInt()
    }

    /** 对应 `float(get_setting(key, "") or 默认值)`；解析失败按默认值处理。 */
    private fun floatSetting(key: String, fallback: Double): Double =
        Py.float(db.getSetting(key, "").ifEmpty { fallback.toString() }) ?: fallback

    /** 对应 `notify._secret`：先读加密键，为空时回退到旧的明文键。 */
    private fun secretSetting(encryptedKey: String, legacyKey: String): String =
        cipher.decrypt(db.getSetting(encryptedKey, "")).ifEmpty { db.getSetting(legacyKey, "") }

    /** 对应 `urlparse(url).hostname`：取不到主机名时返回空串。 */
    private fun urlHost(url: String): String {
        if (url.isEmpty()) return ""
        return try {
            URI(url).host?.lowercase(Locale.ROOT) ?: ""
        } catch (error: URISyntaxException) {
            // URI 只认 RFC 3986；地址里有空格等字符时按「解析不了」处理（Python 抛 ValueError）。
            // 这里必须接 URISyntaxException：它是受检异常，不是 IllegalArgumentException，
            // 接错了会让存了带空格地址的 `GET /api/settings` 直接 500。
            ""
        }
    }

    /** 把设置里的字符串读成 SQLite 能绑定的整数；不是整数时返回 null。 */
    private fun sqliteLong(text: String): Long? {
        val value = Py.int(text) ?: return null
        return if (value.bitLength() > MAX_INTEGER_BITS) null else value.toLong()
    }

    private fun idleSyncState(): Map<String, Any?> = linkedMapOf(
        "state" to "idle",
        "msg" to "",
        "ts" to 0,
        "new" to 0,
    )

    private companion object {

        /** `app.py` 的 `API_VERSION`。 */
        const val API_VERSION = 1

        /** 本机会话的保留地址，见 `LOCAL_MODE.md` 3.1。 */
        const val LOCAL_BASE_URL = "https://local.mailhub.invalid"

        /** 对应 `app.py` 的 `CHANGES_MAX_LIMIT`。 */
        const val CHANGES_MAX_LIMIT = 200

        /** `/api/messages` 的 `page_size` 上下限，对应 `min(100, max(10, page_size))`。 */
        const val MIN_PAGE_SIZE = 10
        const val MAX_PAGE_SIZE = 100

        /** `/api/changes` 屏障：超过 1 小时仍未治理的邮件不再挡路。 */
        const val BARRIER_STALE_SECONDS = 3600.0

        /** 首次同步导入的历史邮件跳过上限所在的设置键前缀。 */
        const val INITIAL_IMPORT_PREFIX = "local_initial_import_"

        /** 对应 `ai.AI_SCOPES`。 */
        val AI_SCOPES = listOf("all", "uncertain", "other")

        /** 对应 `ai.DEFAULT_BACKFILL_DAYS`。 */
        const val DEFAULT_BACKFILL_DAYS = 30

        /** 未定级的旧账单按「重要账单」展示，与分级的保守默认一致。 */
        const val IMPORTANT_TIER_BILL = "重要账单"

        /** 对应 `notify.BARK_SETTING` / `BARK_LEGACY_SETTING`。 */
        const val BARK_SETTING = "notify_bark_url_enc"
        const val BARK_LEGACY_SETTING = "notify_bark_url"

        /** 对应 `notify.TG_TOKEN_SETTING` / `TG_TOKEN_LEGACY_SETTING`。 */
        const val TG_TOKEN_SETTING = "notify_tg_token_enc"
        const val TG_TOKEN_LEGACY_SETTING = "notify_tg_token"

        /** 对应 `notify.ONEPUSH_SETTING`。 */
        const val ONEPUSH_SETTING = "notify_onepush_enc"

        /** 对应 `oauth_auth._OUTLOOK_GOOGLE_CLIENT_ID`（已转小写，比较时也转小写）。 */
        const val OUTLOOK_GOOGLE_CLIENT_ID =
            "445112211283-sk04feuogpcjd3dq8eshrdnr4bpm1sfk.apps.googleusercontent.com"

        /** 对应 `oauth_auth._OUTLOOK_OAUTH_HOST`。 */
        const val OUTLOOK_OAUTH_HOST = "olmoauth.outlook.com"

        /** 只支持 OAuth 的服务商在本机模式下的说明（附录 A）。 */
        const val LOCAL_OAUTH_UNAVAILABLE = "本机模式暂不支持，P2 提供"

        /** 所有 JSON 响应的响应头，与 FastAPI 的默认值一致。 */
        val JSON_HEADERS: Map<String, String> = mapOf("Content-Type" to "application/json")

        /** 对应 `app.py` 的 `DISPLAY_CATEGORIES`：账单按等级拆成两项。 */
        val DISPLAY_CATEGORIES: List<String> = Rules.CATEGORIES.flatMap { category ->
            if (category == "账单") Billing.TIERS.map { tier -> "${tier}账单" } else listOf(category)
        }

        /** 账户列表要输出的列：顺序与 `_acc_public` 里 `dict(row)` 去掉密文列之后一致。 */
        val ACCOUNT_OUTPUT_COLUMNS: List<String> = listOf(
            "id", "name", "provider", "email", "imap_host", "imap_port", "auth_type",
            "oauth_expires", "enabled", "poll_interval", "last_uid", "uidvalidity",
            "last_sync", "last_error", "color", "created_ts", "oauth_scope",
            "oauth_reauth_required",
        )

        /** 账户列表的 SELECT：不回读 `oauth_access_enc` 这类用不上的密文。 */
        const val ACCOUNT_SELECT = "id, name, provider, email, imap_host, imap_port, auth_type, " +
            "secret_enc, oauth_refresh_enc, oauth_expires, enabled, poll_interval, last_uid, " +
            "uidvalidity, last_sync, last_error, color, created_ts, oauth_scope, oauth_reauth_required"

        /** `%m-%d`：固定 ROOT 语言环境，保证输出是 ASCII 数字（与 Python 的 `strftime` 一致）。 */
        val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT)

        /** 把 `detail` 包成 `{"detail": ...}`。 */
        fun detailBody(detail: JsonElement): JsonElement =
            jsonOf(linkedMapOf<String, Any?>("detail" to detail))
    }
}

/** 请求里带 `?` 时按 `&` / `=` 拆开并做 percent-decode（与 `parse_qs` 一致，`+` 是空格）。 */
internal fun parseQueryString(raw: String): Map<String, String> {
    if (raw.isEmpty()) return emptyMap()
    val values = LinkedHashMap<String, String>()
    for (pair in raw.split('&')) {
        if (pair.isEmpty()) continue
        val mark = pair.indexOf('=')
        val name = if (mark < 0) pair else pair.substring(0, mark)
        val value = if (mark < 0) "" else pair.substring(mark + 1)
        values[decodeFormComponent(name)] = decodeFormComponent(value)
    }
    return values
}

private fun decodeFormComponent(text: String): String = try {
    URLDecoder.decode(text, Charsets.UTF_8)
} catch (error: IllegalArgumentException) {
    text
}

/** 按 `/` 切路径；开头的 `/` 不算一段。 */
internal fun pathSegments(path: String): List<String> {
    val trimmed = if (path.startsWith("/")) path.substring(1) else path
    if (trimmed.isEmpty()) return emptyList()
    return trimmed.split('/')
}

/**
 * 查询参数的取值助手，类型不对时抛 422（与 FastAPI 的 `RequestValidationError` 同形）。
 *
 * 整数的解析用 [Py.int]（Python 的 `int()` 语义：认两端空白、正负号、数字之间的下划线），
 * 布尔只认 FastAPI 接受的那一组字面值。
 */
internal class Params(private val values: Map<String, String>) {

    fun str(name: String): String = values[name] ?: ""

    fun long(name: String, fallback: Long): Long {
        val text = values[name] ?: return fallback
        val value: BigInteger = Py.int(text) ?: throw validationError("int_parsing", name, text)
        if (value.bitLength() > MAX_INTEGER_BITS) throw validationError("int_parsing", name, text)
        return value.toLong()
    }

    fun float(name: String, fallback: Double): Double {
        val text = values[name] ?: return fallback
        return Py.float(text) ?: throw validationError("float_parsing", name, text)
    }

    fun bool(name: String, fallback: Boolean): Boolean {
        val text = values[name] ?: return fallback
        return when (text.lowercase(Locale.ROOT)) {
            "1", "true", "t", "yes", "y", "on" -> true
            "0", "false", "f", "no", "n", "off" -> false
            else -> throw validationError("bool_parsing", name, text)
        }
    }

    private fun validationError(type: String, name: String, input: String): ApiException {
        val message = when (type) {
            "int_parsing" -> "Input should be a valid integer, unable to parse string as an integer"
            "float_parsing" -> "Input should be a valid number, unable to parse string as a number"
            else -> "Input should be a valid boolean, unable to interpret input"
        }
        return ApiException(422, validationDetail(type, message, listOf("query", name), input))
    }
}

/** 路径参数转整数，对应 FastAPI 的 `{msg_id: int}`：类型不对 422。 */
internal fun parsePathId(name: String, text: String): Long {
    val value = Py.int(text)
    if (value == null || value.bitLength() > MAX_INTEGER_BITS) {
        throw ApiException(
            422,
            validationDetail(
                "int_parsing",
                "Input should be a valid integer, unable to parse string as an integer",
                listOf("path", name),
                text,
            ),
        )
    }
    return value.toLong()
}

/** FastAPI 的 422 响应体：`{"detail": [{type, loc, msg, input}]}`。 */
private fun validationDetail(
    type: String,
    message: String,
    location: List<String>,
    input: String,
): JsonElement = jsonOf(
    listOf(
        linkedMapOf<String, Any?>(
            "type" to type,
            "loc" to location,
            "msg" to message,
            "input" to input,
        ),
    ),
)

/** `Row.str` 的空值兜底，读分类名这类不可能是 NULL 的列。 */
private fun Row.strOrEmpty(column: String): String = str(column) ?: ""

/** SQLite INTEGER 是带符号 64 位：`bitLength() > 63` 就已经溢出。 */
private const val MAX_INTEGER_BITS = 63

/** 32 位整数（`Int`）的位宽，用于把设置里的大整数夹回默认值。 */
private const val MAX_INT_BITS = 31
