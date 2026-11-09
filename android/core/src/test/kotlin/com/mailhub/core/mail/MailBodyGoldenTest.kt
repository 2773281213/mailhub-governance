package com.mailhub.core.mail

import com.mailhub.core.Clock
import com.mailhub.core.golden.Golden
import com.mailhub.core.golden.GoldenCase
import com.mailhub.core.golden.boolMapOrNull
import com.mailhub.core.golden.double
import com.mailhub.core.golden.str
import com.mailhub.core.golden.strList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.util.TimeZone

/**
 * 邮件正文解析的金标回放：夹具是 `src/test/resources/mail/` 下的合成 `.eml`，
 * 期望值由 `scripts/golden/mail_body.py` 用 Python 的 `mail_client` 算出来。
 *
 * 金标把 `mail_client.time.time` 固定成用例里的 `clock`，所以「日期缺失 / 非法时回退当前时间」
 * 和「整封不可解析」的样本也能逐条比较；时区显式传 UTC（夹具的 Date 都带明确时区，或缺失由时钟兜底）。
 *
 * 每个用例失败时都会写明 id、期望值与实际值，全部跑完再统一失败（见 [Golden.replay]）。
 */
class MailBodyGoldenTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    @Test
    fun parseMessageMatchesPython() = Golden.replay("mail_body", cases("parse_message")) { case ->
        checkParseMessage(case)
    }

    @Test
    fun stringHelpersMatchPython() = Golden.replay("mail_body", cases(*STRING_FUNCTIONS)) { case ->
        when (case.fn) {
            "html_to_text" -> Golden.diff(
                case.expected.jsonPrimitive.content,
                htmlToText(case.args.str("html")),
            )
            "is_stub_text" -> Golden.diff(
                case.expected.jsonPrimitive.boolean,
                isStubText(case.args.str("text"), case.args.str("html_text")),
            )
            "tidy_body" -> Golden.diff(
                case.expected.jsonPrimitive.content,
                tidyBody(case.args.str("text")),
            )
            "make_snippet" -> Golden.diff(
                case.expected.jsonPrimitive.content,
                makeSnippet(case.args.str("text")),
            )
            "readable_body" -> Golden.diff(
                case.expected.jsonPrimitive.content,
                readableBody(case.args.str("body_text"), case.args.str("body_html")),
            )
            else -> "未知的 fn：${case.fn}"
        }
    }

    /** `parse_message` 的完整结果：逐字段比较，长文本只打印前 200 字符和长度。 */
    private fun checkParseMessage(case: GoldenCase): String? {
        val expected: JsonObject = case.expected.jsonObject
        val actual = parseMessage(readFixture(case.args.str("file")), clock = fixedClock(case), zone = utc)
        val problems = mutableListOf<String>()
        fun check(name: String, expectedValue: Any?, actualValue: Any?) {
            if (expectedValue != actualValue) problems += "$name 期望 $expectedValue，实际 $actualValue"
        }
        fun checkText(name: String, expectedValue: String, actualValue: String) {
            if (expectedValue != actualValue) {
                problems += "$name 期望 ${expectedValue.take(200)}(共 ${expectedValue.length} 字符)，" +
                    "实际 ${actualValue.take(200)}(共 ${actualValue.length} 字符)"
            }
        }
        check("msg_id", expected.str("msg_id"), actual.msgId)
        check("subject", expected.str("subject"), actual.subject)
        check("sender_name", expected.str("sender_name"), actual.senderName)
        check("sender_addr", expected.str("sender_addr"), actual.senderAddr)
        check("to_addr", expected.str("to_addr"), actual.toAddr)
        check("reply_to", expected.str("reply_to"), actual.replyTo)
        check("attach_names", expected.strList("attach_names"), actual.attachNames)
        check("date_ts", expected.double("date_ts"), actual.dateTs)
        checkText("snippet", expected.str("snippet"), actual.snippet)
        checkText("body_text", expected.str("body_text"), actual.bodyText)
        checkText("body_html", expected.str("body_html"), actual.bodyHtml)
        check("has_attach", expected.getValue("has_attach").jsonPrimitive.int, actual.hasAttach)
        check("unsubscribe", expected.getValue("unsubscribe").jsonPrimitive.int, actual.unsubscribe)
        check("header_signals", expected.boolMapOrNull("header_signals"), actual.headerSignals)
        check("auth_results", expected.str("auth_results"), actual.authResults)
        return if (problems.isEmpty()) null else problems.joinToString("；")
    }

    private fun cases(vararg functions: String): List<GoldenCase> {
        val all = Golden.load("mail_body")
        return all.filter { it.fn in functions }
    }

    private fun readFixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/mail/$name")) {
            "找不到夹具 /mail/$name，先运行 python scripts/golden/mail_body.py"
        }.use { it.readBytes() }

    /** 金标生成时用 `mock.patch` 把 `mail_client` 里的 `time.time()` 固定成 `args.clock`。 */
    private fun fixedClock(case: GoldenCase): Clock = Clock { case.args.double("clock") }

    private companion object {
        val STRING_FUNCTIONS = arrayOf("html_to_text", "is_stub_text", "tidy_body", "make_snippet", "readable_body")
    }
}
