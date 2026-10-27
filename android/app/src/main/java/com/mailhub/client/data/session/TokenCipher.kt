package com.mailhub.client.data.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.Cipher as JavaxCipher

/**
 * 用 Android Keystore 里的 AES/GCM 256 位密钥加密令牌。
 * IV 和密文拼在一起做 Base64 保存。
 */
class TokenCipher : Cipher {

    // 延迟到第一次加解密时才打开 Keystore：Robolectric 测试里没有 AndroidKeyStore，
    // 但截图测试不会调用加解密，不应该因此失败。
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
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

    override fun encrypt(plain: String): String {
        val cipher = JavaxCipher.getInstance(TRANSFORMATION)
        cipher.init(JavaxCipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    override fun decrypt(blob: String): String {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        require(bytes.size > IV_SIZE) { "令牌密文格式不正确" }
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = JavaxCipher.getInstance(TRANSFORMATION)
        cipher.init(JavaxCipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "mailhub_token_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
