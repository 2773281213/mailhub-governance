package com.mailhub.core.imap

import java.net.IDN
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession

/**
 * 严格的主机名校验，语义对齐 OkHttp 的 `OkHostnameVerifier`，见 LOCAL_MODE.md 3.2。
 *
 * **为什么必须自己实现**（已核对 Angus Mail 2.0.5 的字节码）：
 * - `mail.imaps.ssl.checkserveridentity=true` 只做一件事——给 `SSLParameters` 设
 *   `setEndpointIdentificationAlgorithm("LDAPS")`；
 * - Android 的 Conscrypt 只认 `HTTPS` 这个算法，`LDAPS` 等于没设；
 * - 没配 `mail.imaps.ssl.hostnameverifier` 时 Angus 的 `getHostnameVerifier` 返回 null，
 *   `checkServerIdentity` 看到 null 就直接返回。
 *
 * 于是手机上原本根本不校验主机名：任何持有正规 CA 证书的中间人都能冒充邮箱服务器，
 * 骗走授权码。本类补上这一步，语义与 OkHttp 一致：
 * - 只看第一张证书的 SAN，**不回退到 CN**；
 * - 主机是 IP 字面量（IPv4，或含 `:` 的 IPv6）时只比较 SAN 的 iPAddress（类型 7），
 *   按字节比较，不做 DNS 解析（IP 出现在 dNSName 里不算匹配）；
 * - 否则比较 dNSName（类型 2），不区分大小写；主机先用 [IDN.toASCII] 转成 ASCII，
 *   转换失败即失败；
 * - 通配符只能是最左边一整段（`*.example.com` 匹配 `a.example.com`，不匹配
 *   `a.b.example.com`、也不匹配 `example.com`）；`*.com` 这样的单段后缀不匹配；
 *   模式里不准出现第二个 `*`；
 * - 主机或模式为空、以 `.` 开头、以 `..` 结尾都判失败，末尾单个 `.` 先去掉；
 * - 取证书出任何异常都返回 false，**绝不抛出放行**。
 *
 * 生产路径由 [ImapTransport.hostnameVerifier] 的默认值注入，没有任何关闭它的开关。
 */
internal object StrictHostnameVerifier : HostnameVerifier {

    /** SAN 里的 dNSName 类型（RFC 5280）。 */
    private const val TYPE_DNS_NAME = 2

    /** SAN 里的 iPAddress 类型（RFC 5280）。 */
    private const val TYPE_IP_ADDRESS = 7

    override fun verify(host: String, session: SSLSession): Boolean {
        return try {
            // peerCertificates 在对方没给证书时抛 SSLPeerUnverifiedException，交给外层兜住
            val chain = session.peerCertificates
            val cert = chain.firstOrNull() as? X509Certificate ?: return false
            verifyHost(host, cert)
        } catch (_: Exception) {
            // 取证书 / 解析 SAN 的任何异常都按失败处理，绝不因为异常而放行
            false
        }
    }

