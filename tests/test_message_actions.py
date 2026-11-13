"""删除邮件三个入口与回收站/撤销的现有行为。

覆盖四个公开入口：
  - app.clean_messages / app.batch_messages（批量清理与批量操作）
  - sync.auto_clean（定时自动清理）
  - app.restore_trash / app.undo_action（回收站与撤销）

夹具与辅助函数照 tests/test_pipeline.py 复制到本文件，不 import 其他测试文件；
全部使用合成数据与临时 SQLite 库，不连接真实网络或邮箱。

断言按当前实现的实际行为写死（响应整字典、审计逐列、拦截文案全文相等），
后续把这套流程搬进 message_ops.py 的重构必须让这些用例一字不改继续通过。
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
import time  # noqa: E402

import pytest  # noqa: E402

import db  # noqa: E402
import app as mailhub_app  # noqa: E402
import mail_client  # noqa: E402
import sync  # noqa: E402

# 需要固定时间的用例统一用的时间戳（2026-05 附近），避免依赖真实时钟
_NOW = 2_000_000_000.0
_DAY = 86400.0

# 本文件会改写的 settings 键；用例前后都删回「没有这一行」（读取默认 "0"）
_SETTING_KEYS = ("clean_otp_days", "clean_bill_minor_days", "clean_server",
                 "sync_read_state")


def _clear_setting_keys(conn):
    marks = ",".join("?" * len(_SETTING_KEYS))
    conn.execute(f"DELETE FROM settings WHERE k IN ({marks})", _SETTING_KEYS)


@pytest.fixture(autouse=True)
def fresh_db():
    """每个用例一张干净的表；动过的 settings 键在用例结束时恢复默认。"""
    db.init_db()
    conn = db.get_conn()
    for t in ("governance_decisions", "governance_runs", "messages", "accounts",
              "audit_log", "aliases", "rules", "bill_prefs"):
        conn.execute(f"DELETE FROM {t}")
    _clear_setting_keys(conn)
    conn.commit()
    yield conn
    _clear_setting_keys(conn)
    conn.commit()


def _mk_account(conn, name="acc", email="a@example.com"):
    cur = conn.execute(
        """INSERT INTO accounts(name, provider, email, imap_host, imap_port,
                                auth_type, enabled, created_ts)
           VALUES(?,?,?,?,?,?,1,0)""",
        (name, "custom", email, "imap.example.com", 993, "password"))
    conn.commit()
    return cur.lastrowid


def _insert_msg(conn, acc_id, uid, msg_id="", category="通知", **kw):
    conn.execute(
        """INSERT OR IGNORE INTO messages
           (account_id, uid, msg_id, subject, sender_addr, date_ts, category,
            has_attach, importance, unread, bill_tier, deleted_ts)
           VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
        (acc_id, uid, msg_id, kw.get("subject", "s"), kw.get("sender", "x@y.com"),
         kw.get("date_ts", 1000.0), category, kw.get("has_attach", 0),
         kw.get("importance", 0), kw.get("unread", 1),
         kw.get("bill_tier", ""), kw.get("deleted_ts", 0)))
    conn.commit()
    row = conn.execute("SELECT id FROM messages WHERE account_id=? AND uid=?",
                       (acc_id, uid)).fetchone()
    return row["id"] if row else None


def _deleted_ts(conn, mid):
    return conn.execute("SELECT deleted_ts FROM messages WHERE id=?",
                        (mid,)).fetchone()["deleted_ts"]


def _unread(conn, mid):
    return conn.execute("SELECT unread FROM messages WHERE id=?",
                        (mid,)).fetchone()["unread"]


def _audit_row(conn, audit_id):
    return dict(conn.execute("SELECT * FROM audit_log WHERE id=?", (audit_id,)).fetchone())


def _only_audit(conn):
    """断言当前只有一条审计，并返回它。"""
    rows = conn.execute("SELECT * FROM audit_log ORDER BY id").fetchall()
    assert len(rows) == 1
    return dict(rows[0])


def _last_audit(conn):
    """返回最后写入的那条审计（撤销会追加一条 undo 记录）。"""
    return dict(conn.execute(
        "SELECT * FROM audit_log ORDER BY id DESC LIMIT 1").fetchone())


