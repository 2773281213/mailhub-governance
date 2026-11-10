package com.mailhub.core.sync

import com.mailhub.core.SecretCipher
import com.mailhub.core.db.Row
import com.mailhub.core.imap.AUTH_TYPE_OAUTH
import com.mailhub.core.imap.IMAP_DEFAULT_PORT
import com.mailhub.core.imap.ImapAccount
import com.mailhub.core.imap.ImapException

/**
 * `accounts` 表里同步用得到的字段，对应 Python 里 `dict(acc_row)` 的那些键。
 *
 * Python 直接把 `sqlite3.Row` 转成 dict 再按键取值，这里用数据类把列固定下来：
 * `imap_host`/`imap_port`/`email`/`auth_type`/`secret_enc`/`last_uid`/`uidvalidity`/
 * `poll_interval`/`last_sync`/`enabled`，加上 `provider`（判断是否需要发 IMAP ID）。
 *
 * 整数列一律由 [Row.long] 读出（IMAP 的 UID 与 uidvalidity 都是无符号 32 位），
 * 只有账户 id 在 API 边界上转成 [Int]（与 L9 的接口 id 一致）。
 */
data class SyncAccount(
    val id: Int,
    val name: String = "",
    val provider: String = "",
    val email: String = "",
    val host: String = "",
    val port: Int = IMAP_DEFAULT_PORT,
    /** `"oauth"` 或 `"password"`。 */
    val authType: String = "password",
    /** Keystore 加密后的授权码 / 密码；OAuth 账户的令牌另有列。 */
    val secretEnc: String = "",
    val enabled: Boolean = true,
    val pollInterval: Long = DEFAULT_POLL_INTERVAL_SECONDS,
    val lastUid: Long = 0,
    val uidvalidity: Long = 0,
    val lastSync: Double = 0.0,
    /** 账户上显式要求发送 RFC 2971 ID（对应 `account.get("need_id")`）。 */
    val needId: Boolean = false,
) {

    /** 转成 [com.mailhub.core.imap.openSession] 需要的 IMAP 账户字段。 */
    fun toImapAccount(): ImapAccount = ImapAccount(
        host = host,
        email = email,
        port = port,
        authType = authType,
        provider = provider,
        needId = needId,
    )

    companion object {

        /** `accounts.poll_interval` 的默认值（与 `db.py` 的 schema 一致）。 */
        const val DEFAULT_POLL_INTERVAL_SECONDS: Long = 300

        /** 把 `accounts` 表的一行转成 [SyncAccount]。 */
        fun fromRow(row: Row): SyncAccount = SyncAccount(
            id = Math.toIntExact(row.long("id") ?: 0L),
            name = row.str("name") ?: "",
            provider = row.str("provider") ?: "",
            email = row.str("email") ?: "",
            host = row.str("imap_host") ?: "",
            port = (row.long("imap_port") ?: IMAP_DEFAULT_PORT.toLong()).toInt(),
            authType = row.str("auth_type") ?: "password",
            secretEnc = row.str("secret_enc") ?: "",
            enabled = (row.long("enabled") ?: 1L) != 0L,
            pollInterval = row.long("poll_interval") ?: DEFAULT_POLL_INTERVAL_SECONDS,
            lastUid = row.long("last_uid") ?: 0L,
            uidvalidity = row.long("uidvalidity") ?: 0L,
            lastSync = row.double("last_sync") ?: 0.0,
        )
    }
}

/** 本机模式（P0）还没有 OAuth 令牌刷新，遇到 OAuth 账户时的说明。 */
const val OAUTH_UNSUPPORTED_TEXT: String = "本机模式暂不支持 OAuth 账户"

/**
 * 取得可用登录凭据，对应 `sync.resolve_secret`。
 *
 * 非 OAuth 账户用 [SecretCipher.decrypt] 解出授权码；OAuth 账户在服务器端是
 * `oauth_auth.resolve_oauth_access_token(acc)`，本机模式 P0 还没有这套流程，
 * 抛出明确的异常，由 [SyncService.syncAccount] 写进 `accounts.last_error`。
 */
fun resolveSecret(account: SyncAccount, cipher: SecretCipher): String {
    if (account.authType != AUTH_TYPE_OAUTH) {
        return cipher.decrypt(account.secretEnc)
    }
    throw ImapException(OAUTH_UNSUPPORTED_TEXT)
}
