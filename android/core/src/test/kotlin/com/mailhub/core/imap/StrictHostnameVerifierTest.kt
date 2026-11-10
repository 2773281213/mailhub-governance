package com.mailhub.core.imap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.Principal
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSessionContext
import javax.security.auth.x500.X500Principal

/**
 * [StrictHostnameVerifier] 的表驱动测试，逐条覆盖 LOCAL_MODE.md 3.2 里规定的语义。
 *
 * 这个文件放在 `src/test`，会在手机上回放（不依赖 GreenMail / keytool），所以证书和会话
 * 都用假的实现：假证书只实现 `getSubjectAlternativeNames`，假会话只实现
 * `getPeerCertificates`，其余方法一律抛异常（真被调用说明校验逻辑走错了分支）。
 */
class StrictHostnameVerifierTest {

    /** 一条用例：主机、证书 SAN、期望结果。 */
    private data class Case(
        val name: String,
        val host: String,
        val altNames: Collection<List<*>>?,
        val expected: Boolean,
    )

    private fun dns(vararg names: String): Collection<List<*>> =
        names.map { listOf(2, it) }

    private fun ip(vararg addresses: String): Collection<List<*>> =
        addresses.map { listOf(7, it) }

    private fun verify(host: String, altNames: Collection<List<*>>?): Boolean =
        StrictHostnameVerifier.verify(host, FakeSession(arrayOf<Certificate>(FakeCertificate(altNames))))

    @Test
    fun matchesPlainDnsNames() {
        val cases = listOf(
            Case("完全相同", "example.com", dns("example.com"), true),
            Case("不区分大小写的主机", "ExAmPle.CoM", dns("example.com"), true),
            Case("不区分大小写的 SAN", "example.com", dns("ExAmPle.CoM"), true),
            Case("多张 SAN 里命中一张", "imap.example.com", dns("smtp.example.com", "imap.example.com"), true),
            Case("域名不同", "example.com", dns("example.org"), false),
            Case("子域不算父域", "imap.example.com", dns("example.com"), false),
            Case("父域不算子域", "example.com", dns("imap.example.com"), false),
            Case("末尾单个点先去掉（主机）", "example.com.", dns("example.com"), true),
            Case("末尾单个点先去掉（SAN）", "example.com", dns("example.com."), true),
            Case("主机为空", "", dns("example.com"), false),
            Case("SAN 为空串", "example.com", dns(""), false),
            Case("主机以点开头", ".example.com", dns("example.com"), false),
            Case("SAN 以点开头", "example.com", dns(".example.com"), false),
            Case("主机以两个点结尾", "example.com..", dns("example.com"), false),
            Case("SAN 以两个点结尾", "example.com", dns("example.com.."), false),
        )
        assertCases(cases)
    }

    @Test
    fun matchesWildcardsOnlyAsOneLeftmostLabel() {
        val cases = listOf(
            Case("通配符匹配一段", "a.example.com", dns("*.example.com"), true),
            Case("通配符匹配较长的段", "long-label.example.com", dns("*.example.com"), true),
            Case("通配符不匹配多段", "a.b.example.com", dns("*.example.com"), false),
            Case("通配符不匹配裸域名", "example.com", dns("*.example.com"), false),
            Case("通配符不匹配父域", "com", dns("*.com"), false),
            Case("单段后缀的 *.com 不匹配", "a.com", dns("*.com"), false),
            Case("单段后缀的 *.com 也不匹配更长的", "a.example.com", dns("*.com"), false),
            Case("模式里有第二个星号", "a.example.com", dns("*.*.example.com"), false),
            Case("模式里星号不在开头", "a.example.com", dns("a*.example.com"), false),
            Case("模式里星号在中间", "a.example.com", dns("a.*.example.com"), false),
            Case("只有星号", "a", dns("*"), false),
            Case("只有星号加点的空后缀", "a", dns("*."), false),
            Case("主机比模式短", "a.com", dns("*.example.com"), false),
            Case("裸通配符不能匹配同名后缀", "bexample.com", dns("*.example.com"), false),
        )
        assertCases(cases)
    }