def _audit_count(conn):
    return conn.execute("SELECT COUNT(*) c FROM audit_log").fetchone()["c"]


def _assert_audit(row, *, action, actor, tier, target_ids, reason, allowed, reversible,
                  undone=0, undo_payload=""):
    """逐列钉住一条审计记录；undo_payload 传 "" 表示必须为空串，否则按 JSON 比较。"""
    assert row["action"] == action
    assert row["actor"] == actor
    assert row["tier"] == tier
    assert row["target_count"] == len(target_ids)
    assert json.loads(row["target_ids"]) == target_ids
    assert row["reason"] == reason
    assert row["allowed"] == int(allowed)
    assert row["reversible"] == int(reversible)
    assert row["undone"] == undone
    if undo_payload == "":
        assert row["undo_payload"] == ""
    else:
        assert json.loads(row["undo_payload"]) == undo_payload


def _stub_server_op(monkeypatch, result):
    """替换路由用的 app._server_op：记录调用参数，返回值由 result 决定。"""
    calls = []

    def op(ids, action):
        calls.append((list(ids), action))
        return result(list(ids))

    monkeypatch.setattr(mailhub_app, "_server_op", op)
    return calls


def _stub_auto_clean_action(monkeypatch, result):
    """替换 sync.auto_clean 内部调用的 apply_server_action（不碰真实 IMAP）。"""
    calls = []

    def op(ids, action):
        calls.append((list(ids), action))
        return result(list(ids))

    monkeypatch.setattr(sync, "apply_server_action", op)
    return calls


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


# ---------------- A. clean_messages ----------------

def test_clean_soft_delete_success(fresh_db, monkeypatch):
    """A1：软删除成功——响应整字典、邮件进回收站、审计可撤销"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 1, category="验证码", date_ts=_NOW - 10 * _DAY)

    result = asyncio.run(mailhub_app.clean_messages(
        mailhub_app.CleanBody(category="验证码")))
    audit_id = result.pop("audit_id")

    assert result == {"ok": True, "count": 1, "errors": [], "blocked": [],
                      "reversible": True}
    assert _deleted_ts(fresh_db, mid) == _NOW
    _assert_audit(_audit_row(fresh_db, audit_id),
                  action="soft_delete", actor="user", tier="B",
                  target_ids=[mid], reason="批量清理分类「验证码」",
                  allowed=True, reversible=True, undo_payload={"ids": [mid]})


def test_clean_older_days_keeps_newer_mail(fresh_db, monkeypatch):
    """A2：older_days>0 只清理早于截止时间的那封"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    old = _insert_msg(fresh_db, acc, 1, category="验证码", date_ts=_NOW - 31 * _DAY)
    recent = _insert_msg(fresh_db, acc, 2, category="验证码", date_ts=_NOW - 10 * _DAY)

    result = asyncio.run(mailhub_app.clean_messages(
        mailhub_app.CleanBody(category="验证码", older_days=30)))
    audit_id = result.pop("audit_id")

    assert result == {"ok": True, "count": 1, "errors": [], "blocked": [],
                      "reversible": True}
    assert _deleted_ts(fresh_db, old) == _NOW
    assert _deleted_ts(fresh_db, recent) == 0
    _assert_audit(_audit_row(fresh_db, audit_id),
                  action="soft_delete", actor="user", tier="B",
                  target_ids=[old], reason="批量清理分类「验证码」",
                  allowed=True, reversible=True, undo_payload={"ids": [old]})


