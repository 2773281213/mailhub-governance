"""B6 已读状态双向同步测试：本地登记、推送、拉取、失败重试、设置读写。

全部合成数据：夹具照 tests/test_pipeline.py 的 fresh_db，假 IMAP 会话照
tests/test_large_mail.py 的写法（替换 mail_client.open_session）。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import asyncio  # noqa: E402

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

import app as mailhub_app  # noqa: E402
import db  # noqa: E402
import mail_client  # noqa: E402
import sessions  # noqa: E402
import sync  # noqa: E402


# ---------------- 夹具 ----------------

@pytest.fixture(autouse=True)
def fresh_db():
    """每个用例一张干净的表；同步状态、并发锁与待触发队列也一起清掉。"""
    db.init_db()
    conn = db.get_conn()
    for t in ("governance_decisions", "governance_runs", "messages", "accounts",
              "audit_log", "aliases", "rules"):
        conn.execute(f"DELETE FROM {t}")
    # 本任务新增的开关：默认关闭由「没有这一行设置」体现
    conn.execute("DELETE FROM settings WHERE k='sync_read_state'")
    conn.commit()
    sync.STATUS.clear()
    yield conn
    for lock in sync._sync_locks.values():
        if lock.locked():
            try:
                lock.release()
            except RuntimeError:
                pass
    sync._sync_locks.clear()
    with sync._force_lock:
        sync._force_queue.clear()


def _mk_account(conn, name="acc", email="a@example.com"):
    cur = conn.execute(
        """INSERT INTO accounts(name, provider, email, imap_host, imap_port,
                                auth_type, enabled, created_ts)
           VALUES(?,?,?,?,?,?,1,0)""",
        (name, "custom", email, "imap.example.com", 993, "password"))
    conn.commit()
    return cur.lastrowid


def _insert_msg(conn, acc_id, uid, *, unread=1, flag_push=0, deleted_ts=0):
    conn.execute(
        """INSERT INTO messages(account_id, uid, msg_id, subject, sender_addr, date_ts,
                                category, unread, flag_push, deleted_ts, created_ts)
           VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
        (acc_id, uid, f"<m{uid}@x>", f"mail {uid}", "sender@example.com",
         1000.0 + uid, "通知", unread, flag_push, deleted_ts, 0.0))
    conn.commit()
    return conn.execute("SELECT id FROM messages WHERE account_id=? AND uid=?",
                        (acc_id, uid)).fetchone()["id"]


def _raw_mail(uid: int) -> bytes:
    return (f"Message-ID: <m{uid}@x>\r\nFrom: sender@example.com\r\n"
            f"Subject: mail {uid}\r\n\r\nhello\r\n").encode()


def _field(conn, mid: int, name: str):
    row = conn.execute(f"SELECT {name} AS v FROM messages WHERE id=?", (mid,)).fetchone()
    return row["v"] if row else None


def _enable_read_state_sync(conn):
    db.set_setting("sync_read_state", "1")


# ---------------- 假 IMAP 会话 ----------------

class FakeReadStateSession:
    """记录调用的假会话：可配置邮箱、服务器标记集合与推送失败注入。

    mark_read 会同步更新 server_flags（真的 STORE 也会改服务器上的 \\Seen），
    这样「推送后紧接着的拉取」看到的是推送之后的状态。
    """

    def __init__(self, uidvalidity, mailbox=None, server_flags=None,
                 mark_read_error="", mark_read_fail_uids=()):
        self.uidvalidity = uidvalidity
        self.mailbox = dict(mailbox or {})              # {uid: 原始邮件字节}
        self.server_flags = {u: set(f) for u, f in (server_flags or {}).items()}
        self.mark_read_error = mark_read_error
        self.mark_read_fail_uids = set(mark_read_fail_uids)
        self.mark_read_calls = []                       # [(uids, read)]
        self.fetch_flags_calls = []                     # [uids]
        self.closed = False

    def select_inbox(self):
        return self.uidvalidity

    def search_uids(self, last_uid):
        return sorted(u for u in self.mailbox if u > last_uid)

    def fetch_messages(self, uids):
        out = []
        for uid in uids:
            parsed = mail_client.parse_message(self.mailbox[uid])
            parsed["uid"], parsed["unread"] = uid, 1
            parsed["body_truncated"] = 0
            out.append(parsed)
        return out, set()

    def fetch_flags(self, uids):
        self.fetch_flags_calls.append(list(uids))
        return {u: set(self.server_flags.get(u, set())) for u in uids if u in self.server_flags}

    def mark_read(self, uids, read=True):
        self.mark_read_calls.append((list(uids), read))
        if self.mark_read_error or self.mark_read_fail_uids & set(uids):
            raise RuntimeError(self.mark_read_error or "服务器拒绝设置标记")
        for uid in uids:
            marks = self.server_flags.setdefault(uid, set())
            if read:
                marks.add("\\Seen")
            else:
                marks.discard("\\Seen")

    def close(self):
        self.closed = True