    @Test
    fun comparesIpLiteralsAgainstIpAddressSansOnly() {
        val cases = listOf(
            Case("IPv4 命中 iPAddress", "127.0.0.1", ip("127.0.0.1"), true),
            Case("IPv4 与 SAN 里的 dNSName 同形也不算", "127.0.0.1", dns("127.0.0.1"), false),
            Case("IPv4 不同", "127.0.0.1", ip("10.0.0.1"), false),
            Case("IPv4 混在多张 SAN 里", "10.0.0.1", listOf(listOf(2, "a.example"), listOf(7, "10.0.0.1")), true),
            Case("IPv6 压缩写法与展开写法等价", "2001:db8::1", ip("2001:0db8:0000:0000:0000:0000:0000:0001"), true),
            Case("IPv6 回环", "::1", ip("0:0:0:0:0:0:0:1"), true),
            Case("IPv6 不同", "2001:db8::1", ip("2001:db8::2"), false),
            Case("IPv6 出现在 dNSName 里不算", "::1", dns("::1"), false),
            Case("嵌入 IPv4 的 IPv6", "::ffff:127.0.0.1", ip("::ffff:127.0.0.1"), true),
            Case("IPv4 不匹配 IPv6 写法", "127.0.0.1", ip("::ffff:127.0.0.1"), false),
            Case("不是 IPv4 的四段", "999.0.0.1", ip("999.0.0.1"), false),
        )
        assertCases(cases)
    }

    @Test
    fun convertsIdnHostsToAscii() {
        // IDN.toASCII("bücher.example") 就是 xn--bcher-kva.example
        val cases = listOf(
            Case("Unicode 主机与 punycode SAN", "bücher.example", dns("xn--bcher-kva.example"), true),
            Case("Unicode 主机与 Unicode SAN 不相等", "bücher.example", dns("bücher.example"), false),
        )
        assertCases(cases)
    }

    /** CN 匹配但 SAN 不匹配：绝不回退到 CN。 */
    @Test
    fun doesNotFallBackToCommonName() {
        val cert = CnCertificate(dns("other.example.com"), commonName = "example.com")
        assertFalse(
            "SAN 不匹配时不能回退到 CN",
            StrictHostnameVerifier.verify("example.com", FakeSession(arrayOf<Certificate>(cert))),
        )
    }

    @Test
    fun rejectsMissingOrUnreadableCertificates() {
        // 没有 SAN：即使 CN 一样也不放行（不回退到 CN）
        assertFalse(
            "没有 SAN 必须失败",
            verify("example.com", null),
        )
        assertFalse("空 SAN 列表必须失败", verify("example.com", emptyList()))
        // 取证书抛异常：按失败处理，绝不抛出放行
        assertFalse(
            "取证书抛异常必须失败",
            StrictHostnameVerifier.verify("example.com", ThrowingSession()),
        )
        assertFalse(
            "证书解析抛异常必须失败",
            StrictHostnameVerifier.verifyHost("example.com", ThrowingAltNames()),
        )
        assertFalse(
            "证书链为空必须失败",
            StrictHostnameVerifier.verify("example.com", FakeSession(emptyArray())),
        )
        assertFalse(
            "第一张不是 X509 时必须失败",
            StrictHostnameVerifier.verify(
                "example.com",
                FakeSession(arrayOf<Certificate>(NonX509Certificate)),
            ),
        )
    }

    /** SAN 条目类型不是 2 或 7 时忽略（例如 email、URI 类型的 SAN 不能当主机名用）。 */
    @Test
    fun ignoresOtherSanTypes() {
        val cases = listOf(
            Case("只有 email SAN", "example.com", listOf(listOf(1, "example.com")), false),
            Case("只有 URI SAN", "example.com", listOf(listOf(6, "example.com")), false),
            Case("SAN 条目缺少取值", "example.com", listOf(listOf(2)), false),
        )
        assertCases(cases)
    }