def test_clean_zero_days_cleans_everything(fresh_db, monkeypatch):
    """A2：older_days=0 不设截止时间，全部候选都被清理"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    old = _insert_msg(fresh_db, acc, 1, category="验证码", date_ts=_NOW - 31 * _DAY)
    recent = _insert_msg(fresh_db, acc, 2, category="验证码", date_ts=_NOW - 1 * _DAY)

    result = asyncio.run(mailhub_app.clean_messages(
        mailhub_app.CleanBody(category="验证码", older_days=0)))
    audit_id = result.pop("audit_id")

    assert result == {"ok": True, "count": 2, "errors": [], "blocked": [],
                      "reversible": True}
    assert _deleted_ts(fresh_db, old) == _NOW
    assert _deleted_ts(fresh_db, recent) == _NOW
    row = _audit_row(fresh_db, audit_id)
    # 两封同时被清理；这里只钉住集合，顺序由 SQLite 的扫描顺序决定
    assert set(json.loads(row["target_ids"])) == {old, recent}
    assert row["reason"] == "批量清理分类「验证码」"
    assert row["allowed"] == 1 and row["reversible"] == 1


def test_clean_mixed_blocks_attachment(fresh_db, monkeypatch):
    """A3：普通验证码被清理、带附件的被拦截；blocked 每项只有 id/reason"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    plain = _insert_msg(fresh_db, acc, 1, category="验证码", date_ts=_NOW - 1 * _DAY)
    attached = _insert_msg(fresh_db, acc, 2, category="验证码", has_attach=1,
                           date_ts=_NOW - 1 * _DAY)

    result = asyncio.run(mailhub_app.clean_messages(
        mailhub_app.CleanBody(category="验证码", confirmed=True)))
    audit_id = result.pop("audit_id")

    assert set(result["blocked"][0]) == {"id", "reason"}
    assert result == {
        "ok": True, "count": 1, "errors": [],
        "blocked": [{"id": attached,
                     "reason": "邮件含附件，可能是发票或合同，禁止自动删除"}],
        "reversible": True,
    }
    assert _deleted_ts(fresh_db, plain) == _NOW
    assert _deleted_ts(fresh_db, attached) == 0
    # 有邮件被拦截也要写一条普通成功审计；reason 不会提到被拦截的封数
    _assert_audit(_audit_row(fresh_db, audit_id),
                  action="soft_delete", actor="user", tier="B",
                  target_ids=[plain], reason="批量清理分类「验证码」",
                  allowed=True, reversible=True, undo_payload={"ids": [plain]})


def test_clean_all_blocked_returns_ok_without_reversible(fresh_db, monkeypatch):
    """A4：候选全部被拦截——仍返回 ok，但响应没有 reversible 键，审计 allowed=0"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    attached = _insert_msg(fresh_db, acc, 1, category="验证码", has_attach=1,
                           date_ts=_NOW - 1 * _DAY)

    result = asyncio.run(mailhub_app.clean_messages(
        mailhub_app.CleanBody(category="验证码", confirmed=True)))
    audit_id = result.pop("audit_id")

    assert set(result) == {"ok", "count", "errors", "blocked"}
    assert result == {
        "ok": True, "count": 0, "errors": [],
        "blocked": [{"id": attached,
                     "reason": "邮件含附件，可能是发票或合同，禁止自动删除"}],
    }
    assert _deleted_ts(fresh_db, attached) == 0
    _assert_audit(_audit_row(fresh_db, audit_id),
                  action="soft_delete", actor="user", tier="",
                  target_ids=[attached],
                  reason="所有候选邮件均被安全策略拦截",
                  allowed=False, reversible=False, undo_payload="")


def test_clean_category_outside_whitelist(fresh_db, monkeypatch):
    """A5：分类不在白名单——403，并写一条 action=clean 的拦截审计"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    bill = _insert_msg(fresh_db, acc, 1, category="账单", date_ts=_NOW - 1 * _DAY)
    detail = ("分类「账单」不在可清理白名单内（仅允许 订阅、通知、验证码），"
              "请在收件箱中逐封确认删除")

    with pytest.raises(mailhub_app.HTTPException) as exc_info:
        asyncio.run(mailhub_app.clean_messages(
            mailhub_app.CleanBody(category="账单", confirmed=True)))

    assert exc_info.value.status_code == 403
    assert exc_info.value.detail == detail
    assert _deleted_ts(fresh_db, bill) == 0
    _assert_audit(_only_audit(fresh_db),
                  action="clean", actor="user", tier="", target_ids=[],
                  reason=detail, allowed=False, reversible=False, undo_payload="")


