package com.mailhub.core.governance

import com.mailhub.core.billing.Billing
import com.mailhub.core.rules.Policy
import com.mailhub.core.rules.Rules
import com.mailhub.core.security.TextGuard
import com.mailhub.core.text.Py
import com.mailhub.core.text.PyRegex
import com.mailhub.core.text.cpSlice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale
import java.util.regex.Pattern

/** 六部共享的证据集合，对应 Python `build_evidence` 返回的 dict。 */
data class Evidence(
    val senderKind: String,
    val importantRelationship: Boolean,
    val financial: Boolean,
    val subscription: Boolean,
    val security: Boolean,
    val riskLevel: String,
    val promptInjection: Boolean,
    val otp: Boolean,
    val attachment: Boolean,
    val unsubscribe: Boolean,
    val bulk: Boolean,
    val ruleCategory: String,
) {
    /**
     * 转成 Python 里那个 dict。键顺序与 `build_evidence` 的字面量一致；
     * [aiConsulted] 不为 null 时追加 `ai_consulted`（对应 `dict(evidence, ai_consulted=...)`）。
     */
    fun toMap(aiConsulted: Boolean? = null): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>(
            "sender_kind" to senderKind,
            "important_relationship" to importantRelationship,
            "financial" to financial,
            "subscription" to subscription,
            "security" to security,
            "risk_level" to riskLevel,
            "prompt_injection" to promptInjection,
            "otp" to otp,
            "attachment" to attachment,
            "unsubscribe" to unsubscribe,
            "bulk" to bulk,
            "rule_category" to ruleCategory,
        )
        if (aiConsulted != null) map["ai_consulted"] = aiConsulted
        return map
    }

    companion object {
        /**
         * 从证据字典还原（金标里 `_primary_ministry`、`_wants_ai` 的输入就是字典）。
         * 缺失的键按 Python 里最保守的取值处理：布尔为 false、风险为 none。
         */
        fun fromMap(map: Map<String, Any?>): Evidence = Evidence(
            senderKind = pyStrOrEmpty(map["sender_kind"]),
            importantRelationship = Py.truthy(map["important_relationship"]),
            financial = Py.truthy(map["financial"]),
            subscription = Py.truthy(map["subscription"]),
            security = Py.truthy(map["security"]),
            riskLevel = pyStrOrEmpty(map["risk_level"]).ifEmpty { "none" },
            promptInjection = Py.truthy(map["prompt_injection"]),
            otp = Py.truthy(map["otp"]),
            attachment = Py.truthy(map["attachment"]),
            unsubscribe = Py.truthy(map["unsubscribe"]),
            bulk = Py.truthy(map["bulk"]),
            ruleCategory = pyStrOrEmpty(map["rule_category"]),
        )
    }
}

/** 中书省提案，对应 Python `zhongshu_propose` 返回的 dict。 */
data class Proposal(
    val message: Map<String, Any?>,
    val baselineCategory: String,
    val evidence: Evidence,
    val aiEnabled: Boolean,
    val aiResult: Map<String, Any?>?,
) {
    /** 金标比较用的形状（与 Python 的 dict 一一对应）。 */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "message" to message,
        "baseline_category" to baselineCategory,
        "evidence" to evidence.toMap(),
        "ai_enabled" to aiEnabled,
        "ai_result" to aiResult,
    )
}

/** 账单定级结果，对应 Python `decide_bill` 返回的 `{tier, facets, reason}`。 */
data class BillVerdict(
    val tier: String,
    val facets: Map<String, Any?>,
    val reason: String,
)

/** 门下省裁决，对应 Python `menxia_arbitrate` 返回的 dict；`bill` 由户部后续填入。 */
class Verdict(
    var category: String,
    var ministry: String,
    val action: String,
    var source: String,
    var fallback: Boolean,
    var retry: Boolean,
    var confidence: Double,
    var importance: Int,
    var summary: String,
    var reason: String,
    var evidence: Map<String, Any?>,
    var bill: BillVerdict?,
) {
    /** 金标比较用的形状（与 Python 的 dict 一一对应）。 */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "category" to category,
        "ministry" to ministry,
        "action" to action,
        "source" to source,
        "fallback" to fallback,
        "retry" to retry,
        "confidence" to confidence,
        "importance" to importance,
        "summary" to summary,
        "reason" to reason,
        "evidence" to evidence,
    )
}

