package com.mailhub.core.governance

import com.mailhub.core.db.Db
import com.mailhub.core.db.FakeClock
import com.mailhub.core.db.withTempDb
import com.mailhub.core.golden.Golden
import com.mailhub.core.golden.toPlain
import com.mailhub.core.rules.Rules
import com.mailhub.core.rules.loadCustomRules
import com.mailhub.core.text.Py
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.junit.Test

/**
 * 端到端回放 `golden/governance_run.json`：用同样的场景文件建库、插数据、固定时钟运行
 * `GovernanceService.runGovernance()`（AI 关闭），再逐条比较治理后的邮件列、
 * `governance_decisions`、`governance_runs`、`audit_log` 与 `governance_status()`。
 *
 * 自增 id 不参与比较：运行用 `run_index`（第几次运行），邮件用场景里显式写死的 id。
 * JSON 文本（`ministry_counts`、`bill_facets`、`evidence`、`target_ids`）按解析后的值比较。
 */
class GovernanceRunGoldenTest {

    @Test
    fun scenariosMatchPython() = Golden.replay("governance_run") { case ->
        val name = case.args.str("scenario")
        val scenario = loadScenario(name)
        val now = scenario.double("now")
        var actual: Map<String, Any?>? = null
        val clock = FakeClock(now)
        withTempDb(clock) { db ->
            db.initDb()
            insertScenario(db, scenario)
            if (scenario.booleanOr("preclassify", false)) {
                preclassify(db)
            }
            val service = GovernanceService(db, clock)
            for (element in scenario.array("runs")) {
                val run = element.jsonObject
                service.runGovernance(
                    limit = run.longOr("limit", scenario.longOr("limit", 0L)).toInt(),
                    force = run.booleanOr("force", false),
                    trigger = run.textOr("trigger", "system"),
                    sinceDays = run.longOr("since_days", 0L).toInt(),
                )
            }
            for (element in scenario.array("post")) {
                applyPostStep(db, service, element.jsonObject)
            }
            actual = export(db, service, scenario)
        }
        Golden.diff(case.expected.toPlain(), actual)
    }

    // ---------------- 场景加载与建库 ----------------

    private fun loadScenario(name: String): JsonObject {
        val stream = GovernanceRunGoldenTest::class.java.getResourceAsStream("/governance/$name")
            ?: error("找不到场景文件 governance/$name")
        return Json.parseToJsonElement(stream.use { it.readBytes().toString(Charsets.UTF_8) })
            .jsonObject
    }

    /** 顶层的对象数组；缺失时返回空数组。 */
    private fun JsonObject.array(key: String): JsonArray =
        (get(key) as? JsonArray) ?: JsonArray(emptyList())

    /** 按列名插入一行：场景里没有的列用数据库默认值补齐（两边必须完全一致）。 */
    private fun insert(
        db: Db,
        table: String,
        row: JsonObject,
        columns: List<String>,
        defaults: Map<String, Any?>,
    ) {
        val values = columns.map { column ->
            val element = row[column]
            if (element == null || element is JsonNull) defaults[column] else element.toPlain()
        }
        val placeholders = columns.joinToString(",") { "?" }
        db.execute(
            "INSERT INTO $table(${columns.joinToString(",")}) VALUES($placeholders)",
            *values.toTypedArray(),
        )
    }

    private fun insertScenario(db: Db, scenario: JsonObject) {
        for (element in scenario.array("accounts")) {
            insert(db, "accounts", element.jsonObject, ACCOUNT_COLUMNS, ACCOUNT_DEFAULTS)
        }
        for (element in scenario.array("messages")) {
            insert(db, "messages", element.jsonObject, MESSAGE_COLUMNS, MESSAGE_DEFAULTS)
        }
        (scenario["settings"] as? JsonObject)?.forEach { (key, value) ->
            db.execute(
                "INSERT INTO settings(k,v) VALUES(?,?)",
                key,
                (value as? JsonPrimitive)?.content ?: "",
            )
        }
        for (element in scenario.array("rules")) {
            val rule = element.jsonObject
            db.execute(
                "INSERT INTO rules(name, field, pattern, category, priority, enabled) " +
                    "VALUES(?,?,?,?,?,?)",
                rule.textOr("name", ""),
                rule.textOr("field", ""),
                rule.textOr("pattern", ""),
                rule.textOr("category", ""),
                rule.longOr("priority", 100L),
                rule.longOr("enabled", 1L),
            )
        }
        for (element in scenario.array("bill_prefs")) {
            val pref = element.jsonObject
            db.execute(
                "INSERT INTO bill_prefs(template_key, tier, created_ts) VALUES(?,?,?)",
                pref.textOr("template_key", ""),
                pref.textOr("tier", ""),
                pref.doubleOr("created_ts", 0.0),
            )
        }
    }

