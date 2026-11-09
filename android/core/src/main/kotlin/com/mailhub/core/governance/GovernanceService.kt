package com.mailhub.core.governance

import com.mailhub.core.Clock
import com.mailhub.core.SystemClock
import com.mailhub.core.billing.Billing
import com.mailhub.core.db.Db
import com.mailhub.core.db.ExecResult
import com.mailhub.core.db.toJsonText
import com.mailhub.core.rules.Rules
import com.mailhub.core.text.Py
import com.mailhub.core.text.cpSlice
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Logger

/** `_ai_results` 的返回值：按邮件 id 索引的判定，以及实际送给 AI 的封数。 */
data class AiResults(
    val byId: Map<Long, Map<String, Any?>>,
    val callCount: Int,
)

/**
 * 三省六部治理里用到数据库的部分，逐函数移植 Python `governance.py`；纯逻辑在
 * [Governance]。
 *
 * 与服务器的两个结构性差异（见 `Db` 的 KDoc）：
 * - Python 每个线程一个 `sqlite3.Connection`，这里只有**一个** `Db`，所有访问串行化；
 * - Python 的 `BEGIN IMMEDIATE`（`_claim_run`）改成 [Db.transaction]；每块末尾的
 *   `conn.commit()` 同样对应一个 [Db.transaction]。
 *
 * **锁内不做耗时操作**：AI 判定与裁决（[Governance.zhongshuPropose] /
 * [Governance.menxiaArbitrate] / [applyBillTier]）都在事务外完成，事务里只写库。
 * P1 接真实 AI 时，AI 请求也必须在这把数据库锁之外完成。
 *
 * 时间全部来自注入的 [Clock]（对应 `time.time()`）。
 */
