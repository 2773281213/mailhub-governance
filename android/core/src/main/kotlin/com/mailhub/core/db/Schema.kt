package com.mailhub.core.db

/**
 * `db.py` 里的表结构定义，逐条照搬自服务端 `db.py`：
 * [SCHEMA] 对应 `SCHEMA`，[MIGRATIONS] 对应 `MIGRATIONS`，[FTS_STATEMENTS] 对应 `_fts_statements()`。
 * OAuth 那部分（[OAUTH_SCHEMA]、[OAUTH_ACCOUNT_COLUMNS]、[OAUTH_TRANSACTION_COLUMNS]、
 * [OAUTH_REAUTH_SQL]）照搬自 `oauth_auth.py` 的 `init_oauth_db()`：服务器启动时先
 * `db.init_db()` 再 `oauth_auth.init_oauth_db()`（`app.py`），[Db.initDb] 也按这个顺序执行。
 *
 * 为什么要逐字照搬：表结构必须与服务器完全相同，L7 同步、L8 治理、L9 接口才能把 `sync.py`、
 * `governance.py`、`app.py` 里的 SQL 原样搬过来。金标 `scripts/golden/db_schema.py` 依次跑
 * `db.init_db()` 与 `oauth_auth.init_oauth_db()`，把建出来的 `sqlite_master` 与
 * `PRAGMA table_info` 全部导出，`DbSchemaGoldenTest` 逐条比较，`sql` 文本要求逐字相同。
 *
 * 因此**下面这些 SQL 的换行与缩进都是从 `db.py` 原样抄来的**（`sqlite_master` 保存的是建表
 * 语句原文，差一个空格金标就对不上），不要重新排版、不要合并成一行。多行字符串统一写成
 * 「`"""` 后换行、每行在 Python 原文基础上多缩进 8 个空格、最后 `trimIndent()`」：
 * `trimIndent()` 只去掉这层统一缩进，语句内部的相对缩进原样保留。
 */
object DbSchema {

