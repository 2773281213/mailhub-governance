"""原生客户端令牌登录与已登录设备管理测试。"""
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
    return TestClient(mailhub_app.app, base_url="https://testserver")


def test_android_login_returns_token_without_cookie(client):
    r = client.post("/api/login", json={"password": PASSWORD, "client": "android", "device_name": "Pixel 9"})
    assert r.status_code == 200
    data = r.json()
    assert data["ok"] is True
    assert "token" in data
    assert "session_id" in data
    assert len(data["session_id"]) == 16
    assert "expires_ts" in data
    assert "set-cookie" not in r.headers

    token = data["token"]
    native = TestClient(mailhub_app.app, base_url="https://testserver")
    assert native.get("/api/me").status_code == 401
    assert native.get("/api/me", headers={"Authorization": f"Bearer {token}"}).status_code == 200


def test_bearer_csrf_skip_without_cookie_and_blocked_with_cookie(client, monkeypatch):
    import sync
    monkeypatch.setattr(sync.engine, "trigger_all", lambda **kw: {"started": True})

    r = client.post("/api/login", json={"password": PASSWORD, "client": "android"})
    token = r.json()["token"]

    native = TestClient(mailhub_app.app, base_url="https://testserver")
    # Bearer request with cross-origin Origin, without cookie -> 200
    r_cors = native.post(
        "/api/sync-all",
        headers={"Authorization": f"Bearer {token}", "Origin": "https://evil.example"},
    )
    assert r_cors.status_code == 200

    # With valid cookie AND Bearer AND cross-origin Origin -> 403
    web_token = sessions.create_session(client="web")
    client_with_cookie = TestClient(
        mailhub_app.app,
        base_url="https://testserver",
        cookies={mailhub_app.COOKIE_NAME: web_token},
    )
    r_both = client_with_cookie.post(
        "/api/sync-all",
        headers={"Authorization": f"Bearer {token}", "Origin": "https://evil.example"},
    )
    assert r_both.status_code == 403


def test_bearer_logout_revokes_token(client):
    r = client.post("/api/login", json={"password": PASSWORD, "client": "android"})
    token = r.json()["token"]
    native = TestClient(mailhub_app.app, base_url="https://testserver")
    assert native.get("/api/me", headers={"Authorization": f"Bearer {token}"}).status_code == 200

    r_logout = native.post("/api/logout", headers={"Authorization": f"Bearer {token}"})
    assert r_logout.status_code == 200
    assert not sessions.verify_session(token)
    assert native.get("/api/me", headers={"Authorization": f"Bearer {token}"}).status_code == 401


def test_sessions_list_and_delete_device(client):
    # 网页登录
    r_web = client.post("/api/login", json={"password": PASSWORD, "client": "web"})
    assert r_web.status_code == 200

    # 安卓登录
    r_android = client.post("/api/login", json={"password": PASSWORD, "client": "android", "device_name": "Galaxy S24"})
    assert r_android.status_code == 200
    android_token = r_android.json()["token"]
    android_sid = r_android.json()["session_id"]

    # 网页端获取会话列表
    r_list = client.get("/api/sessions")
    assert r_list.status_code == 200
    items = r_list.json()["items"]
    assert len(items) == 2

    by_client = {item["client"]: item for item in items}
    assert "web" in by_client and "android" in by_client
    assert by_client["web"]["current"] is True
    assert by_client["android"]["current"] is False
    assert by_client["android"]["label"] == "Galaxy S24"

    # 用网页会话 DELETE 吊销安卓会话
    r_del = client.delete(f"/api/sessions/{android_sid}")
    assert r_del.status_code == 200
    assert r_del.json() == {"ok": True, "revoked": 1}

    # 安卓令牌失效
    native = TestClient(mailhub_app.app, base_url="https://testserver")
    assert native.get("/api/me", headers={"Authorization": f"Bearer {android_token}"}).status_code == 401

    # 网页会话依然有效
    assert client.get("/api/me").status_code == 200


def test_web_login_response_has_no_token(client):
    r = client.post("/api/login", json={"password": PASSWORD, "client": "web"})
    assert r.status_code == 200
    data = r.json()
    assert data == {"ok": True}
    assert "token" not in data


def test_bearer_change_password_returns_new_token(client):
    r = client.post("/api/login", json={"password": PASSWORD, "client": "android", "device_name": "Pixel 9"})
    old_token = r.json()["token"]

    native = TestClient(mailhub_app.app, base_url="https://testserver")
    new_pw = "a-brand-new-password"
    r_pw = native.put(
        "/api/settings",
        json={"old_password": PASSWORD, "new_password": new_pw},
        headers={"Authorization": f"Bearer {old_token}"},
    )
    assert r_pw.status_code == 200
    data = r_pw.json()
    assert data["ok"] is True
    assert "token" in data
    new_token = data["token"]
    assert new_token != old_token

    # 旧令牌失效，新令牌可用
    assert native.get("/api/me", headers={"Authorization": f"Bearer {old_token}"}).status_code == 401
    assert native.get("/api/me", headers={"Authorization": f"Bearer {new_token}"}).status_code == 200


def test_bearer_revoke_all_returns_new_token(client):
    other_token = sessions.create_session(client="web")

    r = client.post("/api/login", json={"password": PASSWORD, "client": "android", "device_name": "Tablet"})
    old_token = r.json()["token"]

    native = TestClient(mailhub_app.app, base_url="https://testserver")
    r_rev = native.post("/api/sessions/revoke-all", headers={"Authorization": f"Bearer {old_token}"})
    assert r_rev.status_code == 200
    data = r_rev.json()
    assert data["ok"] is True
    assert "token" in data
    new_token = data["token"]

    # 其他会话与旧令牌失效，新令牌可用
    assert not sessions.verify_session(other_token)
    assert not sessions.verify_session(old_token)
    assert sessions.verify_session(new_token)
    assert native.get("/api/me", headers={"Authorization": f"Bearer {new_token}"}).status_code == 200

    # 新会话沿用原来的 client 和 label
    s_list = sessions.list_sessions(new_token)
    assert len(s_list) == 1
    assert s_list[0]["client"] == "android"
    assert s_list[0]["label"] == "Tablet"


def test_invalid_client_type_returns_400(client):
    r = client.post("/api/login", json={"password": PASSWORD, "client": "ios"})
    assert r.status_code == 400


def test_delete_invalid_session_id_returns_404(client):
    assert client.post("/api/login", json={"password": PASSWORD}).status_code == 200
    r = client.delete("/api/sessions/zzzz")
    assert r.status_code == 404
