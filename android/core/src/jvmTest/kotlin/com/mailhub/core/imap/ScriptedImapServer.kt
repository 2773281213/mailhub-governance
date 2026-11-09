package com.mailhub.core.imap

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket

/**
 * 手写的脚本化 IMAP 服务器（TLS），用来覆盖 GreenMail 做不到的场景：
 * `ID` 命令的发送时机、没有 UIDPLUS 时的 `delete` 两条分支、读超时、响应途中取消。
 *
 * 它只实现测试需要的那几个命令，并且**按收到的顺序记录每一条命令**，测试直接断言顺序。
 * 全部跑在本地回环地址上，用 [TestTls] 现生成的自签名证书，不连接任何真实服务器。
 *
 * @param capabilities greeting 与 `CAPABILITY` 通告的能力（不含 `IMAP4rev1` 之外的默认值）
 * @param uidValidity `SELECT` 应答里的 UIDVALIDITY
 * @param onCommand 测试自己的应答钩子：`(会话, 标签, 命令原文)`，返回 true 表示已经处理完
 */
internal class ScriptedImapServer(
    private val capabilities: String = "IMAP4rev1",
    private val uidValidity: Long = 5,
    private val onCommand: (Session, String, String) -> Boolean = { _, _, _ -> false },
) : AutoCloseable {

    /** 收到的命令原文（去掉标签），按顺序记录。 */
    val commands: MutableList<String> = CopyOnWriteArrayList()

    /** `AUTHENTICATE` 续行的 SASL 载荷（base64 前原文），按顺序记录。 */
    val saslPayloads: MutableList<String> = CopyOnWriteArrayList()

    /** `UID SEARCH DELETED` 的应答内容（UID 列表，空格分隔）。 */
    @Volatile
    var deletedUids: String = ""

    /** `UID SEARCH SINCE ...` / `UID SEARCH UID ...` 的应答内容（UID 列表，空格分隔）。 */
    @Volatile
    var searchUids: String = ""

    /** true 表示让 `UID FETCH` 一律回 NO（用来测「抓取失败时水位不前进」）。 */
    @Volatile
    var failFetch: Boolean = false

    private val serverSocket: SSLServerSocket = (
        TestTls.serverContext.serverSocketFactory.createServerSocket() as SSLServerSocket
        ).apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
    }

    private val clients: MutableList<Socket> = CopyOnWriteArrayList()

    private val connections: MutableList<Session> = CopyOnWriteArrayList()

    /** 监听端口。 */
    val port: Int get() = serverSocket.localPort

    private val acceptor: Thread = Thread({ acceptLoop() }, "scripted-imap-accept").apply {
        isDaemon = true
        start()
    }

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (_: Exception) {
                return
            }
            clients += socket
            val session = Session(socket)
            connections += session
            Thread({ serve(session) }, "scripted-imap-session").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serve(session: Session) {
        try {
            session.send("* OK [CAPABILITY $capabilities] MailHub scripted test server ready")
            while (true) {
                val line = session.readLine() ?: return
                val tag = line.substringBefore(' ')
                val command = line.substringAfter(' ', "").trim()
                commands += command
                if (onCommand(session, tag, command)) {
                    continue
                }
                if (!handleDefault(session, tag, command)) {
                    session.send("$tag BAD unsupported command")
                }
            }
        } catch (_: Exception) {
            // 连接被测试主动关掉是正常路径
        } finally {
            try {
                session.close()
            } catch (_: Exception) {
                // 忽略
            }
        }
    }

    private fun handleDefault(session: Session, tag: String, command: String): Boolean {
        val upper = command.uppercase()
        when {
            upper == "CAPABILITY" -> {
                session.send("* CAPABILITY $capabilities")
                session.send("$tag OK CAPABILITY completed")
            }

            upper.startsWith("LOGIN") -> session.send("$tag OK LOGIN completed")

            upper.startsWith("AUTHENTICATE") -> {
                session.send("+ ")
                val payload = session.readLine() ?: ""
                saslPayloads += payload
                session.send("$tag OK AUTHENTICATE completed")
            }

            upper.startsWith("ID ") -> {
                session.send("""* ID ("name" "Scripted" "version" "1")""")
                session.send("$tag OK ID completed")
            }

            upper.startsWith("SELECT") -> {
                session.send("* 3 EXISTS")
                session.send("* 0 RECENT")
                session.send("* FLAGS (\\Seen \\Deleted \\Flagged)")
                session.send("* OK [PERMANENTFLAGS (\\Seen \\Deleted \\Flagged \\*)] Flags permitted")
                session.send("* OK [UIDVALIDITY $uidValidity] UIDs valid")
                session.send("* OK [UIDNEXT 100] Predicted next UID")
                session.send("$tag OK [READ-WRITE] SELECT completed")
            }

            upper.startsWith("UID SEARCH DELETED") -> {
                session.send("* SEARCH$deletedUids")
                session.send("$tag OK SEARCH completed")
            }

            upper.startsWith("UID SEARCH SINCE") || upper.startsWith("UID SEARCH UID") -> {
                session.send("* SEARCH$searchUids")
                session.send("$tag OK SEARCH completed")
            }

            upper.startsWith("UID FETCH") -> {
                if (failFetch) {
                    session.send("$tag NO fetch failed")
                } else {
                    session.send("$tag BAD scripted server has no FETCH payload")
                }
            }

            upper.startsWith("UID STORE") -> {
                session.send("* 1 FETCH (FLAGS (\\Deleted))")
                session.send("$tag OK STORE completed")
            }

            upper.startsWith("UID EXPUNGE") || upper == "EXPUNGE" -> {
                session.send("$tag OK EXPUNGE completed")
            }

            upper == "CLOSE" -> session.send("$tag OK CLOSE completed")

            upper == "LOGOUT" -> {
                session.send("* BYE logging out")
                session.send("$tag OK LOGOUT completed")
            }

            upper == "NOOP" -> session.send("$tag OK NOOP completed")

            else -> return false
        }
        return true
    }

    /** 等到收到满足 [predicate] 的命令（最多 [timeoutMillis] 毫秒）。 */
    fun awaitCommand(predicate: (String) -> Boolean, timeoutMillis: Long = 5_000): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (commands.any(predicate)) {
                return true
            }
            Thread.sleep(10)
        }
        return commands.any(predicate)
    }

    override fun close() {
        try {
            serverSocket.close()
        } catch (_: Exception) {
            // 忽略
        }
        for (session in connections) {
            try {
                session.close()
            } catch (_: Exception) {
                // 忽略
            }
        }
        for (client in clients) {
            try {
                client.close()
            } catch (_: Exception) {
                // 忽略
            }
        }
        acceptor.interrupt()
    }

    /** 一条连接：按行读写。命令与应答都是 ASCII，正文的原始字节不经过这里。 */
    inner class Session internal constructor(private val socket: Socket) {

        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        private val writer: Writer =
            BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.ISO_8859_1))

        /** 写一行应答（自动补 CRLF 并 flush）。 */
        fun send(line: String) {
            writer.write(line)
            writer.write("\r\n")
            writer.flush()
        }

        /** 读一行命令；连接关掉时返回 null。 */
        fun readLine(): String? = try {
            reader.readLine()
        } catch (_: Exception) {
            null
        }

        internal fun close() {
            try {
                socket.close()
            } catch (_: Exception) {
                // 忽略
            }
        }
    }
}