def test_clean_server_requires_confirmation(fresh_db, monkeypatch):
    """A6：server=True 但没有 confirmed——403，不写审计，本地不动"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 1, category="验证码", date_ts=_NOW - 1 * _DAY)
    calls = _stub_server_op(monkeypatch, lambda ids: (list(ids), []))

    with pytest.raises(mailhub_app.HTTPException) as exc_info:
        asyncio.run(mailhub_app.clean_messages(
            mailhub_app.CleanBody(category="验证码", server=True, confirmed=False)))

    assert exc_info.value.status_code == 403
    assert exc_info.value.detail == "从邮箱服务器永久删除属 C 类动作，需前端显式确认"
    assert calls == []
    assert _audit_count(fresh_db) == 0
    assert _deleted_ts(fresh_db, mid) == 0


def test_clean_without_candidates(fresh_db, monkeypatch):
    """A7：没有候选——直接返回空结果，不写审计"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    _insert_msg(fresh_db, acc, 1, category="通知", date_ts=_NOW - 1 * _DAY)

    result = asyncio.run(mailhub_app.clean_messages(
        mailhub_app.CleanBody(category="验证码")))

    assert result == {"ok": True, "count": 0, "errors": [], "blocked": []}
    assert _audit_count(fresh_db) == 0


def test_clean_server_success_purges_local_rows(fresh_db, monkeypatch):
    """A8：服务器全部成功——本地行被物理删除，审计标为不可撤销"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 7, category="验证码", date_ts=_NOW - 1 * _DAY)
    calls = _stub_server_op(monkeypatch, lambda ids: (list(ids), []))

    result = asyncio.run(mailhub_app.clean_messages(
        mailhub_app.CleanBody(category="验证码", server=True, confirmed=True)))
    audit_id = result.pop("audit_id")

    assert calls == [([mid], "delete")]
    assert result == {"ok": True, "count": 1, "errors": [], "blocked": [],
                      "reversible": False}
    assert fresh_db.execute("SELECT id FROM messages WHERE id=?", (mid,)).fetchone() is None
    _assert_audit(_audit_row(fresh_db, audit_id),
                  action="purge", actor="user", tier="C", target_ids=[mid],
                  reason="批量清理分类「验证码」",
                  allowed=True, reversible=False, undo_payload="")


def test_clean_server_partial_failure_deletes_only_succeeded(fresh_db, monkeypatch):
    """A9：服务器部分成功——只删成功那封，审计 target_ids 只含成功项"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    first = _insert_msg(fresh_db, acc, 1, category="验证码", date_ts=_NOW - 1 * _DAY)
    second = _insert_msg(fresh_db, acc, 2, category="验证码", date_ts=_NOW - 1 * _DAY)
    _stub_server_op(monkeypatch, lambda _ids: ([first], ["账户1: partial failure"]))

    result = asyncio.run(mailhub_app.clean_messages(
        mailhub_app.CleanBody(category="验证码", server=True, confirmed=True)))
    audit_id = result.pop("audit_id")

    assert result == {"ok": False, "count": 1, "errors": ["账户1: partial failure"],
                      "blocked": [], "reversible": False}
    assert fresh_db.execute("SELECT id FROM messages WHERE id=?", (first,)).fetchone() is None
    assert fresh_db.execute("SELECT id FROM messages WHERE id=?", (second,)).fetchone() is not None
    _assert_audit(_audit_row(fresh_db, audit_id),
                  action="purge", actor="user", tier="C", target_ids=[first],
                  reason="批量清理分类「验证码」；1 个账户执行失败",
                  allowed=True, reversible=False, undo_payload="")