    /** 对单张证书做主机名校验（测试直接调它，不必构造真的 TLS 会话）。 */
    internal fun verifyHost(host: String, cert: X509Certificate): Boolean {
        return try {
            val altNames = cert.subjectAlternativeNames ?: return false
            val ip = ipLiteralOf(host)
            if (ip != null) {
                for (entry in altNames) {
                    if (entry == null || entry.size < 2) {
                        continue
                    }
                    if ((entry[0] as? Int) != TYPE_IP_ADDRESS) {
                        continue
                    }
                    val san = ipBytesOf(entry[1]) ?: continue
                    if (san.contentEquals(ip)) {
                        return true
                    }
                }
                // 主机是 IP 字面量时不再看 dNSName，也不回退到 CN
                return false
            }
            val asciiHost = try {
                IDN.toASCII(host)
            } catch (_: Exception) {
                return false
            }
            for (entry in altNames) {
                if (entry == null || entry.size < 2) {
                    continue
                }
                if ((entry[0] as? Int) != TYPE_DNS_NAME) {
                    continue
                }
                val pattern = entry[1] as? String ?: continue
                if (hostnameMatches(asciiHost, pattern)) {
                    return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 主机名与 SAN 模式是否匹配，规则见类文档（对齐 OkHttp 的 `verifyHostname`）。
     *
     * 两个参数都必须已经是 ASCII 主机名 / SAN 原文。
     */
    internal fun hostnameMatches(hostname: String, pattern: String): Boolean {
        if (hostname.isEmpty() || hostname.startsWith(".") || hostname.endsWith("..")) {
            return false
        }
        if (pattern.isEmpty() || pattern.startsWith(".") || pattern.endsWith("..")) {
            return false
        }
        // 末尾单个点先去掉（FQDN 的写法）
        val host = if (hostname.endsWith(".")) hostname.dropLast(1) else hostname
        val target = if (pattern.endsWith(".")) pattern.dropLast(1) else pattern
        if (host.isEmpty() || target.isEmpty()) {
            return false
        }
        if (!target.contains('*')) {
            return host.equals(target, ignoreCase = true)
        }
        // 通配符只能是最左边一整段，且只允许一个
        if (!target.startsWith("*.") || target.indexOf('*', 1) >= 0) {
            return false
        }
        val suffix = target.substring(1)
        if (suffix.length < 2 || suffix.indexOf('.', 1) < 0) {
            // `*.com` 这种单段后缀不算通配（后缀里必须还有别的点）
            return false
        }
        if (host.length <= suffix.length) {
            return false
        }
        if (host[host.length - suffix.length] != '.') {
            return false
        }
        if (!host.endsWith(suffix, ignoreCase = true)) {
            return false
        }
        // 通配符只能覆盖一整段且只有一段：`a.b.example.com` 不匹配 `*.example.com`
        val label = host.substring(0, host.length - suffix.length)
        return label.isNotEmpty() && !label.contains('.')
    }

    /** SAN 里的 IP 值可能是字符串，也可能是 4 / 16 字节数组。 */
    private fun ipBytesOf(value: Any?): ByteArray? = when (value) {
        is ByteArray -> value.takeIf { it.size == 4 || it.size == 16 }
        is String -> ipLiteralOf(value)
        else -> null
    }

    /** 主机是不是 IP 字面量：含 `:` 的按 IPv6 解析，否则按 IPv4（点分十进制）。 */
    internal fun ipLiteralOf(host: String): ByteArray? {
        if (host.isEmpty()) {
            return null
        }
        return if (host.contains(':')) ipv6Bytes(host) else ipv4Bytes(host)
    }

    /** 严格的点分十进制 IPv4；不合法返回 null。 */
    internal fun ipv4Bytes(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) {
            return null
        }
        val out = ByteArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3) {
                return null
            }
            var value = 0
            for (ch in part) {
                if (ch !in '0'..'9') {
                    return null
                }
                value = value * 10 + (ch - '0')
            }
            if (value > 255) {
                return null
            }
            out[index] = value.toByte()
        }
        return out
    }

    /**
     * 严格的 IPv6 字面量（支持 `::` 缩写与末尾嵌入的 IPv4）。
     *
     * 这里手写解析而不用 [java.net.InetAddress.getByName]：后者遇到不合法输入会去做一次
     * 域名解析查询，而主机名来自配置/证书，必须在本地判定，绝不触发网络请求。
     */
    internal fun ipv6Bytes(text: String): ByteArray? {
        if (text.isEmpty()) {
            return null
        }
        val cut = text.indexOf("::")
        if (cut != text.lastIndexOf("::")) {
            return null
        }
        val headGroups = ArrayList<Int>(8)
        val tailGroups = ArrayList<Int>(8)
        val headText = if (cut < 0) text else text.substring(0, cut)
        val tailText = if (cut < 0) "" else text.substring(cut + 2)
        if (!appendGroups(headText, headGroups) || !appendGroups(tailText, tailGroups)) {
            return null
        }
        val total = headGroups.size + tailGroups.size
        if (cut < 0) {
            if (total != 8) {
                return null
            }
        } else if (total > 7) {
            // `::` 至少压缩掉一组
            return null
        }
        val out = ByteArray(16)
        for (index in headGroups.indices) {
            out[index * 2] = (headGroups[index] shr 8).toByte()
            out[index * 2 + 1] = headGroups[index].toByte()
        }
        val tailStart = 8 - tailGroups.size
        for (index in tailGroups.indices) {
            out[(tailStart + index) * 2] = (tailGroups[index] shr 8).toByte()
            out[(tailStart + index) * 2 + 1] = tailGroups[index].toByte()
        }
        return out
    }

    /** 把一段冒号分隔的 IPv6 分组填进 [out]；含非法字符或组数过多时返回 false。 */
    private fun appendGroups(text: String, out: MutableList<Int>): Boolean {
        if (text.isEmpty()) {
            return true
        }
        val parts = text.split(':')
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty()) {
                return false
            }
            if (part.contains('.')) {
                // 只有末尾那一段可以嵌入 IPv4，它占两组
                if (index != parts.size - 1) {
                    return false
                }
                val v4 = ipv4Bytes(part) ?: return false
                out += ((v4[0].toInt() and 0xFF) shl 8) or (v4[1].toInt() and 0xFF)
                out += ((v4[2].toInt() and 0xFF) shl 8) or (v4[3].toInt() and 0xFF)
                continue
            }
            if (part.length > 4) {
                return false
            }
            var value = 0
            for (ch in part) {
                val digit = when (ch) {
                    in '0'..'9' -> ch - '0'
                    in 'a'..'f' -> ch - 'a' + 10
                    in 'A'..'F' -> ch - 'A' + 10
                    else -> return false
                }
                value = value * 16 + digit
            }
            out += value
        }
        return true
    }
}
