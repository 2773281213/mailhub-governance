"""C4a：sessions.describe、governance.pending_clause 与退出所有设备的端到端回归。

夹具写法照 tests/test_token_auth.py；全部合成数据，用临时 SQLite 库（见 tests/conftest.py）。
"""
import hashlib
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

import app as mailhub_app  # noqa: E402
import db  # noqa: E402
import governance  # noqa: E402
import oauth_auth  # noqa: E402
import sessions  # noqa: E402
from security import hash_password  # noqa: E402

PASSWORD = "correct horse battery"


# ---------------- 夹具 ----------------

@pytest.fixture
def client():
    """未登录的 TestClient（secure cookie 只会在 https 上回传）。"""
    db.init_db()
    oauth_auth.init_oauth_db()
    db.set_setting("admin_hash", hash_password(PASSWORD))
    sessions.revoke_all_sessions()
    mailhub_app.login_throttle = sessions.LoginThrottle()
    return TestClient(mailhub_app.app, base_url="https://testserver")


def _id_hash(token: str) -> str:
    """与 sessions 表主键一致的令牌哈希。"""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _session_row(token: str):
    return db.get_conn().execute(
        "SELECT * FROM sessions WHERE id_hash=?", (_id_hash(token),)
    ).fetchone()


# ---------------- sessions.describe ----------------

def test_describe_returns_client_label_and_expires(client):
    token = sessions.create_session(client="android", label="Pixel")
    info = sessions.describe(token)
    assert info is not None
    assert info["client"] == "android"
    assert info["label"] == "Pixel"
    # expires_ts 与数据库里的那一行完全一致
    row = _session_row(token)
    assert info["expires_ts"] == row["expires_ts"]


def test_describe_unknown_none_and_empty_token(client):
    assert sessions.describe("this-token-does-not-exist") is None
    assert sessions.describe(None) is None
    assert sessions.describe("") is None


def test_describe_falls_back_to_web_and_empty_label(client):
    conn = db.get_conn()
    now = time.time()
    cases = [
        ("", ""),
        (None, None),
    ]
    for idx, (client_value, label_value) in enumerate(cases):
        token = f"fallback-token-{idx}"
        conn.execute(
            """INSERT INTO sessions(id_hash, created_ts, expires_ts, label, client, last_seen_ts)
               VALUES(?,?,?,?,?,?)""",
            (_id_hash(token), now, now + 3600, label_value, client_value, now),
        )
        conn.commit()
        info = sessions.describe(token)
        assert info is not None
        assert info["client"] == "web"
        assert info["label"] == ""


def test_describe_keeps_expired_session(client):
    token = sessions.create_session(client="android", label="Pixel")
    conn = db.get_conn()
    conn.execute(
        "UPDATE sessions SET expires_ts=? WHERE id_hash=?",
        (time.time() - 60, _id_hash(token)),
    )
    conn.commit()
    info = sessions.describe(token)
    assert info is not None
    assert info["expires_ts"] == _session_row(token)["expires_ts"]


def test_describe_does_not_touch_last_seen_ts(client):
    token = sessions.create_session(client="android", label="Pixel")
    before = _session_row(token)["last_seen_ts"]
    sessions.describe(token)
    after = _session_row(token)["last_seen_ts"]
    assert after == before


# ---------------- governance.pending_clause ----------------

def test_pending_clause_returns_sql_and_ordered_args():
    assert governance.pending_clause(123.0) == (
        governance._PENDING_SQL,
        [governance.VERSION, 123.0],
    )


# ---------------- 端到端：退出所有设备沿用原 client 与 label ----------------

def test_revoke_all_keeps_client_and_label_end_to_end(client):
    r = client.post(
        "/api/login",
        json={"password": PASSWORD, "client": "android", "device_name": "Pixel"},
    )
    assert r.status_code == 200
    old_token = r.json()["token"]

    native = TestClient(mailhub_app.app, base_url="https://testserver")
    r_rev = native.post(
        "/api/sessions/revoke-all",
        headers={"Authorization": f"Bearer {old_token}"},
    )
    assert r_rev.status_code == 200
    data = r_rev.json()
    new_token = data["token"]
    assert new_token != old_token

    # 新会话沿用原来的 client 和 label
    r_list = native.get(
        "/api/sessions", headers={"Authorization": f"Bearer {new_token}"}
    )
    assert r_list.status_code == 200
    items = r_list.json()["items"]
    assert len(items) == 1
    assert items[0]["client"] == "android"
    assert items[0]["label"] == "Pixel"

    # 返回的 expires_ts 与数据库里新会话那一行一致
    row = _session_row(new_token)
    assert data["expires_ts"] == row["expires_ts"]
    assert items[0]["expires_ts"] == row["expires_ts"]
