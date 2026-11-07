package com.mailhub.core.db

import com.mailhub.core.Clock
import com.mailhub.core.SecretCipher
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

/**
 * 可控时钟，对应 `com.mailhub.core.Clock`（`time.time()`）。
 * 每个用例自己给一个固定的时间戳，断言审计记录的 `ts` 时不用放宽精度。
 */
class FakeClock(var current: Double = 1_700_000_000.0) : Clock {
    override fun now(): Double = current
}

/**
 * 假凭据加解密，对应 App 里用 Android Keystore 的 AES-GCM 实现。
 *
 * 语义与 `security.py` 一致：空串进空串出；解不开返回空串。这里只是可逆变换，
 * `:core` 的测试里不出现任何真实密钥。
 */
class FakeSecretCipher : SecretCipher {
    override fun encrypt(plain: String): String =
        if (plain.isEmpty()) "" else PREFIX + plain.reversed()

    override fun decrypt(token: String): String =
        if (token.startsWith(PREFIX)) token.removePrefix(PREFIX).reversed() else ""

    private companion object {
        const val PREFIX = "fake:"
    }
}

/**
 * 在临时目录里建一个真实库文件再交给 [block]：WAL 是文件级特性，
 * 用真实文件才能和手机上的行为一致（与 [SqliteSmokeTest] 的做法相同）。
 */
internal fun <T> withTempDb(clock: Clock = FakeClock(), block: (Db) -> T): T {
    val dir: Path = Files.createTempDirectory("mailhub-core-db")
    val db = Db(dir.resolve("mailhub.db").toString(), clock)
    try {
        return block(db)
    } finally {
        runCatching { db.close() }
        deleteDatabaseFiles(dir)
    }
}

/** 删掉库文件、WAL 与 shm 以及临时目录。 */
internal fun deleteDatabaseFiles(dir: Path) {
    dir.resolve("mailhub.db-wal").deleteIfExists()
    dir.resolve("mailhub.db-shm").deleteIfExists()
    dir.resolve("mailhub.db").deleteIfExists()
    dir.deleteIfExists()
}
