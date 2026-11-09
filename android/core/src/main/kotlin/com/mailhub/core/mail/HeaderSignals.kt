package com.mailhub.core.mail

import com.mailhub.core.text.PyRegex
import com.mailhub.core.text.pyStrip
import java.util.regex.Pattern

/**
 * `mail_client.header_signals(msg)` 的移植：从邮件头提取群发 / 自动化信号，只返回布尔值，不保存头的原文。
 *
 * 这里只吃 [MimeHeaders]，不碰正文；取值语义与 compat32 下 `msg.get` / `msg.get_all` 相同。
 */
fun headerSignals(headers: MimeHeaders): Map<String, Boolean> {
    val precedence = (headers.get("Precedence") ?: "").pyStrip().lowercase()
    val auto = (headers.get("Auto-Submitted") ?: "").pyStrip().lowercase()
    val dkim = headers.getAll("DKIM-Signature").joinToString(" ")
    val dkimDomains = findAll(DKIM_DOMAIN, dkim).joinToString(" ")
    val origin = (headers.get("Return-Path") ?: "") + " " + dkimDomains
    return linkedMapOf(
        "list_id" to !headers.get("List-Id").isNullOrEmpty(),
        "precedence_bulk" to (precedence == "bulk" || precedence == "list" || precedence == "junk"),
        "auto_submitted" to (auto.isNotEmpty() && auto != "no"),
        "feedback_id" to !headers.get("Feedback-ID").isNullOrEmpty(),
        "esp" to ESP.matcher(origin).find(),
    )
}

/** Python 的 `pattern.findall(text)`：这里只用到带一个捕获组的模式。 */
private fun findAll(pattern: Pattern, text: String): List<String> {
    val matcher = pattern.matcher(text)
    val out = ArrayList<String>()
    while (matcher.find()) out += matcher.group(1) ?: ""
    return out
}

/**
 * `mail_client._ESP_RE`：常见群发 / 营销投递平台。
 * 不含 Amazon SES、Postmark 这类大量承载验证码和回执的事务邮件通道。
 */
private val ESP = PyRegex.compile(
    "sendgrid\\.net|mcsv\\.net|mcdlv\\.net|rsgsv\\.net|mailchimp|mandrillapp\\.com|mailgun\\.org" +
        "|sparkpostmail\\.com|sendinblue\\.com|brevo\\.com|mailjet\\.com|exacttarget\\.com" +
        "|createsend\\.com|cmail\\d+\\.com|klaviyomail\\.com|hubspotemail\\.net|constantcontact\\.com" +
        "|mktomail\\.com|rsys\\d*\\.net|mailerlite\\.com|sendcloud\\.(net|org)",
    ignoreCase = true,
)

/** `mail_client._DKIM_DOMAIN_RE`：取 DKIM-Signature 里的 `d=` 域名。 */
private val DKIM_DOMAIN = PyRegex.compile("(?<![a-z])d=([\\w.-]+)", ignoreCase = true)