    /** 模拟同步入库后的规则分类（自定义规则的命中路径），对应脚本里的 `_preclassify`。 */
    private fun preclassify(db: Db) {
        val custom = loadCustomRules(db)
        val rows = db.query(
            "SELECT id, subject, body_text, sender_addr, unsubscribe, header_signals " +
                "FROM messages ORDER BY id",
        )
        for (row in rows) {
            val parsed = parseJson(row.str("header_signals") ?: "{}")
            val signals = (parsed as? Map<*, *>)?.entries?.associate {
                it.key.toString() to Py.truthy(it.value)
            } ?: emptyMap()
            val result = Rules.classifyDetailed(
                subject = row.str("subject") ?: "",
                body = row.str("body_text") ?: "",
                senderAddr = row.str("sender_addr") ?: "",
                hasUnsubscribe = Py.truthy(row.long("unsubscribe")),
                customRules = custom,
                signals = signals,
            )
            db.execute(
                "UPDATE messages SET category=?, otp_code=?, confidence=? WHERE id=?",
                result.category,
                result.otp,
                result.confidence,
                row.long("id"),
            )
        }
    }

    /** 运行后的维护动作，对应脚本里的 `_apply_post_step`。 */
    private fun applyPostStep(db: Db, service: GovernanceService, step: JsonObject) {
        when (val op = step.textOr("op", "")) {
            "set_settings" -> (step["values"] as? JsonObject)?.forEach { (key, value) ->
                db.setSetting(key, (value as? JsonPrimitive)?.content ?: "")
            }

            "backfill_template_keys" ->
                service.backfillTemplateKeys(step.longOr("batch", 500L).toInt())

            "retier_bills" -> service.retierBills(
                template = step.textOr("template", ""),
                onlyMissing = step.booleanOr("only_missing", false),
            )

            else -> error("未知的 post 动作：$op")
        }
    }

    // ---------------- 导出 ----------------

    private fun export(db: Db, service: GovernanceService, scenario: JsonObject): Map<String, Any?> {
        val runRows = db.query("SELECT * FROM governance_runs ORDER BY id").map { it.toMap() }
        val runIndex = runRows.mapIndexed { index, row -> (row["id"] as Number).toLong() to index }
            .toMap()
        val runs = runRows.map { row ->
            val item = LinkedHashMap<String, Any?>()
            for ((key, value) in row) {
                if (key != "id") item[key] = value
            }
            item["ministry_counts"] = parsedObject(item["ministry_counts"])
            item["category_counts"] = parsedObject(item["category_counts"])
            item
        }

        val messages = db.query(
            "SELECT ${EXPORT_MESSAGE_COLUMNS.joinToString(",")} FROM messages ORDER BY id",
        ).map { row ->
            val item = LinkedHashMap(row.toMap())
            item["bill_facets"] = parsedObject(item["bill_facets"])
            item
        }

        val decisions = db.query(
            """SELECT run_id, message_id, ts, version, ministry, category_before,
                      category_after, confidence, source, fallback, reason, evidence
               FROM governance_decisions ORDER BY id""",
        ).map { row ->
            val item = LinkedHashMap<String, Any?>()
            for ((key, value) in row.toMap()) {
                if (key != "run_id") item[key] = value
            }
            item["run_index"] = runIndex[(row.long("run_id") ?: 0L)]
            item["evidence"] = parsedObject(item["evidence"])
            item
        }

        val audit = db.query(
            """SELECT ts, actor, action, tier, target_count, target_ids, reason,
                      allowed, reversible, undone
               FROM audit_log ORDER BY id""",
        ).map { row ->
            val item = LinkedHashMap(row.toMap())
            item["target_ids"] = parseJson(item["target_ids"] as? String ?: "[]")
            item
        }

        val status = service.governanceStatus()
        return linkedMapOf(
            "scenario" to scenario.textOr("name", ""),
            "runs" to runs,
            "messages" to messages,
            "decisions" to decisions,
            "audit" to audit,
            "status" to linkedMapOf(
                "version" to status.version,
                "running" to status.running,
                "pending" to status.pending,
                "ministries" to status.ministries,
            ),
        )
    }

    /** `json.loads(value or "{}")`：解析成字典；失败或不是对象时返回空字典。 */
    private fun parsedObject(value: Any?): Map<String, Any?> {
        if (value !is String || value.isEmpty()) return emptyMap()
        val parsed = parseJson(value)
        if (parsed !is Map<*, *>) return emptyMap()
        return parsed.entries.associateTo(LinkedHashMap()) { it.key.toString() to it.value }
    }