def test_clean_server_all_failed_keeps_local_rows(fresh_db, monkeypatch):
    """A10：服务器全部失败——本地一封不动，审计 allowed=0 且 target_ids 为空"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 1, category="验证码", date_ts=_NOW - 1 * _DAY)
    _stub_server_op(monkeypatch, lambda _ids: ([], ["账户1: server unavailable"]))

    result = asyncio.run(mailhub_app.clean_messages(
        mailhub_app.CleanBody(category="验证码", server=True, confirmed=True)))
    audit_id = result.pop("audit_id")

    assert result == {"ok": False, "count": 0,
                      "errors": ["账户1: server unavailable"],
                      "blocked": [], "reversible": False}
    assert _deleted_ts(fresh_db, mid) == 0
    _assert_audit(_audit_row(fresh_db, audit_id),
                  action="purge", actor="user", tier="C", target_ids=[],
                  reason="批量清理分类「验证码」；1 个账户执行失败",
                  allowed=False, reversible=False, undo_payload="")


# ---------------- B. sync.auto_clean ----------------

def test_auto_clean_disabled_does_nothing(fresh_db, monkeypatch):
    """B1：两个天数都是 0——什么都不做，没有审计"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    otp = _insert_msg(fresh_db, acc, 1, category="验证码", date_ts=_NOW - 100 * _DAY)
    bill = _insert_msg(fresh_db, acc, 2, category="账单", bill_tier="一般",
                       date_ts=_NOW - 100 * _DAY)
    db.set_setting("clean_otp_days", "0")
    db.set_setting("clean_bill_minor_days", "0")
    db.set_setting("clean_server", "0")
    try:
        sync.auto_clean()
    finally:
        db.set_setting("clean_otp_days", "0")
        db.set_setting("clean_bill_minor_days", "0")
        db.set_setting("clean_server", "0")

    assert _deleted_ts(fresh_db, otp) == 0
    assert _deleted_ts(fresh_db, bill) == 0
    assert _audit_count(fresh_db) == 0


def test_auto_clean_soft_deletes_expired_otp_only(fresh_db, monkeypatch):
    """B2：只开验证码清理——过期进回收站、没过期不动，审计可撤销"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    expired = _insert_msg(fresh_db, acc, 1, category="验证码", date_ts=_NOW - 31 * _DAY)
    fresh = _insert_msg(fresh_db, acc, 2, category="验证码", date_ts=_NOW - 10 * _DAY)
    db.set_setting("clean_otp_days", "30")
    try:
        sync.auto_clean()
    finally:
        db.set_setting("clean_otp_days", "0")

    assert _deleted_ts(fresh_db, expired) == _NOW
    assert _deleted_ts(fresh_db, fresh) == 0
    _assert_audit(_only_audit(fresh_db),
                  action="soft_delete", actor="system", tier="B",
                  target_ids=[expired],
                  reason="自动清理30 天前的验证码邮件，0 封被策略保护",
                  allowed=True, reversible=True, undo_payload={"ids": [expired]})


def test_auto_clean_minor_bills_only(fresh_db, monkeypatch):
    """B3：一般账单清理——只清 bill_tier=一般，重要账单不动"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    minor = _insert_msg(fresh_db, acc, 1, category="账单", bill_tier="一般",
                        date_ts=_NOW - 31 * _DAY)
    important = _insert_msg(fresh_db, acc, 2, category="账单", bill_tier="重要",
                            date_ts=_NOW - 31 * _DAY)
    db.set_setting("clean_bill_minor_days", "30")
    try:
        sync.auto_clean()
    finally:
        db.set_setting("clean_bill_minor_days", "0")

    assert _deleted_ts(fresh_db, minor) == _NOW
    assert _deleted_ts(fresh_db, important) == 0
    _assert_audit(_only_audit(fresh_db),
                  action="soft_delete", actor="system", tier="B",
                  target_ids=[minor],
                  reason="自动清理30 天前的一般账单，0 封被策略保护",
                  allowed=True, reversible=True, undo_payload={"ids": [minor]})