/**
 * 一次治理运行的结果，对应 `run_governance` 返回的字典（`governance_status()` 加上
 * `ok` / `busy` / `running` / `alerts`）。L9 的 `/api/governance/run` 直接把它转成 JSON。
 *
 * `alerts` 只是进程内的推送清单，不属于接口响应：`app.py` 的 `/api/governance/run` 先调
 * `sync.push_alerts(result.pop("alerts"))` 把它取走再返回，所以 [toMap] 也不带这个键。
 * L9 要先取出 [alerts] 交给通知流程，再序列化 [toMap]。
 *
 * `busy=true`（进程内已有运行）在 `app.py` 里对应 **HTTP 409**「已有自动分拣任务正在运行」；
 * 本机模式的 L9 要回同样的状态码，不要把它包成 200 的成功响应。
 */
data class GovernanceRunResult(
    val ok: Boolean,
    val busy: Boolean,
    val running: Boolean,
    val alerts: List<Map<String, Any?>>,
    val status: GovernanceStatus,
) {
    /** 与 Python 返回的字典一一对应（不含 `alerts`，见类注释）；键顺序不影响 JSON 比较。 */
    fun toMap(): Map<String, Any?> {
        val map = LinkedHashMap(status.toMap())
        map["ok"] = ok
        map["busy"] = busy
        map["running"] = running
        return map
    }
}

/** `governance_status()` 的结果。 */
data class GovernanceStatus(
    val version: String,
    val running: Boolean,
    val pending: Int,
    val ministries: List<String>,
    val lastRun: Map<String, Any?>?,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "version" to version,
        "running" to running,
        "pending" to pending,
        "ministries" to ministries,
        "last_run" to lastRun,
    )
}

/**
 * AI 判定的接入点。**本任务（L8）只移植 AI 关闭的路径**：默认实现 [Disabled] 永远返回
 * 空结果、调用次数为 0，`run_governance` 因此把 AI 当作关闭（与设置里 `ai_enabled=0` 完全一致）。
 *
 * P1 接真实 AI 时实现本接口，并把提示词组装、批量请求放在 [classifyBatch] 里：
 * 调用发生在 [GovernanceService.runGovernance] 的数据库事务**之外**（与 Python 一致，
 * `_ai_results` 在 `commit()` 之前、但不在 `BEGIN IMMEDIATE` 的写锁里）。
 */
interface GovernanceAi {

    /**
     * 是否真的有可用的判定实现。默认 false：即使设置里 `ai_enabled=1`，也因为没有实现而
     * 当作关闭（P1 之前的行为）。
     */
    val available: Boolean get() = false

    /**
     * 对应 Python `ai.classify_batch(batch)`：返回每封邮件的判定字典。
     * 抛异常时由调用方统一转成「临时失败」，不影响本次治理。
     */
    fun classifyBatch(mails: List<Map<String, Any?>>): List<Map<String, Any?>>

    /** 默认实现：AI 关闭。 */
    object Disabled : GovernanceAi {
        override fun classifyBatch(mails: List<Map<String, Any?>>): List<Map<String, Any?>> =
            emptyList()
    }
}

/**
 * 邮件自动「三省六部」治理里的纯逻辑部分，逐函数移植 Python `governance.py`。
 *
 * 三省是同一条自动流水线，而不是三个需要用户点击的审批页：
 * - 中书省（[zhongshuPropose]）：汇总本地规则、静态安全扫描与邮件元数据形成提案；
 * - 门下省（[menxiaArbitrate]）：自动驳回冲突、低置信度和被污染的 AI 结果；
 * - 尚书省（[GovernanceService.shangshuApply]）：只落地标签/优先级/摘要并写审计。
 *
 * 持久化证据只含枚举和布尔信号，不保存邮件正文、主题、发件地址或凭据。
 * 用到数据库的函数在 [GovernanceService] 里；金标语料见
 * `android/core/src/test/resources/golden/governance.json`（由 `scripts/golden/governance.py`
 * 导出）。
 */
object Governance {

    /** 对应 Python `VERSION`。 */
    const val VERSION: String = "mailhub-three-six-v2"

