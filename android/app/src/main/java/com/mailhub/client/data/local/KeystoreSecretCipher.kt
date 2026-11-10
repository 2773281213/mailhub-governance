package com.mailhub.client.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.mailhub.core.SecretCipher
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.Cipher as JavaxCipher

/**
 * 邮箱授权码、OAuth 令牌、AI Key 这类本机凭据的加解密，语义见 [SecretCipher]：
 * 空串进空串出，解不开时返回空串，**不抛异常**。
 *
 * 格式与 `TokenCipher` 相同（`Base64(iv + 密文)`，AES/GCM/256、随机 IV），但用**独立的密钥
 * 别名** [ALIAS]：令牌的密钥不拿来加密邮件凭据，两者的泄露面互不影响。
 *
 * 和 `TokenCipher` 一样延迟到第一次加解密才打开 Keystore：Robolectric 里没有 AndroidKeyStore，
 * 只要不真的加解密，构造这个对象就不会失败。
 *
 * 加密失败时把异常抛给调用方，**绝不退回明文**——把凭据明文写进数据库比报错危险得多。
 */
class KeystoreSecretCipher : SecretCipher {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    override fun encrypt(plain: String): String {
        if (plain.isEmpty()) {
            return ""
        }
        val cipher = JavaxCipher.getInstance(TRANSFORMATION)
        cipher.init(JavaxCipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    /**
     * 解不开时返回空串：换了密钥、密文被改坏、Keystore 提供方异常（`ProviderException`）、
     * Base64 不合法（`IllegalArgumentException`）都按「未配置」处理，与 `security.decrypt` 一致。
     */
    override fun decrypt(token: String): String {
        if (token.isEmpty()) {
            return ""
        }
        return try {
            val bytes = Base64.decode(token, Base64.NO_WRAP)
            require(bytes.size > IV_SIZE) { "凭据密文格式不正确" }
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
            val cipher = JavaxCipher.getInstance(TRANSFORMATION)
            cipher.init(JavaxCipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun secretKey(): SecretKey {
        val existing = keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** 本机凭据专用别名，与 `TokenCipher` 的 `mailhub_token_key` 不共用。 */
        const val ALIAS = "mailhub_local_secret_v1"

        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** GCM 的推荐 IV 长度（96 位）。 */
        const val IV_SIZE = 12
    }
}
