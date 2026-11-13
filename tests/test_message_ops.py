"""message_ops 模块的公开函数：策略闸门、本地删除、恢复、撤销、审计。

只测模块本身，不经过路由；夹具与辅助函数照 tests/test_message_actions.py 的写法，
全部使用合成数据与临时 SQLite 库。

最要紧的两条约束在这里各自钉一遍：
  - 「不可逆的服务器动作，服务器确认成功之后才更新本地状态」：
    finish_removal("purge", ...) 不带 server_confirmed 时抛 ValueError 且一行都不删；
  - 只删服务器确认过的那些 id（部分成功时剩下的本地行必须留着）。
"""
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.environ.setdefault("MAILHUB_SECRET", "test-secret-for-unit-tests-only")
# 必须在 import db 之前指定，db 模块在导入时读取该路径
_TMP = tempfile.mkdtemp()
os.environ["MAILHUB_DB"] = os.path.join(_TMP, "test.db")

import json  # noqa: E402
import time  # noqa: E402

import pytest  # noqa: E402

import db  # noqa: E402
import message_ops  # noqa: E402

# 固定时间戳（2026-05 附近），避免依赖真实时钟
_NOW = 2_000_000_000.0


@pytest.fixture(autouse=True)
def fresh_db():
    """每个用例一张干净的表。"""
    db.init_db()
    conn = db.get_conn()
    for t in ("governance_decisions", "governance_runs", "messages", "accounts",
              "audit_log", "aliases", "rules", "bill_prefs"):
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


def _insert_msg(conn, acc_id, uid, category="通知", **kw):
    conn.execute(
        """INSERT OR IGNORE INTO messages
           (account_id, uid, msg_id, subject, sender_addr, date_ts, category,
            has_attach, importance, unread, bill_tier, deleted_ts)
           VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
        (acc_id, uid, "", kw.get("subject", "s"), kw.get("sender", "x@y.com"),
         kw.get("date_ts", 1000.0), category, kw.get("has_attach", 0),
         kw.get("importance", 0), kw.get("unread", 1),
         kw.get("bill_tier", ""), kw.get("deleted_ts", 0)))
    conn.commit()
    row = conn.execute("SELECT id FROM messages WHERE account_id=? AND uid=?",
                       (acc_id, uid)).fetchone()
    return row["id"] if row else None


def _exists(conn, mid):
    return conn.execute("SELECT id FROM messages WHERE id=?", (mid,)).fetchone() is not None


def _deleted_ts(conn, mid):
    return conn.execute("SELECT deleted_ts FROM messages WHERE id=?",
                        (mid,)).fetchone()["deleted_ts"]


def _flag_push(conn, mid):
    return conn.execute("SELECT flag_push FROM messages WHERE id=?",
                        (mid,)).fetchone()["flag_push"]


def _unread(conn, mid):
    return conn.execute("SELECT unread FROM messages WHERE id=?",
                        (mid,)).fetchone()["unread"]


def _msg_row(conn, mid):
    """按 _load_for_policy 的列取一行，供 gate 之类的纯函数使用。"""
    return dict(conn.execute(
        """SELECT id, category, bill_tier, has_attach, importance, sender_addr,
                  unread, deleted_ts
           FROM messages WHERE id=?""", (mid,)).fetchone())


def _audit_row(conn, audit_id):
    return conn.execute("SELECT * FROM audit_log WHERE id=?", (audit_id,)).fetchone()


# ---------------- finish_removal ----------------

def test_purge_without_server_confirmation_raises_and_changes_nothing(fresh_db):
    """没传 server_confirmed 就是没有服务器确认：抛 ValueError，本地一行不动"""
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 1)

    with pytest.raises(ValueError):
        message_ops.finish_removal(fresh_db, "purge", [mid])

    assert _exists(fresh_db, mid)


def test_purge_deletes_only_server_confirmed_ids(fresh_db):
    """部分成功：只删服务器确认的那封，另一封留在本地"""
    acc = _mk_account(fresh_db)
    confirmed = _insert_msg(fresh_db, acc, 1)
    failed = _insert_msg(fresh_db, acc, 2)

    handled, undo_payload = message_ops.finish_removal(
        fresh_db, "purge", [confirmed, failed], server_confirmed=[confirmed])

    assert handled == [confirmed]
    assert undo_payload is None
    assert not _exists(fresh_db, confirmed)
    assert _exists(fresh_db, failed)


def test_purge_with_empty_confirmation_executes_no_sql(fresh_db):
    """服务器一封都没成功：不删任何行，返回值与撤销载荷照旧"""
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 1)

    assert message_ops.finish_removal(
        fresh_db, "purge", [mid], server_confirmed=[]) == ([], None)
    assert _exists(fresh_db, mid)


def test_soft_delete_sets_deleted_ts_and_returns_payload(fresh_db, monkeypatch):
    """软删除：deleted_ts 置为当前时间，撤销载荷带上这些 id"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    first = _insert_msg(fresh_db, acc, 1)
    second = _insert_msg(fresh_db, acc, 2)

    handled, undo_payload = message_ops.finish_removal(
        fresh_db, "soft_delete", [first, second])

    assert handled == [first, second]
    assert undo_payload == {"ids": [first, second]}
    assert _deleted_ts(fresh_db, first) == _NOW
    assert _deleted_ts(fresh_db, second) == _NOW


def test_soft_delete_honours_explicit_ts(fresh_db):
    """显式传 ts 时不用当前时间（C1c 的自动清理要按同一个截止时间写）"""
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 1)

    message_ops.finish_removal(fresh_db, "soft_delete", [mid], ts=123.5)

    assert _deleted_ts(fresh_db, mid) == 123.5


