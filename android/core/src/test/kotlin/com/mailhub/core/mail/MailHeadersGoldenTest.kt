package com.mailhub.core.mail

import com.mailhub.core.golden.Golden
import com.mailhub.core.golden.GoldenCase
import com.mailhub.core.golden.boolMapOrNull
import com.mailhub.core.golden.double
import com.mailhub.core.golden.str
import com.mailhub.core.golden.strOrNull
import com.mailhub.core.golden.strList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.util.TimeZone

/**
 * 邮件头解析的金标回放：夹具是 `src/test/resources/mail/` 下的合成 `.eml`，
 * 期望值由 `scripts/golden/mail_headers.py` 用 Python 的 `email` 标准库算出来。
 *
 * 每个用例失败时都会写明 id、期望值与实际值，全部跑完再统一失败（见 [Golden.replay]）。
 */
class MailHeadersGoldenTest {

    /**
     * 夹具里的 Date 都带明确时区，所以时区参数不影响结果；这里显式传 UTC，
     * 让「万一有夹具漏了时区」这件事在任何机器上都表现为同一个失败，而不是随本机时区漂移。
     */
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    @Test
    fun parseHeadersMatchesPython() = Golden.replay("mail_headers") { case ->
        when (case.fn) {
            "parse_headers" -> checkParseHeaders(case)
            "decode_header" -> Golden.diff(
                stringOrNull(case.expected),
                decodeHeader(case.args.strOrNull("raw")),
            )
            "parseaddr" -> Golden.diff(
                stringList(case.expected),
                parseAddr(case.args.strOrNull("addr")).toList(),
            )
            "getaddresses" -> Golden.diff(
                stringPairs(case.expected),
                getAddresses(case.args.strList("values")).map { it.toList() },
            )
            "parsedate_to_datetime" -> Golden.diff(
                case.expected.jsonPrimitive.doubleOrNull,
                MailDate.timestamp(case.args.str("value"), utc),
            )
            else -> "未知的 fn：${case.fn}"
        }
    }

    /** `parse_headers`：逐字段比较，差异里写明字段名。 */
    private fun checkParseHeaders(case: GoldenCase): String? {
        val expected: JsonObject = case.expected.jsonObject
        val actual = parseHeaders(readFixture(case.args.str("file")), zone = utc)
        val problems = mutableListOf<String>()
        fun check(name: String, expectedValue: Any?, actualValue: Any?) {
            if (expectedValue != actualValue) problems += "$name 期望 $expectedValue，实际 $actualValue"
        }
        check("msg_id", expected.str("msg_id"), actual.msgId)
        check("subject", expected.str("subject"), actual.subject)
        check("sender_name", expected.str("sender_name"), actual.senderName)
        check("sender_addr", expected.str("sender_addr"), actual.senderAddr)
        check("to_addr", expected.str("to_addr"), actual.toAddr)
        check("reply_to", expected.str("reply_to"), actual.replyTo)
        check("date_ts", expected.double("date_ts"), actual.dateTs)
        check("unsubscribe", expected.getValue("unsubscribe").jsonPrimitive.int, actual.unsubscribe)
        check("auth_results", expected.str("auth_results"), actual.authResults)
        check("header_signals", expected.boolMapOrNull("header_signals"), actual.headerSignals)
        return if (problems.isEmpty()) null else problems.joinToString("；")
    }

    private fun readFixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/mail/$name")) {
            "找不到夹具 /mail/$name，先运行 python scripts/golden/mail_headers.py"
        }.use { it.readBytes() }

    private fun stringOrNull(element: Any): String? =
        (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    /** `[["name", "addr"], ...]` 形式的期望值。 */
    private fun stringPairs(element: Any): List<List<String>> =
        (element as JsonArray).map { pair ->
            (pair as JsonArray).map { it.jsonPrimitive.content }
        }

    /** `["name", "addr"]` 形式的期望值。 */
    private fun stringList(element: Any): List<String> =
        (element as JsonArray).map { it.jsonPrimitive.content }
}
