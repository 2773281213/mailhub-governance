"""管理员登录、会话吊销与登录限流。"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

import app as mailhub_app  # noqa: E402
import db  # noqa: E402
import oauth_auth  # noqa: E402
import sessions  # noqa: E402
from security import hash_password  # noqa: E402

PASSWORD = "correct horse battery"


@pytest.fixture
def client():
    db.init_db()
    oauth_auth.init_oauth_db()
    db.set_setting("admin_hash", hash_password(PASSWORD))
    sessions.revoke_all_sessions()
    mailhub_app.login_throttle = sessions.LoginThrottle()
    # secure cookie 只会在 https 上回传
    return TestClient(mailhub_app.app, base_url="https://testserver")


def _login(client, password=PASSWORD):
    return client.post("/api/login", json={"password": password})


# ---------------- 会话 ----------------

def test_api_requires_a_session(client):
    assert client.get("/api/me").status_code == 401
    assert _login(client).status_code == 200
    assert client.get("/api/me").status_code == 200


def test_logout_revokes_the_token_server_side(client):
    _login(client)
    token = client.cookies.get(mailhub_app.COOKIE_NAME)
    assert sessions.verify_session(token)

    assert client.post("/api/logout").status_code == 200
    assert not sessions.verify_session(token)
    # 即使攻击者留着旧 cookie，也不能再用
    stale = TestClient(mailhub_app.app, base_url="https://testserver",
                       cookies={mailhub_app.COOKIE_NAME: token})
    assert stale.get("/api/me").status_code == 401


def test_password_change_revokes_other_sessions(client):
    _login(client)
    other = TestClient(mailhub_app.app, base_url="https://testserver")
    _login(other)
    assert other.get("/api/me").status_code == 200

    r = client.put("/api/settings", json={"old_password": PASSWORD,
                                          "new_password": "a-brand-new-password"})
    assert r.status_code == 200
    assert other.get("/api/me").status_code == 401
    # 当前设备拿到新会话，继续可用
    assert client.get("/api/me").status_code == 200


def test_wrong_old_password_changes_nothing(client):
    _login(client)
    r = client.put("/api/settings", json={"old_password": "nope", "new_password": "another-password",
                                          "google_oauth_client_id": "should-not-save"})
    assert r.status_code == 400
    assert db.get_setting("google_oauth_client_id", "") != "should-not-save"
    assert client.get("/api/me").status_code == 200


def test_revoke_all_keeps_current_device(client):
    _login(client)
    other = TestClient(mailhub_app.app, base_url="https://testserver")
    _login(other)

    r = client.post("/api/sessions/revoke-all")
    assert r.status_code == 200 and r.json()["revoked"] == 2
    assert other.get("/api/me").status_code == 401
    assert client.get("/api/me").status_code == 200


def test_legacy_signed_cookie_is_rejected(client):
    legacy = TestClient(mailhub_app.app, base_url="https://testserver",
                        cookies={mailhub_app.COOKIE_NAME: "9999999999." + "a" * 64})
    assert legacy.get("/api/me").status_code == 401


def test_expired_session_is_rejected(client):
    token = sessions.create_session(ttl=-1)
    assert not sessions.verify_session(token)


def test_initial_password_goes_to_a_private_file_not_the_log(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(db, "DB_PATH", str(tmp_path / "mailhub.db"))
    path = mailhub_app._write_initial_password("s3cret-initial")
    assert open(path, encoding="utf-8").read().strip() == "s3cret-initial"
    assert os.stat(path).st_mode & 0o077 == 0
    assert "s3cret-initial" not in capsys.readouterr().out
    mailhub_app._remove_initial_password_file()
    assert not os.path.exists(path)


# ---------------- 登录限流 ----------------

def test_login_throttle_is_per_ip_with_backoff():
    t = sessions.LoginThrottle()
    now = 1_000_000.0
    for _ in range(5):
        assert t.retry_after("1.1.1.1", now) == 0
        t.record_failure("1.1.1.1", now)
    assert t.retry_after("1.1.1.1", now) == 60
    assert t.retry_after("2.2.2.2", now) == 0          # 其他来源不受影响
    t.record_failure("1.1.1.1", now + 60)
    assert t.retry_after("1.1.1.1", now + 60) == 120   # 每多一次失败翻倍
    t.record_success("1.1.1.1")
    assert t.retry_after("1.1.1.1", now + 60) == 0


def test_login_throttle_caps_delay_and_forgets_old_failures():
    t = sessions.LoginThrottle()
    now = 2_000_000.0
    for i in range(30):
        t.record_failure("3.3.3.3", now + i)
    assert t.retry_after("3.3.3.3", now + 30) <= t.MAX_DELAY
    assert t.retry_after("3.3.3.3", now + t.WINDOW + 60) == 0


def test_login_throttle_global_limit():
    t = sessions.LoginThrottle()
    now = 3_000_000.0
    for i in range(t.GLOBAL_LIMIT):
        t.record_failure(f"10.0.{i // 250}.{i % 250}", now)
    assert t.retry_after("192.0.2.1", now) > 0
    assert t.retry_after("192.0.2.1", now + t.GLOBAL_WINDOW + 1) == 0


def test_login_endpoint_returns_429_after_repeated_failures(client):
    for _ in range(5):
        assert _login(client, "wrong").status_code == 401
    r = _login(client, PASSWORD)
    assert r.status_code == 429
    assert int(r.headers["Retry-After"]) > 0


# ---------------- CSRF 与安全头 ----------------

def test_cross_site_post_is_rejected_even_with_a_session(client):
    _login(client)
    r = client.post("/api/sync-all", headers={"Origin": "https://evil.example"})
    assert r.status_code == 403
    r = client.post("/api/sync-all", headers={"Origin": "null"})
    assert r.status_code == 403
    r = client.post("/api/sync-all", headers={"Sec-Fetch-Site": "cross-site"})
    assert r.status_code == 403


def test_same_origin_and_non_browser_posts_are_allowed(client, monkeypatch):
    monkeypatch.setattr(mailhub_app.sync.engine, "trigger_all", lambda: None)
    _login(client)
    assert client.post("/api/sync-all", headers={"Origin": "https://testserver"}).status_code == 200
    assert client.post("/api/sync-all", headers={"Sec-Fetch-Site": "same-origin"}).status_code == 200
    assert client.post("/api/sync-all").status_code == 200     # curl 等非浏览器客户端


def test_public_url_origin_is_allowed(client, monkeypatch):
    monkeypatch.setattr(mailhub_app.sync.engine, "trigger_all", lambda: None)
    monkeypatch.setenv("MAILHUB_PUBLIC_URL", "https://mail.example.test")
    _login(client)
    r = client.post("/api/sync-all", headers={"Origin": "https://mail.example.test"})
    assert r.status_code == 200


def test_cross_site_login_is_rejected(client):
    r = client.post("/api/login", json={"password": PASSWORD},
                    headers={"Origin": "https://evil.example"})
    assert r.status_code == 403


def test_security_headers_on_pages_and_errors(client):
    for response in (client.get("/api/health"), client.get("/api/me")):
        assert response.headers["X-Frame-Options"] == "DENY"
        assert response.headers["X-Content-Type-Options"] == "nosniff"
        assert "object-src 'none'" in response.headers["Content-Security-Policy"]
        assert "frame-ancestors 'none'" in response.headers["Content-Security-Policy-Report-Only"]


def test_oauth_callback_keeps_its_own_strict_headers(client):
    _login(client)
    r = client.get("/api/oauth/163/callback")
    assert r.headers["Referrer-Policy"] == "no-referrer"
    assert r.headers["Content-Security-Policy"].startswith("default-src 'none'")
