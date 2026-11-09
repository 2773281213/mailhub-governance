package com.mailhub.core.providers

import com.mailhub.core.golden.Golden
import com.mailhub.core.golden.str
import com.mailhub.core.golden.strOrNull
import com.mailhub.core.golden.toPlain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * `providers.py` 的金标回放：服务商全部字段、`detect`、`needs_imap_id`、`get`、
 * `netease_keys` 与列表顺序都逐条与 Python 比。
 */
class ProvidersGoldenTest {

    @Test
    fun providersMatchPython() = Golden.replay("providers") { case ->
        when (case.fn) {
            "provider" -> {
                val provider = get(case.args.str("key"))
                    ?: return@replay "找不到服务商 ${case.args.str("key")}"
                Golden.diff(case.expected.toPlain(), providerMap(provider))
            }
            "detect" -> Golden.diff(
                case.expected.jsonPrimitive.content,
                detect(case.args.str("email")),
            )
            "needs_imap_id" -> Golden.diff(
                case.expected.jsonPrimitive.content.toBooleanStrict(),
                needsImapId(case.args.strOrNull("key")),
            )
            "get" -> Golden.diff(stringOrNull(case.expected), get(case.args.strOrNull("key"))?.key)
            "netease_keys" -> Golden.diff(
                case.expected.jsonArray.map { it.jsonPrimitive.content },
                neteaseKeys(),
            )
            "provider_order" -> Golden.diff(
                case.expected.jsonArray.map { it.jsonPrimitive.content },
                PROVIDERS_ALL.map { it.key },
            )
            else -> "未知的 fn：${case.fn}"
        }
    }

    /** 服务商的全部字段，键名与 `scripts/golden/providers.py` 导出的 JSON 一致。 */
    private fun providerMap(provider: Provider): Map<String, Any?> {
        val secret = provider.secret?.let {
            mapOf(
                "kind" to it.kind,
                "input_label" to it.inputLabel,
                "setup_url" to it.setupUrl,
                "setup_label" to it.setupLabel,
                "steps" to it.steps,
                "prereq_links" to it.prereqLinks.map { (title, url) -> listOf(title, url) },
                "troubleshoot" to it.troubleshoot,
            )
        }
        return linkedMapOf(
            "key" to provider.key,
            "label" to provider.label,
            "imap_host" to provider.imapHost,
            "domains" to provider.domains,
            // JSON 里的整数统一解析成 Long，这里对齐类型
            "imap_port" to provider.imapPort.toLong(),
            "secret" to secret,
            "oauth" to provider.oauth,
            "oauth_label" to provider.oauthLabel,
            "device_flow" to provider.deviceFlow,
            "oauth_imap_host" to provider.oauthImapHost,
            "oauth_missing_hint" to provider.oauthMissingHint,
            "netease_web" to provider.neteaseWeb,
            "send_imap_id" to provider.sendImapId,
            "note" to provider.note,
            "host_for_oauth" to provider.hostFor("oauth"),
            "host_for_password" to provider.hostFor("password"),
            "netease_authorize_url" to provider.neteaseAuthorizeUrl,
        )
    }

    private fun stringOrNull(element: JsonElement): String? =
        (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}