    /** 对应 Python `MINISTRIES`（顺序有意义：统计 JSON 只含这六个键）。 */
    val MINISTRIES: List<String> = listOf("吏部", "户部", "礼部", "兵部", "刑部", "工部")

    /** 每次 AI 调用的邮件数：只发主题/发件人时 24 封；带正文时 8 封。对应 `AI_BATCH_SIZE*`。 */
    const val AI_BATCH_SIZE: Int = 24
    const val AI_BATCH_SIZE_WITH_BODY: Int = 8

    /** 运行锁的陈旧判定窗口：15 分钟。对应 `RUN_STALE_SECONDS`。 */
    const val RUN_STALE_SECONDS: Int = 15 * 60

    /** 每次从数据库读入并处理的邮件数。对应 `CHUNK_SIZE`。 */
    const val CHUNK_SIZE: Int = 200

    /** 正文只读前 20000 个码点做注入扫描。对应 `BODY_SCAN_CHARS`。 */
    const val BODY_SCAN_CHARS: Int = 20000

    /** AI 临时失败后的重试：5 分钟起指数退避，最长 6 小时，最多 5 次。 */
    const val MAX_AI_RETRIES: Int = 5
    const val AI_RETRY_BASE_SECONDS: Int = 300
    const val AI_RETRY_MAX_SECONDS: Int = 6 * 3600

    /** `ai_scope=uncertain` 时，规则置信度低于此值才交 AI。对应 `UNCERTAIN_BELOW`。 */
    const val UNCERTAIN_BELOW: Double = 0.8

    /** 把邮件移出受保护分类或验证码、或判为「可疑」时，AI 置信度必须达到此值。 */
    const val HIGH_CONFIDENCE: Double = 0.85

    /** 只为入库不久的邮件推送；重新治理历史邮件不会补推。对应 `ALERT_FRESH_SECONDS`。 */
    const val ALERT_FRESH_SECONDS: Int = 2 * 3600

    /** 对应 Python `ai.DEFAULT_BACKFILL_DAYS`（`_wants_ai` 的默认回溯窗口）。 */
    const val DEFAULT_BACKFILL_DAYS: Int = 30

    /** `_PENDING_SQL`：未治理或旧版本、旧的人工复核标记、AI 临时失败且已到重试时间。 */
    const val PENDING_SQL: String =
        "(COALESCE(m.governance_version,'') != ? OR m.needs_review=1" +
            " OR (m.ai_retry_ts > 0 AND m.ai_retry_ts <= ?))"

    /** 返回「待治理」筛选子句和参数，对应 Python `governance.pending_clause`。 */
    fun pendingClause(now: Double): Pair<String, List<Any?>> = PENDING_SQL to listOf(VERSION, now)

    /** 对应 Python `_RISK_RANK`。 */
    private val RISK_RANK: Map<String, Int> =
        linkedMapOf("none" to 0, "low" to 1, "medium" to 2, "high" to 3)

    /** 对应 Python `STICKY_CATEGORIES`：受保护分类 + 验证码。 */
    val STICKY_CATEGORIES: Set<String> = Policy.PROTECTED_CATEGORIES + "验证码"

    /** 对应 Python `_AUTO_SENDER`。 */
    private val AUTO_SENDER: Pattern = PyRegex.compile(
        "(^|[._-])(no[-_]?reply|notify|notification|mailer|robot|system)([._@+-]|$)" +
            "|updates?@|news@|info@|support@",
        ignoreCase = true,
    )

    /** 对应 Python `_FINANCE_HINT`。 */
    private val FINANCE_HINT: Pattern = PyRegex.compile(
        "账单|发票|订单|支付|扣款|invoice|receipt|billing|payment|order",
        ignoreCase = true,
    )

    /** 对应 Python `_SUBSCRIPTION_HINT`。 */
    private val SUBSCRIPTION_HINT: Pattern = PyRegex.compile(
        "订阅|退订|周刊|简报|优惠|newsletter|unsubscribe|digest|promotion|coupon",
        ignoreCase = true,
    )

    /** 对应 Python `_SECURITY_HINT`。 */
    private val SECURITY_HINT: Pattern = PyRegex.compile(
        "异常登录|安全提醒|风险|密码|冻结|security|suspicious|password|two[- ]?factor|2fa",
        ignoreCase = true,
    )

