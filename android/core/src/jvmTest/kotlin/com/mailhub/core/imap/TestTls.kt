package com.mailhub.core.imap

import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * 测试用的 TLS 夹具：**运行时**用 JDK 自带的 keytool 生成一张只给 localhost 用的自签名证书。
 *
 * 为什么这么做：
 * - 生产代码（`ImapSession`）写死了 `imaps` + `mail.imaps.ssl.checkserveridentity=true`，
 *   没有任何关闭校验的开关，所以测试必须能信任一张自签名证书——这是
 *   `ImapTransport.sslSocketFactory` 这个 `internal` 参数存在的唯一理由；
 * - 证书的 SAN 明确写了 `localhost` 与 `127.0.0.1`，所以**主机名校验是真的在生效**，
 *   测试不是在偷偷关掉它；
 * - 仓库里不放任何私钥 / keystore 文件：证书每次在临时目录里现生成（有效期 3 天），
 *   JVM 退出后随临时目录一起留下也不会被提交。
 *
 * GreenMail 的服务端 keystore 通过系统属性指定（它自己的默认证书没有 SAN，过不了主机名校验），
 * 见 [installGreenMailKeystoreProperties]。
 */
internal object TestTls {

    /** 生成的 PKCS12 的库口令 / 键口令（同一把，PKCS12 要求一致）。 */
    const val PASSWORD: String = "mailhub-test"

    private const val ALIAS = "mailhub-test"

    /** 生成出来的 PKCS12 文件。 */
    val keystoreFile: File by lazy { generateKeystore() }

    /** 服务端用的 [SSLContext]（用上面那张证书）。 */
    val serverContext: SSLContext by lazy { serverContext(keystoreFile) }

    /** 客户端用的「信任任何证书」工厂：只给测试的 [ImapTransport] 用。 */
    val trustAllFactory: SSLSocketFactory by lazy {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<X509TrustManager>(TrustAllManager), SecureRandom())
        context.socketFactory
    }

    /**
     * 让 GreenMail 的 IMAPS 用我们的证书：它在启动安全服务时读这三个系统属性。
     *
     * 这几个属性是进程级的，所以只能在「第一次启动 GreenMail 之前」设置；测试里统一通过
     * [GreenMailFixture] 启动，那里会先调用本方法。
     */
    fun installGreenMailKeystoreProperties() {
        System.setProperty("greenmail.tls.keystore.file", keystoreFile.absolutePath)
        System.setProperty("greenmail.tls.keystore.password", PASSWORD)
        System.setProperty("greenmail.tls.key.password", PASSWORD)
    }

    /** 读我们自己生成的 keystore。 */
    fun loadKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance("PKCS12")
        keystoreFile.inputStream().use { keyStore.load(it, PASSWORD.toCharArray()) }
        return keyStore
    }

    /** 用 [file] 里的私钥建一个服务端 [SSLContext]。 */
    fun serverContext(file: File): SSLContext {
        val keyStore = KeyStore.getInstance("PKCS12")
        file.inputStream().use { keyStore.load(it, PASSWORD.toCharArray()) }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagers.init(keyStore, PASSWORD.toCharArray())
        val context = SSLContext.getInstance("TLS")
        context.init(keyManagers.keyManagers, null, SecureRandom())
        return context
    }

    /** 生成自签名证书；keytool 的路径从 `java.home` 推出来，不依赖 PATH。 */
    private fun generateKeystore(): File {
        val dir = Files.createTempDirectory("mailhub-test-tls").toFile()
        val file = File(dir, "mailhub-test.p12")
        val binDir = File(System.getProperty("java.home"), "bin")
        val keytool = listOf("keytool.exe", "keytool")
            .map { File(binDir, it) }
            .firstOrNull { it.canExecute() }
            ?: error("找不到 keytool：$binDir")
        val command = listOf(
            keytool.absolutePath,
            "-genkeypair",
            "-alias", ALIAS,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-sigalg", "SHA256withRSA",
            "-validity", "3",
            "-dname", "CN=localhost, OU=MailHub Test, O=MailHub, C=CN",
            "-ext", "SAN=dns:localhost,ip:127.0.0.1",
            "-storetype", "PKCS12",
            "-keystore", file.absolutePath,
            "-storepass", PASSWORD,
            "-keypass", PASSWORD,
        )
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        check(code == 0) { "keytool 生成测试证书失败（退出码 $code）：$output" }
        check(file.isFile) { "keytool 没有生成 keystore：$output" }
        return file
    }

    /** 只用于测试的「信任一切」信任管理器。 */
    private object TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
