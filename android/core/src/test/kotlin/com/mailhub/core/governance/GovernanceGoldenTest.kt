package com.mailhub.core.governance

import com.mailhub.core.golden.Golden
import com.mailhub.core.golden.toPlain
import com.mailhub.core.text.Py
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * 回放 `golden/governance.json`：Python `governance.py` 纯逻辑函数的每条用例都在 Kotlin 实现上
 * 重跑一遍。门下省驳回理由的中文措辞、`:0%` 的百分比、按码点截断的摘要/理由都要逐字相同。
 */
class GovernanceGoldenTest {

    @Test
    fun governanceMatchesPython() = Golden.replay("governance") { case ->
        val args = case.args
        when (case.fn) {
            "_safe_category" -> Golden.diff(
                case.expected.toPlain(),
                Governance.safeCategory(args.anyOrNull("value")),
            )

            "_risk_at_least" -> Golden.diff(
                case.expected.toPlain(),
                Governance.riskAtLeast(args.anyOrNull("value"), args.str("threshold")),
            )

            "_default_importance" -> {
                val importance = Governance.defaultImportance(args.str("category"))
                Golden.diff(case.expected.toPlain(), importance.toLong())
            }

            "_baseline_confidence" -> Golden.diff(
                case.expected.toPlain(),
                Governance.baselineConfidence(args.map("message")),
            )

            "_signals" -> Golden.diff(
                case.expected.toPlain(),
                Governance.signals(args.map("message")),
            )

            "build_evidence" -> Golden.diff(
                case.expected.toPlain(),
                Governance.buildEvidence(args.map("message")).toMap(),
            )

            "zhongshu_propose" -> Golden.diff(
                case.expected.toPlain(),
                Governance.zhongshuPropose(
                    message = args.map("message"),
                    aiResult = args.mapOrNull("ai_result"),
                    aiEnabled = args.bool("ai_enabled"),
                ).toMap(),
            )

            "_primary_ministry" -> Golden.diff(
                case.expected.toPlain(),
                Governance.primaryMinistry(
                    Evidence.fromMap(args.map("evidence")),
                    args.str("category"),
                ),
            )

            "_ai_guardrail" -> Golden.diff(
                case.expected.toPlain(),
                Governance.aiGuardrail(
                    baseline = args.str("baseline"),
                    aiCategory = args.str("ai_category"),
                    aiConfidence = args.double("ai_confidence"),
                    message = args.map("message"),
                ),
            )

            "menxia_arbitrate" -> {
                val proposal = Governance.zhongshuPropose(
                    message = args.map("message"),
                    aiResult = args.mapOrNull("ai_result"),
                    aiEnabled = args.bool("ai_enabled"),
                )
                Golden.diff(case.expected.toPlain(), Governance.menxiaArbitrate(proposal).toMap())
            }

            "display_category" -> Golden.diff(
                case.expected.toPlain(),
                Governance.displayCategory(args.str("category"), args.str("bill_tier")),
            )

            "_next_retry" -> {
                val (count, ts) = Governance.nextRetry(
                    message = args.map("message"),
                    verdict = verdictOf(args.map("verdict")),
                    ts = args.double("ts"),
                )
                Golden.diff(case.expected.toPlain(), listOf(count, ts))
            }

            "_wants_alert" -> Golden.diff(
                case.expected.toPlain(),
                Governance.wantsAlert(
                    message = args.map("message"),
                    verdict = verdictOf(args.map("verdict")),
                    ts = args.double("ts"),
                ),
            )

            "_wants_ai" -> Golden.diff(
                case.expected.toPlain(),
                Governance.wantsAi(
                    row = args.map("row"),
                    evidence = Evidence.fromMap(args.map("evidence")),
                    cfg = args.map("cfg"),
                    now = args.double("now"),
                ),
            )

            "_signal_tags" -> Golden.diff(
                case.expected.toPlain(),
                Governance.signalTags(Evidence.fromMap(args.map("evidence"))),
            )

            else -> "未知的函数名 ${case.fn}"
        }
    }

    /** 金标里的裁决字典还原成 [Verdict]；`_wants_alert` / `_next_retry` 只用其中的两个字段。 */
    private fun verdictOf(map: Map<String, Any?>): Verdict = Verdict(
        category = map["category"] as? String ?: "其他",
        ministry = "",
        action = "label_only",
        source = "",
        fallback = false,
        retry = Py.truthy(map["retry"]),
        confidence = 0.0,
        importance = (map["importance"] as? Number)?.toInt() ?: 0,
        summary = "",
        reason = "",
        evidence = emptyMap(),
        bill = null,
    )
}

/** 金标里的字典参数（message / evidence / cfg / row）。 */
@Suppress("UNCHECKED_CAST")
internal fun JsonObject.map(key: String): Map<String, Any?> =
    getValue(key).toPlain() as Map<String, Any?>

/** 金标里的字典参数；null 时返回 null。 */
@Suppress("UNCHECKED_CAST")
internal fun JsonObject.mapOrNull(key: String): Map<String, Any?>? =
    getValue(key).toPlain() as? Map<String, Any?>

/** 金标里的任意 JSON 参数；null 返回 null。 */
internal fun JsonObject.anyOrNull(key: String): Any? = get(key)?.toPlain()

/** 金标里的字符串参数。 */
internal fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

/** 金标里的布尔参数。 */
internal fun JsonObject.bool(key: String): Boolean =
    getValue(key).jsonPrimitive.content.toBoolean()

/** 金标里的数字参数。 */
internal fun JsonObject.double(key: String): Double {
    val element: JsonElement = getValue(key)
    return element.jsonPrimitive.doubleOrNull ?: error("$key 不是数字")
}
