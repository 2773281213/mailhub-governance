package com.mailhub.core.imap

import com.mailhub.core.db.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 用脚本化的假 IMAP 服务器（手写 TLS，见 [ScriptedImapServer]）覆盖 GreenMail 做不到的
 * 场景：`ID` 的发送时机、没有 UIDPLUS 时 `delete` 的两条分支、读超时、XOAUTH2 载荷、
 * 认证失败的中文文案。
 *
 * 所有数据都是合成的，服务器跑在 127.0.0.1 上，不连接任何真实邮箱。
 */
class ImapSessionScriptedTest {

    private val clock = FakeClock(1_768_492_800.0) // 2026-01-15T00:00:00Z

    private fun account(
        provider: String,
        port: Int,
        authType: String = "password",
    ): ImapAccount = ImapAccount(
        host = "127.0.0.1",
        port = port,
        email = "user@example.com",
        authType = authType,
        provider = provider,
    )

    private fun transport(readTimeoutMillis: Int = IMAP_READ_TIMEOUT_MILLIS) = ImapTransport(
        sslSocketFactory = TestTls.trustAllFactory,
        connectTimeoutMillis = 5_000,
        readTimeoutMillis = readTimeoutMillis,
    )

    /**
     * 网易账户：`ID` 必须在 `LOGIN` 之后、`SELECT` 之前发出去
     * （`mail_client.open_session` 的时机要求）。
     */
    @Test
    fun neteaseAccountSendsIdAfterLoginBeforeSelect() {
        ScriptedImapServer(capabilities = "IMAP4rev1 ID UIDPLUS").use { server ->
            val session = openSession(
                account("163", server.port),
                "authorization-code",
                transport(),
                clock,
            )
            try {
                session.selectInbox()
            } finally {
                session.close()
            }

            val login = server.commands.indexOfFirst { it.startsWith("LOGIN") }
            val id = server.commands.indexOfFirst { it.startsWith("ID ") }
            val select = server.commands.indexOfFirst { it.startsWith("SELECT") }
            assertTrue("应该发出 LOGIN，实际命令：${server.commands}", login >= 0)
            assertTrue("网易账户必须发 ID，实际命令：${server.commands}", id >= 0)
            assertTrue("SELECT 应该发生，实际命令：${server.commands}", select >= 0)
            assertTrue("ID 必须在 LOGIN 之后：${server.commands}", id > login)
            assertTrue("ID 必须在 SELECT 之前：${server.commands}", id < select)
            // 载荷字段与 mail_client.IMAP_ID_PAYLOAD 相同
            val payload = server.commands[id]
            for (field in listOf("\"name\" \"MailHub\"", "\"version\" \"1.1\"", "\"vendor\" \"MailHub\"")) {
                assertTrue("ID 载荷里应有 $field，实际：$payload", payload.contains(field))
            }
            assertTrue(
                "ID 载荷里应有 support-email，实际：$payload",
                payload.contains("\"support-email\" \"mailhub@localhost\""),
            )
        }
    }

    /** 非网易账户（QQ 邮箱）：认证后不发 ID。 */
    @Test
    fun nonNeteaseAccountDoesNotSendId() {
        ScriptedImapServer(capabilities = "IMAP4rev1 ID UIDPLUS").use { server ->
            val session = openSession(account("qq", server.port), "auth-code", transport(), clock)
            session.use {
                it.selectInbox()
            }
            assertTrue(
                "非网易账户不该发 ID，实际命令：${server.commands}",
                server.commands.none { it.startsWith("ID ") },
            )
        }
    }

    /** `needId` 显式为真的账户即使服务商不认识也要发 ID（对应 `account.get("need_id")`）。 */
    @Test
    fun needIdFlagForcesIdCommand() {
        ScriptedImapServer(capabilities = "IMAP4rev1 ID").use { server ->
            val custom = ImapAccount(
                host = "127.0.0.1",
                port = server.port,
                email = "user@example.com",
                provider = "custom",
                needId = true,
            )
            val session = openSession(custom, "secret", transport(), clock)
            session.use { it.selectInbox() }
            assertTrue(
                "need_id 为真时必须发 ID，实际命令：${server.commands}",
                server.commands.any { it.startsWith("ID ") },
            )
        }
    }

