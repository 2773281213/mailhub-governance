"""大邮件保护测试：按大小分批抓取、正文截断、分块入库、迁移列与状态保留。

全部合成数据，用假的 imaplib 连接替换 ImapSession.conn。
"""
import os
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

import app as mailhub_app  # noqa: E402
import db  # noqa: E402
import mail_client  # noqa: E402
import sessions  # noqa: E402
import sync  # noqa: E402

MB = 1024 * 1024


# ---------------- 夹具 ----------------

@pytest.fixture(autouse=True)
def fresh_db():
    """每个用例一张干净的表（照 tests/test_pipeline.py 的 fresh_db）。"""
    db.init_db()
    conn = db.get_conn()
    for t in ("governance_decisions", "governance_runs", "messages", "accounts",
              "audit_log", "aliases", "rules"):
        conn.execute(f"DELETE FROM {t}")
    conn.commit()
    yield conn
    for lock in sync._sync_locks.values():
        if lock.locked():
            try:
                lock.release()
            except RuntimeError:
                pass
    sync._sync_locks.clear()


def _mk_account(conn, name="acc", email="a@example.com"):
    cur = conn.execute(
        """INSERT INTO accounts(name, provider, email, imap_host, imap_port,
                                auth_type, enabled, created_ts)
           VALUES(?,?,?,?,?,?,1,0)""",
        (name, "custom", email, "imap.example.com", 993, "password"))
    conn.commit()
    return cur.lastrowid


def _raw(uid: int, subject: str, body: bytes = b"hello\r\n") -> bytes:
    """构造一封最小的原始邮件（主题故意可读，方便断言解析结果）。"""
    return (f"Message-ID: <m{uid}@x>\r\n"
            f"From: sender@example.com\r\n"
            f"Subject: {subject}\r\n"
            f"Content-Type: text/plain; charset=utf-8\r\n"
            f"\r\n").encode() + body


# ---------------- 假 IMAP 连接 ----------------

class FakeLargeConn:
    """假的 imaplib 连接：按命令返回预设数据，并记录每次 uid("fetch", ...) 的参数。

    sizes 是 UID -> 字节数，缺的 UID 表示「服务器不给大小」；raws 是 UID -> 整封原始
    字节，没给的 UID 用默认邮件代替（大小由 sizes 决定，不必真的造 3MB 数据）。
    """

    def __init__(self, sizes, raws=None):
        self.sizes = dict(sizes)
        self.raws = dict(raws or {})
        self.fetch_calls = []          # [(uidset, query)]
        self.whole_fetches = []        # 被整封抓取（BODY[]）的 UID
        self.truncated_fetches = []    # 被截断抓取（HEADER + TEXT<0.1MB>）的 UID

    def _uids(self, uidset):
        return [int(u) for u in uidset.split(",") if u.strip()]

    def _raw_for(self, uid):
        return self.raws.get(uid) or _raw(uid, f"mail {uid}")

    def uid(self, command, *args):
        assert command.upper() == "FETCH", command
        uidset, query = args[0], args[1]
        self.fetch_calls.append((uidset, query))
        uids = self._uids(uidset)
        if "RFC822.SIZE" in query:
            # 真实 imaplib 对不带 literal 的响应直接给一行文本
            return "OK", [f"{i} (UID {u} RFC822.SIZE {self.sizes[u]})".encode()
                          for i, u in enumerate(uids, 1) if u in self.sizes]
        if "BODY.PEEK[]" in query:
            self.whole_fetches.extend(uids)
            data = []
            for i, u in enumerate(uids, 1):
                raw = self._raw_for(u)
                data.append((f"{i} (UID {u} FLAGS () BODY[] {{{len(raw)}}}".encode(), raw))
                data.append(b")")
            return "OK", data
        self.truncated_fetches.extend(uids)
        data = []
        for i, u in enumerate(uids, 1):
            header, body = self._split(self._raw_for(u))
            data.append((f"{i} (UID {u} FLAGS () BODY[HEADER] {{{len(header)}}}".encode(), header))
            data.append((f" BODY[TEXT]<0> {{{len(body)}}}".encode(), body))
            data.append(b")")
        return "OK", data

    @staticmethod
    def _split(raw: bytes):
        """拆成 (头部含空行, 正文)，正文最多 1MB——服务器就是这么截断的。"""
        head, sep, body = raw.partition(b"\r\n\r\n")
        return head + sep, body[:mail_client.TRUNCATED_BODY_BYTES]


