package com.mailhub.client.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.mailhub.client.data.session.Cipher
import com.mailhub.client.data.session.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/** 假的加解密：只加前缀，方便断言存储里存了什么。 */
class FakeCipher(private val failOnDecrypt: Boolean = false) : Cipher {

    override fun encrypt(plain: String): String = "enc:$plain"

    override fun decrypt(blob: String): String {
        check(!failOnDecrypt) { "解密失败" }
        return blob.removePrefix("enc:")
    }
}

/**
 * 内存版 DataStore。真实 DataStore 在 Windows 上用 renameTo 覆盖文件会失败，
 * 单元测试统一用内存实现；同时统计读取、保存、清空次数，用来断言「只读一次」「只清一次」。
 */
class InMemoryDataStore(private val readDelayMs: Long = 0) : DataStore<Preferences> {

    private val mutex = Mutex()
    private val state = MutableStateFlow<Preferences>(emptyPreferences())

    /** 每次 collect 算一次读取。 */
    val readCount = AtomicInteger()

    /** 写入非空内容的次数。 */
    val saveCount = AtomicInteger()

    /** 清空存储的次数。 */
    val clearCount = AtomicInteger()

    override val data: Flow<Preferences> = flow {
        readCount.incrementAndGet()
        if (readDelayMs > 0) {
            delay(readDelayMs)
        }
        emitAll(state)
    }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        mutex.withLock {
            val next = transform(state.value)
            if (next.asMap().isEmpty()) {
                clearCount.incrementAndGet()
            } else {
                saveCount.incrementAndGet()
            }
            state.value = next
            next
        }
}

/** 测试用会话，默认永不过期（expiresTs = 0 表示服务端没给过期时间）。 */
fun testSession(
    serverUrl: String,
    token: String,
    sessionId: String = "sid-1",
    expiresTs: Double = 0.0,
    deviceName: String = "Pixel 8",
) = Session(
    serverUrl = serverUrl,
    token = token,
    sessionId = sessionId,
    expiresTs = expiresTs,
    deviceName = deviceName,
)