    /** 对应 Python `_CATEGORY_MINISTRY`。 */
    private val CATEGORY_MINISTRY: Map<String, String> = linkedMapOf(
        "安全" to "兵部",
        "账单" to "户部",
        "验证码" to "工部",
        "重要" to "吏部",
        "订阅" to "礼部",
        "通知" to "礼部",
    )

    /** 对应 Python `_safe_category`：非法或非字符串分类一律落到「其他」。 */
    fun safeCategory(value: Any?): String =
        if (value is String && value in Rules.CATEGORIES) value else "其他"

    /** 对应 Python `_risk_at_least`：风险等级按 none < low < medium < high 比较。 */
    fun riskAtLeast(value: Any?, threshold: String): Boolean =
        (RISK_RANK[pyStrOrEmpty(value).ifEmpty { "none" }.lowercase(Locale.ROOT)] ?: 0) >=
            RISK_RANK.getValue(threshold)

    /** 对应 Python `_default_importance`。 */
    fun defaultImportance(category: String): Int = when (category) {
        "重要", "安全", "可疑" -> 5
        "账单" -> 3
        "验证码", "其他" -> 2
        "订阅", "通知" -> 1
        else -> 2
    }

    /** 对应 Python `_baseline_confidence`。 */
    fun baselineConfidence(message: Map<String, Any?>): Double {
        val category = safeCategory(message["category"])
        var value = floatOrNull(if (Py.truthy(message["confidence"])) message["confidence"] else 0)
            ?: 0.0
        if (value <= 0) {
            value = if (category != "其他") 0.9 else 0.55
        }
        return clamp01(value)
    }

    /** 对应 Python `_signals`：邮件头群发信号；不是字典或 JSON 解析失败时返回空字典。 */
    fun signals(message: Map<String, Any?>): Map<String, Any?> {
        val raw = message["header_signals"]
        if (raw is Map<*, *>) {
            return raw.entries.associateTo(LinkedHashMap()) { it.key.toString() to it.value }
        }
        val text = if (Py.truthy(raw)) pyStr(raw) else "{}"
        val parsed = parseJson(text)
        if (parsed !is Map<*, *>) return emptyMap()
        return parsed.entries.associateTo(LinkedHashMap()) { it.key.toString() to it.value }
    }

    /** 对应 Python `build_evidence`：生成六部共享的最小证据集合，不含原文或身份信息。 */
    fun buildEvidence(message: Map<String, Any?>): Evidence {
        val category = safeCategory(message["category"])
        val subject = pyStrOrEmpty(message["subject"])
        val snippet = pyStrOrEmpty(message["snippet"])
        // 正文在本机做注入特征扫描；它不进入 evidence。
        val bodyText = pyStrOrEmpty(message["body_text"])
        val sender = pyStrOrEmpty(message["sender_addr"])
        val text = "$subject\n$snippet"
        var risk = pyStrOrEmpty(message["risk_level"]).ifEmpty { "none" }.lowercase(Locale.ROOT)
        if (risk !in RISK_RANK) risk = "none"

        val automated = AUTO_SENDER.matcher(sender).find()
        val injection = TextGuard.hasInjection(subject, sender, snippet, bodyText)
        val unsubscribe = Py.truthy(message["unsubscribe"])
        return Evidence(
            senderKind = if (automated) "automated" else "person_or_unknown",
            importantRelationship = category == "重要",
            financial = category == "账单" || FINANCE_HINT.matcher(text).find(),
            subscription = category == "订阅" || category == "通知" ||
                SUBSCRIPTION_HINT.matcher(text).find(),
            security = category == "安全" || SECURITY_HINT.matcher(text).find(),
            riskLevel = risk,
            promptInjection = injection,
            otp = category == "验证码" || Py.truthy(message["otp_code"]),
            attachment = Py.truthy(message["has_attach"]),
            unsubscribe = unsubscribe,
            bulk = Rules.isBulk(
                signals(message).mapValues { (_, value) -> Py.truthy(value) },
                unsubscribe,
            ),
            ruleCategory = category,
        )
    }

    /** 对应 Python `zhongshu_propose`：聚合证据形成提案，不在这一阶段执行任何动作。 */
    fun zhongshuPropose(
        message: Map<String, Any?>,
        aiResult: Map<String, Any?>?,
        aiEnabled: Boolean,
    ): Proposal = Proposal(
        message = message,
        baselineCategory = safeCategory(message["category"]),
        evidence = buildEvidence(message),
        aiEnabled = aiEnabled,
        aiResult = aiResult,
    )