class FakeSessionWithoutFlags:
    """同 tests/test_pipeline.py 的 FakeSyncSession：没有 fetch_flags / mark_read。"""

    def __init__(self, uidvalidity, mailbox):
        self.uidvalidity = uidvalidity
        self.mailbox = dict(mailbox)

    def select_inbox(self):
        return self.uidvalidity

    def search_uids(self, last_uid):
        return sorted(u for u in self.mailbox if u > last_uid)

    def fetch_messages(self, uids):
        out = []
        for uid in uids:
            parsed = mail_client.parse_message(self.mailbox[uid])
            parsed["uid"], parsed["unread"] = uid, 1
            out.append(parsed)
        return out, set()

    def close(self):
        pass


def _run_sync(monkeypatch, acc, session):
    monkeypatch.setattr(mail_client, "open_session", lambda _acc, _secret: session)
    monkeypatch.setattr(sync, "resolve_secret", lambda _acc: "secret")
    return sync.sync_account(acc)


# ---------------- 1. 开关关闭：行为与旧版本一致 ----------------

def test_switch_off_keeps_read_state_local_only(fresh_db, monkeypatch):
    """开关关闭：打开邮件只改本地，同步既不推送也不拉取。"""
    acc = _mk_account(fresh_db)
    opened = _insert_msg(fresh_db, acc, 1, unread=1)
    pending = _insert_msg(fresh_db, acc, 2, unread=1, flag_push=1)
    fresh_db.execute("UPDATE accounts SET uidvalidity=5, last_uid=2 WHERE id=?", (acc,))
    fresh_db.commit()

    triggered = []
    monkeypatch.setattr(sync.engine, "trigger", lambda acc_id: triggered.append(acc_id))

    asyncio.run(mailhub_app.get_message(opened))

    assert _field(fresh_db, opened, "unread") == 0
    assert _field(fresh_db, opened, "flag_push") == 0
    assert triggered == []

    session = FakeReadStateSession(5, server_flags={2: {"\\Seen"}})
    _run_sync(monkeypatch, acc, session)

    assert session.mark_read_calls == []
    assert session.fetch_flags_calls == []
    # 手动登记的待推送标记原封不动，本地未读状态也不被服务器影响
    assert _field(fresh_db, pending, "flag_push") == 1
    assert _field(fresh_db, pending, "unread") == 1


# ---------------- 2. 开关开启：打开邮件登记待推送并立即触发同步 ----------------

def test_open_unread_mail_registers_push_and_sync_clears_flag(fresh_db, monkeypatch):
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 7, unread=1)
    fresh_db.execute("UPDATE accounts SET uidvalidity=3, last_uid=7 WHERE id=?", (acc,))
    fresh_db.commit()
    _enable_read_state_sync(fresh_db)

    triggered = []
    monkeypatch.setattr(sync.engine, "trigger", lambda acc_id: triggered.append(acc_id))

    asyncio.run(mailhub_app.get_message(mid))

    assert _field(fresh_db, mid, "unread") == 0
    assert _field(fresh_db, mid, "flag_push") == 1
    assert triggered == [acc]

    session = FakeReadStateSession(3)
    _run_sync(monkeypatch, acc, session)

    assert session.mark_read_calls == [([7], True)]
    assert _field(fresh_db, mid, "flag_push") == 0


# ---------------- 3. 批量标为未读：登记 -1 并推送 -\\Seen ----------------

def test_batch_unread_registers_minus_one_and_pushes_unread(fresh_db, monkeypatch):
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 4, unread=0)
    fresh_db.execute("UPDATE accounts SET uidvalidity=9, last_uid=4 WHERE id=?", (acc,))
    fresh_db.commit()
    _enable_read_state_sync(fresh_db)

    triggered = []
    monkeypatch.setattr(sync.engine, "trigger", lambda acc_id: triggered.append(acc_id))

    result = asyncio.run(mailhub_app.batch_messages(
        mailhub_app.BatchBody(ids=[mid], action="unread")))

    assert result["ok"] is True
    assert _field(fresh_db, mid, "unread") == 1
    assert _field(fresh_db, mid, "flag_push") == -1
    assert triggered == [acc]

    session = FakeReadStateSession(9)
    _run_sync(monkeypatch, acc, session)

    assert session.mark_read_calls == [([4], False)]
    assert _field(fresh_db, mid, "flag_push") == 0