def test_auto_clean_joins_both_scopes(fresh_db, monkeypatch):
    """B4：两项都开——reason 里两段范围用「和」按设置顺序拼接"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    otp = _insert_msg(fresh_db, acc, 1, category="验证码", date_ts=_NOW - 8 * _DAY)
    bill = _insert_msg(fresh_db, acc, 2, category="账单", bill_tier="一般",
                       date_ts=_NOW - 31 * _DAY)
    db.set_setting("clean_otp_days", "7")
    db.set_setting("clean_bill_minor_days", "30")
    try:
        sync.auto_clean()
    finally:
        db.set_setting("clean_otp_days", "0")
        db.set_setting("clean_bill_minor_days", "0")

    assert _deleted_ts(fresh_db, otp) == _NOW
    assert _deleted_ts(fresh_db, bill) == _NOW
    # 先验证码查询、后账单查询，所以顺序固定
    _assert_audit(_only_audit(fresh_db),
                  action="soft_delete", actor="system", tier="B",
                  target_ids=[otp, bill],
                  reason="自动清理7 天前的验证码邮件和30 天前的一般账单，0 封被策略保护",
                  allowed=True, reversible=True, undo_payload={"ids": [otp, bill]})


def test_auto_clean_counts_protected_attachment(fresh_db, monkeypatch):
    """B5：带附件的过期验证码被留下，reason 里「1 封被策略保护」"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    plain = _insert_msg(fresh_db, acc, 1, category="验证码", date_ts=_NOW - 31 * _DAY)
    attached = _insert_msg(fresh_db, acc, 2, category="验证码", has_attach=1,
                           date_ts=_NOW - 31 * _DAY)
    db.set_setting("clean_otp_days", "30")
    try:
        sync.auto_clean()
    finally:
        db.set_setting("clean_otp_days", "0")

    assert _deleted_ts(fresh_db, plain) == _NOW
    assert _deleted_ts(fresh_db, attached) == 0
    _assert_audit(_only_audit(fresh_db),
                  action="soft_delete", actor="system", tier="B",
                  target_ids=[plain],
                  reason="自动清理30 天前的验证码邮件，1 封被策略保护",
                  allowed=True, reversible=True, undo_payload={"ids": [plain]})


def test_auto_clean_all_blocked_writes_rejected_audit(fresh_db, monkeypatch):
    """B6：候选全部被拦截——allowed=0、target_count=0，reason 带第一封的拦截原因"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    attached = _insert_msg(fresh_db, acc, 1, category="验证码", has_attach=1,
                           date_ts=_NOW - 31 * _DAY)
    db.set_setting("clean_otp_days", "30")
    try:
        sync.auto_clean()
    finally:
        db.set_setting("clean_otp_days", "0")

    assert _deleted_ts(fresh_db, attached) == 0
    _assert_audit(_only_audit(fresh_db),
                  action="soft_delete", actor="system", tier="", target_ids=[],
                  reason="自动清理全部被拦截：邮件含附件，可能是发票或合同，禁止自动删除",
                  allowed=False, reversible=False, undo_payload="")


def test_auto_clean_server_success_purges(fresh_db, monkeypatch):
    """B7：clean_server=1 且服务器成功——本地行被删除，审计为不可撤销 purge"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    calls = _fake_imap(monkeypatch)
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 3, category="验证码", date_ts=_NOW - 31 * _DAY)
    db.set_setting("clean_otp_days", "30")
    db.set_setting("clean_server", "1")
    try:
        sync.auto_clean()
    finally:
        db.set_setting("clean_otp_days", "0")
        db.set_setting("clean_server", "0")

    assert ("delete", [3]) in calls
    assert fresh_db.execute("SELECT id FROM messages WHERE id=?", (mid,)).fetchone() is None
    _assert_audit(_only_audit(fresh_db),
                  action="purge", actor="system", tier="C", target_ids=[mid],
                  reason="自动清理30 天前的验证码邮件，0 封被策略保护",
                  allowed=True, reversible=False, undo_payload="")