    /**
     * 对应 Python `_primary_ministry`：按最终分类选择主责部；「其他」才参考关键词证据。
     * 其他部的证据仍完整保存在 decision 中。
     */
    fun primaryMinistry(evidence: Evidence, category: String): String {
        if (evidence.promptInjection || riskAtLeast(evidence.riskLevel, "medium") ||
            category == "可疑"
        ) {
            return "刑部"
        }
        CATEGORY_MINISTRY[category]?.let { return it }
        if (evidence.security) return "兵部"
        if (evidence.financial) return "户部"
        if (evidence.otp) return "工部"
        if (evidence.importantRelationship) return "吏部"
        if (evidence.subscription || evidence.unsubscribe) return "礼部"
        if (evidence.attachment || evidence.senderKind == "automated") return "工部"
        return "吏部"
    }

    /**
     * 对应 Python `_ai_guardrail`：门下省对有效 AI 结果的护栏；返回驳回理由，
     * 空串表示可以采纳。
     */
    fun aiGuardrail(
        baseline: String,
        aiCategory: String,
        aiConfidence: Double,
        message: Map<String, Any?>,
    ): String {
        if (aiConfidence < Policy.MIN_CONFIDENCE_GUARDED) {
            return "门下省自动驳回低置信度 AI（${Py.formatPercent(aiConfidence)}），" +
                "保留本地分类「$baseline」"
        }
        if (aiCategory == "可疑" && aiConfidence < HIGH_CONFIDENCE) {
            return "AI 判为可疑但置信度 ${Py.formatPercent(aiConfidence)} 不足 " +
                "${Py.formatPercent(HIGH_CONFIDENCE)}，保留本地分类「$baseline」"
        }
        if (aiCategory == "验证码" && !Py.truthy(message["otp_code"])) {
            return "AI 判为验证码但本地未提取到验证码，保留本地分类「$baseline」"
        }
        if (baseline in STICKY_CATEGORIES && aiCategory != baseline &&
            aiConfidence < HIGH_CONFIDENCE
        ) {
            return "把邮件移出「$baseline」需要 AI 置信度达到 " +
                "${Py.formatPercent(HIGH_CONFIDENCE)}（实际 ${Py.formatPercent(aiConfidence)}），" +
                "保留本地分类"
        }
        return ""
    }

