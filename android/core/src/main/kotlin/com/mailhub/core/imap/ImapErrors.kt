package com.mailhub.core.imap

import com.mailhub.core.Clock
import com.mailhub.core.SystemClock
import com.mailhub.core.providers.needsImapId
import com.mailhub.core.text.cpSlice
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FolderClosedException
import jakarta.mail.MessagingException
import jakarta.mail.StoreClosedException
import org.eclipse.angus.mail.util.SocketConnectException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/**
 * 登录 IMAP 需要的账户字段，对应 Python `open_session(account, secret)` 里用到的几个键：
 * `imap_host` / `imap_port` / `email` / `auth_type` / `provider`，以及可选的 `need_id`。
 *
 * `need_id` 在服务器端只是个可能出现在 dict 里的旧字段（数据库 accounts 表里没有这一列），
 * 所以这里也给了默认值 false。
 */
data class ImapAccount(
    val host: String,
    val email: String,
    val port: Int = IMAP_DEFAULT_PORT,
    /** `"oauth"` 或 `"password"`，与数据库 accounts.auth_type 一致。 */
    val authType: String = "password",
    /** 服务商 key（`providers.py` 里的 key），用来判断是否需要发 ID。 */
    val provider: String = "",
    /** 账户上显式要求发送 RFC 2971 ID（对应 `account.get("need_id")`）。 */
    val needId: Boolean = false,
)

/**
 * 按账户配置建立并登录 IMAP 会话，对应 `mail_client.open_session`。
 *
 * [secret] 是解密后的密码 / 授权码，或（OAuth 账户）access token。
 *
 * 网易的要求是认证成功之后、`SELECT` 之前提交 ID；失败时与 Python 一样：先关闭会话，
 * 认证类失败统一换成带 [NETEASE_LOGIN_HELP] 的中文说明再抛。
 *
 * [onCreated] 在**登录之前**回调：调用方在这里登记会话，登录卡住时也能从别的线程 `abort`。
 */
fun openSession(
    account: ImapAccount,
    secret: String,
    clock: Clock = SystemClock,
    onCreated: (ImapSession) -> Unit = {},
): ImapSession = openSession(account, secret, ImapTransport(), clock, onCreated)

/**
 * [openSession] 的可注入版本：只用 `:core` 内部（含同模块测试）调用，
 * 测试靠它传信任自签名证书的 [ImapTransport]。
 */
internal fun openSession(
    account: ImapAccount,
    secret: String,
    transport: ImapTransport,
    clock: Clock = SystemClock,
    onCreated: (ImapSession) -> Unit = {},
): ImapSession {
    val session = ImapSession(account.host, account.port, transport, clock)
    onCreated(session)
    val needsId = needsImapId(account.provider) || account.needId
    try {
        if (account.authType == AUTH_TYPE_OAUTH) {
            session.loginOAuth2(account.email, secret)
        } else {
            session.loginPassword(account.email, secret)
        }
        // 网易官方要求认证成功后提交 ID；不要在 NONAUTH 状态提前发送
        if (needsId) {
            session.sendId()
        }
        return session
    } catch (e: Exception) {
        session.close()
        if (needsId && isAuthFailure(e)) {
            throw ImapException(NETEASE_LOGIN_HELP, e)
        }
        throw e
    }
}

/** 账户表里的 OAuth 标记。 */
const val AUTH_TYPE_OAUTH: String = "oauth"

/**
 * 服务器对命令回了 tagged `BAD`，对应 Python `imaplib.IMAP4.error`
 * （`IMAP4._command_complete` 里 `typ == "BAD"` 直接抛）。
 *
 * 单独一个类型是为了让 [connectionErrorText] 能把它与「项目自己的中文说明」
 * （[ImapException]）和认证失败（`AuthenticationFailedException`）区分开；
 * 文案仍按 Python 的分支走，见 [connectionErrorText] 的说明。
 */
class ImapCommandException(message: String, cause: Exception? = null) : MessagingException(message, cause)

/** 异常链最多回溯多少层（防止异常自身成环）。 */
private const val MAX_CAUSE_DEPTH = 8

/** 中文说明最多多少字，对应 Python 的 `str(exc)[:200]`。 */
private const val MAX_ERROR_CHARS = 200

/**
 * 把连接邮箱时的异常转成给用户看的中文说明，对应 `mail_client.connection_error_text`。
 *
 * 用户看不懂 Java 的原始异常文本，那里面还会暴露服务器平台，所以「测试连接」和同步状态
 * 只用这里的文案。判断顺序与 Python 相同（从具体到一般），脚本化的类型映射：
 * - 项目自己抛的 [ImapException] / [IllegalArgumentException] 原文已经是中文，原样返回
 *   （对应 Python 的 `RuntimeError` / `ValueError`）；
 * - [AuthenticationFailedException] → 「邮箱服务器拒绝登录…」（对应 `imaplib.IMAP4.error`）；
 * - [ImapCommandException] → 同上（对应服务器回 tagged `BAD` 时 imaplib 抛的 `IMAP4.error`，
 *   Python 的 `connection_error_text` 也把这一类归成这句文案）；
 * - 超时、网络/主机不可达（`ENETUNREACH` 等，Python 落进 `OSError`）、连接被拒、
 *   连接中断（`ConnectionResetError` / `ConnectionAbortedError`）、域名解析失败、
 *   TLS 握手失败、其他 IO。
 *
 * Angus 会把底层的 socket 异常包在 `MessagingException` / `MailConnectException` /
 * `SocketConnectException` 里，所以要顺着异常链看，才能落进「超时」「拒绝连接」这类分支，
 * 而不是一律报「无法连接邮箱服务器」。
 */