def test_auto_clean_server_failure_keeps_rows(fresh_db, monkeypatch):
    """B8：clean_server=1 但服务器全部失败——本地不动，reason 追加失败详情"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    calls = _stub_auto_clean_action(monkeypatch,
                                    lambda _ids: ([], ["账户1: server unavailable"]))
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 1, category="验证码", date_ts=_NOW - 31 * _DAY)
    db.set_setting("clean_otp_days", "30")
    db.set_setting("clean_server", "1")
    try:
        sync.auto_clean()
    finally:
        db.set_setting("clean_otp_days", "0")
        db.set_setting("clean_server", "0")

    assert calls == [([mid], "delete")]
    assert _deleted_ts(fresh_db, mid) == 0
    # auto_clean 不显式传 allowed，审计按默认值记成 allowed=1
    _assert_audit(_only_audit(fresh_db),
                  action="purge", actor="system", tier="C", target_ids=[],
                  reason=("自动清理30 天前的验证码邮件，0 封被策略保护；"
                          "服务器删除失败 1 项：账户1: server unavailable"),
                  allowed=True, reversible=False, undo_payload="")


def test_auto_clean_server_partial_failure(fresh_db, monkeypatch):
    """B9：clean_server=1 且部分成功——只删成功那封，target_ids 只含成功项"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    first = _insert_msg(fresh_db, acc, 1, category="验证码", date_ts=_NOW - 31 * _DAY)
    second = _insert_msg(fresh_db, acc, 2, category="验证码", date_ts=_NOW - 31 * _DAY)
    calls = _stub_auto_clean_action(monkeypatch,
                                    lambda _ids: ([first], ["账户1: partial failure"]))
    db.set_setting("clean_otp_days", "30")
    db.set_setting("clean_server", "1")
    try:
        sync.auto_clean()
    finally:
        db.set_setting("clean_otp_days", "0")
        db.set_setting("clean_server", "0")

    # 两封都交给了服务器；顺序由 SQLite 扫描顺序决定，只钉集合与动作名
    assert len(calls) == 1 and calls[0][1] == "delete"
    assert set(calls[0][0]) == {first, second}
    assert fresh_db.execute("SELECT id FROM messages WHERE id=?", (first,)).fetchone() is None
    assert fresh_db.execute("SELECT id FROM messages WHERE id=?", (second,)).fetchone() is not None
    _assert_audit(_only_audit(fresh_db),
                  action="purge", actor="system", tier="C", target_ids=[first],
                  reason=("自动清理30 天前的验证码邮件，0 封被策略保护；"
                          "服务器删除失败 1 项：账户1: partial failure"),
                  allowed=True, reversible=False, undo_payload="")


# ---------------- C. restore_trash ----------------

def test_restore_trash_two_messages(fresh_db, monkeypatch):
    """C1：恢复两封回收站邮件——deleted_ts 归零，审计逐列固定"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    first = _insert_msg(fresh_db, acc, 1, category="验证码", deleted_ts=_NOW - 10)
    second = _insert_msg(fresh_db, acc, 2, category="订阅", deleted_ts=_NOW - 20)

    result = asyncio.run(mailhub_app.restore_trash(
        mailhub_app.IdsBody(ids=[first, second])))

    assert result == {"ok": True, "count": 2}
    assert _deleted_ts(fresh_db, first) == 0
    assert _deleted_ts(fresh_db, second) == 0
    _assert_audit(_only_audit(fresh_db),
                  action="restore", actor="user", tier="A",
                  target_ids=[first, second], reason="从回收站恢复",
                  allowed=True, reversible=False, undo_payload="")


def test_restore_trash_empty_ids(fresh_db):
    """C2：ids 为空——直接返回，不写审计"""
    assert asyncio.run(mailhub_app.restore_trash(mailhub_app.IdsBody(ids=[]))) == {
        "ok": True, "count": 0}
    assert _audit_count(fresh_db) == 0


def test_restore_trash_counts_existing_ids_only(fresh_db):
    """C3：混入不在回收站/不存在的 id——count 只数存在的行，审计仍记全部 id"""
    acc = _mk_account(fresh_db)
    trashed = _insert_msg(fresh_db, acc, 1, category="验证码", deleted_ts=_NOW - 10)
    inbox = _insert_msg(fresh_db, acc, 2, category="通知")

    result = asyncio.run(mailhub_app.restore_trash(
        mailhub_app.IdsBody(ids=[trashed, inbox, 999999])))

    # 不在回收站的那封也被 UPDATE 命中（值本来就是 0）；不存在的 id 不计入
    assert result == {"ok": True, "count": 2}
    assert _deleted_ts(fresh_db, trashed) == 0
    assert _deleted_ts(fresh_db, inbox) == 0
    _assert_audit(_only_audit(fresh_db),
                  action="restore", actor="user", tier="A",
                  target_ids=[trashed, inbox, 999999], reason="从回收站恢复",
                  allowed=True, reversible=False, undo_payload="")


# ---------------- D. undo_action ----------------

def test_undo_soft_delete_returns_mail_to_inbox(fresh_db, monkeypatch):
    """D1：撤销 A1 的软删除——邮件回收件箱，原审计 undone=1，新增 undo 审计"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 1, category="验证码", date_ts=_NOW - 1 * _DAY)
    cleaned = asyncio.run(mailhub_app.clean_messages(
        mailhub_app.CleanBody(category="验证码")))
    audit_id = cleaned["audit_id"]

    result = asyncio.run(mailhub_app.undo_action(audit_id))

    assert result == {"ok": True, "restored": 1}
    assert _deleted_ts(fresh_db, mid) == 0
    assert _audit_row(fresh_db, audit_id)["undone"] == 1
    _assert_audit(_last_audit(fresh_db),
                  action="undo", actor="user", tier="A", target_ids=[audit_id],
                  reason=f"撤销审计 #{audit_id}（soft_delete），恢复 1 封",
                  allowed=True, reversible=False, undo_payload="")