    private fun assertCases(cases: List<Case>) {
        for (case in cases) {
            val actual = verify(case.host, case.altNames)
            if (case.expected) {
                assertTrue("${case.name}：主机 ${case.host} 应匹配 ${case.altNames}", actual)
            } else {
                assertFalse("${case.name}：主机 ${case.host} 不应匹配 ${case.altNames}", actual)
            }
        }
    }

    // ---------------- 假的会话与证书 ----------------

    /**
     * 假证书：只实现 `getSubjectAlternativeNames`，其余方法一旦被调用就说明校验逻辑
     * 走到了不该走的路径（例如回退到 CN、或去解析证书本身）。
     */
    private open class FakeCertificate(
        private val altNames: Collection<List<*>>?,
    ) : X509Certificate() {

        override fun getSubjectAlternativeNames(): Collection<List<*>>? = altNames

        override fun getIssuerAlternativeNames(): Collection<List<*>>? =
            throw UnsupportedOperationException("校验只看 subjectAltName")

        override fun checkValidity() = throw UnsupportedOperationException("测试不需要校验证书有效期")

        override fun checkValidity(date: Date?) = throw UnsupportedOperationException("测试不需要校验证书有效期")

        override fun getVersion(): Int = throw UnsupportedOperationException("测试不需要证书版本")

        override fun getSerialNumber(): BigInteger = throw UnsupportedOperationException("测试不需要序列号")

        override fun getIssuerDN(): Principal = throw UnsupportedOperationException("校验不回退到 issuer")

        override fun getSubjectDN(): Principal = throw UnsupportedOperationException("校验不回退到 CN")

        override fun getNotBefore(): Date = throw UnsupportedOperationException("测试不需要有效期")

        override fun getNotAfter(): Date = throw UnsupportedOperationException("测试不需要有效期")

        override fun getTBSCertificate(): ByteArray = throw UnsupportedOperationException("测试不需要证书原文")

        override fun getSignature(): ByteArray = throw UnsupportedOperationException("测试不需要签名")

        override fun getSigAlgName(): String = throw UnsupportedOperationException("测试不需要签名算法")

        override fun getSigAlgOID(): String = throw UnsupportedOperationException("测试不需要签名算法")

        override fun getSigAlgParams(): ByteArray? = throw UnsupportedOperationException("测试不需要签名算法")

        override fun getIssuerUniqueID(): BooleanArray? = throw UnsupportedOperationException("测试不需要唯一标识")

        override fun getSubjectUniqueID(): BooleanArray? = throw UnsupportedOperationException("测试不需要唯一标识")

        override fun getKeyUsage(): BooleanArray? = throw UnsupportedOperationException("测试不需要密钥用途")

        override fun getBasicConstraints(): Int = throw UnsupportedOperationException("测试不需要基本约束")

        override fun getExtendedKeyUsage(): MutableList<String>? = throw UnsupportedOperationException("测试不需要扩展密钥用途")

        override fun getEncoded(): ByteArray = throw UnsupportedOperationException("测试不需要证书编码")

        override fun verify(key: PublicKey?) = throw UnsupportedOperationException("测试不需要验签")

        override fun verify(key: PublicKey?, sigProvider: String?) = throw UnsupportedOperationException("测试不需要验签")

        override fun getPublicKey(): PublicKey = throw UnsupportedOperationException("测试不需要公钥")

        override fun getExtensionValue(oid: String?): ByteArray? = throw UnsupportedOperationException("测试不需要扩展")

        override fun getCriticalExtensionOIDs(): MutableSet<String>? = throw UnsupportedOperationException("测试不需要扩展")

        override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = throw UnsupportedOperationException("测试不需要扩展")

        override fun hasUnsupportedCriticalExtension(): Boolean = throw UnsupportedOperationException("测试不需要扩展")

        override fun toString(): String = "FakeCertificate"
    }