fun connectionErrorText(exc: Throwable?): String {
    if (exc == null) {
        return ERROR_FALLBACK
    }
    // 项目自己的中文说明原样返回（Python 对 RuntimeError / ValueError 也是这么处理的）
    if (exc is ImapException || exc is IllegalArgumentException) {
        return (exc.message ?: "").cpSlice(0, MAX_ERROR_CHARS)
    }
    val chain = causeChain(exc)
    if (chain.any { it is AuthenticationFailedException }) {
        return "邮箱服务器拒绝登录，请检查授权码或密码"
    }
    // tagged BAD：Python 抛 imaplib.IMAP4.error，落在同一句文案上
    if (chain.any { it is ImapCommandException }) {
        return "邮箱服务器拒绝登录，请检查授权码或密码"
    }
    // Angus 合成的 BYE 响应里带着原始 IO 异常的类名，先按它还原（见
    // [synthesizedConnectionErrorText]），否则超时会被报成「无法连接邮箱服务器」。
    chain.firstNotNullOfOrNull { synthesizedConnectionErrorText(it.message) }?.let { return it }
    if (chain.any { it is SocketTimeoutException || it is TimeoutException }) {
        return "连接邮箱服务器超时"
    }
    // Angus 把读超时/连接异常换成一条合成的 BYE 响应，再包成 FolderClosedException /
    // StoreClosedException，**类型信息到这里已经丢了**，只剩服务器那句文本（"Read timed out"），
    // 所以还要按文字兜一层，否则超时会被报成「无法连接邮箱服务器」。
    if (chain.any { looksLikeTimeout(it.message) }) {
        return "连接邮箱服务器超时"
    }
    // 网络/主机不可达（ENETUNREACH、EHOSTUNREACH）：Python 那边是 OSError，不是「拒绝连接」
    if (chain.any { it is NoRouteToHostException || looksLikeUnreachable(it.message) }) {
        return "无法连接邮箱服务器"
    }
    if (chain.any { it is ConnectException }) {
        return "邮箱服务器拒绝连接，请检查服务器地址和端口"
    }
    // `SocketException` 有好几种：只有 reset / aborted / broken pipe 这类才算「连接被中断」
    // （Python 的 ConnectionResetError / ConnectionAbortedError），其余按 OSError 处理。
    if (chain.any { it is SocketException && looksLikeInterrupted(it.message) }) {
        return "连接被邮箱服务器中断，请稍后重试"
    }
    if (chain.any { it is UnknownHostException }) {
        return "找不到邮箱服务器，请检查服务器地址"
    }
    if (chain.any { it is SSLException }) {
        return "与邮箱服务器建立加密连接失败"
    }
    // SocketException 也是 IOException：走到这里包括「连接被关闭」这类普通 IO 失败
    if (chain.any { it is IOException }) {
        return "无法连接邮箱服务器"
    }
    // 测试连接与同步都会用到，兜底文案不写「测试」
    return ERROR_FALLBACK
}

/** 异常文本看起来是不是超时（JDK 的读超时文本是 "Read timed out"）。 */
internal fun looksLikeTimeout(message: String?): Boolean {
    val text = (message ?: "").lowercase(Locale.ROOT)
    return text.contains("timed out") || text.contains("timeout")
}

/**
 * 异常文本看起来是不是「网络/主机不可达」：`ENETUNREACH` / `EHOSTUNREACH`。
 *
 * Python 那边这是普通的 `OSError`，对应「无法连接邮箱服务器」；**不能**因为它出现在
 * `ConnectException` 的文本里就报成「服务器拒绝连接」。
 */
internal fun looksLikeUnreachable(message: String?): Boolean = matchesTokens(message, UNREACHABLE_TOKENS)

/**
 * 异常文本看起来是不是「连接被对方中断」：Python 的 `ConnectionResetError` /
 * `ConnectionAbortedError`，以及我们自己 [ImapSession.abort] 关 socket 之后的 "Socket closed"。
 */
internal fun looksLikeInterrupted(message: String?): Boolean = matchesTokens(message, INTERRUPTED_TOKENS)

/** 文本里是否含列表中的任一特征词（小写比较）。 */
private fun matchesTokens(message: String?, tokens: List<String>): Boolean {
    val text = (message ?: "").lowercase(Locale.ROOT)
    return tokens.any { text.contains(it) }
}

/** 网络/主机不可达的特征文本。 */
private val UNREACHABLE_TOKENS = listOf(
    "unreachable",
    "no route to host",
    "enetunreach",
    "ehostunreach",
)