# ---------------- 4. 推送失败：标记保留、新邮件照常入库 ----------------

def test_push_failure_keeps_flag_and_sync_still_succeeds(fresh_db, monkeypatch):
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 1, unread=0, flag_push=1)
    fresh_db.execute("UPDATE accounts SET uidvalidity=2, last_uid=1 WHERE id=?", (acc,))
    fresh_db.commit()
    _enable_read_state_sync(fresh_db)

    session = FakeReadStateSession(2, mailbox={2: _raw_mail(2)},
                                   mark_read_error="服务器拒绝设置标记")
    assert _run_sync(monkeypatch, acc, session) == 1

    assert session.mark_read_calls == [([1], True)]
    assert _field(fresh_db, mid, "flag_push") == 1      # 保留，下一轮再试
    account = fresh_db.execute("SELECT last_uid, last_error FROM accounts WHERE id=?",
                               (acc,)).fetchone()
    assert account["last_uid"] == 2 and account["last_error"] == ""
    assert sync.get_status()[str(acc)]["state"] == "idle"
    count = fresh_db.execute("SELECT COUNT(*) c FROM messages WHERE account_id=?",
                             (acc,)).fetchone()["c"]
    assert count == 2


# ---------------- 5. 拉取：只把服务器已读的邮件改为本地已读 ----------------

def test_pull_turns_only_server_seen_mails_read(fresh_db, monkeypatch):
    acc = _mk_account(fresh_db)
    seen = _insert_msg(fresh_db, acc, 1, unread=1)
    unseen = _insert_msg(fresh_db, acc, 2, unread=1)
    just_marked_unread = _insert_msg(fresh_db, acc, 3, unread=1, flag_push=-1)
    fresh_db.execute("UPDATE accounts SET uidvalidity=7, last_uid=3 WHERE id=?", (acc,))
    fresh_db.commit()
    _enable_read_state_sync(fresh_db)

    # uid 3 的推送失败：标记留在 -1，服务器上仍带 \Seen，绝不能被拉取冲回已读
    session = FakeReadStateSession(
        7,
        server_flags={1: {"\\Seen"}, 2: {"\\Flagged"}, 3: {"\\Seen"}},
        mark_read_fail_uids={3})
    _run_sync(monkeypatch, acc, session)

    assert _field(fresh_db, seen, "unread") == 0
    assert _field(fresh_db, unseen, "unread") == 1
    assert _field(fresh_db, just_marked_unread, "unread") == 1
    assert _field(fresh_db, just_marked_unread, "flag_push") == -1
    assert session.mark_read_calls == [([3], False)]
    # 拉取只问了两个可拉取的 UID，没有任何 \Seen，本地已读不会被改回未读
    assert session.fetch_flags_calls == [[1, 2]]


def test_pull_never_marks_local_read_mail_unread(fresh_db, monkeypatch):
    """服务器上没有 \\Seen 的本地已读邮件保持已读。"""
    acc = _mk_account(fresh_db)
    local_read = _insert_msg(fresh_db, acc, 1, unread=0)
    fresh_db.execute("UPDATE accounts SET uidvalidity=7, last_uid=1 WHERE id=?", (acc,))
    fresh_db.commit()
    _enable_read_state_sync(fresh_db)

    session = FakeReadStateSession(7, server_flags={1: set()})
    _run_sync(monkeypatch, acc, session)

    assert _field(fresh_db, local_read, "unread") == 0
    # 本地已读的邮件不在拉取范围里（拉取只看本地未读）
    assert session.fetch_flags_calls == []


# ---------------- 6. UIDVALIDITY 变化的那一轮不推送也不拉取 ----------------