def test_undo_twice_is_rejected(fresh_db, monkeypatch):
    """D2：同一条审计撤销第二次——400"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    _insert_msg(fresh_db, acc, 1, category="验证码", date_ts=_NOW - 1 * _DAY)
    audit_id = asyncio.run(mailhub_app.clean_messages(
        mailhub_app.CleanBody(category="验证码")))["audit_id"]
    asyncio.run(mailhub_app.undo_action(audit_id))

    with pytest.raises(mailhub_app.HTTPException) as exc_info:
        asyncio.run(mailhub_app.undo_action(audit_id))

    assert exc_info.value.status_code == 400
    assert exc_info.value.detail == "该操作已撤销过"


def test_undo_purge_is_rejected(fresh_db, monkeypatch):
    """D3：撤销不可逆的 purge——400，详情带动作名"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    _stub_server_op(monkeypatch, lambda ids: (list(ids), []))
    acc = _mk_account(fresh_db)
    _insert_msg(fresh_db, acc, 1, category="验证码", date_ts=_NOW - 1 * _DAY)
    audit_id = asyncio.run(mailhub_app.clean_messages(
        mailhub_app.CleanBody(category="验证码", server=True,
                              confirmed=True)))["audit_id"]

    with pytest.raises(mailhub_app.HTTPException) as exc_info:
        asyncio.run(mailhub_app.undo_action(audit_id))

    assert exc_info.value.status_code == 400
    assert exc_info.value.detail == "动作「purge」不可撤销（已在邮箱服务器上永久生效）"


def test_undo_unknown_audit_id(fresh_db):
    """D4：审计 id 不存在——404"""
    with pytest.raises(mailhub_app.HTTPException) as exc_info:
        asyncio.run(mailhub_app.undo_action(123456))

    assert exc_info.value.status_code == 404
    assert exc_info.value.detail == "审计记录不存在"


def test_undo_auto_clean_payload(fresh_db, monkeypatch):
    """D5：自动清理写的撤销载荷与手工软删除的可以互换——邮件回收件箱"""
    monkeypatch.setattr(time, "time", lambda: _NOW)
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 1, category="验证码", date_ts=_NOW - 31 * _DAY)
    db.set_setting("clean_otp_days", "30")
    try:
        sync.auto_clean()
    finally:
        db.set_setting("clean_otp_days", "0")
    audit_id = _only_audit(fresh_db)["id"]
    assert _deleted_ts(fresh_db, mid) == _NOW

    result = asyncio.run(mailhub_app.undo_action(audit_id))

    assert result == {"ok": True, "restored": 1}
    assert _deleted_ts(fresh_db, mid) == 0
    assert _audit_row(fresh_db, audit_id)["undone"] == 1


def test_undo_local_read_restores_unread(fresh_db):
    """D6：撤销纯本地的「标为已读」——unread 恢复原值"""
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 1, category="通知", unread=1)
    marked = asyncio.run(mailhub_app.batch_messages(
        mailhub_app.BatchBody(ids=[mid], action="read")))
    assert marked["reversible"] is True
    assert _unread(fresh_db, mid) == 0

    result = asyncio.run(mailhub_app.undo_action(marked["audit_id"]))

    assert result == {"ok": True, "restored": 1}
    assert _unread(fresh_db, mid) == 1
    assert _audit_row(fresh_db, marked["audit_id"])["undone"] == 1
