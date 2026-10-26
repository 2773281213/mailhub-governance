"""并发、慢操作移出事件循环与同步队列不丢失测试。"""
import asyncio
import os
import sys
import threading

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from fastapi.testclient import TestClient  # noqa: E402

import app as mailhub_app  # noqa: E402
import db  # noqa: E402
import oauth_auth  # noqa: E402
import sessions  # noqa: E402
import sync  # noqa: E402


def test_drain_force_queue_requeues_when_spawn_fails(monkeypatch):
    engine = sync.SyncEngine()
    with sync._force_lock:
        sync._force_queue.clear()

    try:
        call_count = 0

        def fake_spawn(acc_id):
            nonlocal call_count
            call_count += 1
            return call_count <= 3

        monkeypatch.setattr(engine, "_spawn", fake_spawn)

        for i in [1, 2, 3, 4, 5]:
            engine.trigger(i)

        requeued = engine._drain_force_queue()
        assert requeued == [4, 5]
        with sync._force_lock:
            assert sync._force_queue == {4, 5}
    finally:
        with sync._force_lock:
            sync._force_queue.clear()


def test_concurrent_trigger_and_drain_no_loss(monkeypatch):
    engine = sync.SyncEngine()
    with sync._force_lock:
        sync._force_queue.clear()

    spawned = set()
    spawn_lock = threading.Lock()

    def fake_spawn(acc_id):
        with spawn_lock:
            spawned.add(acc_id)
        return True

    monkeypatch.setattr(engine, "_spawn", fake_spawn)

    try:
        def worker(start_id):
            for i in range(start_id, start_id + 50):
                engine.trigger(i)
                engine._drain_force_queue()

        threads = [threading.Thread(target=worker, args=(base,)) for base in [1, 51, 101, 151]]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        # 最终清理排空剩余待同步账户
        engine._drain_force_queue()

        assert spawned == set(range(1, 201))
    finally:
        with sync._force_lock:
            sync._force_queue.clear()


def test_login_verify_password_off_event_loop(monkeypatch):
    db.init_db()
    mailhub_app.login_throttle = sessions.LoginThrottle()

    loop_thread_id = None
    verify_thread_id = None

    orig_get_setting = mailhub_app.get_setting

    def wrapped_get_setting(k, default=""):
        nonlocal loop_thread_id
        if k == "admin_hash":
            loop_thread_id = threading.get_ident()
        return orig_get_setting(k, default)

    def fake_verify_password(pw, hashed):
        nonlocal verify_thread_id
        verify_thread_id = threading.get_ident()
        return True

    monkeypatch.setattr(mailhub_app, "get_setting", wrapped_get_setting)
    monkeypatch.setattr(mailhub_app, "verify_password", fake_verify_password)

    client = TestClient(mailhub_app.app, base_url="https://testserver")
    r = client.post("/api/login", json={"password": "any"})
    assert r.status_code == 200

    assert loop_thread_id is not None
    assert verify_thread_id is not None
    assert loop_thread_id != verify_thread_id


def test_outlook_device_start_http_off_calling_thread(tmp_path, monkeypatch):
    db_file = str(tmp_path / "test_oauth.db")
    monkeypatch.setattr(db, "DB_PATH", db_file)
    db.init_db()
    oauth_auth.init_oauth_db()

    class Response:
        status_code = 200

        @staticmethod
        def json():
            return {
                "device_code": "private-device-code",
                "user_code": "ABCD-EFGH",
                "verification_uri": "https://microsoft.com/devicelogin",
                "interval": 5,
                "expires_in": 900,
            }

    post_thread_id = None
    calling_thread_id = threading.get_ident()

    def fake_post(*args, **kwargs):
        nonlocal post_thread_id
        post_thread_id = threading.get_ident()
        return Response()

    monkeypatch.setattr(oauth_auth.httpx, "post", fake_post)
    monkeypatch.setenv("MICROSOFT_CLIENT_ID", "mailhub-public-client")

    result = asyncio.run(oauth_auth.outlook_device_start(oauth_auth.OAuthStartBody(
        provider="outlook",
        email="user@outlook.com",
    )))

    assert result["ok"] is True
    assert post_thread_id is not None
    assert post_thread_id != calling_thread_id


def test_get_conn_reopens_on_db_path_change(tmp_path, monkeypatch):
    initial_conn = db.get_conn()
    initial_file = initial_conn.execute("PRAGMA database_list").fetchone()["file"]

    new_db = str(tmp_path / "changed_mailhub.db")
    monkeypatch.setattr(db, "DB_PATH", new_db)

    new_conn = db.get_conn()
    row = new_conn.execute("PRAGMA database_list").fetchone()
    assert os.path.normpath(row["file"]) == os.path.normpath(new_db)
    assert os.path.normpath(row["file"]) != os.path.normpath(initial_file)