def test_soft_delete_with_empty_ids_executes_no_sql(fresh_db):
    """空列表：不执行 SQL，载荷里是空 ids"""
    assert message_ops.finish_removal(fresh_db, "soft_delete", []) == ([], {"ids": []})


def test_finish_removal_rejects_unknown_action(fresh_db):
    """未定义的动作一律拒绝，避免调用方以为已经改了本地状态"""
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 1)

    with pytest.raises(ValueError):
        message_ops.finish_removal(fresh_db, "archive", [mid])

    assert _exists(fresh_db, mid)


# ---------------- gate ----------------

def test_gate_keeps_input_order_and_marks_protected(fresh_db):
    """放行与拦截两个列表都按输入顺序；拦截项带 id / reason / protected"""
    acc = _mk_account(fresh_db)
    plain = _insert_msg(fresh_db, acc, 1, category="通知")
    bill = _insert_msg(fresh_db, acc, 2, category="账单")
    attached = _insert_msg(fresh_db, acc, 3, category="通知", has_attach=1)
    # 输入顺序故意打乱（账单在前、普通邮件在中间）
    msgs = [_msg_row(fresh_db, bill), _msg_row(fresh_db, plain), _msg_row(fresh_db, attached)]

    allowed, blocked = message_ops.gate("soft_delete", msgs, actor="user")

    assert [m["id"] for m in allowed] == [plain]
    assert [b["id"] for b in blocked] == [bill, attached]
    assert set(blocked[0]) == {"id", "reason", "protected"}
    assert blocked[0]["protected"] is True
    assert blocked[0]["reason"] == "分类「账单」属于受保护类别（交易/安全/重要），禁止自动删除"
    assert blocked[1]["protected"] is True
    assert blocked[1]["reason"] == "邮件含附件，可能是发票或合同，禁止自动删除"


def test_gate_only_flags_protected_for_destructive_actions(fresh_db):
    """protected 只对「会让邮件离开收件箱」的动作有意义。

    这里用 send：它不在 DESTRUCTIVE_ACTIONS 里，所以受保护邮件虽然照样被
    C 类确认门槛拦下，拦截项里的 protected 必须是 False。
    """
    acc = _mk_account(fresh_db)
    bill = _insert_msg(fresh_db, acc, 1, category="账单")

    allowed, blocked = message_ops.gate(
        "send", [_msg_row(fresh_db, bill)], actor="user")

    assert allowed == []
    assert blocked == [{"id": bill,
                        "reason": "动作「send」属 C 类（不可逆/对外），必须人工确认",
                        "protected": False}]


def test_gate_unconfirmed_purge_is_blocked_without_protected_mark(fresh_db):
    """C 类未确认：被拦截，但这封邮件本身不受保护，protected 为 False"""
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 1, category="订阅")

    allowed, blocked = message_ops.gate("purge", [_msg_row(fresh_db, mid)], actor="user")

    assert allowed == []
    assert blocked == [{"id": mid,
                        "reason": "动作「purge」属 C 类（不可逆/对外），必须人工确认",
                        "protected": False}]