    /** `login_oauth2` 发出 XOAUTH2 的 SASL 载荷（与 Python 的 user=...\\x01auth=Bearer ... 一致）。 */
    @Test
    fun loginOAuth2SendsXoauth2Payload() {
        ScriptedImapServer(capabilities = "IMAP4rev1 AUTH=XOAUTH2").use { server ->
            val session = openSession(
                account("outlook", server.port, authType = "oauth"),
                "access-token-123",
                transport(),
                clock,
            )
            session.close()
            assertTrue(
                "应该发出 AUTHENTICATE XOAUTH2，实际命令：${server.commands}",
                server.commands.any { it.startsWith("AUTHENTICATE XOAUTH2") },
            )
            // SASL 载荷在线上是 base64 编码的，先解码再比较（与 Python 的组装结果对齐）
            val payload = server.saslPayloads.singleOrNull()
            assertTrue("应该收到一条 SASL 载荷，实际：${server.saslPayloads}", payload != null)
            val decoded = String(Base64.getDecoder().decode(payload!!.trim()), Charsets.UTF_8)
            assertEquals(
                "XOAUTH2 载荷要按 RFC 7628 组装",
                "user=user@example.com\u0001auth=Bearer access-token-123\u0001\u0001",
                decoded,
            )
        }
    }

    /** 认证失败：服务器回 NO，中文文案是「邮箱服务器拒绝登录…」。 */
    @Test
    fun authFailureIsReportedInChinese() {
        val server = ScriptedImapServer(capabilities = "IMAP4rev1") { session, tag, command ->
            if (command.startsWith("LOGIN")) {
                session.send("$tag NO [AUTHENTICATIONFAILED] Invalid credentials")
                true
            } else {
                false
            }
        }
        server.use {
            val error = runCatching {
                openSession(account("qq", server.port), "wrong", transport(), clock)
            }.exceptionOrNull()
            assertTrue("认证失败应该抛异常", error != null)
            assertEquals("邮箱服务器拒绝登录，请检查授权码或密码", connectionErrorText(error))
            assertTrue("AuthenticationFailedException 应被识别成认证失败", isAuthFailure(error))
        }
    }

    /** 网易账户认证失败：按 `open_session` 的规则换成带网易提示的说明。 */
    @Test
    fun neteaseAuthFailureAddsNeteaseHelp() {
        val server = ScriptedImapServer(capabilities = "IMAP4rev1 ID") { session, tag, command ->
            if (command.startsWith("LOGIN")) {
                session.send("$tag NO Invalid credentials")
                true
            } else {
                false
            }
        }
        server.use {
            val error = runCatching {
                openSession(account("163", server.port), "wrong", transport(), clock)
            }.exceptionOrNull()
            assertEquals(NETEASE_LOGIN_HELP, connectionErrorText(error))
            // 会话已经关掉：连不上之后的命令
            assertTrue(server.commands.any { it.startsWith("LOGIN") })
        }
    }

    /**
     * 没有 UIDPLUS 时 `delete` 的第一条分支：收件箱里带 `\Deleted` 的邮件恰好都是本次标记的，
     * 于是执行普通 `EXPUNGE`，返回 expunged。
     */
    @Test
    fun deleteWithoutUidPlusExpungesWhenOnlyOwnMessagesAreDeleted() {
        // 的能力里没有 UIDPLUS
        ScriptedImapServer(capabilities = "IMAP4rev1").use { server ->
            server.deletedUids = " 7 8"
            val session = openSession(account("qq", server.port), "code", transport(), clock)
            session.use {
                it.selectInbox()
                assertEquals("expunged", it.delete(listOf(7, 8)))
            }
            assertTrue(
                "必须打上 \\Deleted：${server.commands}",
                server.commands.any { it.startsWith("UID STORE 7,8 +FLAGS (\\Deleted)") },
            )
            assertTrue(
                "要先查一遍 DELETED：${server.commands}",
                server.commands.any { it.startsWith("UID SEARCH DELETED") },
            )
            assertTrue(
                "安全的场合必须执行 EXPUNGE：${server.commands}",
                server.commands.any { it == "EXPUNGE" },
            )
            assertFalse(
                "没有 UIDPLUS 就不能用 UID EXPUNGE：${server.commands}",
                server.commands.any { it.startsWith("UID EXPUNGE") },
            )
        }
    }

