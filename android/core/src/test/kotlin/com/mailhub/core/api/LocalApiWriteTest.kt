package com.mailhub.core.api

import com.mailhub.core.db.Db
import com.mailhub.core.db.FakeClock
import com.mailhub.core.db.withTempDb
import com.mailhub.core.sync.ServerActionResult
import com.mailhub.core.sync.SyncAccount
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 写接口里**本机语义**与服务器的不同之处，逐个对应 `LOCAL_MODE.md` 附录 A。
 *
 * 这些行为不能进金标（金标只录服务器返回），所以用普通单元测试钉死：
 * - `/api/login` 等会话类接口一律 400，`/api/logout` 是空操作；
 * - `/api/notify/test` 返回 400（本机用系统通知）；
 * - OAuth 登录的四个入口 400，OAuth 账户的「测试连接」也是 `ok=false`；
 * - `PUT /api/settings`：改管理密码与改凭据类字段都 400（**不静默忽略**），
 *   而 App 的 `SettingsUpdate` 实际发送的字段与服务器行为完全一致；
 * - 删除账户时清掉 `local_initial_import_<id>` 并通知协调器忘掉该账户。
 *
 * 另外钉住两条硬性约束：`SyncBackend` 的方法**不能在** `Db.transaction` 里调用，以及
 * 请求体里显式传 `null`（App 不会发，但 pydantic 接受）等价于「不修改」。
 */
class LocalApiWriteTest {

    // ---------------- 会话类接口 ----------------

    @Test
    fun loginIsRejectedButLogoutIsANoOp() {
        withWriteApi { api, _, backend ->
            val login = api.handle(LocalRequest("POST", "/api/login", body = """{"password":"x"}"""))
            assertEquals(400, login.status)
            assertEquals("本机模式不需要登录", login.detailText())

            val logout = api.handle(LocalRequest("POST", "/api/logout"))
            assertEquals(200, logout.status)
            assertEquals("""{"ok":true}""", logout.body.toString())
            // 本机模式没有会话，也就没有任何外部调用
            assertTrue(backend.calls.isEmpty())
        }
    }

    @Test
    fun sessionsCannotBeRevokedBecauseThereAreNoOtherDevices() {
        withWriteApi { api, db, _ ->
            val before = sessionCount(db)
            val deleted = api.handle(LocalRequest("DELETE", "/api/sessions/abc"))
            assertEquals(400, deleted.status)
            assertEquals("本机模式没有其他设备", deleted.detailText())

            val revoked = api.handle(LocalRequest("POST", "/api/sessions/revoke-all"))
            assertEquals(400, revoked.status)
            assertEquals("本机模式没有其他设备", revoked.detailText())
            assertEquals("本机模式不该有会话表数据", before, sessionCount(db))
        }
    }

    @Test
    fun notifyTestExplainsThatLocalModeUsesSystemNotifications() {
        withWriteApi { api, _, _ ->
            // App 的 PushTestBody 固定发空串
            val response = api.handle(
                LocalRequest("POST", "/api/notify/test", body = """{"onepush_config":""}"""),
            )
            assertEquals(400, response.status)
            assertEquals("本机模式使用系统通知", response.detailText())
        }
    }

    @Test
    fun everyOauthEntryPointIsUnsupported() {
        withWriteApi { api, _, _ ->
            val requests = listOf(
                LocalRequest("POST", "/api/oauth/start", body = """{"provider":"gmail"}"""),
                LocalRequest("GET", "/api/oauth/transactions/tx-1"),
                LocalRequest("POST", "/api/oauth/outlook/device/start"),
                LocalRequest("POST", "/api/oauth/outlook/device/poll", body = """{"id":"tx"}"""),
            )
            for (request in requests) {
                val response = api.handle(request)
                assertEquals("${request.method} ${request.path}", 400, response.status)
                assertEquals("本机模式暂不支持 OAuth 登录", response.detailText())
            }
        }
    }

    // ---------------- OAuth 账户 ----------------

