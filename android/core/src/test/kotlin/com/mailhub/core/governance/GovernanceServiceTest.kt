package com.mailhub.core.governance

import com.mailhub.core.db.Db
import com.mailhub.core.db.FakeClock
import com.mailhub.core.db.withTempDb
import com.mailhub.core.rules.loadCustomRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * L8 的补充测试：并发运行互斥、运行中途失败的回滚与留痕，以及 L9 会用到的
 * 账单重新定级、模板键回填、自定义规则读取。
 *
 * 全部是合成数据；时钟固定为 [FakeClock]。
 */
class GovernanceServiceTest {

    @Test
    fun concurrentRunReturnsBusyForTheSecondCaller() {
        withTempDb(clock()) { db ->
            db.initDb()
            insertAccount(db, 1)
            insertMessage(db, id = 1, uid = 1, category = "通知")
            db.setSetting("ai_enabled", "1")

            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            // 测试用的 AI 实现：进入后阻塞，让第一个运行停在「AI 判定」阶段。
            val blockingAi = object : GovernanceAi {
                override val available: Boolean get() = true

                override fun classifyBatch(mails: List<Map<String, Any?>>): List<Map<String, Any?>> {
                    entered.countDown()
                    release.await(30, TimeUnit.SECONDS)
                    return emptyList()
                }
            }
            val service = GovernanceService(db, clock(), blockingAi)
            val pool = Executors.newSingleThreadExecutor()
            try {
                val first = pool.submit<GovernanceRunResult> { service.runGovernance(trigger = "first") }
                assertTrue("第一个运行没有进入 AI 阶段", entered.await(30, TimeUnit.SECONDS))

                val second = service.runGovernance(trigger = "second")
                assertTrue(second.busy)
                assertFalse(second.ok)

                release.countDown()
                val done = first.get(30, TimeUnit.SECONDS)
                assertTrue(done.ok)
                assertFalse(done.running)

                // 只有第一个运行被记录；第二个在进程内锁上就被挡回，没有碰数据库。
                val runs = db.queryOne("SELECT COUNT(*) AS c FROM governance_runs")!!.long("c")!!
                assertEquals(1L, runs)
                assertEquals("first", db.queryOne(
                    "SELECT trigger FROM governance_runs ORDER BY id DESC LIMIT 1",
                )!!.str("trigger"))
            } finally {
                release.countDown()
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun failedRunRollsBackChunkAndRecordsAndAuditsFailure() {
        withTempDb(clock()) { db ->
            db.initDb()
            insertAccount(db, 1)
            insertMessage(db, id = 1, uid = 1, category = "通知")
            insertMessage(db, id = 2, uid = 2, category = "重要")
            // 让治理的 UPDATE 在第一条就失败：整块事务必须回滚。
            db.execute(
                """CREATE TRIGGER governance_fail BEFORE UPDATE ON messages
                   WHEN NEW.governance_version='mailhub-three-six-v2'
                   BEGIN SELECT RAISE(ABORT, 'boom'); END""",
            )
            val service = GovernanceService(db, clock())

            assertThrows(Exception::class.java) { service.runGovernance(trigger = "broken") }

            // 本块回滚：邮件没被改写，也没有留下决策。
            assertEquals(0L, db.queryOne(
                "SELECT COUNT(*) AS c FROM messages WHERE governance_version<>''",
            )!!.long("c"))
            assertEquals(0L, db.queryOne(
                "SELECT COUNT(*) AS c FROM governance_decisions",
            )!!.long("c"))
            val run = db.queryOne("SELECT * FROM governance_runs ORDER BY id DESC LIMIT 1")!!
            assertEquals("failed", run.str("status"))
            assertEquals(1L, run.long("error_count"))
            assertTrue(run.str("error")!!.contains("boom"))

            // 审计写入，标记为被拦截。
            val audit = db.queryOne("SELECT * FROM audit_log ORDER BY id DESC LIMIT 1")!!
            assertEquals("governance_run", audit.str("action"))
            assertEquals(0L, audit.long("allowed"))
            assertTrue(audit.str("reason")!!.contains("三省六部自动分拣失败"))

            // 进程内锁已经释放：去掉触发器后可以继续治理。
            db.execute("DROP TRIGGER governance_fail")
            val again = service.runGovernance(trigger = "retry")
            assertTrue(again.ok)
            assertEquals(
                2,
                (again.status.lastRun!!["processed_count"] as Number).toInt(),
            )
        }
    }

    @Test
    fun aiEnabledWithoutImplementationStillUsesClosedPath() {
        withTempDb(clock()) { db ->
            db.initDb()
            insertAccount(db, 1)
            insertMessage(db, id = 1, uid = 1, category = "其他")
            db.setSetting("ai_enabled", "1")

            // 默认 GovernanceAi.Disabled：即使设置为 1，也按 AI 关闭处理。
            val result = GovernanceService(db, clock()).runGovernance(trigger = "no-ai")
            assertTrue(result.ok)
            val row = db.queryOne(
                "SELECT category, governance_source FROM messages WHERE id=1",
            )!!
            assertEquals("其他", row.str("category"))
            assertEquals("ai_disabled", row.str("governance_source"))

            val rows = GovernanceService(db, clock()).loadMessages(listOf(1L))
            val aiResults = GovernanceService(db, clock()).aiResults(
                rows,
                enabled = false,
                cfg = emptyMap(),
                now = clock().now(),
            )
            assertTrue(aiResults.byId.isEmpty())
            assertEquals(0, aiResults.callCount)
        }
    }

    @Test
    fun retierBillsUsesRoutineCountsPreferencesAndLocks() {
        withTempDb(clock()) { db ->
            db.initDb()
            insertAccount(db, 1)
            db.setSetting("bill_routine_count", "2")
            db.setSetting("bill_amount_threshold", "200")
            val now = clock().now()
            // 同一模板两封：例行流水 → 一般；金额超过阈值的那封仍按金额定级 → 重要。
            insertBill(db, 1, 1, "订单确认 12345", "订单金额 ¥250.00", now - 100, "example.com|订单确认 #")
            insertBill(db, 2, 2, "订单确认 67890", "订单金额 ¥150.00", now - 200, "example.com|订单确认 #")
            // 用户偏好优先。
            db.execute(
                "INSERT INTO bill_prefs(template_key, tier, created_ts) VALUES(?,?,?)",
                "example.com|月度订阅扣款",
                "一般",
                now,
            )
            insertBill(db, 3, 3, "月度订阅扣款", "本期扣款明细", now - 300, "example.com|月度订阅扣款")
            // 手动锁定的等级保持不变（画像原样读出）。
            insertBill(db, 4, 4, "手动锁定账单", "用户已手动设为一般", now - 400,
                "example.com|手动锁定账单", tier = "一般", locked = 1,
                facets = "{\"direction\": \"out\"}")
            // 已停用的无效偏好按没有偏好处理。
            db.execute(
                "INSERT INTO bill_prefs(template_key, tier, created_ts) VALUES(?,?,?)",
                "example.com|待确认账单",
                "普通",
                now,
            )
            insertBill(db, 5, 5, "待确认账单", "请核对本期账目", now - 500, "example.com|待确认账单")

            val changed = GovernanceService(db, clock()).retierBills()
            assertEquals(4, changed)
            assertEquals("重要", tier(db, 1))
            assertEquals("一般", tier(db, 2))
            assertEquals("一般", tier(db, 3))
            assertEquals("一般", tier(db, 4))
            // 锁定行的画像原样保留，没有被重新抽取覆盖。
            assertEquals("{\"direction\": \"out\"}", facets(db, 4))
            // 第二次运行没有变化。
            assertEquals(0, GovernanceService(db, clock()).retierBills())
        }
    }

    @Test
    fun backfillTemplateKeysFillsOnlyEmptyKeys() {
        withTempDb(clock()) { db ->
            db.initDb()
            insertAccount(db, 1)
            insertMessage(db, id = 1, uid = 1, category = "账单",
                subject = "您的订单收据(2026年10月18日)", sender = "shop@example.com")
            insertMessage(db, id = 2, uid = 2, category = "通知", subject = "周报",
                sender = "news@example.com", templateKey = "example.com|已有键")
            val total = GovernanceService(db, clock()).backfillTemplateKeys(batch = 10)
            assertEquals(1, total)
            assertEquals("example.com|您的订单收据",
                db.queryOne("SELECT template_key FROM messages WHERE id=1")!!.str("template_key"))
            assertEquals("example.com|已有键",
                db.queryOne("SELECT template_key FROM messages WHERE id=2")!!.str("template_key"))
        }
    }

    @Test
    fun loadCustomRulesFiltersDisabledAndOrdersByPriority() {
        withTempDb(clock()) { db ->
            db.initDb()
            db.execute(
                "INSERT INTO rules(name, field, pattern, category, priority, enabled) VALUES(?,?,?,?,?,?)",
                "低优先", "subject", "低", "通知", 50, 1,
            )
            db.execute(
                "INSERT INTO rules(name, field, pattern, category, priority, enabled) VALUES(?,?,?,?,?,?)",
                "停用", "subject", "停用", "订阅", 1, 0,
            )
            db.execute(
                "INSERT INTO rules(name, field, pattern, category, priority, enabled) VALUES(?,?,?,?,?,?)",
                "高优先", "body", "高", "重要", 1, 1,
            )
            val rules = loadCustomRules(db)
            assertEquals(listOf("高", "低"), rules.map { it.pattern })
            assertEquals("重要", rules[0].category)
            assertEquals("body", rules[0].field)
        }
    }

    @Test
    fun alertsUseBillDisplayCategoryAndAreQueuedOnlyOnce() {
        withTempDb(clock()) { db ->
            db.initDb()
            insertAccount(db, 1, name = "工作邮箱")
            val now = clock().now()
            db.execute(
                """INSERT INTO messages(id, account_id, uid, subject, sender_addr, date_ts,
                                        body_text, snippet, category, created_ts, importance)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                1, 1, 1, "Payment failed for your subscription", "billing@example.com",
                now - 100, "We were unable to charge your card.", "支付失败", "账单",
                now - 600, 0,
            )
            db.execute(
                """INSERT INTO messages(id, account_id, uid, subject, sender_addr, date_ts,
                                        body_text, snippet, category, created_ts, importance)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                2, 1, 2, "面试邀请", "hr@corp.example", now - 200, "请于本周五参加面试",
                "面试邀请", "重要", now - 600, 0,
            )
            val service = GovernanceService(db, clock())

            val first = service.runGovernance(trigger = "alerts")
            assertEquals(2, first.alerts.size)
            val billAlert = first.alerts.first { (it["subject"] as String).startsWith("Payment") }
            assertEquals("重要账单", billAlert["category"])
            assertEquals("工作邮箱", billAlert["account_name"])
            assertEquals("", billAlert["otp_code"])
            val importantAlert = first.alerts.first { it["subject"] == "面试邀请" }
            assertEquals("重要", importantAlert["category"])
            assertTrue(
                db.query("SELECT alert_ts FROM messages ORDER BY id").all {
                    (it.double("alert_ts") ?: 0.0) > 0
                },
            )

            // 重新治理（例如版本升级）不会重复排队推送。
            db.execute("UPDATE messages SET governance_version=''")
            val second = service.runGovernance(trigger = "again")
            assertTrue(second.alerts.isEmpty())
        }
    }

    // ---------------- 辅助 ----------------

    private fun clock() = FakeClock(1_760_000_000.0)

    private fun insertAccount(db: Db, id: Int, name: String = "测试账户") {
        db.execute(
            """INSERT INTO accounts(id, name, provider, email, imap_host, imap_port,
                                    auth_type, enabled, created_ts)
               VALUES(?,?,?,?,?,?,?,1,0)""",
            id,
            name,
            "custom",
            "user$id@example.test",
            "imap.example.test",
            993,
            "password",
        )
    }

    private fun insertMessage(
        db: Db,
        id: Int,
        uid: Int,
        category: String,
        subject: String = "合成邮件",
        sender: String = "sender@example.test",
        templateKey: String = "",
    ) {
        db.execute(
            """INSERT INTO messages(id, account_id, uid, subject, sender_addr, date_ts,
                                    body_text, snippet, category, template_key)
               VALUES(?,?,?,?,?,?,?,?,?,?)""",
            id,
            1,
            uid,
            subject,
            sender,
            1_759_999_000.0,
            "正文",
            "摘要",
            category,
            templateKey,
        )
    }

    private fun insertBill(
        db: Db,
        id: Int,
        uid: Int,
        subject: String,
        body: String,
        dateTs: Double,
        templateKey: String,
        tier: String = "",
        locked: Int = 0,
        facets: String = "{}",
    ) {
        db.execute(
            """INSERT INTO messages(id, account_id, uid, subject, sender_addr, date_ts,
                                    body_text, snippet, category, template_key, bill_tier,
                                    bill_facets, bill_tier_locked, importance)
               VALUES(?,?,?,?,?,?,?,?, '账单', ?,?,?,?,?)""",
            id,
            1,
            uid,
            subject,
            "shop@example.com",
            dateTs,
            body,
            body.take(20),
            templateKey,
            tier,
            facets,
            locked,
            2,
        )
    }

    private fun tier(db: Db, id: Int): String? =
        db.queryOne("SELECT bill_tier FROM messages WHERE id=?", id)!!.str("bill_tier")

    private fun facets(db: Db, id: Int): String? =
        db.queryOne("SELECT bill_facets FROM messages WHERE id=?", id)!!.str("bill_facets")
}