def test_gate_override_releases_protected_mail_for_first_message(fresh_db):
    """单封二次确认（confirmed + override_protection）可以带走受保护邮件"""
    acc = _mk_account(fresh_db)
    bill = _insert_msg(fresh_db, acc, 1, category="账单")

    allowed, blocked = message_ops.gate(
        "soft_delete", [_msg_row(fresh_db, bill)], actor="user", confirmed=True,
        override_protection=True)

    assert blocked == []
    assert [m["id"] for m in allowed] == [bill]


def test_gate_system_actor_needs_user_rule(fresh_db):
    """system 侧（自动清理）：没开长期规则时 B 类动作按现有口径放行，
    但受保护邮件照样拦下来，且不受 confirmed 影响。"""
    acc = _mk_account(fresh_db)
    plain = _insert_msg(fresh_db, acc, 1, category="验证码")
    attached = _insert_msg(fresh_db, acc, 2, category="验证码", has_attach=1)

    allowed, blocked = message_ops.gate(
        "soft_delete", [_msg_row(fresh_db, plain), _msg_row(fresh_db, attached)],
        actor="system", confirmed=True)

    assert [m["id"] for m in allowed] == [plain]
    assert [b["id"] for b in blocked] == [attached]


# ---------------- record_action ----------------

def test_record_action_defaults_allowed_to_bool_ids(fresh_db):
    """allowed 没传时取 bool(ids)：有目标就是允许，全失败就是不允许"""
    audit_id = message_ops.record_action(
        "soft_delete", actor="user", ids=[1, 2], reason="用户批量操作",
        undo_payload={"ids": [1, 2]})
    row = _audit_row(fresh_db, audit_id)
    assert row["action"] == "soft_delete"
    assert row["actor"] == "user"
    assert row["tier"] == "B"
    assert row["target_count"] == 2
    assert json.loads(row["target_ids"]) == [1, 2]
    assert row["reason"] == "用户批量操作"
    assert row["allowed"] == 1
    assert row["reversible"] == 1
    assert json.loads(row["undo_payload"]) == {"ids": [1, 2]}

    empty_id = message_ops.record_action(
        "purge", actor="user", ids=[], reason="批量清理分类「验证码」；1 个账户执行失败",
        undo_payload=None)
    row = _audit_row(fresh_db, empty_id)
    assert row["tier"] == "C"
    assert row["allowed"] == 0
    assert row["reversible"] == 0
    assert row["undo_payload"] == ""


def test_record_action_honours_explicit_allowed(fresh_db):
    """显式传 allowed 时以调用方为准（含与 bool(ids) 相反的组合）"""
    denied = message_ops.record_action(
        "soft_delete", actor="user", ids=[7], reason="测试", undo_payload=None,
        allowed=False)
    row = _audit_row(fresh_db, denied)
    assert row["allowed"] == 0 and row["reversible"] == 0

    granted = message_ops.record_action(
        "read", actor="user", ids=[], reason="测试", undo_payload={"unread": {}},
        allowed=True)
    row = _audit_row(fresh_db, granted)
    assert row["tier"] == "A"
    assert row["allowed"] == 1
    assert row["reversible"] == 1


# ---------------- load_for_policy / register_flag_push ----------------

def test_load_for_policy_skips_trash_and_empty_ids(fresh_db):
    """回收站里的邮件不算；空 id 列表返回 []"""
    acc = _mk_account(fresh_db)
    inbox = _insert_msg(fresh_db, acc, 1)
    trashed = _insert_msg(fresh_db, acc, 2, deleted_ts=_NOW - 10)

    rows = message_ops.load_for_policy(fresh_db, [inbox, trashed])

    assert [r["id"] for r in rows] == [inbox]
    assert set(rows[0]) == {"id", "category", "bill_tier", "has_attach", "importance",
                            "sender_addr", "unread", "deleted_ts"}
    assert message_ops.load_for_policy(fresh_db, []) == []