def test_uidvalidity_change_round_skips_both_directions(fresh_db, monkeypatch):
    acc = _mk_account(fresh_db)
    fresh_db.execute("UPDATE accounts SET uidvalidity=100, last_uid=5 WHERE id=?", (acc,))
    fresh_db.commit()
    pending = _insert_msg(fresh_db, acc, 5, unread=0, flag_push=1)
    unread = _insert_msg(fresh_db, acc, 6, unread=1)
    _enable_read_state_sync(fresh_db)

    session = FakeReadStateSession(200, mailbox={6: _raw_mail(6)},
                                   server_flags={6: {"\\Seen"}})
    _run_sync(monkeypatch, acc, session)

    assert session.mark_read_calls == []
    assert session.fetch_flags_calls == []
    assert _field(fresh_db, pending, "flag_push") == 1
    assert _field(fresh_db, unread, "unread") == 1


# ---------------- 7. 会话没有 fetch_flags / mark_read 也要能完成同步 ----------------

def test_sync_completes_with_session_lacking_flag_methods(fresh_db, monkeypatch):
    acc = _mk_account(fresh_db)
    _insert_msg(fresh_db, acc, 1, unread=1)     # 拉取方向会去调用 fetch_flags
    fresh_db.execute("UPDATE accounts SET uidvalidity=4, last_uid=1 WHERE id=?", (acc,))
    fresh_db.commit()
    _enable_read_state_sync(fresh_db)

    session = FakeSessionWithoutFlags(4, {2: _raw_mail(2), 3: _raw_mail(3)})
    assert _run_sync(monkeypatch, acc, session) == 2

    account = fresh_db.execute("SELECT last_uid, last_error FROM accounts WHERE id=?",
                               (acc,)).fetchone()
    assert account["last_uid"] == 3 and account["last_error"] == ""
    assert sync.get_status()[str(acc)]["state"] == "idle"
    count = fresh_db.execute("SELECT COUNT(*) c FROM messages WHERE account_id=?",
                             (acc,)).fetchone()["c"]
    assert count == 3


# ---------------- 8. 审计撤销：按恢复后的状态登记待推送 ----------------

def test_undo_registers_flag_push_for_restored_state(fresh_db, monkeypatch):
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 1, unread=1)
    fresh_db.execute("UPDATE accounts SET uidvalidity=1, last_uid=1 WHERE id=?", (acc,))
    fresh_db.commit()
    _enable_read_state_sync(fresh_db)

    triggered = []
    monkeypatch.setattr(sync.engine, "trigger", lambda acc_id: triggered.append(acc_id))

    result = asyncio.run(mailhub_app.batch_messages(
        mailhub_app.BatchBody(ids=[mid], action="read")))
    assert _field(fresh_db, mid, "unread") == 0
    assert _field(fresh_db, mid, "flag_push") == 1

    undo = asyncio.run(mailhub_app.undo_action(result["audit_id"]))

    assert undo["restored"] == 1
    # 恢复成未读：待推送标记变成「要在服务器上标未读」
    assert _field(fresh_db, mid, "unread") == 1
    assert _field(fresh_db, mid, "flag_push") == -1
    assert triggered == [acc, acc]


# ---------------- 9. 设置接口读写 sync_read_state ----------------

def test_settings_api_reads_and_writes_sync_read_state(fresh_db):
    token = sessions.create_session(client="web")
    client = TestClient(mailhub_app.app, base_url="https://testserver",
                        cookies={mailhub_app.COOKIE_NAME: token})

    assert client.get("/api/settings").json()["sync_read_state"] is False

    assert client.put("/api/settings", json={"sync_read_state": True}).status_code == 200
    assert client.get("/api/settings").json()["sync_read_state"] is True
    assert db.get_setting("sync_read_state") == "1"

    assert client.put("/api/settings", json={"sync_read_state": False}).status_code == 200
    assert client.get("/api/settings").json()["sync_read_state"] is False
    # 其他设置项不受影响
    assert client.get("/api/settings").json()["clean_server"] is False


# ---------------- 10. ImapSession.fetch_flags 解析与分块 ----------------

class FakeFlagsConn:
    """假 imaplib 连接：奇数列带 \\Seen，偶数列为空，缺的 UID 不返回。"""

    def __init__(self, missing=()):
        self.missing = set(missing)
        self.calls = []

    def uid(self, command, *args):
        self.calls.append((command.upper(),) + args)
        uidset = args[0]
        data = []
        for i, uid in enumerate(uidset.split(","), 1):
            if int(uid) in self.missing:
                continue
            flags = "\\Seen \\Flagged" if int(uid) % 2 else ""
            data.append(f"{i} (UID {uid} FLAGS ({flags}))".encode())
        return "OK", data