    /**
     * 主体里写着要匹配的 CN、但 SAN 是另一个名字的证书。
     *
     * 只用它证明校验**不回退到 CN**：`getSubjectDN` 能返回 CN，实现如果偷懒去看 CN
     * 就会返回 true，测试立刻失败。
     */
    private class CnCertificate(
        altNames: Collection<List<*>>?,
        private val commonName: String,
    ) : FakeCertificate(altNames) {

        override fun getSubjectDN(): Principal = X500Principal("CN=$commonName")

        override fun getSubjectX500Principal(): X500Principal = X500Principal("CN=$commonName")
    }

    /** 解析 SAN 时抛异常（真实的证书描述符损坏时就是这样）。 */
    private class ThrowingAltNames : FakeCertificate(null) {
        override fun getSubjectAlternativeNames(): Collection<List<*>>? =
            throw CertificateParsingException("坏掉的 subjectAltName 扩展")
    }

    /** 假会话：只实现 `getPeerCertificates`。 */
    private open class FakeSession(
        private val certificates: Array<Certificate>,
    ) : SSLSession {

        override fun getPeerCertificates(): Array<Certificate> = certificates

        override fun getId(): ByteArray = throw UnsupportedOperationException("测试不需要会话 id")

        override fun getSessionContext(): SSLSessionContext = throw UnsupportedOperationException("测试不需要会话上下文")

        override fun getValue(name: String?): Any? = throw UnsupportedOperationException("测试不需要会话取值")

        override fun getValueNames(): Array<String> = throw UnsupportedOperationException("测试不需要会话取值")

        override fun putValue(name: String?, value: Any?) = throw UnsupportedOperationException("测试不需要会话取值")

        override fun removeValue(name: String?) = throw UnsupportedOperationException("测试不需要会话取值")

        override fun getCreationTime(): Long = throw UnsupportedOperationException("测试不需要时间")

        override fun getLastAccessedTime(): Long = throw UnsupportedOperationException("测试不需要时间")

        override fun invalidate() = throw UnsupportedOperationException("测试不需要会话管理")

        override fun isValid(): Boolean = throw UnsupportedOperationException("测试不需要会话管理")

        override fun getLocalCertificates(): Array<Certificate>? = throw UnsupportedOperationException("测试不需要本端证书")

        @Suppress("DEPRECATION")
        override fun getPeerCertificateChain(): Array<javax.security.cert.X509Certificate> =
            throw SSLPeerUnverifiedException("测试不使用废弃接口")

        override fun getPeerPrincipal(): Principal = throw UnsupportedOperationException("测试只按 SAN 判断")

        override fun getLocalPrincipal(): Principal? = throw UnsupportedOperationException("测试不需要本端主体")

        override fun getCipherSuite(): String = throw UnsupportedOperationException("测试不需要密码套件")

        override fun getProtocol(): String = throw UnsupportedOperationException("测试不需要协议版本")

        override fun getPeerHost(): String = throw UnsupportedOperationException("测试不需要主机信息")

        override fun getPeerPort(): Int = throw UnsupportedOperationException("测试不需要端口")

        override fun getPacketBufferSize(): Int = throw UnsupportedOperationException("测试不需要缓冲区大小")

        override fun getApplicationBufferSize(): Int = throw UnsupportedOperationException("测试不需要缓冲区大小")

        override fun toString(): String = "FakeSession"
    }

    /** 取对端证书就抛异常（对方没给证书时 `SSLSession` 的行为）。 */
    private class ThrowingSession : FakeSession(emptyArray()) {
        override fun getPeerCertificates(): Array<Certificate> =
            throw SSLPeerUnverifiedException("peer not authenticated")
    }

    /** 第一张证书不是 X509（例如原始证书类型）。 */
    private object NonX509Certificate : Certificate("X.509") {
        override fun getEncoded(): ByteArray = ByteArray(0)

        override fun verify(key: PublicKey?) = throw CertificateNotYetValidException()

        override fun verify(key: PublicKey?, sigProvider: String?) = throw CertificateExpiredException()

        override fun toString(): String = "NonX509Certificate"

        override fun getPublicKey(): PublicKey = throw UnsupportedOperationException("测试不需要公钥")
    }
}