    /**
     * 对应 Python `menxia_arbitrate`：全自动安全裁决；失败时保留本地规则，
     * 不创建人工队列。
     */
    fun menxiaArbitrate(proposal: Proposal): Verdict {
        val message = proposal.message
        val baseline = proposal.baselineCategory
        val evidence = proposal.evidence
        val result = proposal.aiResult

        var category = baseline
        var source = "rules"
        var fallback = false
        var retry = false
        var confidence = baselineConfidence(message)
        var importance = importanceOf(message, baseline)
        var summary = pyStrOrEmpty(message["summary"]).cpSlice(0, 120)
        var reason = "本地规则确定为「$baseline」"

        // 刑部/兵部安全证据拥有最高优先级。中风险也保守标为可疑，但仅打标签。
        if (evidence.promptInjection) {
            category = "可疑"
            source = "security"
            fallback = true
            confidence = 1.0
            importance = maxOf(importance, 5)
            reason = "刑部检出疑似提示注入，自动标记可疑"
        } else if (riskAtLeast(evidence.riskLevel, "medium")) {
            category = "可疑"
            source = "security"
            confidence = 1.0
            importance = maxOf(importance, if (evidence.riskLevel == "high") 5 else 4)
            reason = "刑部依据静态扫描 ${evidence.riskLevel} 风险自动标记可疑"
        } else if (result == null) {
            if (!proposal.aiEnabled) {
                if (baseline == "其他") {
                    fallback = true
                    source = "ai_disabled"
                    reason = "AI 未启用，门下省采用本地保守分类「其他」"
                }
            } else {
                reason = "本地规则确定为「$baseline」（不在 AI 判定范围内）"
            }
        } else if (Py.truthy(result["needs_review"])) {
            fallback = true
            source = "fallback"
            // 网络失败、超时、漏答属于临时问题，稍后自动重试；注入、越界等不重试
            retry = Py.truthy(result["transient"])
            val detail = pyStrOrEmpty(result["reason"]).ifEmpty { "AI 未返回有效判定" }.cpSlice(0, 120)
            reason = "门下省自动驳回不可信 AI 结果：$detail"
            if (retry) {
                reason += "（稍后自动重试）"
            }
        } else {
            // 非有限置信度按 0 处理，见 [finiteConfidence] 的偏差说明。
            val rawConfidence = floatOrNull(result["confidence"] ?: 0) ?: 0.0
            val aiConfidence = clamp01(finiteConfidence(rawConfidence))
            val aiCategory = safeCategory(result["category"])
            val rejected = aiGuardrail(baseline, aiCategory, aiConfidence, message)
            if (rejected.isNotEmpty()) {
                fallback = true
                source = "fallback"
                if (baseline == "其他") {
                    // 规则本来就没有把握，记录 AI 的真实置信度
                    confidence = aiConfidence
                }
                reason = rejected
            } else {
                category = aiCategory
                source = if (aiCategory == "可疑") "ai_risk" else "ai"
                confidence = aiConfidence
                val rawImportance = result["importance"]
                if (rawImportance != null) {
                    val parsed = pyIntOrNull(rawImportance)
                    if (parsed != null) {
                        // 先按 Long 夹到 1..5 再转 Int：AI 给出超大整数时不会溢出成负数。
                        importance = parsed.coerceIn(1L, 5L).toInt()
                    }
                }
                if (aiCategory == "可疑") {
                    importance = maxOf(importance, 4)
                }
                summary = pyStrOrEmpty(result["summary"]).ifEmpty { summary }.cpSlice(0, 120)
                val why = pyStrOrEmpty(result["risk_reason"])
                    .ifEmpty { pyStrOrEmpty(result["reason"]) }
                    .ifEmpty { "语义判定" }
                    .cpSlice(0, 100)
                reason = "门下省核准 AI 分类「$category」：$why"
            }
        }

        val ministry = primaryMinistry(evidence, category)
        return Verdict(
            category = category,
            ministry = ministry,
            action = "label_only",
            source = source,
            fallback = fallback,
            retry = retry,
            confidence = clamp01(finiteConfidence(confidence)),
            importance = maxOf(1, minOf(5, importance)),
            summary = summary,
            reason = reason.cpSlice(0, 500),
            evidence = evidence.toMap(aiConsulted = result != null),
            bill = null,
        )
    }

    /**
     * 对应 Python `display_category`：界面和晨报展示用的分类名，
     * 账单按等级显示为「重要账单」「一般账单」。
     */
    fun displayCategory(category: String, billTier: String = ""): String =
        if (category == "账单" && billTier in Billing.TIERS) "${billTier}账单" else category

    /** 对应 Python `_next_retry`：返回 (ai_retry_count, ai_retry_ts)；ts=0 表示不再重试。 */
    fun nextRetry(message: Map<String, Any?>, verdict: Verdict, ts: Double): Pair<Int, Double> {
        if (!verdict.retry) return 0 to 0.0
        val raw = pyIntOrNull(message["ai_retry_count"]) ?: 0L
        // 超过上限就不再重试；计数按 Python 的 raw+1 返回，只把极端大值夹到 Int 上限。
        if (raw >= MAX_AI_RETRIES) {
            val capped = if (raw >= Int.MAX_VALUE.toLong()) Int.MAX_VALUE else (raw + 1).toInt()
            return capped to 0.0
        }
        val count = raw.toInt() + 1
        val delay = minOf(AI_RETRY_MAX_SECONDS, AI_RETRY_BASE_SECONDS * (1 shl (count - 1)))
        return count to ts + delay
    }

    /** 对应 Python `_wants_alert`：重要/安全（或重要度 ≥4）的新邮件裁决后推送一次。 */
    fun wantsAlert(message: Map<String, Any?>, verdict: Verdict, ts: Double): Boolean {
        if (verdict.category == "可疑") return false
        if (verdict.category != "重要" && verdict.category != "安全" && verdict.importance < 4) {
            return false
        }
        if ((floatOrNull(message["alert_ts"]) ?: 0.0) > 0) return false
        return (floatOrNull(message["created_ts"]) ?: 0.0) >= ts - ALERT_FRESH_SECONDS
    }