def test_fetch_flags_parses_flags_and_chunks_uids():
    conn = FakeFlagsConn(missing={250})
    session = object.__new__(mail_client.ImapSession)
    session.conn = conn
    session.caps = frozenset()

    flags = session.fetch_flags(list(range(1, 251)))

    # 按 100 个一块发送 UID FETCH
    assert [c[1] for c in conn.calls] == [",".join(str(u) for u in range(1, 101)),
                                          ",".join(str(u) for u in range(101, 201)),
                                          ",".join(str(u) for u in range(201, 251))]
    assert flags[1] == {"\\Seen", "\\Flagged"}
    assert flags[2] == set()
    assert 250 not in flags        # 服务器没返回的 UID 不出现
    assert len(flags) == 249

# ---------------- 11. 条件写入：推送期间用户改了意图，新意图不能被清掉 ----------------

class _RaceSession(FakeReadStateSession):
    """在推送 / 拉取的关键时刻插一次「用户操作」，模拟 API 线程与同步线程交错。

    真实场景里 API 线程用自己的连接在另一个线程里改库；这里在假会话的回调里
    直接改同一张表，时机等价：都发生在同步线程读完库、写回之前。
    """

    def __init__(self, *args, on_mark_read=None, on_fetch_flags=None, **kwargs):
        super().__init__(*args, **kwargs)
        self.on_mark_read = on_mark_read
        self.on_fetch_flags = on_fetch_flags

    def mark_read(self, uids, read=True):
        # 先完成服务器上的置位，再让「用户」改本地库，最后才轮到同步线程清标记
        super().mark_read(uids, read)
        if self.on_mark_read:
            self.on_mark_read(list(uids), read)

    def fetch_flags(self, uids):
        flags = super().fetch_flags(uids)
        if self.on_fetch_flags:
            self.on_fetch_flags(list(uids), flags)
        return flags


def test_push_keeps_user_flip_to_unread_during_push(fresh_db, monkeypatch):
    """推送期间用户把同一封邮件改成未读（flag_push=-1）：新意图不能被清掉。"""
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 1, unread=0, flag_push=1)
    fresh_db.execute("UPDATE accounts SET uidvalidity=4, last_uid=1 WHERE id=?", (acc,))
    fresh_db.commit()
    _enable_read_state_sync(fresh_db)

    def user_marks_unread(uids, read):
        """模拟用户在这封邮件的 \\Seen 刚写到服务器时又点了「标为未读」。"""
        fresh_db.execute("UPDATE messages SET unread=1, flag_push=-1 WHERE id=?", (mid,))
        fresh_db.commit()

    session = _RaceSession(4, server_flags={1: set()}, on_mark_read=user_marks_unread)
    _run_sync(monkeypatch, acc, session)

    assert session.mark_read_calls == [([1], True)]
    # 推送只清掉「刚推的 1」，用户新登记的 -1 必须保留
    assert _field(fresh_db, mid, "flag_push") == -1
    assert _field(fresh_db, mid, "unread") == 1

    # 下一轮按新意图推送：在服务器上标回未读
    nxt = _RaceSession(4, server_flags={1: {"\\Seen"}})
    _run_sync(monkeypatch, acc, nxt)

    assert nxt.mark_read_calls == [([1], False)]
    assert _field(fresh_db, mid, "flag_push") == 0
    assert _field(fresh_db, mid, "unread") == 1


def test_pull_keeps_user_marked_unread_after_query(fresh_db, monkeypatch):
    """拉取：查询之后用户把这封邮件标为未读，写回不能把它冲回已读。"""
    acc = _mk_account(fresh_db)
    mid = _insert_msg(fresh_db, acc, 1, unread=1)
    fresh_db.execute("UPDATE accounts SET uidvalidity=6, last_uid=1 WHERE id=?", (acc,))
    fresh_db.commit()
    _enable_read_state_sync(fresh_db)

    def user_marks_unread(uids, flags):
        """服务器说 uid 1 带 \\Seen，就在这一刻用户点了「标为未读」。"""
        fresh_db.execute("UPDATE messages SET unread=1, flag_push=-1 WHERE id=?", (mid,))
        fresh_db.commit()

    session = _RaceSession(6, server_flags={1: {"\\Seen"}}, on_fetch_flags=user_marks_unread)
    _run_sync(monkeypatch, acc, session)

    assert session.fetch_flags_calls == [[1]]
    # 写回带了 unread=1 AND flag_push=0 的条件，用户刚标的未读必须保住
    assert _field(fresh_db, mid, "unread") == 1
    assert _field(fresh_db, mid, "flag_push") == -1