    @Test
    fun oauthAccountCannotBeAddedOrTested() {
        withWriteApi { api, db, backend ->
            // 只支持 OAuth 的服务商：没有授权码这条路
            val created = api.handle(
                LocalRequest(
                    "POST",
                    "/api/accounts",
                    body = """{"provider":"outlook","email":"someone@outlook.com","secret":"p"}""",
                ),
            )
            assertEquals(400, created.status)
            assertTrue("应说明只能走 OAuth", created.detailText().contains("OAuth"))
            assertEquals("不该探测，也不该触发同步", 0, backend.calls.size)

            // 已存在的 OAuth 账户（种子里的 #2）不能直接改服务商与邮箱
            val updated = api.handle(
                LocalRequest(
                    "PUT",
                    "/api/accounts/2",
                    body = """{"provider":"gmail","email":"other@example.com"}""",
                ),
            )
            assertEquals(400, updated.status)
            assertEquals("OAuth 账户的服务商和邮箱地址不能直接修改，请重新登录", updated.detailText())

            // 测试连接：本机模式没有 OAuth 令牌，也是 200 + ok=false，不去连服务器
            val tested = api.handle(LocalRequest("POST", "/api/accounts/2/test"))
            assertEquals(200, tested.status)
            val payload = tested.body.jsonObject
            assertFalse(payload.getValue("ok").jsonPrimitive.content.toBoolean())
            assertEquals("本机模式暂不支持 OAuth 账户", payload.getValue("msg").jsonPrimitive.content)
            assertTrue("解析不出 OAuth 令牌时不该发起探测", backend.calls.isEmpty())
            assertNull(db.queryOne("SELECT id FROM accounts WHERE email='other@example.com'"))
        }
    }

    // ---------------- 设置 ----------------

    @Test
    fun settingsRejectPasswordChangeWithoutWritingAnything() {
        withWriteApi { api, db, _ ->
            val before = settingSnapshot(db)
            val response = api.handle(
                LocalRequest(
                    "PUT",
                    "/api/settings",
                    // 同时带一个合法字段：校验不过时一条设置都不该写进去
                    body = """{"ai_enabled":true,"old_password":"old","new_password":"new-password"}""",
                ),
            )
            assertEquals(400, response.status)
            assertEquals("本机模式没有管理密码", response.detailText())
            assertEquals(before, settingSnapshot(db))
        }
    }

    @Test
    fun settingsRejectEveryCredentialField() {
        withWriteApi { api, db, _ ->
            val before = settingSnapshot(db)
            // 每个凭据类字段单独一例；判定与服务器的「这条改动会不会真的写凭据」一致
            val bodies = listOf(
                """{"ai_key":"sk-test"}""",
                """{"notify_bark_url":"https://api.day.app/key"}""",
                """{"clear_notify_bark":true}""",
                """{"notify_tg_token":"123:abc"}""",
                """{"clear_notify_tg_token":true}""",
                """{"notify_onepush_config":"provider: bark"}""",
                """{"clear_notify_onepush":true}""",
                """{"google_oauth_client_id":"client"}""",
                """{"google_oauth_client_secret":"secret"}""",
                """{"clear_google_oauth_client_secret":true}""",
                """{"microsoft_oauth_client_id":"client"}""",
                """{"microsoft_oauth_client_secret":"secret"}""",
                """{"clear_microsoft_oauth_client_secret":true}""",
            )
            for (body in bodies) {
                val response = api.handle(LocalRequest("PUT", "/api/settings", body = body))
                assertEquals(body, 400, response.status)
                assertEquals("本机模式暂不支持修改此项", response.detailText())
            }
            assertEquals("拒绝时一条设置都不该写", before, settingSnapshot(db))
        }
    }

    @Test
    fun settingsAcceptEveryFieldTheAppSends() {
        withWriteApi { api, db, _ ->
            // App 的 SettingsUpdate 的全部字段（密码留空，凭据字段本来就不发）
            val response = api.handle(
                LocalRequest(
                    "PUT",
                    "/api/settings",
                    body = """{"ai_enabled":true,"ai_send_body":false,"ai_scope":"uncertain",
                              "ai_backfill_days":30,"clean_otp_days":14,"clean_server":true,
                              "clean_bill_minor_days":7,"sync_read_state":true,"digest_hour":23,
                              "notify_important":false,"notify_otp":true,"body_keep_days":60}""",
                ),
            )
            assertEquals(200, response.status)
            assertEquals("""{"ok":true}""", response.body.toString())
            val expected = mapOf(
                "ai_enabled" to "1",
                "ai_send_body" to "0",
                "ai_scope" to "uncertain",
                "ai_backfill_days" to "30",
                "clean_otp_days" to "14",
                "clean_server" to "1",
                "clean_bill_minor_days" to "7",
                "sync_read_state" to "1",
                "digest_hour" to "23",
                "notify_important" to "0",
                "notify_otp" to "1",
                "body_keep_days" to "60",
            )
            for ((key, value) in expected) {
                assertEquals(key, value, db.getSetting(key, "<缺失>"))
            }
        }
    }

