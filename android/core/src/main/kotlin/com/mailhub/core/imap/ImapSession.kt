package com.mailhub.core.imap

import com.mailhub.core.Clock
import com.mailhub.core.SystemClock
import com.mailhub.core.mail.ParsedMessage
import com.mailhub.core.mail.parseMessage
import com.mailhub.core.text.cpSlice
import jakarta.mail.Folder
import jakarta.mail.MessagingException
import jakarta.mail.Session
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import org.eclipse.angus.mail.imap.protocol.BODY
import org.eclipse.angus.mail.imap.protocol.FetchResponse
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol
import org.eclipse.angus.mail.imap.protocol.IMAPResponse
import org.eclipse.angus.mail.imap.protocol.RFC822SIZE
import org.eclipse.angus.mail.imap.protocol.UID
import org.eclipse.angus.mail.iap.Response
import java.net.Socket
import java.net.SocketException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory

// ---------- 与 mail_client.py 一一对应的常量 ----------

/**
 * 服务商的主机、认证方式以及是否需要发送 ID 统一定义在 providers 包；
 * 这份 ID 载荷与 `mail_client.IMAP_ID_PAYLOAD` 逐字相同（字段顺序也一样）。
 */
const val IMAP_ID_PAYLOAD: String =
    """("name" "MailHub" "version" "1.1" "vendor" "MailHub" "support-email" "mailhub@localhost")"""

/** 网易认证失败时给用户的说明，对应 `mail_client.NETEASE_LOGIN_HELP`。 */
const val NETEASE_LOGIN_HELP: String =
    "网易邮箱认证失败：请使用完整邮箱地址登录，确认网页邮箱已开启 IMAP/SMTP，" +
        "并填写客户端授权密码（不是网页登录密码）"

/** 单封超过这个大小就只取头部和正文前 [TRUNCATED_BODY_BYTES]，对应 `LARGE_MESSAGE_BYTES`。 */
const val LARGE_MESSAGE_BYTES: Long = 2L * 1024 * 1024

/** 整封抓取时每批累计字节上限，对应 `BATCH_BYTES`。 */
const val BATCH_BYTES: Long = 8L * 1024 * 1024

/** 整封抓取时每批最多几封，对应 `BATCH_UIDS`。 */
const val BATCH_UIDS: Int = 20

/** 大邮件只保留正文前这么多字节，对应 `TRUNCATED_BODY_BYTES`。 */
const val TRUNCATED_BODY_BYTES: Int = 1024 * 1024

/** `_uid_chunks` 每块最多多少个 UID（对应 Python 默认参数 `size=100`）。 */
internal const val UID_CHUNK_SIZE: Int = 100

/** 只用 imaps（993，隐式 TLS），见 LOCAL_MODE.md 3.2。 */
const val IMAP_PROTOCOL: String = "imaps"

/** imaps 的默认端口。 */
const val IMAP_DEFAULT_PORT: Int = 993

/** 连接超时 15 秒，对应 `mail.imaps.connectiontimeout`。 */
const val IMAP_CONNECT_TIMEOUT_MILLIS: Int = 15_000

/** 读超时 30 秒，对应 `mail.imaps.timeout`。 */
const val IMAP_READ_TIMEOUT_MILLIS: Int = 30_000

/** 收件箱名：Python 的 `conn.select("INBOX")` 也用这个名字。 */
private const val INBOX_NAME = "INBOX"

/**
 * 项目自己抛出的异常，对应 Python 里 `raise RuntimeError("中文说明")`。
 *
 * `connection_error_text` 对这类异常原样返回文案（Python 对 `RuntimeError` / `ValueError`
 * 也是这么处理的），所以 [message] 必须是给用户看的中文，不携带服务器平台信息与凭据。
 */
class ImapException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 会话的传输层设置。[sslSocketFactory] 与 [hostnameVerifier] 只允许 `:core` 内部
 * （含同模块测试）传入，见 [ImapSession] 的构造参数说明。
 */
internal data class ImapTransport(
    /** null 表示用 JDK 默认的 [SSLSocketFactory]（正式路径只会是 null）。 */
    val sslSocketFactory: SSLSocketFactory? = null,
    val connectTimeoutMillis: Int = IMAP_CONNECT_TIMEOUT_MILLIS,
    val readTimeoutMillis: Int = IMAP_READ_TIMEOUT_MILLIS,
    /**
     * 服务器主机名校验器，默认是严格的 [StrictHostnameVerifier]。
     *
     * 正式路径**没有**任何把它换成「放行一切」的开关；只有 `:core` 的测试会注入记录调用
     * 或一律拒绝的实现，用来确认它确实被 Angus 调用、且拒绝时连接在认证之前就失败。
     */
    val hostnameVerifier: HostnameVerifier = StrictHostnameVerifier,
)

/**
 * `mail_client.parse_message` 的结果 + Python `_collect` 补上的三个键。
 *
 * Python 是往 `parse_message` 返回的 dict 里塞 `uid` / `unread` / `body_truncated`；
 * Kotlin 侧 [ParsedMessage] 是 L5 的不可变类型，所以用这个包装类承载同样的信息。
 */
data class FetchedMessage(
    /** 服务器上的 UID（无符号 32 位，用 Long 承载）。 */
    val uid: Long,
    /** 1 表示未读、0 表示已读，对应 `0 if "\\Seen" in flags else 1`。 */
    val unread: Int,
    /** 1 表示只取到了头部与正文前 1MB，对应 `body_truncated`。 */
    val bodyTruncated: Int,
    val parsed: ParsedMessage,
)

