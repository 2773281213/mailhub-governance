"""大批量清理回归测试：id 列表必须分块，不能撞上 SQLite 的绑定参数上限

SQLite 一条语句最多 32766 个绑定参数（本项目实测 32767 个就报
`too many SQL variables`）。一个分类里有几万封可清理邮件是常见情况，
所以 `message_ops` 与 `sync.apply_server_action` 的 `id IN (...)` 都要分块执行。

用临时 SQLite 库，全部合成数据；批量插入走 executemany，只在最后 commit 一次。
"""
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.environ.setdefault("MAILHUB_SECRET", "test-secret-for-unit-tests-only")
# 必须在 import db 之前指定，db 模块在导入时读取该路径
_TMP = tempfile.mkdtemp()
os.environ["MAILHUB_DB"] = os.path.join(_TMP, "test.db")

import asyncio  # noqa: E402
import json  # noqa: E402

import pytest  # noqa: E402

import db  # noqa: E402
import app as mailhub_app  # noqa: E402
import mail_client  # noqa: E402
import message_ops  # noqa: E402
import sync  # noqa: E402

# 需要超过 SQLite 一条语句的绑定参数上限（32766）才能复现问题
BULK = 33000


@pytest.fixture(autouse=True)
def fresh_db():
    """每个用例一张干净的表"""
    db.init_db()
    conn = db.get_conn()
    for t in ("governance_decisions", "governance_runs", "messages", "accounts",
              "audit_log", "aliases", "rules"):
        conn.execute(f"DELETE FROM {t}")
    conn.commit()
    yield conn


def _mk_account(conn, name="acc", email="a@example.com"):
    cur = conn.execute(
        """INSERT INTO accounts(name, provider, email, imap_host, imap_port,
                                auth_type, enabled, created_ts)
           VALUES(?,?,?,?,?,?,1,0)""",
        (name, "custom", email, "imap.example.com", 993, "password"))
    conn.commit()
    return cur.lastrowid


def _insert_bulk(conn, acc_id, n=BULK, category="通知"):
    """一次 executemany 插完 n 封邮件，只在最后 commit 一次；返回按 id 升序的 id 列表。

    本机实测：FTS 插入触发器逐行执行，33000 行要 5 秒以上，占满了整个用例的时间。
    所以这里先摘掉触发器、一次性插完后再用一条语句补齐三元组索引，最后在 finally
    里恢复原样的触发器。索引内容与触发器逐行写入完全一致，其他用例不受影响。
    """
    insert_trigger = db._fts_statements()[1]
    has_fts = db.fts_search_available()
    if has_fts:
        conn.execute("DROP TRIGGER IF EXISTS messages_fts_ai")
    try:
        conn.executemany(
            """INSERT INTO messages
               (account_id, uid, msg_id, subject, sender_addr, date_ts, category,
                has_attach, importance, deleted_ts)
               VALUES(?,?,?,?,?,?,?,0,0,0)""",
            [(acc_id, uid, f"<bulk{uid}@x>", "s", "x@y.com", 1000.0, category)
             for uid in range(1, n + 1)],
        )
        if has_fts:
            conn.execute(
                """INSERT INTO messages_fts(rowid, subject, sender_name, sender_addr, snippet)
                   SELECT id, subject, sender_name, sender_addr, snippet FROM messages""")
        conn.commit()
    finally:
        if has_fts:
            conn.execute(insert_trigger)
            conn.commit()
    return [r["id"] for r in conn.execute(
        "SELECT id FROM messages WHERE account_id=? ORDER BY id", (acc_id,)).fetchall()]


# ---------------- 1. 软删除三万三千封 ----------------

def test_clean_soft_delete_handles_more_than_parameter_limit(fresh_db):
    acc = _mk_account(fresh_db)
    ids = _insert_bulk(fresh_db, acc)

    result = asyncio.run(mailhub_app.clean_messages(mailhub_app.CleanBody(
        category="通知", confirmed=True,
    )))

    assert result["ok"] is True
    assert result["count"] == BULK
    assert result["blocked"] == []
    deleted_ids = {r["id"] for r in fresh_db.execute(
        "SELECT id FROM messages WHERE deleted_ts > 0").fetchall()}
    assert deleted_ids == set(ids)
    audit_row = fresh_db.execute(
        "SELECT * FROM audit_log WHERE id=?", (result["audit_id"],)).fetchone()
    assert audit_row["action"] == "soft_delete"
    assert audit_row["target_count"] == BULK
    assert audit_row["reversible"] == 1


# ---------------- 2. 撤销：现状是只恢复前 2000 封 ----------------

