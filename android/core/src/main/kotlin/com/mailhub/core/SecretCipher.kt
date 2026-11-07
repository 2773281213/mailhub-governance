package com.mailhub.core

/**
 * 邮箱授权码、OAuth 令牌、AI Key 这类凭据的加解密，对应服务端 `security.py` 的 `encrypt` / `decrypt`。
 *
 * `:core` 里不出现任何密钥：App 注入用 Android Keystore 的 AES-GCM 实现，测试注入假实现。
 *
 * 约定与 `security.py` 一致：
 * - [encrypt] 空串返回空串；
 * - [decrypt] 空串返回空串；
 * - 无法解密（换了密钥、数据损坏）时返回空串，不抛异常，调用方把它当成「未配置」处理。
 */
interface SecretCipher {
    fun encrypt(plain: String): String
    fun decrypt(token: String): String
}
