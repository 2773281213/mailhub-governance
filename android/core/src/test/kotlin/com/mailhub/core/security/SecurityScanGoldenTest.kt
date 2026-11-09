package com.mailhub.core.security

import com.mailhub.core.golden.Golden
import com.mailhub.core.golden.strOrNull
import com.mailhub.core.golden.toPlain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * 回放 `golden/security_scan.json`：Python `security_scan.py` 的每条用例都在 Kotlin 实现上
 * 重跑一遍，期望值与实际值必须逐字相同。差异由 [Golden.replay] 汇总，一次列出全部失败
 * （含用例 id、输入摘要、期望值和实际值）。
 */
class SecurityScanGoldenTest {

    @Test
    fun securityScanMatchesPython() = Golden.replay("security_scan") { case ->
        val args = case.args
        when (case.fn) {
            "scan" -> {
                val result = SecurityScan.scan(
                    subject = args.strOrNull("subject"),
                    body = args.strOrNull("body"),
                    senderName = args.strOrNull("sender_name"),
                    senderAddr = args.strOrNull("sender_addr"),
                    replyTo = args.strOrNull("reply_to") ?: "",
                    attachments = (args["attachments"] as? JsonArray)?.map { it.jsonPrimitive.content },
                    html = args.strOrNull("html") ?: "",
                    authResults = args.strOrNull("auth_results") ?: "",
                )
                Golden.diff(
                    case.expected.toPlain(),
                    linkedMapOf("risk" to result.risk, "reasons" to result.reasons),
                )
            }

            "extract_html_links" -> {
                val limit = (args["limit"] as? JsonPrimitive)?.content?.toInt() ?: 200
                val links = SecurityScan.extractHtmlLinks(args.strOrNull("html"), limit)
                    .map { listOf(it.first, it.second) }
                Golden.diff(case.expected.toPlain(), links)
            }

            "link_host" -> Golden.diff(
                case.expected.toPlain(),
                SecurityScan.linkHost(args.strOrNull("href")),
            )

            "parse_authentication_results" -> Golden.diff(
                case.expected.toPlain(),
                SecurityScan.parseAuthenticationResults(args.strOrNull("value")),
            )

            "registrable_domain" -> Golden.diff(
                case.expected.toPlain(),
                SecurityScan.registrableDomain(args.strOrNull("domain")),
            )

            "risk_at_least" -> Golden.diff(
                case.expected.toPlain(),
                SecurityScan.riskAtLeast(args.strOrNull("risk"), args.strOrNull("threshold")),
            )

            "has_confusable" -> Golden.diff(
                case.expected.toPlain(),
                SecurityScan.hasConfusable(args.strOrNull("s")),
            )

            "domain_of" -> Golden.diff(
                case.expected.toPlain(),
                SecurityScan.domainOf(args.strOrNull("addr")),
            )

            else -> "未知的函数名 ${case.fn}"
        }
    }
}
