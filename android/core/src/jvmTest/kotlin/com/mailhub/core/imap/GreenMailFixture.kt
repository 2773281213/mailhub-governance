package com.mailhub.core.imap

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeUtility
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone

/**
 * 本地 GreenMail（只有 IMAPS）夹具：起一个真实的 IMAP 服务器、建一个账户、投递合成的邮件。
 *
 * 证书用 [TestTls] 现生成的自签名证书（SAN 里带 localhost / 127.0.0.1），所以
 * `mail.imaps.ssl.checkserveridentity=true` 依然是生效的；客户端只信任这张证书。
 * 所有邮件都是测试里现造的合成数据，不连接任何真实服务器。
 */
internal class GreenMailFixture(
    val email: String = "user@example.com",
    val password: String = "test-secret",
) : AutoCloseable {

    /** 夹具创建时的时间（`time.time()`），测试拿它当可控时钟的基准。 */
    val now: Double = System.currentTimeMillis() / 1000.0

    private val greenMail: GreenMail

    init {
        // GreenMail 自己的默认证书没有 SAN，过不了主机名校验，所以先换成我们的证书
        TestTls.installGreenMailKeystoreProperties()
        // 端口 0 表示让系统挑一个空闲端口，测试之间不会撞车
        val setup = ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAPS)
        greenMail = GreenMail(setup)
        greenMail.start()
        greenMail.setUser(email, password)
    }

    /** IMAPS 实际监听端口。 */
    val port: Int get() = greenMail.imaps.port

    /** 建一套连接这个 GreenMail 的账户配置。 */
    fun account(provider: String = "custom"): ImapAccount = ImapAccount(
        host = "127.0.0.1",
        port = port,
        email = email,
        authType = "password",
        provider = provider,
    )

    /** 测试用的传输设置：信任自签名证书、超时调短，避免失败用例真的等 30 秒。 */
    fun transport(readTimeoutMillis: Int = IMAP_READ_TIMEOUT_MILLIS): ImapTransport = ImapTransport(
        sslSocketFactory = TestTls.trustAllFactory,
        connectTimeoutMillis = 5_000,
        readTimeoutMillis = readTimeoutMillis,
    )

    /**
     * 投递一封纯文本邮件（直接给原始 RFC822 文本，保证 Message-ID、Date 这些头不被
     * JavaMail 的 `saveChanges()` 改写，与真实收信时服务器存的原始字节一致）。
     */
    fun deliver(
        subject: String,
        body: String = "正文 $subject",
        messageId: String = "",
        from: String = "sender@example.org",
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val raw = buildString {
            append("From: $from\r\n")
            append("To: $email\r\n")
            // 主题按 RFC 2047 编成 =?UTF-8?B?...?=，与真实邮件的头部一致
            append("Subject: ${MimeUtility.encodeText(subject, "UTF-8", "B")}\r\n")
            append("Date: ${rfc5322Date()} \r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Transfer-Encoding: 8bit\r\n")
            if (messageId.isNotEmpty()) {
                // messageId 传完整头部值（含尖括号），与 parse_message 存下来的 msg_id 一致
                append("Message-ID: $messageId\r\n")
            }
            for ((name, value) in extraHeaders) {
                append("$name: $value\r\n")
            }
            append("\r\n")
            append(body)
        }
        deliverRaw(raw)
    }

    /** 直接投递一份原始 RFC822 文本。 */
    fun deliverRaw(raw: String) {
        val message = MimeMessage(
            Session.getInstance(Properties()),
            ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)),
        )
        greenMail.userManager.getUser(email).deliver(message)
    }

    /** 固定的 Date 头（合成数据，不依赖真实时间）。 */
    private fun rfc5322Date(): String {
        val format = SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date((now * 1000).toLong()))
    }

    /** 清空所有邮箱里的邮件（模拟服务器重建邮箱前的准备）。 */
    fun purgeAll() = greenMail.purgeEmailFromAllMailboxes()

    /** 把 INBOX 的 UIDVALIDITY 直接改掉，模拟服务器重建邮箱（GreenMail 的默认值是创建时间秒数）。 */
    fun rewriteUidValidity(value: Long) {
        val inbox = greenMail.managers.imapHostManager.getInbox(greenMail.userManager.getUser(email))
        val field = inbox.javaClass.getDeclaredField("uidValidity")
        field.isAccessible = true
        field.setLong(inbox, value)
    }

    override fun close() {
        try {
            greenMail.stop()
        } catch (_: Exception) {
            // 测试结束时关不掉也不该让用例失败
        }
    }
}
