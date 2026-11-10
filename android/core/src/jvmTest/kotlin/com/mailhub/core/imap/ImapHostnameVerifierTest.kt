package com.mailhub.core.imap

import com.mailhub.core.db.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.HostnameVerifier

/**
 * [StrictHostnameVerifier] 真的被 Angus 调用吗？用脚本化服务器（[ScriptedImapServer]，
 * 自签名证书的 SAN 是 `localhost, 127.0.0.1`）验证两件事：
 *
 * - 注入一个总返回 false 的校验器时，连接必须失败，而且失败发生在发出 `LOGIN` **之前**
 *   （服务器一条 `LOGIN` 都收不到）。这条用例守住「手机上必须校验主机名」这个结论：
 *   如果 Angus 不调用我们配的校验器，连接会成功，测试立刻红。
 * - 记录调用参数的校验器必须收到配置的主机名，确认传下去的是我们要校验的那个主机。
 */
class ImapHostnameVerifierTest {

    private val clock = FakeClock(1_768_492_800.0) // 2026-01-15T00:00:00Z

    private fun account(port: Int): ImapAccount = ImapAccount(
        host = "127.0.0.1",
        port = port,
        email = "user@example.com",
        provider = "qq",
    )

    /** 用 [verifier] 组装传输层：信任测试的自签名证书，但主机名校验按参数走。 */
    private fun transport(verifier: HostnameVerifier): ImapTransport = ImapTransport(
        sslSocketFactory = TestTls.trustAllFactory,
        connectTimeoutMillis = 5_000,
        readTimeoutMillis = IMAP_READ_TIMEOUT_MILLIS,
        hostnameVerifier = verifier,
    )

    /** 总返回 false 的校验器：连接必须在认证之前失败，服务器不能收到 LOGIN。 */
    @Test
    fun rejectingVerifierFailsConnectionBeforeLogin() {
        ScriptedImapServer(capabilities = "IMAP4rev1").use { server ->
            val verifier = HostnameVerifier { _, _ -> false }
            val error = runCatching {
                openSession(account(server.port), "authorization-code", transport(verifier), clock)
            }.exceptionOrNull()

            assertTrue("主机名校验失败时必须抛异常，实际命令：${server.commands}", error != null)
            assertFalse(
                "校验失败必须发生在 LOGIN 之前，实际命令：${server.commands}",
                server.commands.any { it.startsWith("LOGIN") },
            )
            assertEquals("与邮箱服务器建立加密连接失败", connectionErrorText(error))
        }
    }

    /** 记录调用的校验器：必须被调用，而且参数是配置里的主机名。 */
    @Test
    fun verifierIsCalledWithConfiguredHost() {
        ScriptedImapServer(capabilities = "IMAP4rev1").use { server ->
            val hosts = CopyOnWriteArrayList<String>()
            val verifier = HostnameVerifier { host, _ ->
                hosts += host
                true
            }
            openSession(account(server.port), "authorization-code", transport(verifier), clock).close()

            assertEquals("校验器应该收到配置的主机名", listOf("127.0.0.1"), hosts.toList())
            assertTrue(
                "连接成功时应该发出 LOGIN，实际命令：${server.commands}",
                server.commands.any { it.startsWith("LOGIN") },
            )
        }
    }
}