    /**
     * 没有 UIDPLUS 时的第二条分支：收件箱里还有别的客户端标记待删的邮件（UID 99），
     * 于是不执行 `EXPUNGE`，返回 flagged。
     */
    @Test
    fun deleteWithoutUidPlusLeavesFlaggedWhenOtherClientsDeletedSomething() {
        ScriptedImapServer(capabilities = "IMAP4rev1").use { server ->
            server.deletedUids = " 7 99"
            val session = openSession(account("qq", server.port), "code", transport(), clock)
            session.use {
                it.selectInbox()
                assertEquals("flagged", it.delete(listOf(7)))
            }
            assertTrue(
                "必须打上 \\Deleted：${server.commands}",
                server.commands.any { it.startsWith("UID STORE 7 +FLAGS (\\Deleted)") },
            )
            assertFalse(
                "会连带清掉别人的邮件时不能 EXPUNGE：${server.commands}",
                server.commands.any { it == "EXPUNGE" || it.startsWith("UID EXPUNGE") },
            )
        }
    }

    /** 有 UIDPLUS 时用 `UID EXPUNGE`，只清列出的 UID，不发普通 EXPUNGE。 */
    @Test
    fun deleteWithUidPlusUsesUidExpunge() {
        ScriptedImapServer(capabilities = "IMAP4rev1 UIDPLUS").use { server ->
            val session = openSession(account("qq", server.port), "code", transport(), clock)
            session.use {
                it.selectInbox()
                assertEquals("expunged", it.delete(listOf(3, 4)))
            }
            assertTrue(
                "有 UIDPLUS 时必须发 UID EXPUNGE：${server.commands}",
                server.commands.any { it == "UID EXPUNGE 3,4" },
            )
            assertFalse(
                "有 UIDPLUS 时不该发普通 EXPUNGE：${server.commands}",
                server.commands.any { it == "EXPUNGE" },
            )
        }
    }

    /** 首次搜索发的是 `SINCE dd-MMM-yyyy`（UTC、英文月份），增量搜索发的是 `UID n:*`。 */
    @Test
    fun searchCommandsMatchPython() {
        ScriptedImapServer(capabilities = "IMAP4rev1").use { server ->
            val session = openSession(account("qq", server.port), "code", transport(), clock)
            session.use {
                it.selectInbox()
                it.searchUids(lastUid = 0, sinceDays = 30)
                it.searchUids(lastUid = 41)
            }
            // 2026-01-15 往前 30 天是 2025-12-16
            assertTrue(
                "首次搜索应为 SINCE 2025-12-16（UTC），实际：${server.commands}",
                server.commands.any { it == "UID SEARCH SINCE 16-Dec-2025" },
            )
            assertTrue(
                "增量搜索应为 UID 42:*，实际：${server.commands}",
                server.commands.any { it == "UID SEARCH UID 42:*" },
            )
        }
    }

    /** 读超时：服务器收了 SELECT 却不回话，异常要说成「连接邮箱服务器超时」。 */
    @Test
    fun readTimeoutIsReportedAsChineseTimeoutText() {
        val server = ScriptedImapServer(capabilities = "IMAP4rev1") { _, _, command ->
            // 卡在 SELECT 上：返回 true 表示「已处理」，但一个字节都不写
            command.startsWith("SELECT")
        }
        server.use {
            val session = openSession(account("qq", server.port), "code", transport(300), clock)
            try {
                val error = runCatching { session.selectInbox() }.exceptionOrNull()
                assertTrue("SELECT 卡住后应该抛异常，实际没有：${server.commands}", error != null)
                assertEquals("连接邮箱服务器超时", connectionErrorText(error))
            } finally {
                session.close()
            }
        }
    }

    /** [ImapSession.abort] 后所有操作立刻失败，不会再等 socket。 */
    @Test
    fun abortMakesSessionUnusable() {
        ScriptedImapServer(capabilities = "IMAP4rev1").use { server ->
            val session = openSession(account("qq", server.port), "code", transport(), clock)
            session.use {
                it.selectInbox()
                it.abort()
                assertTrue(it.isAborted())
                val error = runCatching { it.searchUids(0) }.exceptionOrNull()
                assertTrue("abort 之后应该抛 ImapException", error is ImapException)
            }
        }
    }
}