    private companion object {
        /** 与 `scripts/golden/governance_run.py` 的 `ACCOUNT_COLUMNS` 完全一致。 */
        val ACCOUNT_COLUMNS = listOf(
            "id", "name", "provider", "email", "imap_host", "imap_port", "auth_type",
            "enabled", "created_ts",
        )

        val ACCOUNT_DEFAULTS: Map<String, Any?> = linkedMapOf(
            "name" to "",
            "provider" to "custom",
            "email" to "",
            "imap_host" to "",
            "imap_port" to 993L,
            "auth_type" to "password",
            "enabled" to 1L,
            "created_ts" to 0.0,
        )

        /** 与 `scripts/golden/governance_run.py` 的 `MESSAGE_COLUMNS` 完全一致。 */
        val MESSAGE_COLUMNS = listOf(
            "id", "account_id", "uid", "msg_id", "subject", "sender_name", "sender_addr",
            "date_ts", "snippet", "body_text", "body_html", "category", "otp_code",
            "importance", "summary", "confidence", "ai_done", "unread", "has_attach",
            "unsubscribe", "created_ts", "governance_version", "governance_ministry",
            "governance_action", "governance_source", "governance_reason", "governance_ts",
            "deleted_ts", "risk_level", "risk_reasons", "needs_review", "ai_reason",
            "ai_retry_count", "ai_retry_ts", "header_signals", "alert_ts", "template_key",
            "bill_tier", "bill_facets", "bill_tier_locked", "flag_push",
        )

        val MESSAGE_DEFAULTS: Map<String, Any?> = linkedMapOf(
            "msg_id" to "",
            "subject" to "",
            "sender_name" to "",
            "sender_addr" to "",
            "date_ts" to 0.0,
            "snippet" to "",
            "body_text" to "",
            "body_html" to "",
            "category" to "未分类",
            "otp_code" to "",
            "importance" to 0L,
            "summary" to "",
            "confidence" to 0.0,
            "ai_done" to 0L,
            "unread" to 1L,
            "has_attach" to 0L,
            "unsubscribe" to 0L,
            "created_ts" to 0.0,
            "governance_version" to "",
            "governance_ministry" to "",
            "governance_action" to "",
            "governance_source" to "",
            "governance_reason" to "",
            "governance_ts" to 0.0,
            "deleted_ts" to 0.0,
            "risk_level" to "none",
            "risk_reasons" to "",
            "needs_review" to 0L,
            "ai_reason" to "",
            "ai_retry_count" to 0L,
            "ai_retry_ts" to 0.0,
            "header_signals" to "{}",
            "alert_ts" to 0.0,
            "template_key" to "",
            "bill_tier" to "",
            "bill_facets" to "{}",
            "bill_tier_locked" to 0L,
            "flag_push" to 0L,
        )

        /**
         * 与 `scripts/golden/governance_run.py` 的 `EXPORT_MESSAGE_COLUMNS` 完全一致。
         * 末尾五列（验证码、锁定标记、已读、回收站时间、推送标记）治理不应该改动，
         * 导出它们是为了让 UPDATE 多写一列时金标立刻失败。
         */
        val EXPORT_MESSAGE_COLUMNS = listOf(
            "id", "category", "importance", "summary", "confidence", "ai_reason",
            "needs_review", "ai_done", "risk_level", "governance_version",
            "governance_ministry", "governance_action", "governance_source",
            "governance_reason", "governance_ts", "ai_retry_count", "ai_retry_ts",
            "bill_tier", "bill_facets", "alert_ts", "template_key",
            "otp_code", "bill_tier_locked", "unread", "deleted_ts", "flag_push",
        )
    }
}

// ---------------- 场景 JSON 的取值辅助 ----------------

private fun JsonObject.booleanOr(key: String, default: Boolean): Boolean {
    val element = get(key)
    if (element == null || element is JsonNull) return default
    return (element as? JsonPrimitive)?.booleanOrNull ?: default
}

private fun JsonObject.longOr(key: String, default: Long): Long {
    val element = get(key)
    if (element == null || element is JsonNull) return default
    return (element as? JsonPrimitive)?.longOrNull ?: default
}

private fun JsonObject.doubleOr(key: String, default: Double): Double {
    val element = get(key)
    if (element == null || element is JsonNull) return default
    val plain: Any? = element.toPlain()
    return (plain as? Number)?.toDouble() ?: default
}

private fun JsonObject.textOr(key: String, default: String): String {
    val element: JsonElement = get(key) ?: return default
    if (element is JsonNull) return default
    return element.toPlain()?.toString() ?: default
}
