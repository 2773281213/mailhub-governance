package com.mailhub.core.rules

import com.mailhub.core.golden.Golden
import com.mailhub.core.golden.double
import com.mailhub.core.golden.str
import com.mailhub.core.golden.toPlain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Test

/**
 * 回放 `golden/policy.json`：Python `policy.py` 的每条用例都在 Kotlin 实现上重跑一遍。
 * 拦截原因的文字必须逐字相同，所以这里整条三元组一起比。
 */
class PolicyGoldenTest {

    @Test
    fun policyMatchesPython() = Golden.replay("policy") { case ->
        val args = case.args
        when (case.fn) {
            "evaluate" -> {
                val decision = Policy.evaluate(
                    action = args.str("action"),
                    msg = args.msg(),
                    actor = args.str("actor"),
                    confidence = args.double("confidence"),
                    userRule = args.boolOr("user_rule", false),
                    confirmed = args.boolOr("confirmed", false),
                    overrideProtection = args.boolOr("override_protection", false),
                )
                Golden.diff(
                    case.expected.toPlain(),
                    listOf(decision.allowed, decision.tier, decision.reason),
                )
            }

            "is_protected" -> {
                val protection = Policy.isProtected(args.msg())
                Golden.diff(case.expected.toPlain(), listOf(protection.protected, protection.reason))
            }

            "filter_cleanable" -> {
                val cleanable = Policy.filterCleanable(args.str("category"))
                Golden.diff(case.expected.toPlain(), listOf(cleanable.allowed, cleanable.reason))
            }

            else -> "未知的函数名 ${case.fn}"
        }
    }
}

/** 金标里的布尔参数；字段缺失时取默认值（Python 的默认参数）。 */
private fun JsonObject.boolOr(key: String, default: Boolean): Boolean =
    (get(key) as? JsonPrimitive)?.booleanOrNull ?: default

/** 金标里的 msg 字典：值可能是字符串、数字、布尔或缺失，统一转成 Map<String, Any?>。 */
@Suppress("UNCHECKED_CAST")
private fun JsonObject.msg(): Map<String, Any?> = getValue("msg").toPlain() as Map<String, Any?>