/** 连接被中断的特征文本。 */
private val INTERRUPTED_TOKENS = listOf(
    "connection reset",
    "connection aborted",
    "connection closed",
    "broken pipe",
    "socket closed",
    "closed by the peer",
    "econnreset",
    "econnaborted",
    "epipe",
)

/**
 * Angus 在读写失败时用 `Response.byeResponse` 造一个合成的 BYE 响应，**原始的异常类型
 * 在这一步就丢了**，只剩下 `* BYE Jakarta Mail Exception: <原始异常>` 这段文本
 * （`FolderClosedException` / `StoreClosedException` / `MessagingException` 的
 * `message` 都可能是它）。按文本里的原始异常类名还原成 Python 那边会落进的分支；
 * 认不出来时返回 null。
 *
 * 对应关系：Python 的 `select_inbox` / `fetch_*` 让 `socket.timeout`、
 * `ConnectionResetError` 之类的 OSError 直接往上抛，`connection_error_text` 再按类型
 * 选中文案。Kotlin 侧只剩文本，所以这里按文本恢复同样的结论。
 */
internal fun synthesizedConnectionErrorText(message: String?): String? {
    val text = (message ?: "")
    if (!text.contains(BYE_MARKER)) {
        return null
    }
    val lower = text.lowercase(Locale.ROOT)
    return when {
        looksLikeTimeout(lower) -> "连接邮箱服务器超时"
        // 不可达要排在「拒绝连接」前面：Android 上 ENETUNREACH 也可能包在 ConnectException 里
        looksLikeUnreachable(lower) -> "无法连接邮箱服务器"
        lower.contains("connectexception") && !lower.contains("socketconnect") ->
            "邮箱服务器拒绝连接，请检查服务器地址和端口"
        looksLikeInterrupted(lower) -> "连接被邮箱服务器中断，请稍后重试"
        lower.contains("ssl") || lower.contains("handshake") -> "与邮箱服务器建立加密连接失败"
        lower.contains("ioexception") -> "无法连接邮箱服务器"
        else -> null
    }
}

/** `Response.byeResponse` 合成的响应文本前缀。 */
private const val BYE_MARKER = "Jakarta Mail Exception:"

/**
 * 失败是不是「连接层面」的，对应 Python 里 `select_inbox` 直接抛出的 socket 异常。
 *
 * 打开收件箱失败时要区分两种情形：服务器回了 `NO`（Python 抛 `RuntimeError`，中文是
 * 「无法打开收件箱：…」），和连接本身坏了（Python 抛 `socket.timeout` / OSError，
 * 「测试连接」和同步状态要给的是「连接邮箱服务器超时」这类文案）。Angus 把后者报成
 * [FolderClosedException] / [StoreClosedException]，或者在异常链里挂一个 IO 异常；
 * 读超时被 `Response.byeResponse` 换成合成响应后类型全丢，只能看文本（见
 * [synthesizedConnectionErrorText]）。
 */
internal fun isConnectionFailure(exc: Throwable?): Boolean {
    if (exc == null) {
        return false
    }
    for (error in causeChain(exc)) {
        if (error is FolderClosedException || error is StoreClosedException) {
            return true
        }
        if (error is IOException || error is SocketTimeoutException || error is TimeoutException) {
            return true
        }
        if (synthesizedConnectionErrorText(error.message) != null) {
            return true
        }
    }
    return false
}

/** 兜底文案，与 Python 相同。 */
private const val ERROR_FALLBACK = "连接邮箱失败，请查看服务器日志"

/**
 * 异常是否像认证失败（密码、授权码错误），对应 `mail_client._is_auth_failure`。
 *
 * Python 只看异常文本里有没有这几个词；这里额外把 Angus 的
 * [AuthenticationFailedException] 直接算作认证失败（它的文本可能是服务器原话或空串）。
 */
fun isAuthFailure(exc: Throwable?): Boolean {
    if (exc == null) {
        return false
    }
    val chain = causeChain(exc)
    if (chain.any { it is AuthenticationFailedException }) {
        return true
    }
    for (error in chain) {
        val text = (error.message ?: "").lowercase(Locale.ROOT)
        if (AUTH_FAILURE_TOKENS.any { text.contains(it) }) {
            return true
        }
    }
    return false
}

/** 对应 Python `_is_auth_failure` 里的那个元组。 */
private val AUTH_FAILURE_TOKENS = listOf(
    "auth", "login", "password", "credential", "invalid", "denied", "unsafe login",
)

/**
 * 顺着 `cause` / `MessagingException.nextException` / `SocketConnectException.exception`
 * 展开异常链。Angus 把底层 IO 异常挂在这三个位置的不同组合上。
 */
private fun causeChain(exc: Throwable): List<Throwable> {
    val out = ArrayList<Throwable>(MAX_CAUSE_DEPTH)
    var current: Throwable? = exc
    while (current != null && out.size < MAX_CAUSE_DEPTH) {
        if (out.any { it === current }) {
            break
        }
        out += current
        current = when (current) {
            is MessagingException -> current.nextException ?: current.cause
            is SocketConnectException -> current.exception ?: current.cause
            else -> current.cause
        }
    }
    return out
}
