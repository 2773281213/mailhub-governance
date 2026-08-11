"""SQLite 数据层：线程本地连接、建表、settings 读写"""
import os
import sqlite3
import threading
import time

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.environ.get("MAILHUB_DB", os.path.join(BASE_DIR, "data", "mailhub.db"))

_local = threading.local()

SCHEMA = """
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
"""

# 增量迁移：旧库补列（新库建表语句里已包含则忽略报错）
MIGRATIONS = [
    "ALTER TABLE messages ADD COLUMN to_addr TEXT DEFAULT ''",
    # 软删除：置为删除时间戳而非物理删除，回收站可恢复
    "ALTER TABLE messages ADD COLUMN deleted_ts REAL DEFAULT 0",
    # 安全扫描结果
    "ALTER TABLE messages ADD COLUMN risk_level TEXT DEFAULT 'none'",
    "ALTER TABLE messages ADD COLUMN risk_reasons TEXT DEFAULT ''",
    # AI 判定的置信度与人工复核标记
    "ALTER TABLE messages ADD COLUMN confidence REAL DEFAULT 0",
    "ALTER TABLE messages ADD COLUMN needs_review INTEGER DEFAULT 0",
    "ALTER TABLE messages ADD COLUMN ai_reason TEXT DEFAULT ''",
    # 自动三省六部治理：只写标签、摘要与审计元数据，不执行外部动作
    "ALTER TABLE messages ADD COLUMN governance_version TEXT DEFAULT ''",
    "ALTER TABLE messages ADD COLUMN governance_ministry TEXT DEFAULT ''",
    "ALTER TABLE messages ADD COLUMN governance_action TEXT DEFAULT ''",
    "ALTER TABLE messages ADD COLUMN governance_source TEXT DEFAULT ''",
    "ALTER TABLE messages ADD COLUMN governance_reason TEXT DEFAULT ''",
    "ALTER TABLE messages ADD COLUMN governance_ts REAL DEFAULT 0",
    # 供 Message-ID 去重（跨账户/转发场景）
    "CREATE INDEX IF NOT EXISTS idx_msg_msgid ON messages(msg_id)",
    "CREATE INDEX IF NOT EXISTS idx_msg_deleted ON messages(deleted_ts)",
    "CREATE INDEX IF NOT EXISTS idx_msg_governance ON messages(governance_version, deleted_ts)",
]


def get_conn() -> sqlite3.Connection:
    conn = getattr(_local, "conn", None)
    if conn is None:
        os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
        conn = sqlite3.connect(DB_PATH, timeout=30)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA synchronous=NORMAL")
        conn.execute("PRAGMA foreign_keys=ON")
        _local.conn = conn
    return conn


def init_db():
    conn = get_conn()
    conn.executescript(SCHEMA)
    for sql in MIGRATIONS:
        try:
            conn.execute(sql)
        except sqlite3.OperationalError as exc:
            # 只忽略预期的重复列。权限、损坏或 SQL 错误必须在启动时立即暴露，
            # 不能等到治理运行时才以“缺列”形式晚失败。
            if "duplicate column name" not in str(exc).lower():
                raise
    conn.commit()


def get_setting(key: str, default: str = "") -> str:
    row = get_conn().execute("SELECT v FROM settings WHERE k=?", (key,)).fetchone()
    return row["v"] if row else default


def set_setting(key: str, value: str):
    conn = get_conn()
    conn.execute(
        "INSERT INTO settings(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v",
        (key, str(value)),
    )
    conn.commit()


def now() -> float:
    return time.time()


# ---------- 审计 ----------

def audit(action: str, *, actor: str = "system", tier: str = "",
          target_ids=None, reason: str = "", allowed: bool = True,
          reversible: bool = False, undo_payload=None) -> int:
    """写一条审计记录，返回其 id。任何改变邮件状态的动作都应调用（含被拦截的）。"""
    import json as _json
    ids = list(target_ids or [])
    conn = get_conn()
    cur = conn.execute(
        """INSERT INTO audit_log(ts, actor, action, tier, target_count, target_ids,
                                 reason, allowed, reversible, undone, undo_payload)
           VALUES(?,?,?,?,?,?,?,?,?,0,?)""",
        (time.time(), actor, action, tier, len(ids), _json.dumps(ids[:2000]),
         reason[:500], int(allowed), int(reversible),
         _json.dumps(undo_payload) if undo_payload is not None else ""),
    )
    conn.commit()
    return cur.lastrowid