def test_register_flag_push_returns_sorted_unique_accounts(fresh_db):
    """返回涉及的账户 id（去重、升序），并按方向改本地未读状态与推送标记"""
    first_acc = _mk_account(fresh_db, "a1", "one@example.com")
    second_acc = _mk_account(fresh_db, "a2", "two@example.com")
    a = _insert_msg(fresh_db, second_acc, 1, unread=1)
    b = _insert_msg(fresh_db, second_acc, 2, unread=1)
    c = _insert_msg(fresh_db, first_acc, 3, unread=1)

    accounts = message_ops.register_flag_push(fresh_db, [a, b, c], 1)

    assert accounts == [first_acc, second_acc]
    assert _unread(fresh_db, a) == 0 and _flag_push(fresh_db, a) == 1
    assert _unread(fresh_db, c) == 0 and _flag_push(fresh_db, c) == 1
    # 反向：标为未读，登记 -1
    message_ops.register_flag_push(fresh_db, [a], -1)
    assert _unread(fresh_db, a) == 1 and _flag_push(fresh_db, a) == -1
    assert message_ops.register_flag_push(fresh_db, [], 1) == []


# ---------------- restore ----------------

def test_restore_returns_rowcount_and_clears_deleted_ts(fresh_db):
    """恢复返回实际命中的行数；不存在的 id 不计入"""
    acc = _mk_account(fresh_db)
    trashed = _insert_msg(fresh_db, acc, 1, deleted_ts=_NOW - 10)
    inbox = _insert_msg(fresh_db, acc, 2)

    assert message_ops.restore(fresh_db, [trashed, inbox, 999999]) == 2
    assert _deleted_ts(fresh_db, trashed) == 0
    assert _deleted_ts(fresh_db, inbox) == 0


# ---------------- undo ----------------

def test_undo_soft_delete_restores_rows_and_marks_audit_undone(fresh_db):
    """撤销软删除：邮件回收件箱，这条审计标为已撤销，不写新审计"""
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 1, deleted_ts=_NOW - 10)
    audit_id = db.audit("soft_delete", actor="user", tier="B", target_ids=[mid],
                        reason="用户批量操作", allowed=True, reversible=True,
                        undo_payload={"ids": [mid]})

    restored, accounts = message_ops.undo(
        fresh_db, _audit_row(fresh_db, audit_id), push_read_state=False)

    assert restored == 1
    assert accounts == []
    assert _deleted_ts(fresh_db, mid) == 0
    assert _audit_row(fresh_db, audit_id)["undone"] == 1
    assert fresh_db.execute("SELECT COUNT(*) c FROM audit_log").fetchone()["c"] == 1


def test_undo_read_state_registers_push_in_both_directions(fresh_db):
    """撤销已读/未读：按恢复后的状态登记待推送（恢复成已读 1、恢复成未读 -1）"""
    acc = _mk_account(fresh_db)
    was_read = _insert_msg(fresh_db, acc, 1, unread=0)
    was_unread = _insert_msg(fresh_db, acc, 2, unread=0)
    payload = {"unread": {str(was_read): 0, str(was_unread): 1}}
    audit_id = db.audit("read", actor="user", tier="A",
                        target_ids=[was_read, was_unread], reason="用户批量操作",
                        allowed=True, reversible=True, undo_payload=payload)

    restored, accounts = message_ops.undo(
        fresh_db, _audit_row(fresh_db, audit_id), push_read_state=True)

    assert restored == 2
    # 两组各登记一次，同一账户会出现两次；触发同步的调用方会去重
    assert set(accounts) == {acc}
    # 恢复成已读：本地 0、待推送 1
    assert _unread(fresh_db, was_read) == 0 and _flag_push(fresh_db, was_read) == 1
    # 恢复成未读：本地 1、待推送 -1
    assert _unread(fresh_db, was_unread) == 1 and _flag_push(fresh_db, was_unread) == -1
    assert _audit_row(fresh_db, audit_id)["undone"] == 1


def test_undo_without_push_leaves_flags_alone(fresh_db):
    """push_read_state=False（开关关闭）时只改本地未读状态，不登记待推送"""
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 1, unread=0)
    audit_id = db.audit("unread", actor="user", tier="A", target_ids=[mid],
                        reason="用户批量操作", allowed=True, reversible=True,
                        undo_payload={"unread": {str(mid): 1}})

    restored, accounts = message_ops.undo(
        fresh_db, _audit_row(fresh_db, audit_id), push_read_state=False)

    assert restored == 1
    assert accounts == []
    assert _unread(fresh_db, mid) == 1
    assert _flag_push(fresh_db, mid) == 0