/** [ImapSession.fetchMessages] 的返回值：解析好的邮件与被判定为失败的 UID。 */
data class FetchResult(
    val messages: List<FetchedMessage>,
    /** 抓取失败（命令异常、服务器返回非 OK）的 UID；调用方不能把水位越过它们。 */
    val failed: Set<Long>,
)

/**
 * IMAP 会话，逐方法对照 `mail_client.ImapSession`。
 *
 * **协议与传输（硬性要求，见 LOCAL_MODE.md 3.2）**：只用 `imaps` + 隐式 TLS，
 * `mail.imaps.ssl.checkserveridentity=true`，连接超时 15 秒、读超时 30 秒。
 * 这里**没有任何**关闭证书校验的开关：没有 `mail.imaps.ssl.trust=*`，也没有
 * `mail.imaps.ssl.checkserveridentity=false`；测试要连自签名证书时只能通过
 * [ImapTransport.sslSocketFactory] 注入一个信任该证书的工厂。
 *
 * **主机名校验**：`checkserveridentity` 在 Android 上等于没设（Angus 设的是 `LDAPS`
 * 算法，Conscrypt 只认 `HTTPS`），所以一定要另外配置 `mail.imaps.ssl.hostnameverifier`，
 * 默认值就是 [StrictHostnameVerifier]。同时显式设
 * `mail.imaps.socketFactory.fallback=false`：连接失败后不准绕过我们的工厂（[socketFactory]）
 * 再连一次，否则新 socket 不受 [abort] 控制。
 *
 * **构造参数的可见性**：主构造函数是 `internal`，所以 `SSLSocketFactory`、超时这些
 * 「正式代码不该碰」的开关只有 `:core` 内部（含同模块的测试）能传。生产路径统一走
 * [openSession]，它只给 [ImapTransport] 的默认值。
 *
 * **与 Python 的差别**：Python 在 `__init__` 里就建立 TCP 连接（`imaplib.IMAP4_SSL`），
 * 这里把连接放到 [loginPassword] / [loginOAuth2]（Angus 的 `Store.connect` 一次完成连接
 * 与认证）。对调用方的可观察行为不变：构造不会失败，连接与认证失败都由 [openSession]
 * 统一映射成中文说明。
 *
 * **取消**：IMAP 是阻塞 socket。协程被取消时调用 [abort]，它从另一个线程关闭底层
 * socket，阻塞中的读写立刻抛 `SocketException`，同步线程随之退出。
 *
 * @param host IMAP 主机（只走 993 隐式 TLS）
 * @param port IMAP 端口，默认 [IMAP_DEFAULT_PORT]
 * @param transport 传输层设置（测试注入自签名证书的信任工厂与更短的超时）
 * @param clock 当前时间，对应 Python `time.time()`（首次搜索的 30 天窗口、解析失败时的
 *   时间戳都用它）
 */
