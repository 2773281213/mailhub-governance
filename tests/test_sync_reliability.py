"""同步引擎可靠性测试：手动同步不丢失、治理不阻塞心跳、删除账户不写回状态。"""
import os
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest  # noqa: E402

import db  # noqa: E402
import governance  # noqa: E402
import mail_client  # noqa: E402
import sync  # noqa: E402


@pytest.fixture(autouse=True)
def fresh_db():
    """每个用例一张干净的表，并清空同步引擎的全局状态（参照 test_pipeline 的夹具）。"""
    db.init_db()
    conn = db.get_conn()
    for t in ("governance_decisions", "governance_runs", "messages", "accounts",
              "audit_log", "aliases", "rules"):
        conn.execute(f"DELETE FROM {t}")
    conn.commit()
    with sync._status_lock:
        sync.STATUS.clear()
    with sync._force_lock:
        sync._force_queue.clear()
    sync._sync_locks.clear()
    yield conn
    with sync._status_lock:
        sync.STATUS.clear()
    with sync._force_lock:
        sync._force_queue.clear()
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


def _hold_lock(acc_id: int) -> threading.Lock:
    """占住账户的同步锁，模拟「该账户正在同步」。"""
    lock = threading.Lock()
    sync._sync_locks[acc_id] = lock
    lock.acquire()
    return lock


def test_sync_account_requeues_manual_request_when_lock_busy():
    """拿不到锁时把手动请求放回队列并返回 0，请求不能丢。"""
    acc = 7
    _hold_lock(acc)

    assert sync.sync_account(acc) == 0
    with sync._force_lock:
        assert acc in sync._force_queue


def test_busy_account_waits_in_force_queue_until_unlocked(monkeypatch):
    """同步中的账户本轮不 spawn、留在队列；锁释放后下一次心跳才真正执行。"""
    acc = 11
    lock = _hold_lock(acc)
    engine = sync.SyncEngine()
    spawned = []

    def fake_spawn(acc_id):
        spawned.append(acc_id)
        return True

    monkeypatch.setattr(engine, "_spawn", fake_spawn)

    engine.trigger(acc)
    engine._drain_force_queue()
    assert spawned == []
    with sync._force_lock:
        assert acc in sync._force_queue

    lock.release()
    engine._drain_force_queue()
    assert spawned == [acc]
    with sync._force_lock:
        assert acc not in sync._force_queue


def test_tick_skips_account_that_is_already_syncing(fresh_db, monkeypatch):
    """定时调度跳过正在同步的账户，避免每 30 秒空占一个 worker。"""
    db.set_setting("digest_hour", "24")   # 关掉每日任务，避免干扰
    acc = _mk_account(fresh_db)
    engine = sync.SyncEngine()
    spawned = []

    def fake_spawn(acc_id):
        spawned.append(acc_id)
        return True

    monkeypatch.setattr(engine, "_spawn", fake_spawn)

    lock = _hold_lock(acc)
    engine._tick()
    assert acc not in spawned

    # 锁释放后（本轮同步结束），定时调度恢复；last_sync=0 一定到点
    lock.release()
    engine._tick()
    assert acc in spawned


def test_tick_does_not_block_on_governance(fresh_db, monkeypatch):
    """治理在独立线程里跑：_tick 立即返回，且同一时间只有一个治理线程。"""
    db.set_setting("digest_hour", "24")
    calls = []

    def slow_governance(limit=30):
        calls.append(limit)
        time.sleep(2)

    monkeypatch.setattr(sync, "ai_classify_pending", slow_governance)
    monkeypatch.setattr(governance, "governance_status", lambda: {"pending": 1})

    engine = sync.SyncEngine()
    started = time.monotonic()
    engine._tick()
    elapsed = time.monotonic() - started
    assert elapsed < 1.0

    first = engine._gov_thread
    assert first is not None and first.is_alive()

    engine._tick()   # 上一个治理线程还在跑，不应再起一个
    assert engine._gov_thread is first

    first.join(timeout=5)
    assert calls == [120]


_RAW_MAIL = (b"Message-ID: <deleted@x>\r\nFrom: sender@example.com\r\n"
             b"Subject: deleted during sync\r\n\r\nhello\r\n")


class _DeleteDuringFetchSession:
    """抓取邮件时顺手删掉账户，模拟同步期间用户在另一个请求里删除账户。"""

    def __init__(self, conn, acc_id):
        self.conn = conn
        self.acc_id = acc_id

    def select_inbox(self):
        return 1

    def search_uids(self, last_uid):
        return [1]

    def fetch_messages(self, uids):
        self.conn.execute("DELETE FROM accounts WHERE id=?", (self.acc_id,))
        self.conn.commit()
        parsed = mail_client.parse_message(_RAW_MAIL)
        parsed["uid"], parsed["unread"] = 1, 1
        return [parsed], set()

    def close(self):
        pass


def test_deleted_account_is_not_written_back_to_status(fresh_db, monkeypatch):
    """同步期间账户被删除：结束后不能再把它写回 STATUS。"""
    acc = _mk_account(fresh_db)
    fresh_db.execute("UPDATE accounts SET uidvalidity=1 WHERE id=?", (acc,))
    fresh_db.commit()
    session = _DeleteDuringFetchSession(fresh_db, acc)
    monkeypatch.setattr(mail_client, "open_session", lambda _acc, _secret: session)
    monkeypatch.setattr(sync, "resolve_secret", lambda _acc: "secret")

    sync.sync_account(acc)

    assert fresh_db.execute("SELECT 1 FROM accounts WHERE id=?", (acc,)).fetchone() is None
    assert str(acc) not in sync.get_status()
