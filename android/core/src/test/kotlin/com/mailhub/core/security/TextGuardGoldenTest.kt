package com.mailhub.core.security

import com.mailhub.core.golden.Golden
import com.mailhub.core.golden.strOrNull
import com.mailhub.core.golden.toPlain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * 回放 `golden/ai_text.json`：Python `ai.py` 里不联网的文本函数（`sanitize_field`、
 * `has_injection`、`mask_secrets`、`prepare_excerpt`）的每条用例都在 Kotlin 实现上重跑一遍。
 * 差异由 [Golden.replay] 汇总，一次列出全部失败。
 */
class TextGuardGoldenTest {

    @Test
    fun textGuardMatchesPython() = Golden.replay("ai_text") { case ->
        val args = case.args
        when (case.fn) {
            "sanitize_field" -> Golden.diff(
                case.expected.toPlain(),
                TextGuard.sanitizeField(args.strOrNull("value"), args.intOr("limit", 200)),
            )

            "has_injection" -> {
                val fields = (args["fields"] as? JsonArray)?.map { element ->
                    (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                } ?: emptyList()
                Golden.diff(
                    case.expected.toPlain(),
                    TextGuard.hasInjection(*fields.toTypedArray()),
                )
            }

            "mask_secrets" -> Golden.diff(
                case.expected.toPlain(),
                TextGuard.maskSecrets(
                    args.strOrNull("text"),
                    args.strOrNull("otp_code") ?: "",
                ),
            )

            "prepare_excerpt" -> Golden.diff(
                case.expected.toPlain(),
                TextGuard.prepareExcerpt(
                    args.strOrNull("body"),
                    args.strOrNull("otp_code") ?: "",
                    args.intOr("limit", TextGuard.AI_BODY_CHARS),
                ),
            )

            else -> "未知的函数名 ${case.fn}"
        }
    }
}

/** 金标里的整数参数；字段缺失时取 Python 的默认值。 */
private fun JsonObject.intOr(key: String, default: Int): Int =
    (get(key) as? JsonPrimitive)?.content?.toInt() ?: default