    /** 对应 `db.py` 的 `SCHEMA`：完整的建表语句。 */
    val SCHEMA: String = """
        CREATE TABLE IF NOT EXISTS settings(
          k TEXT PRIMARY KEY,
          v TEXT
        );

        CREATE TABLE IF NOT EXISTS accounts(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          name TEXT NOT NULL,
          provider TEXT NOT NULL,
          email TEXT NOT NULL,
          imap_host TEXT NOT NULL,
          imap_port INTEGER NOT NULL DEFAULT 993,
          auth_type TEXT NOT NULL DEFAULT 'password',
          secret_enc TEXT DEFAULT '',
          oauth_refresh_enc TEXT DEFAULT '',
          oauth_access_enc TEXT DEFAULT '',
          oauth_expires REAL DEFAULT 0,
          enabled INTEGER DEFAULT 1,
          poll_interval INTEGER DEFAULT 300,
          last_uid INTEGER DEFAULT 0,
          uidvalidity INTEGER DEFAULT 0,
          last_sync REAL DEFAULT 0,
          last_error TEXT DEFAULT '',
          color TEXT DEFAULT '#38bdf8',
          created_ts REAL DEFAULT 0
        );

        CREATE TABLE IF NOT EXISTS messages(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
          uid INTEGER NOT NULL,
          msg_id TEXT DEFAULT '',
          subject TEXT DEFAULT '',
          sender_name TEXT DEFAULT '',
          sender_addr TEXT DEFAULT '',
          date_ts REAL DEFAULT 0,
          snippet TEXT DEFAULT '',
          body_text TEXT DEFAULT '',
          body_html TEXT DEFAULT '',
          category TEXT DEFAULT '未分类',
          otp_code TEXT DEFAULT '',
          importance INTEGER DEFAULT 0,
          summary TEXT DEFAULT '',
          ai_done INTEGER DEFAULT 0,
          unread INTEGER DEFAULT 1,
          has_attach INTEGER DEFAULT 0,
          unsubscribe INTEGER DEFAULT 0,
          created_ts REAL DEFAULT 0,
          governance_version TEXT DEFAULT '',
          governance_ministry TEXT DEFAULT '',
          governance_action TEXT DEFAULT '',
          governance_source TEXT DEFAULT '',
          governance_reason TEXT DEFAULT '',
          governance_ts REAL DEFAULT 0,
          UNIQUE(account_id, uid)
        );
        CREATE INDEX IF NOT EXISTS idx_msg_date ON messages(date_ts DESC);
        CREATE INDEX IF NOT EXISTS idx_msg_cat ON messages(category);
        CREATE INDEX IF NOT EXISTS idx_msg_acc ON messages(account_id);

        CREATE TABLE IF NOT EXISTS rules(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          name TEXT DEFAULT '',
          field TEXT NOT NULL,
          pattern TEXT NOT NULL,
          category TEXT NOT NULL,
          priority INTEGER DEFAULT 100,
          enabled INTEGER DEFAULT 1
        );

        CREATE TABLE IF NOT EXISTS digests(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          day TEXT UNIQUE,
          content TEXT DEFAULT '',
          created_ts REAL DEFAULT 0
        );

        CREATE TABLE IF NOT EXISTS aliases(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          alias TEXT UNIQUE NOT NULL,
          account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
          name TEXT DEFAULT '',
          created_ts REAL DEFAULT 0,
          last_query_ts REAL DEFAULT 0
        );

        -- 审计日志：每一个改变邮件状态的动作都要留痕，可撤销的附带还原载荷
        CREATE TABLE IF NOT EXISTS audit_log(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          ts REAL NOT NULL,
          actor TEXT NOT NULL,          -- user | rule | ai | system
          action TEXT NOT NULL,         -- soft_delete | purge | read | archive ...
          tier TEXT DEFAULT '',         -- A | B | C
          target_count INTEGER DEFAULT 0,
          target_ids TEXT DEFAULT '',   -- JSON 数组
          reason TEXT DEFAULT '',
          allowed INTEGER DEFAULT 1,    -- 0 = 被策略引擎拦截
          reversible INTEGER DEFAULT 0,
          undone INTEGER DEFAULT 0,
          undo_payload TEXT DEFAULT ''  -- JSON，撤销所需的原始状态
        );
        CREATE INDEX IF NOT EXISTS idx_audit_ts ON audit_log(ts DESC);

        -- 自动「三省六部」治理运行。只保存统计，不保存邮件正文或凭据。
        CREATE TABLE IF NOT EXISTS governance_runs(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          version TEXT NOT NULL,
          trigger TEXT NOT NULL DEFAULT 'system',
          status TEXT NOT NULL DEFAULT 'running',
          started_ts REAL NOT NULL,
          finished_ts REAL DEFAULT 0,
          selected_count INTEGER DEFAULT 0,
          processed_count INTEGER DEFAULT 0,
          ai_count INTEGER DEFAULT 0,
          fallback_count INTEGER DEFAULT 0,
          suspicious_count INTEGER DEFAULT 0,
          error_count INTEGER DEFAULT 0,
          ministry_counts TEXT DEFAULT '{}',
          category_counts TEXT DEFAULT '{}',
          error TEXT DEFAULT ''
        );
        CREATE INDEX IF NOT EXISTS idx_governance_runs_ts ON governance_runs(started_ts DESC);

        -- 每封邮件的自动裁决轨迹。evidence 只含布尔/枚举信号，不含原文。
        CREATE TABLE IF NOT EXISTS governance_decisions(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          run_id INTEGER NOT NULL REFERENCES governance_runs(id) ON DELETE CASCADE,
          message_id INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
          ts REAL NOT NULL,
          version TEXT NOT NULL,
          ministry TEXT NOT NULL,
          category_before TEXT DEFAULT '',
          category_after TEXT NOT NULL,
          confidence REAL DEFAULT 0,
          source TEXT DEFAULT '',
          fallback INTEGER DEFAULT 0,
          reason TEXT DEFAULT '',
          evidence TEXT DEFAULT '{}',
          UNIQUE(run_id, message_id)
        );
        CREATE INDEX IF NOT EXISTS idx_governance_decisions_message
          ON governance_decisions(message_id, ts DESC);

        -- 管理员会话：只保存令牌的 SHA-256，可逐个吊销
        CREATE TABLE IF NOT EXISTS sessions(
          id_hash TEXT PRIMARY KEY,
          created_ts REAL NOT NULL,
          expires_ts REAL NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_sessions_expiry ON sessions(expires_ts);
    """.trimIndent()