class GovernanceService(
    private val db: Db,
    private val clock: Clock = SystemClock,
    private val ai: GovernanceAi = GovernanceAi.Disabled,
) {

    private val log: Logger = Logger.getLogger("com.mailhub.core.governance.Governance")

    // ---------------- 账单分级 ----------------

    /** 对应 Python `bill_config`：读取账单策略设置在数据库里的原文（空串表示没有设置）。 */
    fun billConfig(): Map<String, String> = linkedMapOf(
        "amount_threshold" to db.getSetting("bill_amount_threshold", ""),
        "routine_count" to db.getSetting("bill_routine_count", ""),
        "auto_renew" to db.getSetting("bill_auto_renew", ""),
        "refund" to db.getSetting("bill_refund", ""),
    )

    /** 对应 Python `display_category`，供 L9 的接口直接使用。 */
    fun displayCategory(category: String, billTier: String = ""): String =
        Governance.displayCategory(category, billTier)

    /** 对应 Python `_routine_count`：同类模板键在 30 天窗口内的邮件数（不过滤回收站）。 */
    fun routineCount(key: String, now: Double): Int {
        if (key.isEmpty()) return 0
        val row = db.queryOne(
            "SELECT COUNT(*) AS c FROM messages WHERE template_key=? AND date_ts >= ?",
            key,
            now - Billing.ROUTINE_WINDOW_SECONDS,
        )
        return row?.long("c")?.toInt() ?: 0
    }

    /** 对应 Python `_preference`：同类账单的用户偏好；非法等级按没有偏好处理。 */
    fun preference(key: String): String {
        if (key.isEmpty()) return ""
        val row = db.queryOne("SELECT tier FROM bill_prefs WHERE template_key=?", key)
        val tier = row?.str("tier") ?: return ""
        return if (tier in Billing.TIERS) tier else ""
    }

    /** 对应 Python `_stored_facets`：解析库里存的交易画像 JSON；失败或不是对象时返回空字典。 */
    fun storedFacets(row: Map<String, Any?>): Map<String, Any?> {
        val raw = row["bill_facets"]
        // Python 是 json.loads(row.get("bill_facets") or "{}")：非字符串会抛 TypeError 并被吞掉
        if (raw !is String) return emptyMap()
        val text = if (raw.isEmpty()) "{}" else raw
        val parsed = parseJson(text)
        if (parsed !is Map<*, *>) return emptyMap()
        return parsed.entries.associateTo(LinkedHashMap()) { it.key.toString() to it.value }
    }

    /**
     * 对应 Python `decide_bill`：为一封账单计算 `{tier, facets, reason}`。
     * 用户手动锁定的等级保持不变。
     *
     * `routineCounts` / `preferences` 是批量预先查好的同类统计与偏好（键为模板键）：
     * 传了就只用字典，不再逐封查库；不传则沿用原来的单封查询。
     */
    fun decideBill(
        row: Map<String, Any?>,
        aiFacets: Any? = null,
        cfg: Map<String, Any?>? = null,
        now: Double? = null,
        reuseFacets: Boolean = false,
        routineCounts: Map<String, Long>? = null,
        preferences: Map<String, String>? = null,
    ): BillVerdict {
        val current = now ?: clock.now()
        val lockedTier = row["bill_tier"]
        if (Py.truthy(row["bill_tier_locked"]) && lockedTier is String &&
            lockedTier in Billing.TIERS
        ) {
            return BillVerdict(lockedTier, storedFacets(row), "用户手动设定")
        }
        val rawKey = row["template_key"]
        val key = if (Py.truthy(rawKey)) {
            pyStr(rawKey)
        } else {
            Billing.templateKey(pyStrOrEmpty(row["sender_addr"]), pyStrOrEmpty(row["subject"]))
        }
        var facets = if (reuseFacets) storedFacets(row) else emptyMap<String, Any?>()
        if (!Py.truthy(facets["direction"])) {
            facets = Billing.extractFacets(
                pyStrOrEmpty(row["subject"]),
                pyStrOrEmpty(row["body_text"]),
            )
            facets = Billing.mergeAiFacets(facets, aiFacets)
        }
        val routine: Int
        if (routineCounts == null) {
            routine = routineCount(key, current)
        } else {
            routine = if (key.isEmpty()) 0 else (routineCounts[key] ?: 0L).toInt()
        }
        val preference: String
        if (preferences == null) {
            preference = preference(key)
        } else {
            val value = if (key.isEmpty()) "" else preferences[key] ?: ""
            preference = if (value in Billing.TIERS) value else ""
        }
        val decision = Billing.decideTier(
            facets = facets,
            routineCount = routine.toLong(),
            preference = preference,
            cfg = cfg,
        )
        return BillVerdict(decision.tier, Billing.storedFacets(facets), decision.reason)
    }

    /**
     * 对应 Python `apply_bill_tier`：裁决为「账单」时定级，并按等级调整重要度；
     * 理由只进审计证据和服务日志，不进界面。
     */
    fun applyBillTier(
        row: Map<String, Any?>,
        verdict: Verdict,
        aiResult: Map<String, Any?>?,
        cfg: Map<String, Any?>?,
        now: Double,
    ) {
        if (verdict.category != "账单") {
            verdict.bill = null
            return
        }
        val aiFacets = if (aiResult != null && !Py.truthy(aiResult["needs_review"])) {
            aiResult["bill"]
        } else {
            null
        }
        val bill = decideBill(row, aiFacets = aiFacets, cfg = cfg, now = now)
        verdict.bill = bill
        verdict.importance = Billing.tierImportance(bill.tier, bill.facets, verdict.importance)
        val evidence = LinkedHashMap(verdict.evidence)
        evidence["bill_tier"] = bill.tier
        evidence["bill"] = bill.facets
        evidence["bill_reason"] = bill.reason
        verdict.evidence = evidence
        log.info("账单定级 #${row["id"]} → ${bill.tier}账单：${bill.reason}")
    }

    /**
     * 对应 Python `retier_bills`：不调用 AI，按已存画像（没有时本地抽取）重新定级。
     * 用于升级回填、改设置和用户标记同类后。
     *
     * 与 Python 一样按 JSON **文本**判断 `bill_facets` 是否需要更新；本机写的 JSON 键顺序
     * 固定，文本稳定。返回实际更新的行数。
     */
    fun retierBills(template: String = "", onlyMissing: Boolean = false): Int {
        // 读取与画像计算都在事务外（Python 的第一条 UPDATE 才开始写事务，语义相同），
        // 事务里只做最后的几条 UPDATE，避免长时间占着数据库锁。
        var where = "category='账单' AND deleted_ts=0 AND bill_tier_locked=0"
        val args = mutableListOf<Any?>()
        if (template.isNotEmpty()) {
            where += " AND template_key=?"
            args.add(template)
        }
        if (onlyMissing) {
            where += " AND bill_tier=''"
        }
        val rows = db.query(
            """SELECT id, subject, sender_addr, substr(body_text, 1, ${Governance.BODY_SCAN_CHARS}) AS body_text,
                      importance, template_key, bill_tier, bill_facets, bill_tier_locked
               FROM messages WHERE $where""",
            *args.toTypedArray(),
        )
        val cfg: Map<String, Any?> = billConfig()
        val now = clock.now()
        // 同类统计与偏好各用一条查询预先取好，避免每封账单再各查一次（N+1）。
        // 口径与原单封查询一致：时间窗口相同，统计不过滤 deleted_ts，偏好只认有效等级。
        val routineCounts = LinkedHashMap<String, Long>()
        val preferences = LinkedHashMap<String, String>()
        if (rows.isNotEmpty()) {
            val windowStart = now - Billing.ROUTINE_WINDOW_SECONDS
            for (r in db.query(
                "SELECT template_key AS k, COUNT(*) AS c FROM messages " +
                    "WHERE template_key<>'' AND date_ts >= ? GROUP BY template_key",
                windowStart,
            )) {
                routineCounts[r.str("k") ?: ""] = r.long("c") ?: 0L
            }
            for (r in db.query("SELECT template_key AS k, tier FROM bill_prefs")) {
                preferences[r.str("k") ?: ""] = r.str("tier") ?: ""
            }
        }
        val updates = ArrayList<BillUpdate>()
        for (r in rows) {
            val row = r.toMap()
            val bill = decideBill(
                row,
                cfg = cfg,
                now = now,
                reuseFacets = true,
                routineCounts = routineCounts,
                preferences = preferences,
            )
            val current = pyIntOrNull(row["importance"])
            val importance = Billing.tierImportance(
                bill.tier,
                bill.facets,
                current?.coerceIn(0L, 5L)?.toInt() ?: 0,
            )
            val facetsJson = toJsonText(bill.facets)
            val stored = row["bill_facets"]
            val storedText = if (stored is String && stored.isNotEmpty()) stored else "{}"
            if (bill.tier != row["bill_tier"] || facetsJson != storedText ||
                current == null || importance.toLong() != current
            ) {
                updates.add(BillUpdate(row["id"], bill, facetsJson, importance))
            }
        }
        if (updates.isEmpty()) return 0
        return db.transaction {
            for (update in updates) {
                db.execute(
                    "UPDATE messages SET bill_tier=?, bill_facets=?, importance=? WHERE id=?",
                    update.bill.tier,
                    update.facetsJson,
                    update.importance,
                    update.id,
                )
                log.info("账单重新定级 #${update.id} → ${update.bill.tier}账单：${update.bill.reason}")
            }
            updates.size
        }
    }

    /**
     * 对应 Python `backfill_template_keys`：旧邮件补算模板键（发件域名 + 规整后的标题），
     * 供「同类邮件」统计和偏好使用。每批一次事务，与 Python 每批 `commit()` 一致。
     */
    fun backfillTemplateKeys(batch: Int = 500): Int {
        var total = 0
        while (true) {
            val rows = db.query(
                "SELECT id, sender_addr, subject FROM messages WHERE template_key='' LIMIT ?",
                batch,
            )
            if (rows.isEmpty()) break
            db.executeMany(
                "UPDATE messages SET template_key=? WHERE id=?",
                rows.map { row ->
                    val key = Billing.templateKey(
                        pyStrOrEmpty(row["sender_addr"]),
                        pyStrOrEmpty(row["subject"]),
                    )
                    arrayOf<Any?>(key.ifEmpty { "|" }, row.long("id"))
                },
            )
            total += rows.size
        }
        return total
    }

    // ---------------- 尚书省 ----------------

    /**
     * 对应 Python `shangshu_apply`：仅写入分类元数据和审计轨迹，绝不调用邮箱或外部动作。
     *
     * 与 Python 一样依赖调用方的事务（`run_governance` 每块一个事务）：
     * 邮件可能在中书省选取后被并发移入回收站，此时返回 false，不伪造「已治理」决策。
     */
    fun shangshuApply(runId: Long, message: Map<String, Any?>, verdict: Verdict, ts: Double): Boolean {
        val mid = pyIntOrNull(message["id"])
            ?: throw IllegalArgumentException("message 缺少整数 id：${message["id"]}")
        val (retryCount, retryTs) = Governance.nextRetry(message, verdict, ts)
        val bill = verdict.bill
        val result: ExecResult = db.execute(
            """UPDATE messages
               SET category=?, importance=?, summary=?, confidence=?, ai_reason=?,
                   needs_review=0, ai_done=1, governance_version=?, governance_ministry=?,
                   governance_action=?, governance_source=?, governance_reason=?, governance_ts=?,
                   ai_retry_count=?, ai_retry_ts=?, bill_tier=?, bill_facets=?
               WHERE id=? AND deleted_ts=0""",
            verdict.category,
            verdict.importance,
            verdict.summary,
            verdict.confidence,
            verdict.reason.cpSlice(0, 200),
            Governance.VERSION,
            verdict.ministry,
            verdict.action,
            verdict.source,
            verdict.reason,
            ts,
            retryCount,
            retryTs,
            bill?.tier ?: "",
            toJsonText(bill?.facets ?: emptyMap<String, Any?>()),
            mid,
        )
        if (result.changes != 1L) {
            return false
        }
        db.execute(
            """INSERT INTO governance_decisions
               (run_id, message_id, ts, version, ministry, category_before, category_after,
                confidence, source, fallback, reason, evidence)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
            runId,
            mid,
            ts,
            Governance.VERSION,
            verdict.ministry,
            Governance.safeCategory(message["category"]),
            verdict.category,
            verdict.confidence,
            verdict.source,
            if (verdict.fallback) 1 else 0,
            verdict.reason,
            toJsonText(verdict.evidence),
        )
        return true
    }

    /**
     * 对应 Python `_queue_alert`：把推送标记写进库里（只标记一次），返回交给调用方推送的条目。
     * 本机模式不推送，返回值保留给 L9/L10。
     */
    fun queueAlert(message: Map<String, Any?>, verdict: Verdict, ts: Double): Map<String, Any?>? {
        if (!Governance.wantsAlert(message, verdict, ts)) return null
        val mid = pyIntOrNull(message["id"])
            ?: throw IllegalArgumentException("message 缺少整数 id：${message["id"]}")
        val result = db.execute(
            "UPDATE messages SET alert_ts=? WHERE id=? AND alert_ts=0",
            ts,
            mid,
        )
        if (result.changes != 1L) return null
        return linkedMapOf(
            "account_name" to pyStrOrEmpty(message["account_name"]),
            "subject" to pyStrOrEmpty(message["subject"]),
            "sender_addr" to pyStrOrEmpty(message["sender_addr"]),
            "category" to Governance.displayCategory(
                verdict.category,
                verdict.bill?.tier ?: "",
            ),
            "otp_code" to "",
            "importance" to verdict.importance,
        )
    }

    // ---------------- 选取与读取 ----------------

    /**
     * 对应 Python `_select_ids`：按日期倒序取待治理邮件 id。
     * `sinceTs > 0` 时不论是否已治理过，窗口内的邮件全部重新走一遍。
     */
    fun selectIds(limit: Int, force: Boolean, now: Double, sinceTs: Double = 0.0): List<Long> {
        var where = "m.deleted_ts=0"
        val args = mutableListOf<Any?>()
        if (sinceTs > 0) {
            where += " AND m.date_ts >= ?"
            args.add(sinceTs)
        } else if (!force) {
            where += " AND " + Governance.PENDING_SQL
            args.add(Governance.VERSION)
            args.add(now)
        }
        var sql = "SELECT m.id FROM messages m WHERE $where ORDER BY m.date_ts DESC, m.id DESC"
        if (limit > 0) {
            sql += " LIMIT ?"
            args.add(minOf(10000, maxOf(1, limit)))
        }
        return db.query(sql, *args.toTypedArray()).map {
            it.long("id") ?: error("messages.id 不是整数")
        }
    }

    /**
     * 对应 Python `_load_messages`：按 id 读入一批邮件；正文截断后用于本机注入扫描
     * 和（遮蔽后的）AI 正文摘录。返回顺序与传入的 [ids] 一致。
     */
    fun loadMessages(ids: List<Long>): List<Map<String, Any?>> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val rows = db.query(
            """SELECT m.id, m.category, m.subject, m.sender_addr, m.snippet,
                      substr(m.body_text, 1, ${Governance.BODY_SCAN_CHARS}) AS body_text,
                      m.otp_code, m.importance, m.summary, m.confidence,
                      m.has_attach, m.unsubscribe, m.risk_level, m.needs_review, m.ai_retry_count,
                      m.date_ts, m.created_ts, m.header_signals, m.governance_version, m.alert_ts,
                      m.template_key, m.bill_tier, m.bill_facets, m.bill_tier_locked,
                      a.name AS account_name
               FROM messages m LEFT JOIN accounts a ON a.id=m.account_id
               WHERE m.id IN ($placeholders) AND m.deleted_ts=0""",
            *ids.toTypedArray(),
        )
        val order = ids.withIndex().associate { (index, id) -> id to index }
        return rows.map { it.toMap() }
            .sortedBy { order[pyIntOrNull(it["id"])] ?: Int.MAX_VALUE }
    }

    /** 对应 Python `_select_messages`：兼容旧调用，一次性读入全部待治理邮件。 */
    fun selectMessages(limit: Int, force: Boolean): List<Map<String, Any?>> =
        loadMessages(selectIds(limit = limit, force = force, now = clock.now()))

    // ---------------- 运行 ----------------

    /**
     * 对应 Python `_claim_run`：用 SQLite 写锁领取运行权，避免多 worker 同时治理同一批邮件。
     * Python 的 `BEGIN IMMEDIATE` 在这里是 [Db.transaction]（第一条语句就是 UPDATE，
     * 真正的写锁在那一刻取得）。返回 0 表示已有运行在进行。
     */
    fun claimRun(trigger: String, started: Double): Long = db.transaction {
        db.execute(
            """UPDATE governance_runs
               SET status='failed', finished_ts=?, error_count=1,
                   error='运行进程中断，锁已自动回收'
               WHERE status='running' AND started_ts < ?""",
            started,
            started - Governance.RUN_STALE_SECONDS,
        )
        val active = db.queryOne(
            "SELECT id FROM governance_runs WHERE status='running' ORDER BY id DESC LIMIT 1",
        )
        if (active != null) {
            0L
        } else {
            db.execute(
                "INSERT INTO governance_runs(version, trigger, status, started_ts) VALUES(?,?, 'running', ?)",
                Governance.VERSION,
                (trigger.ifEmpty { "system" }).cpSlice(0, 40),
                started,
            ).lastInsertRowid
        }
    }

    /**
     * 对应 Python `run_governance`：一键运行完整自动治理；重复运行只处理未治理/旧版本邮件。
     *
     * `sinceDays > 0` 时改为重新分拣最近这些天的全部邮件（用户主动发起）。
     * 返回值里的 `alerts` 是裁决后需要推送的新邮件，由调用方（L10 的通知流程）发送。
     *
     * 进程内互斥：同一时刻只允许一个运行，忙时立刻返回 `busy=true`。数据库层面还有
     * [claimRun] 的占位（对应 Python 的多 worker 保护）。分块处理，每块一个事务，
     * 失败时本块回滚、`governance_runs` 记为 failed、写审计后重新抛出。
     */
    fun runGovernance(
        limit: Int = 0,
        force: Boolean = false,
        trigger: String = "system",
        sinceDays: Int = 0,
    ): GovernanceRunResult {
        if (!acquireRunLock()) {
            val status = governanceStatus()
            return GovernanceRunResult(
                ok = false,
                busy = true,
                running = status.running,
                alerts = emptyList(),
                status = status,
            )
        }
        var runId = 0L
        val started = clock.now()
        try {
            runId = claimRun(trigger, started)
            if (runId == 0L) {
                val status = governanceStatus()
                return GovernanceRunResult(
                    ok = false,
                    busy = true,
                    running = status.running,
                    alerts = emptyList(),
                    status = status,
                )
            }

            val sinceTs = if (sinceDays != 0) {
                started - maxOf(0, sinceDays) * 86400.0
            } else {
                0.0
            }
            val ids = selectIds(limit = limit, force = force, now = started, sinceTs = sinceTs)
            // 本任务只读设置里的 ai_enabled；默认实现 available=false，所以即使设置为 1
            // 也走 AI 关闭的路径（与 Python 里 ai_config()["enabled"]=False 完全一致）。
            val cfg: Map<String, Any?> = linkedMapOf(
                "enabled" to (db.getSetting("ai_enabled", "0") == "1"),
            )
            val enabled = Py.truthy(cfg["enabled"]) && ai.available
            val billCfg: Map<String, Any?> = billConfig()

            val ministryCounts = LinkedHashMap<String, Int>()
            val categoryCounts = LinkedHashMap<String, Int>()
            var fallbackCount = 0
            var suspiciousCount = 0
            var aiCount = 0
            val processedIds = ArrayList<Long>()
            val alerts = ArrayList<Map<String, Any?>>()
            val decisionTs = clock.now()

            // 分批读取和处理：首次治理大邮箱时不会把所有正文一次性读进内存。
            var start = 0
            while (start < ids.size) {
                val end = minOf(start + Governance.CHUNK_SIZE, ids.size)
                val rows = loadMessages(ids.subList(start, end))
                // AI 判定在事务外完成（P1 接真实 AI 时同样如此），本任务 enabled 恒为 false。
                val aiBatch = aiResults(rows, enabled, cfg, started)
                aiCount += aiBatch.callCount
                // 裁决与账单定级只读库、只改内存，也放在事务外；事务里只剩写库。
                val decisions = rows.map { row ->
                    val result = aiBatch.byId[pyIntOrNull(row["id"])]
                    val proposal = Governance.zhongshuPropose(row, result, enabled)
                    val verdict = Governance.menxiaArbitrate(proposal)
                    applyBillTier(row, verdict, result, billCfg, now = started)
                    row to verdict
                }
                // 每块一个事务，对应 Python 每块末尾的 conn.commit()。
                db.transaction {
                    for ((row, verdict) in decisions) {
                        if (!shangshuApply(runId, row, verdict, decisionTs)) {
                            continue
                        }
                        queueAlert(row, verdict, decisionTs)?.let { alerts.add(it) }
                        processedIds.add(pyIntOrNull(row["id"]) ?: 0L)
                        val ministry = verdict.ministry
                        ministryCounts[ministry] = (ministryCounts[ministry] ?: 0) + 1
                        val category = verdict.category
                        categoryCounts[category] = (categoryCounts[category] ?: 0) + 1
                        if (verdict.fallback) fallbackCount++
                        if (verdict.category == "可疑") suspiciousCount++
                    }
                }
                start = end
            }

            val finished = clock.now()
            db.execute(
                """UPDATE governance_runs
                   SET status='completed', finished_ts=?, selected_count=?, processed_count=?,
                       ai_count=?, fallback_count=?, suspicious_count=?, error_count=0,
                       ministry_counts=?, category_counts=?
                   WHERE id=?""",
                finished,
                ids.size,
                processedIds.size,
                aiCount,
                fallbackCount,
                suspiciousCount,
                toJsonText(Governance.MINISTRIES.associateWith { ministryCounts[it] ?: 0 }),
                toJsonText(categoryCounts),
                runId,
            )
            db.audit(
                "governance_run",
                actor = "system",
                tier = "A",
                targetIds = processedIds,
                reason = "三省六部自动分拣完成：${processedIds.size} 封，" +
                    "自动回退 $fallbackCount 封，可疑 $suspiciousCount 封",
            )
            val status = governanceStatus()
            // 本函数返回后才释放进程内锁；对调用者而言本次运行已经完成。
            return GovernanceRunResult(
                ok = true,
                busy = false,
                running = false,
                alerts = alerts,
                status = status,
            )
        } catch (exc: Throwable) {
            // 失败的分块事务已经在 Db.transaction 里回滚；这里补记运行状态与审计后重新抛出。
            if (runId != 0L) {
                db.execute(
                    """UPDATE governance_runs
                       SET status='failed', finished_ts=?, error_count=1, error=? WHERE id=?""",
                    clock.now(),
                    pyStr(exc).cpSlice(0, 500),
                    runId,
                )
            }
            db.audit(
                "governance_run",
                actor = "system",
                tier = "A",
                targetIds = emptyList(),
                allowed = false,
                reason = "三省六部自动分拣失败：${pyStr(exc).cpSlice(0, 300)}",
            )
            throw exc
        } finally {
            releaseRunLock()
        }
    }

    /** 对应 Python `governance_status`：返回最近运行和待治理数量，供一键入口展示。 */
    fun governanceStatus(): GovernanceStatus {
        val now = clock.now()
        val pending = db.queryOne(
            "SELECT COUNT(*) AS c FROM messages m WHERE m.deleted_ts=0 AND ${Governance.PENDING_SQL}",
            Governance.VERSION,
            now,
        )?.long("c")?.toInt() ?: 0
        val lastRow = db.queryOne("SELECT * FROM governance_runs ORDER BY id DESC LIMIT 1")
        val last = lastRow?.let { row ->
            val map = LinkedHashMap(row.toMap())
            for (key in listOf("ministry_counts", "category_counts")) {
                val parsed = parseJson(if (Py.truthy(map[key])) pyStr(map[key]) else "{}")
                map[key] = if (parsed is Map<*, *>) {
                    parsed.entries.associateTo(LinkedHashMap()) { it.key.toString() to it.value }
                } else {
                    emptyMap<String, Any?>()
                }
            }
            @Suppress("UNCHECKED_CAST")
            val counts = map["ministry_counts"] as Map<String, Any?>
            map["ministry_counts"] = Governance.MINISTRIES.associateWith { ministry ->
                pyIntOrNull(counts[ministry])?.toInt() ?: 0
            }
            map
        }
        val dbRunning = last != null && last["status"] == "running" &&
            (floatOrNull(last["started_ts"]) ?: 0.0) >= now - Governance.RUN_STALE_SECONDS
        return GovernanceStatus(
            version = Governance.VERSION,
            running = RUN_LOCK.isLocked || dbRunning,
            pending = pending,
            ministries = Governance.MINISTRIES,
            lastRun = last,
        )
    }

    // ---------------- AI 接口（AI 关闭时不产生任何调用） ----------------

    /**
     * 对应 Python `_ai_results`。`enabled=false`（本任务的默认情况）时直接返回空结果、
     * 调用次数 0，不会构造任何请求。`enabled=true` 只可能来自测试或 P1 注入的
     * [GovernanceAi] 实现；P1 要在这里接入 `ai.classify_batch` 的等价物。
     */
    internal fun aiResults(
        rows: List<Map<String, Any?>>,
        enabled: Boolean,
        cfg: Map<String, Any?>?,
        now: Double,
    ): AiResults {
        if (!enabled) return AiResults(emptyMap(), 0)
        val config = cfg ?: emptyMap()
        val candidates = ArrayList<Map<String, Any?>>()
        for (row in rows) {
            val evidence = Governance.buildEvidence(row)
            if (!Governance.wantsAi(row, evidence, config, now)) continue
            val subject = pyStrOrEmpty(row["subject"])
            val bodyText = pyStrOrEmpty(row["body_text"])
            val body = if (bodyText.isNotEmpty()) bodyText else pyStrOrEmpty(row["snippet"])
            val storedOtp = pyStrOrEmpty(row["otp_code"])
            candidates.add(
                linkedMapOf(
                    "id" to pyIntOrNull(row["id"]),
                    "subject" to subject,
                    "sender" to pyStrOrEmpty(row["sender_addr"]),
                    "snippet" to pyStrOrEmpty(row["snippet"]),
                    "body" to body,
                    "rule_category" to Governance.safeCategory(row["category"]),
                    "signals" to Governance.signalTags(evidence),
                    // 发送前遮蔽：已存的验证码，或规则漏判时在正文里找到的码
                    "otp_code" to storedOtp.ifEmpty { Rules.extractOtp(subject, body) },
                    "has_otp" to storedOtp.isNotEmpty(),
                ),
            )
        }

        val sendBody = if (config.containsKey("send_body")) {
            Py.truthy(config["send_body"])
        } else {
            true
        }
        val size = if (sendBody) Governance.AI_BATCH_SIZE_WITH_BODY else Governance.AI_BATCH_SIZE
        val results = LinkedHashMap<Long, Map<String, Any?>>()
        var start = 0
        while (start < candidates.size) {
            val end = minOf(start + size, candidates.size)
            val batch = candidates.subList(start, end)
            val returned: List<Map<String, Any?>> = try {
                ai.classifyBatch(batch)
            } catch (exc: Exception) {
                // 自定义适配器异常也必须自动回退；Python 把 str(exc)[:80] 写进理由。
                batch.map { item ->
                    linkedMapOf<String, Any?>(
                        "id" to item["id"],
                        "needs_review" to true,
                        "transient" to true,
                        "reason" to "AI 分类异常：${pyStr(exc).cpSlice(0, 80)}",
                    )
                }
            }
            val batchIds = batch.mapNotNull { pyIntOrNull(it["id"]) }.toSet()
            for (item in returned) {
                val mid = pyIntOrNull(item["id"]) ?: continue
                if (mid in batchIds) {
                    results[mid] = item
                }
            }
            for (item in batch) {
                val mid = pyIntOrNull(item["id"]) ?: continue
                results.putIfAbsent(
                    mid,
                    linkedMapOf<String, Any?>(
                        "id" to mid,
                        "needs_review" to true,
                        "transient" to true,
                        "reason" to "AI 漏答",
                    ),
                )
            }
            start = end
        }
        return AiResults(results, candidates.size)
    }

    // ---------------- 内部实现 ----------------

    /** 一次待写库的账单重新定级结果。 */
    private data class BillUpdate(
        val id: Any?,
        val bill: BillVerdict,
        val facetsJson: String,
        val importance: Int,
    )

    private fun acquireRunLock(): Boolean {
        // Python 的 threading.Lock 不可重入：同一线程再次进入也要返回 busy。
        if (RUN_LOCK.isHeldByCurrentThread) return false
        return RUN_LOCK.tryLock()
    }

    private fun releaseRunLock() {
        if (RUN_LOCK.isHeldByCurrentThread) RUN_LOCK.unlock()
    }

    private companion object {
        /**
         * 对应 Python 模块级的 `_RUN_LOCK`：进程内只允许一个治理运行。
         * 放在伴生对象里，多个 [GovernanceService] 实例共享同一把锁（与模块级全局变量一致）。
         */
        val RUN_LOCK = ReentrantLock()
    }
}