def test_undo_after_large_clean_restores_only_first_2000(fresh_db):
    """已知限制（本任务不改）：审计只存前 2000 个 id，撤销也只处理前 2000 个。"""
    acc = _mk_account(fresh_db)
    ids = _insert_bulk(fresh_db, acc)
    result = asyncio.run(mailhub_app.clean_messages(mailhub_app.CleanBody(
        category="通知", confirmed=True,
    )))
    audit_id = result["audit_id"]

    audit_row = fresh_db.execute(
        "SELECT * FROM audit_log WHERE id=?", (audit_id,)).fetchone()
    # 审计的 target_ids 列被截到 2000 个，但撤销载荷是完整的 33000 个
    assert len(json.loads(audit_row["target_ids"])) == 2000
    assert len(json.loads(audit_row["undo_payload"])["ids"]) == BULK

    undo_result = asyncio.run(mailhub_app.undo_action(audit_id))

    # 现有行为：只恢复载荷里的前 2000 封（按 id 升序），其余仍在回收站
    assert undo_result == {"ok": True, "restored": 2000}
    assert fresh_db.execute(
        "SELECT COUNT(*) c FROM messages WHERE deleted_ts=0").fetchone()["c"] == 2000
    assert fresh_db.execute(
        "SELECT COUNT(*) c FROM messages WHERE deleted_ts>0").fetchone()["c"] == BULK - 2000
    restored_ids = {r["id"] for r in fresh_db.execute(
        "SELECT id FROM messages WHERE deleted_ts=0").fetchall()}
    assert restored_ids == set(ids[:2000])
    assert fresh_db.execute(
        "SELECT undone FROM audit_log WHERE id=?", (audit_id,)).fetchone()["undone"] == 1


# ---------------- 3. 服务器删除三万三千封 ----------------

def test_clean_server_purge_handles_more_than_parameter_limit(fresh_db, monkeypatch):
    acc = _mk_account(fresh_db)
    _insert_bulk(fresh_db, acc)
    monkeypatch.setattr(mailhub_app, "_server_op",
                        lambda ids, _action: (list(ids), []))

    result = asyncio.run(mailhub_app.clean_messages(mailhub_app.CleanBody(
        category="通知", server=True, confirmed=True,
    )))

    assert result["ok"] is True
    assert result["count"] == BULK
    assert fresh_db.execute("SELECT COUNT(*) c FROM messages").fetchone()["c"] == 0


# ---------------- 4. sync.apply_server_action 的分块查询 ----------------

class FakeSession:
    """记录 IMAP 调用的假会话；fail=True 时模拟服务器不可用。"""

    def __init__(self, calls, fail=False):
        self.calls = calls
        self.fail = fail

    def select_inbox(self):
        self.calls.append(("select",))
        return 1

    def delete(self, uids):
        if self.fail:
            raise RuntimeError("server unavailable")
        self.calls.append(("delete", sorted(uids)))

    def mark_read(self, uids, read=True):
        self.calls.append(("read" if read else "unread", sorted(uids)))

    def close(self):
        self.calls.append(("close",))


def _fake_imap(monkeypatch, fail=False):
    calls = []
    monkeypatch.setattr(mail_client, "open_session",
                        lambda _acc, _secret: FakeSession(calls, fail=fail))
    monkeypatch.setattr(sync, "resolve_secret", lambda _acc: "secret")
    return calls


def test_apply_server_action_handles_more_than_parameter_limit(fresh_db, monkeypatch):
    calls = _fake_imap(monkeypatch)
    acc = _mk_account(fresh_db)
    ids = _insert_bulk(fresh_db, acc)

    succeeded, errors = sync.apply_server_action(ids, "delete")

    assert errors == []
    assert len(succeeded) == BULK
    deleted_uids = [uid for call in calls if call[0] == "delete" for uid in call[1]]
    assert len(deleted_uids) == BULK
    assert calls[-1] == ("close",)


# ---------------- 5. 分块后结果顺序与去重不变 ----------------

def test_load_for_policy_keeps_id_order_and_dedupes(fresh_db):
    acc = _mk_account(fresh_db)
    ids = _insert_bulk(fresh_db, acc, n=10)
    shuffled = [ids[7], ids[2], ids[9], ids[0], ids[5], ids[3], ids[8], ids[1],
                ids[6], ids[4]]
    # 重复 id 现在只返回一行，改后也必须如此
    rows = message_ops.load_for_policy(fresh_db, shuffled + shuffled[:3])

    assert [r["id"] for r in rows] == ids