    /**
     * 对应 Python `_wants_ai`：这封邮件是否交给 AI 判定。注入、中高风险和已判可疑的邮件
     * 永不外送。AI 关闭时本函数不会被调用，保留它是为了 P1 与金标一致。
     */
    fun wantsAi(
        row: Map<String, Any?>,
        evidence: Evidence,
        cfg: Map<String, Any?>,
        now: Double,
    ): Boolean {
        if (evidence.promptInjection || riskAtLeast(evidence.riskLevel, "medium")) return false
        val category = safeCategory(row["category"])
        if (category == "可疑") return false
        val scope: Any? = cfg["scope"] ?: "all"
        if (scope == "other") {
            if (category != "其他" || !Rules.allowsCloudAi(category)) return false
        } else if (scope == "uncertain") {
            if (category != "其他" && baselineConfidence(row) >= UNCERTAIN_BELOW) return false
        }
        // 回溯窗口只限制「上一版治理过的历史邮件」：新邮件和本版的重试不受影响
        val raw = if (cfg.containsKey("backfill_days")) {
            cfg["backfill_days"]
        } else {
            DEFAULT_BACKFILL_DAYS
        }
        val backfillDays = pyIntOrNull(if (Py.truthy(raw)) raw else 0)
            ?: DEFAULT_BACKFILL_DAYS.toLong()
        val oldVersion = pyStrOrEmpty(row["governance_version"])
        if (backfillDays > 0 && oldVersion.isNotEmpty() && oldVersion != VERSION &&
            (floatOrNull(row["date_ts"]) ?: 0.0) < now - backfillDays * 86400.0
        ) {
            return false
        }
        return true
    }

    /** 对应 Python `_signal_tags`：送给 AI 的群发/附件/自动发件人标签。 */
    fun signalTags(evidence: Evidence): List<String> {
        val tags = ArrayList<String>(3)
        if (evidence.bulk) tags.add("群发")
        if (evidence.attachment) tags.add("附件")
        if (evidence.senderKind == "automated") tags.add("自动发件人")
        return tags
    }

    // ---------------- 内部工具 ----------------

    /** 对应 Python `int(message.get("importance") or _default_importance(baseline))`。 */
    private fun importanceOf(message: Map<String, Any?>, baseline: String): Int {
        val raw = if (Py.truthy(message["importance"])) {
            message["importance"]
        } else {
            defaultImportance(baseline)
        }
        val parsed = pyIntOrNull(raw)
            ?: throw IllegalArgumentException("importance 不是整数：$raw")
        // Python 最后一定会 max(1, min(5, ...))，提前夹住不影响任何分支。
        return parsed.coerceIn(1L, 5L).toInt()
    }

    /** `max(0.0, min(1.0, value))`；NaN 先按 0 处理，见 [finiteConfidence]。 */
    internal fun clamp01(value: Double): Double = maxOf(0.0, minOf(1.0, finiteConfidence(value)))

    /**
     * 非有限的置信度（NaN、±Infinity）一律按 0 处理。AI 结果与基线置信度都经过这里。
     *
     * **与 Python 的偏差（接入 AI 前必须保留）：** Python 的 `max(0.0, min(1.0, nan))` 得到
     * **1.0**——`nan < 1.0` 为假，`min` 留下 1.0——所以 AI 给出 NaN 时会通过所有护栏（低置信度、
     * 可疑、受保护分类都拦不住）。这里按 0 处理，等价于「AI 没有任何把握」，只会回退到本地
     * 规则。另外 SQLite 会把 NaN 存成 NULL，写库前必须清掉，否则 `messages.confidence`
     * 会变成 NULL 而不是一个数。
     *
     * AI 关闭时（本任务）走不到这条路径；金标不放 NaN 用例（JSON 表达不了，且这是有意偏差）。
     */
    internal fun finiteConfidence(value: Double): Double = if (value.isFinite()) value else 0.0
}

/**
 * Python 的 `str(value)`。只覆盖本模块会遇到的 JSON 值类型：null、布尔、数字、字符串。
 * 浮点按 Python `repr` 的常见形式（整数值写成 `1.0`，科学计数法写成 `1e+20`）。
 */