class ImapSession internal constructor(
    private val host: String,
    private val port: Int = IMAP_DEFAULT_PORT,
    private val transport: ImapTransport = ImapTransport(),
    private val clock: Clock = SystemClock,
) : AutoCloseable {

    /** 建立会话期间创建过的 socket；[abort] 靠它们把阻塞的读写踢醒。 */
    private val sockets = CopyOnWriteArrayList<Socket>()

    @Volatile
    private var aborted = false

    /** 记录 socket 的工厂：既做证书校验的注入点，也是取消时关闭 socket 的抓手。 */
    private val socketFactory = RecordingSocketFactory(
        delegate = transport.sslSocketFactory
            ?: (SSLSocketFactory.getDefault() as SSLSocketFactory),
        created = sockets,
        isAborted = { aborted },
    )

    private var store: IMAPStore? = null
    private var inboxFolder: IMAPFolder? = null

    /** 最近一次 `CAPABILITY` 的结果（大写），对应 Python 的 `self.caps`。 */
    var capabilities: Set<String> = emptySet()
        private set

    /** 大小查询意外带回的正文，留给 [fetchMessages] 复用，对应 `self._probe_parts`。 */
    private val probeParts = LinkedHashMap<Long, ProbePart>()

    // ---------------- 认证 ----------------

    /**
     * 用普通密码 / 授权码登录，对应 `ImapSession.login_password`。
     *
     * Angus 在服务器通告 `AUTH=PLAIN` 时会走 `AUTHENTICATE PLAIN`，否则发 `LOGIN`
     * （Python 的 `imaplib.login` 发的是后者）。这里把机制列表固定成 `PLAIN LOGIN`：
     * 不把密码当 OAuth 令牌发出去（Angus 默认的机制列表里带 XOAUTH2），与 Python 的
     * 行为一致。登录成功后重新读取能力列表，对应 `_refresh_capabilities()`。
     */
    fun loginPassword(user: String, password: String) {
        ensureAlive()
        connect(user, password, mapOf("mail.imaps.auth.mechanisms" to "PLAIN LOGIN"))
        refreshCapabilities()
    }

    /**
     * 用 XOAUTH2 登录（P2 才真正用到），对应 `ImapSession.login_oauth2`。
     *
     * Angus 的 XOAUTH2 把传进来的密码参数当作 access token，构造的 SASL 载荷与 Python
     * 的 `user=<user>\x01auth=Bearer <token>\x01\x01` 相同。
     */
    fun loginOAuth2(user: String, accessToken: String) {
        ensureAlive()
        connect(user, accessToken, mapOf("mail.imaps.auth.mechanisms" to "XOAUTH2"))
        refreshCapabilities()
    }

    /**
     * 认证后提交 RFC 2971 客户端身份，对应 `ImapSession.send_id`。
     *
     * 时机是认证之后、`SELECT` 之前：网易未收到有效 ID 时会拒绝 `SELECT`（Unsafe Login）。
     * 载荷字段与 [IMAP_ID_PAYLOAD] 相同，走 `IMAPStore.id(map)`。
     *
     * **与 Python 的差别**：Angus 在服务器未通告 `ID` 能力时**不发命令**，直接抛
     * `BadCommandException("ID not supported")`；Python 的 `imaplib.xatom("ID", ...)`
     * 是无条件发送。网易的服务器都通告 ID，所以对唯一需要 ID 的服务商行为一致；两者都
     * 会被下面的 catch 换成带 [NETEASE_LOGIN_HELP] 的中文说明。
     */
    fun sendId() {
        ensureAlive()
        try {
            storeOrThrow().id(IMAP_ID)?.let { /* 服务器的 ID 响应，Python 里也丢弃 */ }
        } catch (e: Exception) {
            throw ImapException("网易邮箱客户端身份声明失败；$NETEASE_LOGIN_HELP", e)
        }
    }

    /** 登录后重新读取能力列表，对应 `_refresh_capabilities`（Gmail 等认证前不通告 UIDPLUS）。 */
    fun refreshCapabilities() {
        ensureAlive()
        val fresh = try {
            withStoreProtocol { protocol ->
                protocol.capability()
                protocol.capabilities
            }
        } catch (_: Exception) {
            // Python：`capability()` 返回非 OK 时保留旧的能力列表
            return
        }
        val out = LinkedHashSet<String>(fresh.size * 2)
        for (key in fresh.keys) {
            // Angus 的能力表是 token -> token（value 没有额外含义），键就是 Python 里的那个标记
            out += key.uppercase(Locale.ROOT)
        }
        capabilities = out
    }

    // ---------------- 收件箱 ----------------

    /**
     * 打开收件箱并返回 UIDVALIDITY，对应 `ImapSession.select_inbox`。
     *
     * 打开失败时给出与 Python 相同的中文说明：网易的 `Unsafe Login` 带上
     * [NETEASE_LOGIN_HELP]，其余是「无法打开收件箱：<服务器说明>」。
     *
     * **服务器不返回 UIDVALIDITY 时返回 0**（对应 Python 的
     * `int(uv[0])` 失败 → 0）：Angus 的 `getUIDValidity()` 这时给的是 -1，直接往上抛会让
     * 调用方误判「UIDVALIDITY 变了」而重建整个邮箱的 UID。
     */
    fun selectInbox(): Long {
        ensureAlive()
        val folder = inbox()
        if (!folder.isOpen) {
            try {
                folder.open(Folder.READ_WRITE)
            } catch (e: MessagingException) {
                // 连接层的失败（读超时、连接被断开）在 Python 那边是 socket 异常直接往上抛，
                // 不能被下面的「无法打开收件箱」盖掉，否则用户看到的是收件箱打不开，
                // 而不是「连接邮箱服务器超时」。原样抛给 connection_error_text 判断。
                if (isConnectionFailure(e)) {
                    throw e
                }
                val detail = e.message ?: ""
                if (detail.lowercase(Locale.ROOT).contains("unsafe login")) {
                    throw ImapException("网易邮箱返回 Unsafe Login；$NETEASE_LOGIN_HELP", e)
                }
                throw ImapException(
                    "无法打开收件箱" + (if (detail.isNotEmpty()) "：${detail.cpSlice(0, 120)}" else ""),
                    e,
                )
            }
        }
        return try {
            val uidValidity = folder.uidValidity
            // Angus 在服务器没返回 UIDVALIDITY 时给 -1（见 IMAPFolder 的字段初值），
            // 与 Python 归一化成 0 的行为对齐
            if (uidValidity < 0L) 0L else uidValidity
        } catch (_: MessagingException) {
            0L
        }
    }

    /**
     * 搜索待拉取的 UID，对应 `ImapSession.search_uids`。
     *
     * 增量时取 `UID <last_uid + 1>:*`；首次（`last_uid == 0`）取最近 [sinceDays] 天、
     * 最多 [firstLimit] 封（服务器返回升序，超限时保留**最新**的那一批）。
     *
     * **失败语义与 Python 一致**：只有 tagged `NO`（以及没有 tagged 响应）按空列表处理；
     * tagged `BAD` 与 `BYE`（含读超时、连接断开）都抛异常，[connectionErrorText] 才能给出
     * 「连接邮箱服务器超时」这类正确文案。返回空列表意味着「确实没有新邮件」，
     * 调用方据此更新 `last_sync`、清掉 `last_error`。
     */
    fun searchUids(
        lastUid: Long,
        sinceDays: Int = DEFAULT_SINCE_DAYS,
        firstLimit: Int = DEFAULT_FIRST_LIMIT,
    ): List<Long> {
        ensureAlive()
        val command = if (lastUid > 0) {
            "UID SEARCH UID ${lastUid + 1}:*"
        } else {
            "UID SEARCH SINCE ${imapDate(clock.now() - sinceDays * 86400.0)}"
        }
        val responses = withInboxProtocol { protocol -> protocol.command(command, null) }
        if (!commandSucceeded(responses)) {
            return emptyList()
        }
        val found = searchUidsOf(responses).filter { it > lastUid }
        if (lastUid == 0L && found.size > firstLimit) {
            return found.subList(found.size - firstLimit, found.size)
        }
        return found
    }

    /**
     * 查询邮件大小（UID -> 字节数），对应 `ImapSession.fetch_sizes`。
     *
     * 只取元数据、不下载正文。服务器没返回大小、响应失败或命令抛异常的 UID 不会出现在
     * 结果里，调用方应当把它们当大邮件处理。
     */
    fun fetchSizes(uids: List<Long>): Map<Long, Long> {
        ensureAlive()
        val sizes = LinkedHashMap<Long, Long>()
        probeParts.clear()
        for (uidset in uidChunks(uids)) {
            val responses = try {
                withInboxProtocol { protocol -> protocol.command("UID FETCH $uidset (RFC822.SIZE)", null) }
            } catch (_: Exception) {
                continue
            }
            if (!lastResponseOk(responses)) {
                continue
            }
            for (response in fetchResponses(responses)) {
                val uid = uidOf(response) ?: continue
                val size = response.getItem(RFC822SIZE::class.java)?.size
                if (size != null) {
                    sizes[uid] = size
                    continue
                }
                // 非标准行为：大小查询里夹带了正文，留给 fetch_messages 复用
                val payload = payloadOf(response)
                if (payload != null) {
                    probeParts[uid] = ProbePart(flagsOf(response), payload, truncated = false)
                }
            }
        }
        return sizes
    }

    /**
     * 查询标记集合（UID -> {\Seen, \Flagged, ...}），对应 `ImapSession.fetch_flags`。
     *
     * 服务器没有返回、响应失败或命令抛异常的 UID 不会出现在结果里，调用方应按
     * 「状态未知」处理，不要据此改本地状态。
     */
    fun fetchFlags(uids: List<Long>): Map<Long, Set<String>> {
        ensureAlive()
        val flags = LinkedHashMap<Long, Set<String>>()
        for (uidset in uidChunks(uids)) {
            val responses = try {
                withInboxProtocol { protocol -> protocol.command("UID FETCH $uidset (FLAGS)", null) }
            } catch (_: Exception) {
                continue
            }
            if (!lastResponseOk(responses)) {
                continue
            }
            for (response in fetchResponses(responses)) {
                val uid = uidOf(response) ?: continue
                flags[uid] = flagsOf(response)
            }
        }
        return flags
    }

    /**
     * 按 UID 抓取邮件，对应 `ImapSession.fetch_messages`：先查大小再决定怎么抓。
     *
     * - 不超过 [LARGE_MESSAGE_BYTES] 的按累计字节数分批（每批不超过 [BATCH_BYTES] 且
     *   不超过 [BATCH_UIDS] 封），用 `BODY.PEEK[]` 整封抓取，不改变已读状态；
     * - 超过 [LARGE_MESSAGE_BYTES] 的逐封抓取，只取头部和正文前 [TRUNCATED_BODY_BYTES]；
     *   查不到大小的也按大邮件处理——宁可截断，也不整封下载。
     *
     * 批次成功但缺少某个 UID 时视为该邮件已被服务器删除，不算失败；命令异常或响应非 OK
     * 的整批记为失败，调用方只能把水位推进到第一个失败 UID 之前。
     */
    fun fetchMessages(uids: List<Long>): FetchResult {
        ensureAlive()
        val out = ArrayList<FetchedMessage>(uids.size)
        val failed = LinkedHashSet<Long>()
        val wanted = uids.map { it }
        if (wanted.isEmpty()) {
            return FetchResult(out, failed)
        }

        // 先查大小：这一步同时把残留的「大小查询夹带正文」翻出来
        val sizes = LinkedHashMap(fetchSizes(wanted))
        val leftovers = LinkedHashMap(probeParts)
        probeParts.clear()

        val pending = ArrayList<Long>(wanted.size)
        for (uid in wanted) {
            val probe = leftovers[uid]
            if (probe == null) {
                pending += uid
                continue
            }
            collect(out, uid, probe.flags, probe.payload, truncated = probe.truncated)
        }

        val small = ArrayList<Long>(pending.size)
        val large = ArrayList<Long>()
        for (uid in pending) {
            val size = sizes[uid]
            if (size != null && size <= LARGE_MESSAGE_BYTES) small += uid else large += uid
        }

        var batch = ArrayList<Long>(BATCH_UIDS)
        var batchBytes = 0L
        for (uid in small) {
            val size = sizes[uid] ?: 0L
            if (batch.isNotEmpty() && (batch.size >= BATCH_UIDS || batchBytes + size > BATCH_BYTES)) {
                fetchBatch(out, failed, batch)
                batch = ArrayList(BATCH_UIDS)
                batchBytes = 0L
            }
            batch += uid
            batchBytes += size
        }
        if (batch.isNotEmpty()) {
            fetchBatch(out, failed, batch)
        }

        for (uid in large) {
            fetchTruncated(out, failed, listOf(uid))
        }
        return FetchResult(out, failed)
    }

    /**
     * 整封抓取一小批邮件，对应 `_fetch_batch`。
     *
     * 响应里有 UID 就入库，即使服务器把 `BODY[]` 回成 `NIL`：Python 的 `_iter_parts` 这时
     * 拿到的是空正文字节，照样 `_collect` 一条空记录（`parse_message(b"")`）。静默跳过会让
     * 水位越过这封邮件，之后再也取不到它。
     */
    private fun fetchBatch(out: MutableList<FetchedMessage>, failed: MutableSet<Long>, batch: List<Long>) {
        val uidset = batch.joinToString(",")
        val responses = try {
            withInboxProtocol { protocol -> protocol.command("UID FETCH $uidset (UID FLAGS BODY.PEEK[])", null) }
        } catch (_: Exception) {
            failed += batch
            return
        }
        if (!lastResponseOk(responses)) {
            failed += batch
            return
        }
        for (response in fetchResponses(responses)) {
            val uid = uidOf(response) ?: continue
            // BODY[] NIL：Python 拿到空字节并入库一条空记录，这里同样处理
            val payload = payloadOf(response) ?: ByteArray(0)
            collect(out, uid, flagsOf(response), payload, truncated = false)
        }
    }

    /**
     * 只取头部和正文前 1MB，对应 `_fetch_truncated`（大附件邮件不整封下载）。
     *
     * `BODY[...]` 回成 `NIL` 时与 [_fetch_batch 的说明][fetchBatch] 相同：照样入库空记录。
     */
    private fun fetchTruncated(out: MutableList<FetchedMessage>, failed: MutableSet<Long>, batch: List<Long>) {
        val uidset = batch.joinToString(",")
        val query = "(UID FLAGS BODY.PEEK[HEADER] BODY.PEEK[TEXT]<0.$TRUNCATED_BODY_BYTES>)"
        val responses = try {
            withInboxProtocol { protocol -> protocol.command("UID FETCH $uidset $query", null) }
        } catch (_: Exception) {
            failed += batch
            return
        }
        if (!lastResponseOk(responses)) {
            failed += batch
            return
        }
        for (response in fetchResponses(responses)) {
            val uid = uidOf(response) ?: continue
            val payload = payloadOf(response) ?: ByteArray(0)
            collect(out, uid, flagsOf(response), payload, truncated = true)
        }
    }

    /** 解析一封邮件的原始字节，补上 UID、未读标记与截断标记，对应 `_collect`。 */
    private fun collect(
        out: MutableList<FetchedMessage>,
        uid: Long,
        flags: Set<String>,
        payload: ByteArray,
        truncated: Boolean,
    ) {
        // parseMessage 自己捕获解析异常并返回 unparsableMessage，调用方不用再包
        val parsed = parseMessage(payload, clock)
        // 标记名按 RFC 3501 大小写不敏感；Python 那边是 `"\\Seen" in flags` 的子串比较，
        // 服务器给 `\SEEN` 时会判成未读。这里按 RFC 处理（差异写进任务总结）。
        val seen = flags.any { it.equals("\\Seen", ignoreCase = true) }
        out += FetchedMessage(
            uid = uid,
            unread = if (seen) 0 else 1,
            bodyTruncated = if (truncated) 1 else 0,
            parsed = parsed,
        )
    }

    // ---------------- 写操作 ----------------

    /**
     * 设置邮件标记，对应 `ImapSession.store_flags`。
     *
     * @param flag IMAP 标记原文，例如 `\Seen`、`\Deleted`
     * @param add true 是 `+FLAGS`，false 是 `-FLAGS`
     */
    fun storeFlags(uids: List<Long>, flag: String, add: Boolean = true) {
        if (uids.isEmpty()) {
            return
        }
        ensureAlive()
        val op = if (add) "+FLAGS" else "-FLAGS"
        for (uidset in uidChunks(uids)) {
            val responses = withInboxProtocol { protocol ->
                protocol.command("UID STORE $uidset $op ($flag)", null)
            }
            // BAD / BYE 抛异常（对应 imaplib 的 IMAP4.error / abort），NO 才走下面这条中文说明
            if (!commandSucceeded(responses)) {
                val detail = responseText(responses)
                throw ImapException(
                    "设置邮件标记失败" + (if (detail.isNotEmpty()) "：${detail.cpSlice(0, 120)}" else ""),
                )
            }
        }
    }

    /** 标记已读 / 未读，对应 `ImapSession.mark_read`。 */
    fun markRead(uids: List<Long>, read: Boolean = true) {
        storeFlags(uids, "\\Seen", add = read)
    }

    /**
     * 永久删除指定 UID，对应 `ImapSession.delete`，只清除 MailHub 自己标记的邮件。
     *
     * 返回 `"expunged"`（已从服务器清除）或 `"flagged"`（已打上 `\Deleted`，但收件箱里
     * 还有其他客户端标记待删的邮件，为避免连带清除而暂不 `EXPUNGE`；大多数客户端会隐藏
     * 这些邮件，并在下次清除时一并移除）。
     *
     * 有 `UIDPLUS` 时用 RFC 4315 的 `UID EXPUNGE` 只清列出的 UID；没有时普通 `EXPUNGE`
     * 会清掉收件箱里所有带 `\Deleted` 的邮件，所以只有当待删集合恰好都是本次标记的邮件时
     * 才执行。
     */
    fun delete(uids: List<Long>): String {
        val targets = uids.filter { it > 0 }.toSortedSet().toList()
        if (targets.isEmpty()) {
            return DELETED_EXPUNGED
        }
        ensureAlive()
        storeFlags(targets, "\\Deleted", add = true)

        if ("UIDPLUS" in capabilities) {
            for (uidset in uidChunks(targets)) {
                val responses = withInboxProtocol { protocol ->
                    protocol.command("UID EXPUNGE $uidset", null)
                }
                if (!commandSucceeded(responses)) {
                    val detail = responseText(responses)
                    throw ImapException(
                        "服务器拒绝清除邮件" + (if (detail.isNotEmpty()) "：${detail.cpSlice(0, 120)}" else ""),
                    )
                }
            }
            return DELETED_EXPUNGED
        }

        // 没有 UIDPLUS：先看收件箱里所有 \Deleted 是不是都在本次目标里
        val searchResponses = withInboxProtocol { protocol -> protocol.command("UID SEARCH DELETED", null) }
        // tagged NO / 没有 tagged 响应时按 Python 返回 flagged；BAD 与 BYE 直接抛异常。
        // 绝不能把 BAD / BYE 当成「收件箱里没有别人的 \Deleted」而去做 EXPUNGE。
        if (!commandSucceeded(searchResponses)) {
            return DELETED_FLAGGED
        }
        // UID 解析遇到非数字 token 也直接抛异常：宁可不 EXPUNGE，也不能漏算别人标记的邮件
        val flagged = searchUidsOf(searchResponses).toSet()
        if (!targets.toSet().containsAll(flagged)) {
            return DELETED_FLAGGED
        }
        val expungeResponses = withInboxProtocol { protocol -> protocol.command("EXPUNGE", null) }
        if (!commandSucceeded(expungeResponses)) {
            val detail = responseText(expungeResponses)
            throw ImapException(
                "服务器拒绝清除邮件" + (if (detail.isNotEmpty()) "：${detail.cpSlice(0, 120)}" else ""),
            )
        }
        return DELETED_EXPUNGED
    }

    // ---------------- 关 / 取消 ----------------

    /**
     * 关闭会话，对应 `ImapSession.close`。
     *
     * 两步都吞掉异常（Python 同样如此）。**与 Python 的差别**：Python 的 `conn.close()`
     * 发的是 IMAP `CLOSE`，按 RFC 3501 会**顺带 EXPUNGE** 收件箱里所有 `\Deleted` 邮件；
     * 这里用 `folder.close(false)`（不 EXPUNGE），所以 [delete] 返回 `"flagged"` 时那些
     * 邮件会保持 `\Deleted` 状态留给其他客户端，符合 [delete] 文档里承诺的安全语义。
     *
     * 实现 [AutoCloseable] 只是为了让调用方能写 `session.use { ... }`，
     * 与 Python 的 `try/finally: sess.close()` 等价。
     */
    override fun close() {
        val folder = inboxFolder
        if (folder != null) {
            try {
                if (folder.isOpen) {
                    folder.close(false)
                }
            } catch (_: Exception) {
                // 与 Python 一致：关闭阶段的异常不往上抛
            }
        }
        store?.let { current ->
            try {
                current.close()
            } catch (_: Exception) {
                // 同上
            }
        }
        store = null
        inboxFolder = null
        closeSockets()
    }

    /**
     * 从另一个线程强制中断会话：关闭底层 socket，让阻塞中的读写立刻抛异常。
     *
     * 协程调度器不会打断阻塞在 socket 读上的线程，所以取消时调用这个方法；
     * 之后的所有操作都会抛 [ImapException]。
     */
    fun abort() {
        aborted = true
        closeSockets()
    }

    /** 已中断的会话不会再做任何网络操作。 */
    fun isAborted(): Boolean = aborted

    private fun closeSockets() {
        for (socket in sockets) {
            try {
                socket.close()
            } catch (_: Exception) {
                // 关闭失败没有补救手段
            }
        }
    }

    // ---------------- 内部：连接与命令 ----------------

    private fun connect(user: String, password: String, extra: Map<String, String>) {
        if (aborted) {
            throw ImapException("IMAP 会话已取消")
        }
        val properties = Properties().apply {
            put("mail.store.protocol", IMAP_PROTOCOL)
            put("mail.imaps.host", host)
            put("mail.imaps.port", port.toString())
            put("mail.imaps.ssl.enable", "true")
            // 硬性要求：校验服务器身份，且不提供任何关闭校验的开关
            put("mail.imaps.ssl.checkserveridentity", "true")
            // checkserveridentity 在 Android 上等于没设（Angus 设的是 LDAPS 算法，Conscrypt
            // 只认 HTTPS），真正干活的是这个校验器：没有开关能把它换成放行一切。
            put("mail.imaps.ssl.hostnameverifier", transport.hostnameVerifier)
            // 失败后不准绕过下面这个工厂回退到普通 socket：新 socket 不受 abort 控制
            put("mail.imaps.socketFactory.fallback", "false")
            put("mail.imaps.connectiontimeout", transport.connectTimeoutMillis.toString())
            put("mail.imaps.timeout", transport.readTimeoutMillis.toString())
            // 只有一个账户一次同步，不需要连接池
            put("mail.imaps.connectionpoolsize", "1")
            for ((key, value) in extra) {
                put(key, value)
            }
            // 注入的 socket 工厂：正式路径是 JDK 默认实现，测试用来信任自签名证书
            put("mail.imaps.ssl.socketFactory", socketFactory)
        }
        val session = Session.getInstance(properties)
        val created = session.getStore(IMAP_PROTOCOL) as IMAPStore
        try {
            created.connect(host, port, user, password)
        } catch (e: Exception) {
            try {
                created.close()
            } catch (_: Exception) {
                // 连接失败时 Store 可能还没连上，close() 的异常没有意义
            }
            closeSockets()
            throw e
        }
        store = created
    }

    private fun storeOrThrow(): IMAPStore =
        store ?: throw ImapException("IMAP 会话尚未登录")

    /** 收件箱对象（未打开时也可以取，命令内部会判断状态）。 */
    private fun inbox(): IMAPFolder {
        inboxFolder?.let { return it }
        val folder = storeOrThrow().getFolder(INBOX_NAME) as IMAPFolder
        inboxFolder = folder
        return folder
    }

    /**
     * 在**已 SELECT 的收件箱连接**上执行命令（UID 系列命令都需要 SELECTED 状态）。
     *
     * `IMAPFolder.doCommand` 是 Angus 提供的受支持逃生口：它把 `IMAPProtocol` 交给回调，
     * 我们就能发出与 Python `imaplib.uid(...)` 一模一样的命令文本，并按 Angus 解好的
     * 数据结构读取响应。
     */
    private fun <T : Any> withInboxProtocol(block: (IMAPProtocol) -> T): T {
        val folder = inbox()
        if (!folder.isOpen) {
            throw ImapException("IMAP 会话尚未打开收件箱")
        }
        @Suppress("UNCHECKED_CAST")
        return folder.doCommand { protocol -> block(protocol) } as T
    }

    /** 在登录状态（未 SELECT）的 store 连接上执行命令，例如 `CAPABILITY`。 */
    private fun <T : Any> withStoreProtocol(block: (IMAPProtocol) -> T): T {
        val folder = storeOrThrow().defaultFolder as IMAPFolder
        @Suppress("UNCHECKED_CAST")
        return folder.doCommand { protocol -> block(protocol) } as T
    }

    private fun ensureAlive() {
        if (aborted) {
            throw ImapException("IMAP 会话已取消")
        }
    }

    /** 大小查询夹带回来的正文，对应 `_probe_parts` 里的 `(head, payload)`。 */
    private class ProbePart(val flags: Set<String>, val payload: ByteArray, val truncated: Boolean)

    private companion object {
        /** `search_uids` 的默认窗口：最近 30 天。 */
        const val DEFAULT_SINCE_DAYS = 30

        /** 首次同步最多取 200 封。 */
        const val DEFAULT_FIRST_LIMIT = 200

        /** [delete] 的两种结果，与 Python 返回的字符串相同。 */
        const val DELETED_EXPUNGED = "expunged"
        const val DELETED_FLAGGED = "flagged"

        /** `IMAPStore.id(mapOf(...))` 的载荷，字段与 [IMAP_ID_PAYLOAD] 相同。 */
        val IMAP_ID: Map<String, String> = linkedMapOf(
            "name" to "MailHub",
            "version" to "1.1",
            "vendor" to "MailHub",
            "support-email" to "mailhub@localhost",
        )

        /**
         * 把时间格式化成 IMAP 的 `dd-MMM-yyyy`（UTC、英文月份），
         * 对应 Python 的 `time.strftime("%d-%b-%Y", time.gmtime(...))`。
         */
        fun imapDate(epochSeconds: Double): String {
            val format = SimpleDateFormat("dd-MMM-yyyy", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            return format.format(Date((epochSeconds * 1000.0).toLong()))
        }
    }
}

// ---------------- 响应解析辅助 ----------------

/** 按 [UID_CHUNK_SIZE] 把 UID 列表切成逗号分隔的 UID 集合，对应 `_uid_chunks`。 */
internal fun uidChunks(uids: List<Long>, size: Int = UID_CHUNK_SIZE): List<String> =
    uids.chunked(size).map { chunk -> chunk.joinToString(",") }

/** 响应数组的最后一条是带标签的完成响应：`OK` 才算成功。 */
internal fun lastResponseOk(responses: Array<Response>): Boolean =
    responses.isNotEmpty() && responses[responses.size - 1].isOK

/**
 * 命令成功吗？语义对齐 imaplib 的 `_command_complete`：
 * - untagged `BYE`（服务器关连接；读超时、连接断开也被 Angus 换成合成的 `* BYE ...`）
 *   对应 imaplib 的 `IMAP4.abort`，直接抛 [SocketException]：它的文本里带着原始异常
 *   （`* BYE Jakarta Mail Exception: java.net.SocketTimeoutException: ...`），
 *   [connectionErrorText] 才能还原成「连接邮箱服务器超时」这类文案；
 * - tagged `BAD` 对应 imaplib 的 `IMAP4.error`，抛 [ImapCommandException]；
 * - tagged `NO` 与没有 tagged 响应返回 false，由调用方按 Python 的 `typ != "OK"` 分支处理。
 */
internal fun commandSucceeded(responses: Array<Response>): Boolean {
    val bye = responses.firstOrNull { it.isBYE }
    if (bye != null) {
        throw SocketException("connection closed: " + (bye.rest.ifEmpty { "BYE" }))
    }
    val last = responses.lastOrNull() ?: return false
    if (!last.isTagged) {
        return false
    }
    if (last.isOK) {
        return true
    }
    if (last.isBAD) {
        throw ImapCommandException("IMAP command error: " + responseText(responses))
    }
    return false
}

/** 取出响应里的全部 FETCH 响应（Angus 已按条目解析好）。 */
internal fun fetchResponses(responses: Array<Response>): List<FetchResponse> =
    responses.filterIsInstance<FetchResponse>()

/**
 * 把 `* SEARCH 1 2 3` 解析成 UID 列表，对应 Python `int(u) for u in data[0].split()`。
 *
 * **遇到非数字 token 直接抛异常**，与 Python 的 `int()` 一致：调用方（尤其是 [ImapSession.delete]
 * 的保守分支）宁可放弃操作，也不能把解析不了的结果当成「没有其它邮件」。
 */
internal fun searchUidsOf(responses: Array<Response>): List<Long> {
    val out = ArrayList<Long>()
    for (response in responses) {
        if (response !is IMAPResponse || !response.isUnTagged || !response.keyEquals("SEARCH")) {
            continue
        }
        for (token in response.rest.split(' ')) {
            if (token.isEmpty()) {
                continue
            }
            val uid = token.toLongOrNull()
                ?: throw ImapException("无法解析服务器返回的搜索结果：${token.cpSlice(0, 40)}")
            out += uid
        }
    }
    return out
}

/** 把 IMAP 响应压平成可展示文本，不包含用户凭据，对应 `_imap_response_text`。 */
internal fun responseText(responses: Array<Response>): String {
    val parts = ArrayList<String>(responses.size)
    for (response in responses) {
        if (response.isTagged) {
            val text = response.rest
            if (text.isNotEmpty()) {
                parts += text
            }
        }
    }
    return parts.joinToString(" ").trim()
}

/** FETCH 响应里的 UID；没有（或为 0）时返回 null，对应 `_uid_of` 返回 0 的分支。 */
internal fun uidOf(response: FetchResponse): Long? {
    val uid = response.getItem(UID::class.java)?.uid ?: return null
    return if (uid > 0) uid else null
}

/**
 * FETCH 响应里的标记集合，对应 `_flags_of` + `set(...split())`：**按服务器原文**取
 * `FLAGS (...)` 里的 token。
 *
 * **为什么不用 Angus 解析好的 `FLAGS` 条目**：Angus Mail 2.0.5 的 `FLAGS` 构造函数只看
 * 反斜杠后的**第二个字符**（`\S` → SEEN、`\F` → FLAGGED、`\R` → RECENT…），于是
 * `\Spam` 会被当成 `\Seen`、`\Forwarded` 会被当成 `\Flagged`——这封邮件就被误判成已读。
 * 原文里的 token 才有这个信息，所以这里按 Python 的方式从响应文本里取：
 * `FLAGS (` 到最近的 `)` 之间的内容按空白切分。
 *
 * 与 Python 的细节一致：只在第一个 literal（`{...}`）之前找 `FLAGS`（Python 拿到的
 * `head` 本来就在 literal 之前），`FLAGS` 按原样大小写匹配。
 */
internal fun flagsOf(response: FetchResponse): Set<String> {
    // Response.toString() 是整条响应的原文（index 早就被 Angus 的解析消费掉了，getRest() 拿不到）
    val raw = response.toString()
    val brace = raw.indexOf('{')
    val head = if (brace >= 0) raw.substring(0, brace) else raw
    val marker = "FLAGS ("
    val start = head.indexOf(marker)
    if (start < 0) {
        return emptySet()
    }
    val rest = head.substring(start + marker.length)
    val end = rest.indexOf(')')
    if (end < 0) {
        return emptySet()
    }
    val out = LinkedHashSet<String>(8)
    for (token in rest.substring(0, end).split(' ')) {
        if (token.isNotEmpty()) {
            out += token
        }
    }
    return out
}

/**
 * 把 FETCH 响应里的 BODY[...] 字节按响应顺序拼起来，对应 `_iter_parts` 的「同一 UID 的
 * 多个元组依次拼接」。同时取 `BODY.PEEK[HEADER]` 与 `BODY.PEEK[TEXT]<0.N>` 时就是
 * 「头部 + 截断的正文」。没有 BODY 条目、或者服务器把 BODY 回成 `NIL` 时返回 null。
 *
 * 调用方据此区分两种情形：`NIL` 与「压根没有 BODY 条目」在 Python 里都是「空正文字节」，
 * 要入库一条空记录（见 [ImapSession.fetchMessages] 的两个抓取函数）；而 `fetch_sizes`
 * 里夹带的正文只在真有字节时才复用。
 *
 * **与 Python 的差别（必要）**：RFC 3501 不规定同一封邮件里多个 `BODY[...]` 条目的先后，
 * GreenMail 就会随机把 `BODY[TEXT]` 排在 `BODY[HEADER]` 前面。Python 直接按收到的顺序拼接，
 * 拼出来的是「正文 + 头部」，`parse_message` 只会得到一封没有正文的邮件（正文为空）。
 * 这里先把 `HEADER` 段排到前面（[BODY.isHeader]），其余保持收到的顺序：结果永远是
 * 「头部 + 正文」，与 `_fetch_truncated` 的意图一致；正常服务器本来就按请求顺序回，
 * 这一排序对它们没有影响。
 */
internal fun payloadOf(response: FetchResponse): ByteArray? {
    val bodies = ArrayList<BODY>(2)
    for (index in 0 until response.itemCount) {
        val item = response.getItem(index)
        if (item is BODY) {
            bodies += item
        }
    }
    if (bodies.isEmpty()) {
        return null
    }
    if (bodies.size == 1) {
        return bodies[0].byteArray?.newBytes
    }
    // sortedBy 是稳定排序：头部在前，其余保持原顺序
    val ordered = bodies.sortedBy { if (it.isHeader()) 0 else 1 }
    var total = 0
    val chunks = ArrayList<ByteArray>(ordered.size)
    for (body in ordered) {
        val bytes = body.byteArray?.newBytes ?: continue
        chunks += bytes
        total += bytes.size
    }
    val out = ByteArray(total)
    var offset = 0
    for (chunk in chunks) {
        System.arraycopy(chunk, 0, out, offset, chunk.size)
        offset += chunk.size
    }
    return out
}

/**
 * 记录创建过的 socket，并在需要时关闭它们。
 *
 * 正式路径只把它当成「透传的默认 [SSLSocketFactory]」；测试用它信任自签名证书，
 * [ImapSession.abort] 用它把阻塞中的读写踢醒。
 */
private class RecordingSocketFactory(
    private val delegate: SSLSocketFactory,
    private val created: MutableList<Socket>,
    private val isAborted: () -> Boolean,
) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(host: String, port: Int): Socket =
        record(delegate.createSocket(host, port))

    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket =
        record(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: java.net.InetAddress, port: Int): Socket =
        record(delegate.createSocket(host, port))

    override fun createSocket(
        address: java.net.InetAddress,
        port: Int,
        localAddress: java.net.InetAddress,
        localPort: Int,
    ): Socket = record(delegate.createSocket(address, port, localAddress, localPort))

    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        record(delegate.createSocket(socket, host, port, autoClose))

    override fun createSocket(): Socket = record(delegate.createSocket())

    private fun record(socket: Socket): Socket {
        created += socket
        if (isAborted()) {
            try {
                socket.close()
            } catch (_: Exception) {
                // 已经取消：关闭失败也无关紧要
            }
        }
        return socket
    }
}