def _session(conn):
    session = object.__new__(mail_client.ImapSession)
    session.conn = conn
    session.caps = frozenset()
    return session


# ---------------- 1. 大邮件只取头部 + 正文前 1MB ----------------

def test_large_message_is_truncated_and_small_ones_stay_whole():
    sizes = {1: 1000, 2: 1500, 3: 800, 4: 3 * MB}
    raws = {u: _raw(u, f"mail {u}") for u in (1, 2, 3)}
    raws[4] = _raw(4, "big attachment", body=b"x" * 4096)
    conn = FakeLargeConn(sizes, raws)

    msgs, failed = _session(conn).fetch_messages([1, 2, 3, 4])

    assert failed == set()
    by_uid = {m["uid"]: m for m in msgs}
    assert set(by_uid) == {1, 2, 3, 4}

    # 小邮件整封抓取，且合在一批里
    assert conn.whole_fetches == [1, 2, 3]
    assert ("1,2,3", "(UID FLAGS BODY.PEEK[])") in conn.fetch_calls

    # 大邮件只取头部和正文前 1MB，从未整封下载
    assert conn.truncated_fetches == [4]
    for uidset, query in conn.fetch_calls:
        if "BODY.PEEK[]" in query and "HEADER" not in query:
            assert "4" not in uidset.split(","), f"大邮件被整封抓取了: {uidset}"
    truncated = [q for uidset, q in conn.fetch_calls if "BODY.PEEK[HEADER]" in q]
    assert truncated and all("<0.1048576>" in q for q in truncated)

    # 结果标记与解析
    assert by_uid[4]["body_truncated"] == 1
    assert by_uid[4]["subject"] == "big attachment"
    assert [by_uid[u]["body_truncated"] for u in (1, 2, 3)] == [0, 0, 0]
    assert by_uid[1]["subject"] == "mail 1"


# ---------------- 2. 小邮件按累计字节数分批 ----------------

def test_small_messages_are_batched_by_accumulated_bytes():
    one_and_half = 1536 * 1024          # 1.5MB：5 封 7.5MB，6 封就超过 8MB
    uids = list(range(1, 11))
    conn = FakeLargeConn({u: one_and_half for u in uids})

    msgs, failed = _session(conn).fetch_messages(uids)

    assert failed == set() and len(msgs) == 10
    batches = [uidset for uidset, query in conn.fetch_calls
               if query == "(UID FLAGS BODY.PEEK[])"]
    assert batches == ["1,2,3,4,5", "6,7,8,9,10"]
    for uidset in batches:
        chunk = [int(u) for u in uidset.split(",")]
        assert len(chunk) <= mail_client.BATCH_UIDS
        assert len(chunk) * one_and_half <= mail_client.BATCH_BYTES
    assert conn.truncated_fetches == []


# ---------------- 3. 拿不到大小的邮件按大邮件处理 ----------------

def test_message_without_size_is_fetched_truncated():
    conn = FakeLargeConn({1: 1000}, {2: _raw(2, "no size")})

    msgs, failed = _session(conn).fetch_messages([1, 2])

    assert failed == set()
    by_uid = {m["uid"]: m for m in msgs}
    assert by_uid[2]["body_truncated"] == 1
    assert by_uid[1]["body_truncated"] == 0
    assert conn.truncated_fetches == [2]
    assert conn.whole_fetches == [1]


# ---------------- 4. 分块入库 ----------------