internal fun pyStr(value: Any?): String = when (value) {
    null -> "None"
    is Boolean -> if (value) "True" else "False"
    is Double -> pyFloatRepr(value)
    is Float -> pyFloatRepr(value.toDouble())
    is BigDecimal -> value.toString()
    is BigInteger -> value.toString()
    is Number -> value.toString()
    is String -> value
    is List<*> -> value.joinToString(", ", "[", "]") { item ->
        if (item is String) "'$item'" else pyStr(item)
    }
    is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { (k, v) ->
        "${if (k is String) "'$k'" else pyStr(k)}: ${if (v is String) "'$v'" else pyStr(v)}"
    }
    else -> value.toString()
}

/** Python 的 `str(value or "")`：假值（None、False、0、空串、空集合）一律变成空串。 */
internal fun pyStrOrEmpty(value: Any?): String = if (Py.truthy(value)) pyStr(value) else ""

/** Python 的 `float(value)`；失败（TypeError / ValueError）返回 null。 */
internal fun floatOrNull(value: Any?): Double? = when (value) {
    null -> null
    is Boolean -> if (value) 1.0 else 0.0
    is Double -> value
    is Float -> value.toDouble()
    is BigDecimal -> value.toDouble()
    is BigInteger -> value.toDouble()
    is Number -> value.toDouble()
    is String -> Py.float(value)
    else -> null
}

/**
 * Python 的 `int(value)`；失败（TypeError / ValueError）返回 null。
 * 小数向零截断，超出 Long 范围的大整数返回 null（本模块只用它处理计数和重要度）。
 */
internal fun pyIntOrNull(value: Any?): Long? = when (value) {
    null -> null
    is Boolean -> if (value) 1L else 0L
    is Byte -> value.toLong()
    is Short -> value.toLong()
    is Int -> value.toLong()
    is Long -> value
    is BigInteger -> bigIntegerToLongOrNull(value)
    is BigDecimal -> bigIntegerToLongOrNull(value.toBigInteger())
    is Double -> if (value.isFinite()) value.toLong() else null
    is Float -> if (value.isFinite()) value.toLong() else null
    is String -> Py.int(value)?.let { bigIntegerToLongOrNull(it) }
    else -> null
}

/** BigInteger 转 Long；超出 64 位整数范围时返回 null（对应 Python 里不会发生的大整数场景）。 */
private fun bigIntegerToLongOrNull(value: BigInteger): Long? = try {
    value.longValueExact()
} catch (_: ArithmeticException) {
    null
}

/** Python 浮点 `repr` 的常见形式；金标只用到整数值和小数，不追求覆盖全部指数写法。 */
private fun pyFloatRepr(value: Double): String {
    if (value.isNaN()) return "nan"
    if (value == Double.POSITIVE_INFINITY) return "inf"
    if (value == Double.NEGATIVE_INFINITY) return "-inf"
    val text = value.toString()
    if ('E' !in text) return text
    val (mantissa, exponent) = text.split('E')
    val exp = exponent.toInt()
    val sign = if (exp < 0) "-" else "+"
    val digits = Math.abs(exp).toString().padStart(2, '0')
    return mantissa.removeSuffix(".0") + "e" + sign + digits
}

/** 解析 JSON 文本，对应 Python `json.loads` 成功时的值；失败返回 null。 */
internal fun parseJson(text: String): Any? = try {
    jsonElementToPlain(Json.parseToJsonElement(text))
} catch (_: IllegalArgumentException) {
    // kotlinx.serialization 的 SerializationException 也是 IllegalArgumentException 的子类
    null
}

/** 把 [JsonElement] 转成 Python 侧等价的 Kotlin 值（整数 → Long，小数 → Double）。 */
internal fun jsonElementToPlain(element: JsonElement): Any? = when (element) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        element.isString -> element.content
        element.booleanOrNull != null -> element.booleanOrNull
        element.content.contains('.') || element.content.contains('e') ||
            element.content.contains('E') -> element.doubleOrNull
        else -> element.content.toLongOrNull() ?: element.doubleOrNull
    }
    is JsonArray -> element.map { jsonElementToPlain(it) }
    is JsonObject -> element.entries.associateTo(LinkedHashMap()) {
        it.key to jsonElementToPlain(it.value)
    }
}
