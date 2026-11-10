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
 * L8-fix 新增的两类（金标覆盖不到，只能用单元测试打）：
 * - 进程被杀后留下的孤儿运行记录（Kotlin 与 Python 在这一点上行为不同，金标里不放这种用例）；
 * - 快照之后用户手动锁定账单等级的并发窗口，以及失败补记本身也失败时的根因保留。
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

    // ---------------- 孤儿运行、并发锁定与失败路径 ----------------

    @Test
    fun orphanRunningRowIsRecoveredImmediatelyEvenAfterClockRollback() {
        val clock = clock()
        withTempDb(clock) { db ->
            db.initDb()
            insertAccount(db, 1)
            insertMessage(db, id = 1, uid = 1, category = "通知")
            val now = clock.now()
            // 被系统杀掉的进程留下的 running 行：刚写进去，还远没到 15 分钟。
            db.execute(
                "INSERT INTO governance_runs(version, trigger, status, started_ts) VALUES(?,?,?,?)",
                Governance.VERSION,
                "killed",
                "running",
                now,
            )
            val service = GovernanceService(db, clock)
            // 库里残留的 running 不再让状态显示「运行中」。
            assertFalse(service.governanceStatus().running)
            // 用户把系统时间往回调：孤儿行的 started_ts 反而比新的运行更晚，也不能耽误回收。
            clock.current = now - 3600

            val result = service.runGovernance(trigger = "after-kill")
            assertTrue(result.ok)
            assertFalse(result.busy)
            assertEquals(1, (result.status.lastRun!!["processed_count"] as Number).toInt())

            val rows = db.query("SELECT * FROM governance_runs ORDER BY id")
            assertEquals(2, rows.size)
            assertEquals("failed", rows[0].str("status"))
            assertEquals(1L, rows[0].long("error_count"))
            assertEquals("运行进程中断，锁已自动回收", rows[0].str("error"))
            assertEquals(now - 3600, rows[0].double("finished_ts")!!, 1e-9)
            assertEquals("completed", rows[1].str("status"))
        }
    }

    @Test
    fun claimRunRecoversStaleRunningRowFromDatabase() {
        val clock = clock()
        withTempDb(clock) { db ->
            db.initDb()
            val now = clock.now()
            db.execute(
                "INSERT INTO governance_runs(version, trigger, status, started_ts) VALUES(?,?,?,?)",
                Governance.VERSION,
                "stale",
                "running",
                now - Governance.RUN_STALE_SECONDS - 1,
            )
            // 没有持有 RUN_LOCK：退回 Python 的 15 分钟判定，这一行正好超过窗口。
            val runId = GovernanceService(db, clock).claimRun("after-stale", now)
            assertTrue("陈旧的运行没有被回收", runId > 0L)
            val rows = db.query("SELECT * FROM governance_runs ORDER BY id")
            assertEquals(2, rows.size)
            assertEquals("failed", rows[0].str("status"))
            assertEquals("运行进程中断，锁已自动回收", rows[0].str("error"))
            assertEquals(1L, rows[0].long("error_count"))
            assertEquals("running", rows[1].str("status"))
            assertEquals(runId, rows[1].long("id"))
        }
    }

    @Test
    fun claimRunReturnsZeroWhenLiveRunningRowExists() {
        val clock = clock()
        withTempDb(clock) { db ->
            db.initDb()
            val now = clock.now()
            db.execute(
                "INSERT INTO governance_runs(version, trigger, status, started_ts) VALUES(?,?,?,?)",
                Governance.VERSION,
                "live",
                "running",
                now - 60,
            )
            // 未持有 RUN_LOCK 时仍按 15 分钟判定：窗口内的运行算「已有运行」，返回 0。
            assertEquals(0L, GovernanceService(db, clock).claimRun("second", now))
            val rows = db.query("SELECT * FROM governance_runs ORDER BY id")
            assertEquals(1, rows.size)
            assertEquals("running", rows[0].str("status"))
            assertEquals("live", rows[0].str("trigger"))
        }
    }

    @Test
    fun shangshuApplySkipsMessageDeletedAfterSnapshot() {
        val clock = clock()
        withTempDb(clock) { db ->
            db.initDb()
            insertAccount(db, 1)
            insertMessage(db, id = 1, uid = 1, category = "通知")
            val service = GovernanceService(db, clock)
            val runId = service.claimRun("test", clock.now())
            val (message, verdict) = snapshotVerdict(service, 1L)
            // 中书省选出之后、落库之前被并发移入回收站。
            db.execute("UPDATE messages SET deleted_ts=? WHERE id=1", clock.now())

            assertFalse(service.shangshuApply(runId, message, verdict, clock.now()))
            assertEquals(0L, db.queryOne(
                "SELECT COUNT(*) AS c FROM governance_decisions",
            )!!.long("c"))
            assertEquals("", db.queryOne(
                "SELECT governance_version FROM messages WHERE id=1",
            )!!.str("governance_version"))
        }
    }

    @Test
    fun shangshuApplyKeepsTierLockedAfterSnapshot() {
        val clock = clock()
        withTempDb(clock) { db ->
            db.initDb()
            insertAccount(db, 1)
            val now = clock.now()
            insertBill(db, 1, 1, "订单确认 12345", "订单金额 ¥250.00", now - 100,
                "example.com|订单确认 #")
            val service = GovernanceService(db, clock)
            val runId = service.claimRun("test", now)
            // 快照里 bill_tier_locked=0；随后用户手动设了等级并锁定。
            val (message, verdict) = snapshotVerdict(service, 1L)
            db.execute("UPDATE messages SET bill_tier='重要', bill_tier_locked=1 WHERE id=1")

            assertFalse(service.shangshuApply(runId, message, verdict, now))
            val row = db.queryOne("SELECT * FROM messages WHERE id=1")!!
            assertEquals("用户手动设定的等级被覆盖了", "重要", row.str("bill_tier"))
            assertEquals(1L, row.long("bill_tier_locked"))
            assertEquals("", row.str("governance_version"))
            assertEquals(0L, db.queryOne(
                "SELECT COUNT(*) AS c FROM governance_decisions",
            )!!.long("c"))
        }
    }

    @Test
    fun retierBillsDoesNotOverwriteTierLockedAfterSnapshot() {
        val clock = clock()
        withTempDb(clock) { db ->
            db.initDb()
            insertAccount(db, 1)
            val now = clock.now()
            // 两封都在快照里（未锁定），且都需要重新定级。
            insertBill(db, 1, 1, "订单确认 12345", "订单金额 ¥250.00", now - 100,
                "example.com|订单确认 #")
            insertBill(db, 2, 2, "返现已到账", "您的账户已收到返现 2.30 元", now - 200,
                "example.com|返现已到账")
            // 第一行落库之后、第二行落库之前被用户手动锁定：用 AFTER UPDATE 触发器模拟这段
            // 并发窗口（两行都在同一个事务里，只有数据库自己能在中间插进来）。两行按 rowid
            // 升序返回，所以「先 1 后 2」是确定的；下面的断言会连带确认触发器确实跑了。
            db.execute(
                """CREATE TRIGGER lock_after_snapshot AFTER UPDATE ON messages WHEN NEW.id=1
                   BEGIN
                       UPDATE messages SET bill_tier='重要', bill_facets='{"direction": "out"}',
                                           bill_tier_locked=1
                       WHERE id=2;
                   END""",
            )

            val changed = GovernanceService(db, clock).retierBills()

            // 第一行照常改写；第二行命中 UPDATE 的 bill_tier_locked=0 条件，被跳过。
            assertEquals(1, changed)
            assertEquals("重要", tier(db, 2))
            assertEquals("{\"direction\": \"out\"}", facets(db, 2))
            assertEquals(1L, db.queryOne(
                "SELECT bill_tier_locked FROM messages WHERE id=2",
            )!!.long("bill_tier_locked"))
            assertEquals("重要", tier(db, 1))
        }
    }

    @Test
    fun retierBillsCommitsInChunks() {
        val clock = clock()
        withTempDb(clock) { db ->
            db.initDb()
            insertAccount(db, 1)
            val now = clock.now()
            val total = Governance.CHUNK_SIZE * 2 + 1
            // 一次超过两个分块，验证分块循环把每一块都提交了。
            db.transaction {
                for (i in 1..total) {
                    db.execute(
                        """INSERT INTO messages(id, account_id, uid, subject, sender_addr, date_ts,
                                                body_text, snippet, category, template_key,
                                                bill_tier, importance)
                           VALUES(?,?,?,?,?,?,?,?, '账单', ?,?,?)""",
                        i,
                        1,
                        i,
                        "返现到账$i",
                        "cash@example.com",
                        now - i,
                        "您的账户已收到返现 2.30 元",
                        "返现 2.30 元",
                        "example.com|返现到账#$i",
                        "",
                        0,
                    )
                }
            }

            // 第二个分块的第一行落库时失败：分块提交时第一个分块必须已经落盘。
            val failing = Governance.CHUNK_SIZE + 1
            db.execute(
                """CREATE TRIGGER retier_fail BEFORE UPDATE ON messages WHEN NEW.id=$failing
                   BEGIN SELECT RAISE(ABORT, 'boom'); END""",
            )
            val service = GovernanceService(db, clock)
            assertThrows(Exception::class.java) { service.retierBills() }
            assertEquals(Governance.CHUNK_SIZE.toLong(), db.queryOne(
                "SELECT COUNT(*) AS c FROM messages WHERE bill_tier<>''",
            )!!.long("c"))

            // 去掉触发器重跑：剩下的是一个分块的量，retier 幂等，补完就一致。
            db.execute("DROP TRIGGER retier_fail")
            assertEquals(total - Governance.CHUNK_SIZE, service.retierBills())
            assertEquals(0L, db.queryOne(
                "SELECT COUNT(*) AS c FROM messages WHERE bill_tier=''",
            )!!.long("c"))
        }
    }

    @Test
    fun failedRunKeepsRootCauseWhenBookkeepingAlsoFails() {
        val clock = clock()
        withTempDb(clock) { db ->
            db.initDb()
            insertAccount(db, 1)
            insertMessage(db, id = 1, uid = 1, category = "通知")
            // 治理本身在第一条 UPDATE 就失败。
            db.execute(
                """CREATE TRIGGER governance_fail BEFORE UPDATE ON messages
                   WHEN NEW.governance_version='mailhub-three-six-v2'
                   BEGIN SELECT RAISE(ABORT, 'boom'); END""",
            )
            // 补记失败状态和写审计也写不进去（磁盘满、连接已被关闭的等价物）。
            db.execute(
                """CREATE TRIGGER runs_fail BEFORE UPDATE ON governance_runs
                   WHEN NEW.status='failed'
                   BEGIN SELECT RAISE(ABORT, '写失败记录时磁盘满'); END""",
            )
            db.execute(
                """CREATE TRIGGER audit_fail BEFORE INSERT ON audit_log
                   BEGIN SELECT RAISE(ABORT, '审计也写不进去'); END""",
            )
            val service = GovernanceService(db, clock)

            val error = assertThrows(Exception::class.java) { service.runGovernance(trigger = "broken") }

            assertTrue("根因被换掉了：${error.message}", error.message!!.contains("boom"))
            val suppressed = error.suppressed.map { it.message ?: "" }
            assertEquals("补记失败的异常没有全部挂到原异常上：$suppressed", 2, suppressed.size)
            assertTrue(suppressed.any { it.contains("写失败记录时磁盘满") })
            assertTrue(suppressed.any { it.contains("审计也写不进去") })
            // 运行记录停在 running（补记失败），邮件没有被改写。
            assertEquals("running", db.queryOne(
                "SELECT status FROM governance_runs ORDER BY id DESC LIMIT 1",
            )!!.str("status"))
            assertEquals(0L, db.queryOne(
                "SELECT COUNT(*) AS c FROM messages WHERE governance_version<>''",
            )!!.long("c"))

            // 进程内锁已经释放：去掉全部触发器后可以继续治理。
            db.execute("DROP TRIGGER governance_fail")
            db.execute("DROP TRIGGER runs_fail")
            db.execute("DROP TRIGGER audit_fail")
            assertTrue(service.runGovernance(trigger = "retry").ok)
        }
    }

    @Test
    fun nonFiniteAiConfidenceIsTreatedAsZero() {
        val now = clock().now()
        val message = linkedMapOf<String, Any?>(
            "id" to 1L,
            "category" to "其他",
            "subject" to "本周周报",
            "sender_addr" to "news@example.com",
            "snippet" to "本周摘要",
            "body_text" to "正文",
            "date_ts" to now - 60,
            "created_ts" to now - 60,
        )
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val aiResult = linkedMapOf<String, Any?>(
                "id" to 1L,
                "category" to "通知",
                "confidence" to bad,
                "needs_review" to false,
            )
            val verdict = Governance.menxiaArbitrate(
                Governance.zhongshuPropose(message, aiResult, aiEnabled = true),
            )
            assertTrue("$bad 通过了护栏", verdict.fallback)
            assertEquals("fallback", verdict.source)
            assertTrue("置信度不是有限值：${verdict.confidence}", verdict.confidence.isFinite())
            assertEquals("$bad 应该按 0 处理", 0.0, verdict.confidence, 1e-9)
        }

        // 写库前必须清掉 NaN：SQLite 会把 NaN 存成 NULL。
        val clock = clock()
        withTempDb(clock) { db ->
            db.initDb()
            insertAccount(db, 1)
            insertMessage(db, id = 1, uid = 1, category = "其他", subject = "本周周报",
                sender = "news@example.com")
            val service = GovernanceService(db, clock)
            val runId = service.claimRun("test", clock.now())
            val row = service.loadMessages(listOf(1L)).single()
            val aiResult = linkedMapOf<String, Any?>(
                "id" to 1L,
                "category" to "通知",
                "confidence" to Double.NaN,
                "needs_review" to false,
            )
            val verdict = Governance.menxiaArbitrate(
                Governance.zhongshuPropose(row, aiResult, aiEnabled = true),
            )
            assertTrue(service.shangshuApply(runId, row, verdict, clock.now()))
            val stored = db.queryOne(
                "SELECT confidence, typeof(confidence) AS kind FROM messages WHERE id=1",
            )!!
            assertEquals("real", stored.str("kind"))
            assertEquals(0.0, stored.double("confidence")!!, 1e-9)
        }
    }

    @Test
    fun runResultMapOmitsAlerts() {
        val result = GovernanceRunResult(
            ok = false,
            busy = true,
            running = true,
            alerts = listOf(linkedMapOf<String, Any?>("subject" to "合成邮件")),
            status = GovernanceStatus(
                version = Governance.VERSION,
                running = true,
                pending = 0,
                ministries = Governance.MINISTRIES,
                lastRun = null,
            ),
        )
        val map = result.toMap()
        // app.py 先 pop("alerts") 再返回，接口响应里没有这个键；busy 对应 HTTP 409。
        assertFalse("alerts 不能进接口响应", map.containsKey("alerts"))
        assertEquals(true, map["busy"])
        assertEquals(1, result.alerts.size)
    }

    // ---------------- 辅助 ----------------

    private fun clock() = FakeClock(1_760_000_000.0)

    /** 按中书省的读取方式取一份快照，并裁决出对应的 [Verdict]。 */
    private fun snapshotVerdict(service: GovernanceService, id: Long): Pair<Map<String, Any?>, Verdict> {
        val message = service.loadMessages(listOf(id)).single()
        val verdict = Governance.menxiaArbitrate(
            Governance.zhongshuPropose(message, aiResult = null, aiEnabled = false),
        )
        return message to verdict
    }

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
