package com.mailhub.core.billing

import com.mailhub.core.golden.Golden
import com.mailhub.core.golden.str
import com.mailhub.core.golden.toPlain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Test

/**
 * 回放 `golden/billing.json`：Python `billing.py` 的每条用例都在 Kotlin 实现上重跑一遍。
 * 定级理由的中文措辞、金额的 Python `%g` 格式、按码点截断的模板键都要逐字相同，
 * 所以这里把整条输出一起比。
 */
class BillingGoldenTest {

    @Test
    fun billingMatchesPython() = Golden.replay("billing") { case ->
        val args = case.args
        when (case.fn) {
            "extract_amount" -> {
                val amount = Billing.extractAmount(args.str("text"))
                Golden.diff(case.expected.toPlain(), listOf(amount.value, amount.currency))
            }

            "to_cny" -> {
                val amount = args.numberOrNull("amount")
                val currency = args.stringOrNull("currency")
                Golden.diff(case.expected.toPlain(), Billing.toCny(amount, currency))
            }

            "extract_facets" -> Golden.diff(
                case.expected.toPlain(),
                Billing.extractFacets(args.stringOrNull("subject"), args.stringOrNull("body")),
            )

            "merge_ai_facets" -> Golden.diff(
                case.expected.toPlain(),
                Billing.mergeAiFacets(args.facetsMap("local"), args.getValue("ai_facets").toPlain()),
            )

            "template_key" -> Golden.diff(
                case.expected.toPlain(),
                Billing.templateKey(args.stringOrNull("sender_addr"), args.stringOrNull("subject")),
            )

            "decide_tier" -> {
                val decision = Billing.decideTier(
                    facets = args.facetsMap("facets"),
                    routineCount = args.longOr("routine_count", 0L),
                    preference = args.stringOrNull("preference").orEmpty(),
                    cfg = args.mapOrNull("cfg"),
                )
                Golden.diff(case.expected.toPlain(), listOf(decision.tier, decision.reason))
            }

            "tier_importance" -> {
                val importance = Billing.tierImportance(
                    tier = args.str("tier"),
                    facets = args.facetsMap("facets"),
                    current = args.longOr("current", 0L).toInt(),
                )
                // 金标里的 Python int 经 toPlain 变成 Long，这里对齐类型再比
                Golden.diff(case.expected.toPlain(), importance.toLong())
            }

            "stored_facets" -> Golden.diff(
                case.expected.toPlain(),
                Billing.storedFacets(args.facetsMap("facets")),
            )

            else -> "未知的函数名 ${case.fn}"
        }
    }
}

/** 金标里的画像字典：值可能是字符串、数字、布尔或 null，统一转成 Map<String, Any?>。 */
@Suppress("UNCHECKED_CAST")
internal fun JsonObject.facetsMap(key: String): Map<String, Any?> =
    getValue(key).toPlain() as Map<String, Any?>

/** 金标里的字典参数（cfg）；null 时返回 null。 */
@Suppress("UNCHECKED_CAST")
internal fun JsonObject.mapOrNull(key: String): Map<String, Any?>? =
    getValue(key).toPlain() as? Map<String, Any?>

/** 金标里的字符串参数；缺失或 null 时返回 null。 */
internal fun JsonObject.stringOrNull(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/** 金标里的数字参数（整数或小数）；缺失或 null 时返回 null。 */
internal fun JsonObject.numberOrNull(key: String): Double? {
    val element: JsonElement = get(key) ?: return null
    if (element is JsonNull) return null
    return element.jsonPrimitive.doubleOrNull
}

/** 金标里的整型参数，缺失时取默认值（Python 的默认参数）。 */
internal fun JsonObject.longOr(key: String, default: Long): Long =
    (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull ?: default
