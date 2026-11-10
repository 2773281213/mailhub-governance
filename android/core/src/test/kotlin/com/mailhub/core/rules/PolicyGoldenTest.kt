package com.mailhub.core.rules

import com.mailhub.core.golden.Golden
import com.mailhub.core.golden.double
import com.mailhub.core.golden.str
import com.mailhub.core.golden.toPlain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * 回放 `golden/policy.json`：Python `policy.py` 的每条用例都在 Kotlin 实现上重跑一遍。
 * 拦截原因的文字必须逐字相同，所以这里整条三元组一起比。
 *
 * expected 写成 `{"raised": "TypeError"}` 的用例表示 Python 端抛了异常（见 `add_raises`）：
 * 这类用例 Kotlin 必须抛 [IllegalArgumentException]，对应关系见 [Policy] 的 KDoc。
 */
class PolicyGoldenTest {

    @Test
    fun policyMatchesPython() = Golden.replay("policy") { case ->
        val args = case.args
        // expected 是 {"raised": …} 时，记录的是 Python 端抛出的异常类型
        val pythonError = (case.expected as? JsonObject)?.get("raised")?.jsonPrimitive?.content
        when (case.fn) {
            "evaluate" -> {
                val call = {
                    Policy.evaluate(
                        action = args.str("action"),
                        msg = args.msg(),
                        actor = args.str("actor"),
                        confidence = args.double("confidence"),
                        userRule = args.boolOr("user_rule", false),
                        confirmed = args.boolOr("confirmed", false),
                        overrideProtection = args.boolOr("override_protection", false),
                    )
                }
                if (pythonError != null) {
                    expectIllegalArgument(pythonError, call)
                } else {
                    val decision = call()
                    Golden.diff(
                        case.expected.toPlain(),
                        listOf(decision.allowed, decision.tier, decision.reason),
                    )
                }
            }

            "is_protected" -> {
                val call = { Policy.isProtected(args.msg()) }
                if (pythonError != null) {
                    expectIllegalArgument(pythonError, call)
                } else {
                    val protection = call()
                    Golden.diff(case.expected.toPlain(), listOf(protection.protected, protection.reason))
                }
            }

            "filter_cleanable" -> {
                val call = { Policy.filterCleanable(args.str("category")) }
                if (pythonError != null) {
                    expectIllegalArgument(pythonError, call)
                } else {
                    val cleanable = call()
                    Golden.diff(case.expected.toPlain(), listOf(cleanable.allowed, cleanable.reason))
                }
            }

            else -> "未知的函数名 ${case.fn}"
        }
    }
}

/**
 * 金标 expected 为 `{"raised": "<Python 异常>"}` 的用例：Kotlin 必须抛 [IllegalArgumentException] 才算一致。
 * 返回 null 表示一致，否则是差异说明。
 */
private fun expectIllegalArgument(pythonError: String, call: () -> Any?): String? = try {
    call()
    "期望抛出 IllegalArgumentException（对应 Python 的 $pythonError），实际正常返回"
} catch (_: IllegalArgumentException) {
    null
} catch (e: Throwable) {
    "期望抛出 IllegalArgumentException（对应 Python 的 $pythonError），" +
        "实际抛出 ${e::class.simpleName}: ${e.message}"
}

/** 金标里的布尔参数；字段缺失时取默认值（Python 的默认参数）。 */
private fun JsonObject.boolOr(key: String, default: Boolean): Boolean =
    (get(key) as? JsonPrimitive)?.booleanOrNull ?: default

/** 金标里的 msg 字典：值可能是字符串、数字、布尔或缺失，统一转成 Map<String, Any?>。 */
@Suppress("UNCHECKED_CAST")
private fun JsonObject.msg(): Map<String, Any?> = getValue("msg").toPlain() as Map<String, Any?>