    /**
     * 对应 `db.py` 的 `MIGRATIONS`：增量迁移，旧库补列。
     *
     * 新库执行时每一条都会以「列已存在」失败，`Db.initDb()` 只忽略这种错误，
     * 其余错误照常抛出（与 Python 一致）。
     */
    val MIGRATIONS: List<String> = listOf(
        "ALTER TABLE messages ADD COLUMN to_addr TEXT DEFAULT ''",
        // 软删除：置为删除时间戳而非物理删除，回收站可恢复
        "ALTER TABLE messages ADD COLUMN deleted_ts REAL DEFAULT 0",
        // 安全扫描结果
        "ALTER TABLE messages ADD COLUMN risk_level TEXT DEFAULT 'none'",
        "ALTER TABLE messages ADD COLUMN risk_reasons TEXT DEFAULT ''",
        // AI 判定的置信度与人工复核标记
        "ALTER TABLE messages ADD COLUMN confidence REAL DEFAULT 0",
        "ALTER TABLE messages ADD COLUMN needs_review INTEGER DEFAULT 0",
        "ALTER TABLE messages ADD COLUMN ai_reason TEXT DEFAULT ''",
        // 自动三省六部治理：只写标签、摘要与审计元数据，不执行外部动作
        "ALTER TABLE messages ADD COLUMN governance_version TEXT DEFAULT ''",
        "ALTER TABLE messages ADD COLUMN governance_ministry TEXT DEFAULT ''",
        "ALTER TABLE messages ADD COLUMN governance_action TEXT DEFAULT ''",
        "ALTER TABLE messages ADD COLUMN governance_source TEXT DEFAULT ''",
        "ALTER TABLE messages ADD COLUMN governance_reason TEXT DEFAULT ''",
        "ALTER TABLE messages ADD COLUMN governance_ts REAL DEFAULT 0",
        // 供 Message-ID 去重（跨账户/转发场景）
        "CREATE INDEX IF NOT EXISTS idx_msg_msgid ON messages(msg_id)",
        "CREATE INDEX IF NOT EXISTS idx_msg_deleted ON messages(deleted_ts)",
        "CREATE INDEX IF NOT EXISTS idx_msg_governance ON messages(governance_version, deleted_ts)",
        // AI 临时失败后的自动重试（次数与下次重试时间）
        "ALTER TABLE messages ADD COLUMN ai_retry_count INTEGER DEFAULT 0",
        "ALTER TABLE messages ADD COLUMN ai_retry_ts REAL DEFAULT 0",
        // 邮件头群发信号（只含布尔值的 JSON），供规则与 AI 判定
        "ALTER TABLE messages ADD COLUMN header_signals TEXT DEFAULT '{}'",
        // 重要/安全推送在治理裁决后发出；非 0 表示已排队推送，避免重复
        "ALTER TABLE messages ADD COLUMN alert_ts REAL DEFAULT 0",
        // 账单分级：同类模板键、等级（重要/一般）、交易画像（只含枚举与金额）、用户手动锁定
        "ALTER TABLE messages ADD COLUMN template_key TEXT DEFAULT ''",
        "ALTER TABLE messages ADD COLUMN bill_tier TEXT DEFAULT ''",
        "ALTER TABLE messages ADD COLUMN bill_facets TEXT DEFAULT '{}'",
        "ALTER TABLE messages ADD COLUMN bill_tier_locked INTEGER DEFAULT 0",
        "CREATE INDEX IF NOT EXISTS idx_msg_template ON messages(template_key, date_ts)",
        // 用户对「同类账单」的分级偏好，按模板键保存
        """
        CREATE TABLE IF NOT EXISTS bill_prefs(
                 template_key TEXT PRIMARY KEY,
                 tier TEXT NOT NULL,
                 created_ts REAL NOT NULL
               )
        """.trimIndent(),
        "ALTER TABLE sessions ADD COLUMN label TEXT DEFAULT ''",
        "ALTER TABLE sessions ADD COLUMN client TEXT DEFAULT 'web'",
        "ALTER TABLE sessions ADD COLUMN last_seen_ts REAL DEFAULT 0",
        // 移动端游标分页与增量变更：收件箱列表按 (date_ts DESC, id DESC) 翻页
        "CREATE INDEX IF NOT EXISTS idx_msg_deleted_date ON messages(deleted_ts, date_ts DESC, id DESC)",
        // 大邮件保护：正文只存了头部和开头的 1MB 时置 1，提示用户到网页邮箱看原文
        "ALTER TABLE messages ADD COLUMN body_truncated INTEGER DEFAULT 0",
        // 已读状态双向同步的待推送标记：1 = 要在服务器上标为已读，-1 = 标为未读，0 = 无
        "ALTER TABLE messages ADD COLUMN flag_push INTEGER DEFAULT 0",
    )