    @Test
    fun settingsClampOutOfRangeValues() {
        withWriteApi { api, db, _ ->
            val response = api.handle(
                LocalRequest(
                    "PUT",
                    "/api/settings",
                    body = """{"ai_backfill_days":10000,"clean_otp_days":-5,
                              "digest_hour":99,"body_keep_days":-3,"clean_bill_minor_days":-1}""",
                ),
            )
            assertEquals(200, response.status)
            assertEquals("365", db.getSetting("ai_backfill_days"))
            assertEquals("0", db.getSetting("clean_otp_days"))
            assertEquals("23", db.getSetting("digest_hour"))
            assertEquals("0", db.getSetting("body_keep_days"))
            assertEquals("0", db.getSetting("clean_bill_minor_days"))
        }
    }

    @Test
    fun settingsTreatExplicitNullAsNoChange() {
        withWriteApi { api, db, _ ->
            val before = settingSnapshot(db)
            // App 的 JSON 不会带 null（explicitNulls=false），但 pydantic 接受它：等价于不修改
            val response = api.handle(
                LocalRequest(
                    "PUT",
                    "/api/settings",
                    body = """{"ai_enabled":null,"new_password":null,"digest_hour":null}""",
                ),
            )
            assertEquals(200, response.status)
            assertEquals(before, settingSnapshot(db))
        }
    }

    // ---------------- 删除账户的本机收尾 ----------------

    @Test
    fun deleteAccountClearsInitialImportMarkAndForgetsTheAccount() {
        withWriteApi { api, db, backend ->
            db.setSetting("local_initial_import_1", "3")
            db.setSetting("local_initial_import_2", "13")
            assertEquals(7L, db.queryOne("SELECT COUNT(*) c FROM messages WHERE account_id=1")?.long("c"))

            val response = api.handle(LocalRequest("DELETE", "/api/accounts/1"))
            assertEquals(200, response.status)
            assertEquals("""{"ok":true}""", response.body.toString())

            assertNull("账户要删掉", db.queryOne("SELECT id FROM accounts WHERE id=1"))
            assertEquals(
                "邮件一并删除",
                0L,
                db.queryOne("SELECT COUNT(*) c FROM messages WHERE account_id=1")?.long("c"),
            )
            assertEquals("首次导入标记要清掉", listOf("local_initial_import_2"), initialImportKeys(db))
            assertEquals("要通知协调器忘掉账户", listOf("forget:1"), backend.calls)
            val audit = db.queryOne(
                "SELECT action, tier, reason FROM audit_log ORDER BY id DESC LIMIT 1",
            )
            assertEquals("delete_account", audit?.str("action"))
            assertEquals("C", audit?.str("tier"))
            assertEquals("删除账户 work@corp.example，本地邮件一并删除", audit?.str("reason"))
        }
    }

    @Test
    fun deleteMissingAccountIs404AndDoesNotForget() {
        withWriteApi { api, _, backend ->
            val response = api.handle(LocalRequest("DELETE", "/api/accounts/999"))
            assertEquals(404, response.status)
            assertEquals("账户不存在", response.detailText())
            assertTrue(backend.calls.isEmpty())
        }
    }

    // ---------------- 与同步子系统的边界 ----------------