class FakeChunkSession:
    """只实现 sync_account 用到的四个方法（不能依赖假会话没有的方法）。"""

    def __init__(self, mailbox, failed=()):
        self.mailbox = mailbox
        self.failed = set(failed)
        self.calls = []

    def select_inbox(self):
        return 1

    def search_uids(self, last_uid):
        return sorted(u for u in self.mailbox if u > last_uid)

    def fetch_messages(self, uids):
        self.calls.append(list(uids))
        out = []
        for uid in uids:
            if uid in self.failed:
                continue
            parsed = mail_client.parse_message(self.mailbox[uid])
            parsed["uid"], parsed["unread"] = uid, 1
            parsed["body_truncated"] = 0
            out.append(parsed)
        return out, {u for u in uids if u in self.failed}

    def close(self):
        pass


def _run_sync(monkeypatch, acc, session):
    monkeypatch.setattr(mail_client, "open_session", lambda _acc, _secret: session)
    monkeypatch.setattr(sync, "resolve_secret", lambda _acc: "secret")
    return sync.sync_account(acc)


def test_sync_fetches_and_stores_in_chunks_of_ten(fresh_db, monkeypatch):
    acc = _mk_account(fresh_db)
    mailbox = {u: _raw(u, f"mail {u}") for u in range(1, 26)}
    session = FakeChunkSession(mailbox)

    assert _run_sync(monkeypatch, acc, session) == 25

    assert [len(chunk) for chunk in session.calls] == [10, 10, 5]
    assert session.calls[0] == list(range(1, 11))
    assert session.calls[2] == list(range(21, 26))
    count = fresh_db.execute("SELECT COUNT(*) c FROM messages WHERE account_id=?",
                             (acc,)).fetchone()["c"]
    assert count == 25
    account = fresh_db.execute("SELECT last_uid, last_error FROM accounts WHERE id=?",
                              (acc,)).fetchone()
    assert account["last_uid"] == 25 and account["last_error"] == ""


def test_failed_uid_in_second_chunk_stops_watermark_before_it(fresh_db, monkeypatch):
    acc = _mk_account(fresh_db)
    mailbox = {u: _raw(u, f"mail {u}") for u in range(1, 26)}
    session = FakeChunkSession(mailbox, failed={13})   # 失败落在第 2 块

    _run_sync(monkeypatch, acc, session)

    assert [len(chunk) for chunk in session.calls] == [10, 10, 5]
    account = fresh_db.execute("SELECT last_uid, last_error FROM accounts WHERE id=?",
                              (acc,)).fetchone()
    assert account["last_uid"] == 12          # 只推进到第一个失败 UID 之前
    assert "下次同步重试" in account["last_error"]
    count = fresh_db.execute("SELECT COUNT(*) c FROM messages WHERE account_id=?",
                             (acc,)).fetchone()["c"]
    assert count == 24


# ---------------- 5. 迁移列与接口返回 ----------------

def test_body_truncated_column_exists_after_migration(fresh_db):
    cols = {r["name"] for r in fresh_db.execute("PRAGMA table_info(messages)").fetchall()}
    assert "body_truncated" in cols


def test_message_detail_api_exposes_body_truncated(fresh_db):
    acc = _mk_account(fresh_db)
    fresh_db.execute(
        """INSERT INTO messages(account_id, uid, msg_id, subject, sender_addr, date_ts,
                                category, body_truncated, created_ts)
           VALUES(?,?,?,?,?,?,?,?,?)""",
        (acc, 1, "<t@x>", "被截断的邮件", "sender@example.com", 1000.0, "通知", 1, 0.0))
    fresh_db.commit()
    mid = fresh_db.execute("SELECT id FROM messages WHERE account_id=? AND uid=?",
                           (acc, 1)).fetchone()["id"]

    token = sessions.create_session(client="web")
    client = TestClient(mailhub_app.app, base_url="https://testserver",
                        cookies={mailhub_app.COOKIE_NAME: token})
    body = client.get(f"/api/messages/{mid}")

    assert body.status_code == 200
    data = body.json()
    assert "body_truncated" in data and data["body_truncated"] == 1


# ---------------- 6. 账户存在性检查遇数据库忙时保留状态 ----------------

class _LockedConn:
    """execute 直接抛「数据库忙」的假连接。"""

    def execute(self, *_args, **_kwargs):
        raise sqlite3.OperationalError("database is locked")


def test_account_exists_keeps_status_when_query_fails():
    assert sync._account_exists(_LockedConn(), 1) is True