    /**
     * 对应 `db.py` 的 `_fts_statements()`：FTS5 三元组索引表与三个同步触发器
     * （官方 external content 写法，可重复执行）。
     *
     * 与 Python 一致，这里不索引体积大的 `body_text`：原来的搜索也不含正文。
     * trigram 默认折叠 ASCII 大小写，与 LIKE 一致。
     */
    val FTS_STATEMENTS: List<String> = listOf(
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
                     subject, sender_name, sender_addr, snippet,
                     content='messages', content_rowid='id', tokenize='trigram'
                   )
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS messages_fts_ai AFTER INSERT ON messages BEGIN
                     INSERT INTO messages_fts(rowid, subject, sender_name, sender_addr, snippet)
                     VALUES(new.id, new.subject, new.sender_name, new.sender_addr, new.snippet);
                   END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS messages_fts_ad AFTER DELETE ON messages BEGIN
                     INSERT INTO messages_fts(messages_fts, rowid, subject, sender_name, sender_addr, snippet)
                     VALUES('delete', old.id, old.subject, old.sender_name, old.sender_addr, old.snippet);
                   END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS messages_fts_au
                   AFTER UPDATE OF subject, sender_name, sender_addr, snippet ON messages BEGIN
                     INSERT INTO messages_fts(messages_fts, rowid, subject, sender_name, sender_addr, snippet)
                     VALUES('delete', old.id, old.subject, old.sender_name, old.sender_addr, old.snippet);
                     INSERT INTO messages_fts(rowid, subject, sender_name, sender_addr, snippet)
                     VALUES(new.id, new.subject, new.sender_name, new.sender_addr, new.snippet);
                   END
        """.trimIndent(),
    )

    /**
     * 对应 `oauth_auth.py` 的 `init_oauth_db()` 里的建表与建索引语句：OAuth 登录的临时事务表。
     *
     * `app.py` 的账户读写、`oauth_auth.py` 的登录流程都依赖这张表，所以本机库必须与服务器
     * 一起建出来。注意 `CREATE TABLE` 的原文里，列名缩进 8 个空格、结尾的 `)` 只缩进 4 个空格
     * （`sqlite_master` 保存原文，金标逐字比较）；`trimIndent()` 表达不了比基准更小的缩进，
     * 所以最后那一行用字符串拼接补上。
     */
    val OAUTH_SCHEMA: String =
        """
        CREATE TABLE IF NOT EXISTS oauth_transactions(
                id TEXT PRIMARY KEY,
                state_hash TEXT DEFAULT '',
                provider TEXT NOT NULL,
                flow TEXT NOT NULL,
                email TEXT NOT NULL,
                display_name TEXT DEFAULT '',
                color TEXT DEFAULT '#38bdf8',
                sync_interval INTEGER DEFAULT 300,
                account_id INTEGER DEFAULT 0,
                client_id TEXT DEFAULT '',
                scope TEXT DEFAULT '',
                redirect_uri TEXT DEFAULT '',
                verifier_enc TEXT DEFAULT '',
                device_code_enc TEXT DEFAULT '',
                expires_ts REAL NOT NULL,
                next_poll_ts REAL DEFAULT 0,
                oauth_interval INTEGER DEFAULT 5,
                created_ts REAL NOT NULL
        """.trimIndent() + "\n    );\n" + """
        CREATE UNIQUE INDEX IF NOT EXISTS idx_oauth_state ON oauth_transactions(state_hash) WHERE state_hash != '';
        CREATE INDEX IF NOT EXISTS idx_oauth_expiry ON oauth_transactions(expires_ts);
        """.trimIndent()

    /**
     * 对应 `init_oauth_db()` 里给 `accounts` 补的三列。
     *
     * `app.py` 的账户 SQL（读账户、写 OAuth 客户端、标「需要重新授权」）依赖这三列；
     * 新库执行时每一条都会以「列已存在」失败，[Db.initDb] 只忽略这种错误。
     */
    val OAUTH_ACCOUNT_COLUMNS: List<String> = listOf(
        "ALTER TABLE accounts ADD COLUMN oauth_client_id TEXT DEFAULT ''",
        "ALTER TABLE accounts ADD COLUMN oauth_scope TEXT DEFAULT ''",
        "ALTER TABLE accounts ADD COLUMN oauth_reauth_required INTEGER DEFAULT 0",
    )

    /**
     * 对应 `init_oauth_db()` 里给 `oauth_transactions` 补的四列。
     *
     * 服务器是「取出即删」改成「保留到过期」时加的列，旧库升级要用；新库已包含，只会报
     * 「列已存在」。
     */
    val OAUTH_TRANSACTION_COLUMNS: List<String> = listOf(
        "ALTER TABLE oauth_transactions ADD COLUMN status TEXT DEFAULT 'pending'",
        "ALTER TABLE oauth_transactions ADD COLUMN result_account_id INTEGER DEFAULT 0",
        "ALTER TABLE oauth_transactions ADD COLUMN result_message TEXT DEFAULT ''",
        "ALTER TABLE oauth_transactions ADD COLUMN client TEXT DEFAULT 'web'",
    )

    /**
     * 对应 `init_oauth_db()` 末尾调的 `_require_reauth_for_borrowed_identities()`：旧版借用了
     * Thunderbird / 网易的 Client ID 登录的账户，标记为「需要重新授权」。两个参数依次是
     * [OAUTH_REAUTH_MESSAGE] 与被拒绝的网易 appid。
     *
     * 本机新建的库里没有这类账户，语句是空操作；保留它只为与服务器的迁移保持一致（P2 接 OAuth
     * 时不用再补一遍）。
     */
    val OAUTH_REAUTH_SQL: String =
        """UPDATE accounts SET oauth_reauth_required=1, last_error=?
           WHERE auth_type='oauth' AND oauth_reauth_required=0
             AND ((provider='outlook' AND COALESCE(oauth_client_id, '')='')
                  OR oauth_client_id=?)""".trimIndent()

    /** [OAUTH_REAUTH_SQL] 里写进 `accounts.last_error` 的说明。 */
    const val OAUTH_REAUTH_MESSAGE = "该账户使用的是已停用的借用登录身份，请配置自有 OAuth 客户端后重新登录"

    /** 旧版网易 outlook 通道复用的 Outlook 合作方 appid（`oauth_auth._OUTLOOK_NETEASE_APP_ID`）。 */
    const val OAUTH_BORROWED_CLIENT_ID = "rjg1fubwqzie5unhx6"
}
