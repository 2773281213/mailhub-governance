package com.mailhub.core.rules

import com.mailhub.core.golden.Golden
import com.mailhub.core.golden.boolMapOrNull
import com.mailhub.core.golden.str
import com.mailhub.core.golden.strOrNull
import com.mailhub.core.golden.toPlain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * 回放 `golden/rules.json`：Python `rules.py` 的每条用例都在 Kotlin 实现上重跑一遍，
 * 期望值与实际值必须逐字相同。差异由 [Golden.replay] 汇总，一次列出全部失败（含用例 id、
 * 输入摘要、期望值和实际值）。
 */
class RulesGoldenTest {

    @Test
    fun rulesMatchPython() = Golden.replay("rules") { case ->
        val args = case.args
        when (case.fn) {
            "classify_detailed" -> {
                val result = Rules.classifyDetailed(
                    subject = args.str("subject"),
                    body = args.strOrNull("body").orEmpty(),
                    senderAddr = args.strOrNull("sender_addr").orEmpty(),
                    hasUnsubscribe = args.boolOr("has_unsubscribe", false),
                    customRules = args.customRules(),
                    signals = args.boolMapOrNull("signals"),
                )
                Golden.diff(
                    case.expected.toPlain(),
                    linkedMapOf(
                        "category" to result.category,
                        "otp" to result.otp,
                        "confidence" to result.confidence,
                        "hits" to result.hits,
                    ),
                )
            }

            "classify" -> {
                val result = Rules.classify(
                    subject = args.str("subject"),
                    body = args.strOrNull("body").orEmpty(),
                    senderAddr = args.strOrNull("sender_addr").orEmpty(),
                    hasUnsubscribe = args.boolOr("has_unsubscribe", false),
                    customRules = args.customRules(),
                    signals = args.boolMapOrNull("signals"),
                )
                Golden.diff(case.expected.toPlain(), listOf(result.first, result.second))
            }

            "extract_otp" -> Golden.diff(
                case.expected.toPlain(),
                Rules.extractOtp(args.str("subject"), args.strOrNull("body").orEmpty()),
            )

            "is_bulk" -> Golden.diff(
                case.expected.toPlain(),
                Rules.isBulk(args.boolMapOrNull("signals"), args.boolOr("has_unsubscribe", false)),
            )

            "allows_cloud_ai" -> Golden.diff(
                case.expected.toPlain(),
                Rules.allowsCloudAi(args.str("category")),
            )

            // 分类固定集合：逐项比较，顺序也要一致
            "categories" -> Golden.diff(case.expected.toPlain(), Rules.CATEGORIES)

            // 整张策略表：每个分类的 6 列逐字段比较
            "category_policy" -> Golden.diff(
                case.expected.toPlain(),
                Rules.CATEGORY_POLICY.mapValues { (_, policy) -> policy.toPlain() },
            )

            else -> "未知的函数名 ${case.fn}"
        }
    }
}

/** 把一条 [CategoryPolicy] 摊成与 Python `CATEGORY_POLICY` 逐字对应的 6 列字典。 */
private fun CategoryPolicy.toPlain(): Map<String, Any> = linkedMapOf(
    "risk" to risk,
    "action" to action,
    "keep" to keep,
    "auto_del" to autoDel,
    "cloud_ai" to cloudAi,
    "alert" to alert,
)

/** 金标里的布尔参数；字段缺失时取默认值（Python 的默认参数）。 */
private fun JsonObject.boolOr(key: String, default: Boolean): Boolean =
    (get(key) as? JsonPrimitive)?.booleanOrNull ?: default

/** 金标里的 custom_rules：`[{"field": …, "pattern": …, "category": …}, …]`，缺失为空列表。 */
private fun JsonObject.customRules(): List<CustomRule> =
    (this["custom_rules"] as? JsonArray)?.map { element ->
        val rule = element.jsonObject
        CustomRule(rule.str("field"), rule.str("pattern"), rule.str("category"))
    } ?: emptyList()