    @Test
    fun syncBackendIsNeverCalledInsideADatabaseTransaction() {
        val seed = ApiSeed.load("write_seed")
        withTempDb(FakeClock(seed.now)) { db ->
            seedDatabase(db, seed)
            // 每次被调用时都从**另一条线程**碰一下数据库：如果调用方把这次调用包在
            // Db.transaction 里，那唯一一把锁还在原线程手上，这里会一直等到超时。
            val backend = LockProbingSyncBackend(db)
            val api = apiFor(db, seed, sync = backend)

            assertEquals(200, api.handle(LocalRequest("POST", "/api/accounts/1/sync")).status)
            assertEquals(
                200,
                api.handle(
                    LocalRequest("POST", "/api/sync-all"),
                ).status,
            )
            assertEquals(
                200,
                api.handle(
                    LocalRequest(
                        "POST",
                        "/api/messages/batch",
                        body = """{"ids":[9],"action":"read","server":true}""",
                    ),
                ).status,
            )
            assertEquals(200, api.handle(LocalRequest("DELETE", "/api/accounts/3")).status)
            assertTrue("至少要被调用几次", backend.probes >= 4)
        }
    }

    // ---------------- 工具 ----------------

    /**
     * 用写接口的种子建库，并给出注入假 [SyncBackend] 的 [LocalApi]。
     *
     * 缺省用 [RecordingBackend]：只记录调用，探测与服务器动作都成功。
     */
    private fun withWriteApi(
        block: (LocalApi, Db, RecordingBackend) -> Unit,
    ) {
        val seed = ApiSeed.load("write_seed")
        withTempDb(FakeClock(seed.now)) { db ->
            seedDatabase(db, seed)
            val backend = RecordingBackend()
            block(apiFor(db, seed, sync = backend), db, backend)
        }
    }

    /** 假 [SyncBackend]：记录调用（`"kind:id"` 形式的字符串），一律成功。 */
    private class RecordingBackend : SyncBackend {

        val calls = ArrayList<String>()

        override fun triggerAccount(id: Int) {
            calls.add("trigger:$id")
        }

        override fun triggerAll() {
            calls.add("trigger_all")
        }

        override fun forgetAccount(id: Int) {
            calls.add("forget:$id")
        }

        override fun probe(account: SyncAccount, credential: String) {
            calls.add("probe:${account.provider}")
        }

        override fun applyServerAction(ids: List<Long>, action: String): ServerActionResult {
            calls.add("server:$action")
            return ServerActionResult(ids, emptyList())
        }
    }

    /**
     * 假 [SyncBackend]，每次被调用都验证「此刻另一条线程还能用数据库」。
     *
     * 这条是硬性约束（见 `Db.transaction` 的 KDoc）：同步会在别的线程上抢同一把锁，
     * 在事务里调用 [SyncBackend] 必然死锁，所以这里用一条真正并发的查询去证明没有那样做。
     */
    private class LockProbingSyncBackend(private val db: Db) : SyncBackend {

        var probes = 0
            private set

        override fun triggerAccount(id: Int) = checkDbIsFree("triggerAccount")

        override fun triggerAll() = checkDbIsFree("triggerAll")

        override fun forgetAccount(id: Int) = checkDbIsFree("forgetAccount")

        override fun probe(account: SyncAccount, credential: String) = checkDbIsFree("probe")

        override fun applyServerAction(ids: List<Long>, action: String): ServerActionResult {
            checkDbIsFree("applyServerAction")
            return ServerActionResult(ids, emptyList())
        }

        private fun checkDbIsFree(what: String) {
            probes++
            val done = CountDownLatch(1)
            val thread = Thread {
                try {
                    db.queryOne("SELECT 1 AS one")
                } finally {
                    done.countDown()
                }
            }
            thread.isDaemon = true
            thread.start()
            assertTrue(
                "$what 被调用时另一条线程拿不到数据库锁：SyncBackend 不能在 Db.transaction 里调用",
                done.await(10, TimeUnit.SECONDS),
            )
        }
    }

    private fun sessionCount(db: Db): Long =
        db.queryOne("SELECT COUNT(*) c FROM sessions")?.long("c") ?: 0L

    private fun initialImportKeys(db: Db): List<String> = db
        .query("SELECT k FROM settings WHERE k LIKE 'local_initial_import_%' ORDER BY k")
        .mapNotNull { it.str("k") }

    /** `settings` 表的全部键值，用来断言「拒绝时不写任何一条」。 */
    private fun settingSnapshot(db: Db): Map<String, String> =
        db.query("SELECT k, v FROM settings ORDER BY k")
            .associate { (it.str("k") ?: "") to (it.str("v") ?: "") }

    private fun LocalResponse.detailText(): String =
        body.jsonObject.getValue("detail").jsonPrimitive.content
}
