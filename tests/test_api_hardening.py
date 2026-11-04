"""B1 API 加固回归测试：登录限流并发绕过、参数与响应加固。

夹具写法照 tests/test_mobile_api.py；替换 verify_password / login_throttle 的写法
照 tests/test_concurrency.py::test_login_verify_password_off_event_loop。
全部合成数据，用临时 SQLite 库（见 tests/conftest.py）。
"""
import os
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

import app as mailhub_app  # noqa: E402
import db  # noqa: E402
import governance  # noqa: E402
import oauth_auth  # noqa: E402
import sessions  # noqa: E402
import sync  # noqa: E402
from security import hash_password  # noqa: E402

PASSWORD = "correct horse battery"


# ---------------- 夹具 ----------------

@pytest.fixture(autouse=True)
def clean_db():
    """每个用例前清空业务表（照 tests/test_pipeline.py 的 fresh_db）。"""
    db.init_db()
    oauth_auth.init_oauth_db()
    conn = db.get_conn()
    for t in ("governance_decisions", "governance_runs", "messages", "accounts",
              "audit_log", "aliases", "rules", "digests"):
        conn.execute(f"DELETE FROM {t}")
    conn.commit()
    yield conn


@pytest.fixture
def client(clean_db):
    """用网页方式登录的 TestClient（secure cookie 只会在 https 上回传）。"""
    db.set_setting("admin_hash", hash_password(PASSWORD))
    sessions.revoke_all_sessions()
    mailhub_app.login_throttle = sessions.LoginThrottle()
    c = TestClient(mailhub_app.app, base_url="https://testserver")
    assert c.post("/api/login", json={"password": PASSWORD}).status_code == 200
    return c


def _mk_account(conn, name="acc", email="a@example.com"):
    cur = conn.execute(
        """INSERT INTO accounts(name, provider, email, imap_host, imap_port,
                                auth_type, enabled, created_ts)
           VALUES(?,?,?,?,?,?,1,0)""",
        (name, "custom", email, "imap.example.com", 993, "password"))
    conn.commit()
    return cur.lastrowid


def _insert(conn, acc_id, uid, **kw):
    row = {
        "subject": f"邮件 {uid}",
        "sender_name": "发件人",
        "sender_addr": "sender@example.com",
        "date_ts": 1_700_000_000.0 + uid,
        "category": "通知",
        "unread": 1,
        "deleted_ts": 0,
        "created_ts": 0,
        "governance_version": governance.VERSION,
        "bill_tier": "",
        "importance": 0,
        "snippet": "",
        "risk_level": "none",
        "otp_code": "",
        "summary": "",
    }
    row.update(kw)
    row["account_id"] = acc_id
    row["uid"] = uid
    cols = list(row)
    conn.execute(
        f"INSERT INTO messages({','.join(cols)}) VALUES({','.join('?' * len(cols))})",
        [row[c] for c in cols])
    conn.commit()
    return conn.execute("SELECT id FROM messages WHERE account_id=? AND uid=?",
                        (acc_id, uid)).fetchone()["id"]


# ---------------- 1. 登录限流的并发绕过 ----------------

def test_concurrent_wrong_passwords_are_throttled(clean_db, monkeypatch):
    """同一 IP 并发错误密码：进入密码校验的次数不超过 MAX_FREE，其余 429。"""
    mailhub_app.login_throttle = sessions.LoginThrottle()

    calls = 0
    calls_lock = threading.Lock()

    def slow_wrong_password(pw, hashed):
        nonlocal calls
        with calls_lock:
            calls += 1
        time.sleep(0.2)          # 模拟真实校验耗时，放大「先查后记」的窗口
        return False

    monkeypatch.setattr(mailhub_app, "verify_password", slow_wrong_password)

    workers = 8
    clients = [TestClient(mailhub_app.app, base_url="https://testserver")
               for _ in range(workers)]
    barrier = threading.Barrier(workers)
    statuses = [None] * workers

    def worker(i):
        barrier.wait()
        statuses[i] = clients[i].post("/api/login", json={"password": "wrong"}).status_code

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(workers)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert calls <= sessions.LoginThrottle.MAX_FREE
    assert calls == sessions.LoginThrottle.MAX_FREE
    assert statuses.count(401) == sessions.LoginThrottle.MAX_FREE
    assert statuses.count(429) == workers - sessions.LoginThrottle.MAX_FREE


def test_invalid_client_type_is_rejected_before_throttle(clean_db):
    """400（不支持的客户端类型）不占用限流名额。"""
    mailhub_app.login_throttle = sessions.LoginThrottle()
    c = TestClient(mailhub_app.app, base_url="https://testserver")
    for _ in range(10):
        assert c.post("/api/login", json={"password": "x", "client": "ios"}).status_code == 400
    throttle = mailhub_app.login_throttle
    assert throttle._pending == {}
    assert throttle._pending_total == 0
    assert throttle._by_ip == {}


def test_successful_login_releases_pending_and_clears_failures(clean_db, monkeypatch):
    mailhub_app.login_throttle = sessions.LoginThrottle()
    throttle = mailhub_app.login_throttle
    ip = "testclient"
    # 先制造 3 次失败记录
    for _ in range(3):
        assert throttle.begin(ip) == 0
        throttle.finish(ip, ok=False)
    assert ip in throttle._by_ip

    observed = {}

    def ok_verify(pw, hashed):
        observed["during"] = dict(throttle._pending)
        return True

    monkeypatch.setattr(mailhub_app, "verify_password", ok_verify)
    c = TestClient(mailhub_app.app, base_url="https://testserver")
    assert c.post("/api/login", json={"password": "any"}).status_code == 200

    # 校验期间确实占着一个进行中名额（IP 由 TestClient 提供）
    assert list(observed["during"].values()) == [1]
    ip = next(iter(observed["during"]))
    # 结束后名额归还、失败记录被清除
    assert throttle._pending == {}
    assert throttle._pending_total == 0
    assert ip not in throttle._by_ip
    assert throttle.retry_after(ip) == 0


def test_exception_in_verify_password_also_releases_pending(clean_db, monkeypatch):
    mailhub_app.login_throttle = sessions.LoginThrottle()
    throttle = mailhub_app.login_throttle

    def boom(pw, hashed):
        raise RuntimeError("校验线程炸了")

    monkeypatch.setattr(mailhub_app, "verify_password", boom)
    c = TestClient(mailhub_app.app, base_url="https://testserver",
                   raise_server_exceptions=False)
    assert c.post("/api/login", json={"password": "any"}).status_code == 500

    # 名额归还，且按一次失败记账（异常不能把 IP 卡在限流里）
    assert throttle._pending == {}
    assert throttle._pending_total == 0
    assert sum(len(v) for v in throttle._by_ip.values()) == 1


# ---------------- 2. 负数 limit / offset ----------------

def test_negative_limits_do_not_disable_row_caps(clean_db, client):
    acc = _mk_account(clean_db)
    for i in range(3):
        _insert(clean_db, acc, i + 1, deleted_ts=time.time() - i)
    for i in range(3):
        _insert(clean_db, acc, 10 + i, category="验证码", otp_code=f"10000{i}")
    for i in range(3):
        db.audit("soft_delete", actor="user", tier="A", target_ids=[i + 1], reason="测试")

    trash = client.get("/api/trash", params={"limit": -1})
    assert trash.status_code == 200
    assert len(trash.json()) == 1

    audit = client.get("/api/audit", params={"limit": -1, "offset": -5})
    assert audit.status_code == 200
    body = audit.json()
    assert body["total"] >= 3
    assert len(body["items"]) == 1

    otp = client.get("/api/otp", params={"limit": -1})
    assert otp.status_code == 200
    assert len(otp.json()) == 1

    # 超过上限照旧截断、不报错
    assert len(client.get("/api/trash", params={"limit": 0}).json()) == 1
    assert client.get("/api/audit", params={"limit": 10 ** 6}).status_code == 200


# ---------------- 3. put_settings 不允许部分保存 ----------------

def test_microsoft_validation_failure_does_not_save_google(clean_db, client):
    db.set_setting("google_oauth_client_id", "keep-me")
    db.set_setting("microsoft_oauth_client_id", "")
    try:
        r = client.put("/api/settings", json={
            "google_oauth_client_id": "123-abc.apps.googleusercontent.com",
            "google_oauth_client_secret": "google-secret",
            "microsoft_oauth_client_id": "ms-client-id",
            "microsoft_oauth_client_secret": "x" * 2001,
        })
        assert r.status_code == 400
        assert "Microsoft" in r.json()["detail"]
        assert db.get_setting("google_oauth_client_id", "") == "keep-me"
        assert db.get_setting("microsoft_oauth_client_id", "") == ""
    finally:
        db.set_setting("google_oauth_client_id", "")


def test_both_providers_are_saved_when_valid(clean_db, client):
    db.set_setting("google_oauth_client_id", "")
    db.set_setting("microsoft_oauth_client_id", "")
    try:
        r = client.put("/api/settings", json={
            "google_oauth_client_id": "123-abc.apps.googleusercontent.com",
            "microsoft_oauth_client_id": "ms-client-id",
        })
        assert r.status_code == 200
        assert db.get_setting("google_oauth_client_id", "") == "123-abc.apps.googleusercontent.com"
        assert db.get_setting("microsoft_oauth_client_id", "") == "ms-client-id"
    finally:
        db.set_setting("google_oauth_client_id", "")
        db.set_setting("microsoft_oauth_client_id", "")


# ---------------- 4. 账户颜色校验 ----------------

def test_create_account_normalizes_invalid_color(clean_db, client, monkeypatch):
    monkeypatch.setattr(oauth_auth, "probe_connection", lambda *a, **kw: None)
    monkeypatch.setattr(sync.engine, "trigger", lambda *a, **kw: None)

    bad = client.post("/api/accounts", json={
        "name": "注入尝试", "provider": "custom", "email": "bad@example.com",
        "secret": "app-password", "imap_host": "imap.example.com", "imap_port": 993,
        "color": "red; drop",
    })
    assert bad.status_code == 200
    row = clean_db.execute("SELECT color FROM accounts WHERE id=?",
                           (bad.json()["id"],)).fetchone()
    assert row["color"] == "#38bdf8"

    good = client.post("/api/accounts", json={
        "name": "合法颜色", "provider": "custom", "email": "good@example.com",
        "secret": "app-password", "imap_host": "imap.example.com", "imap_port": 993,
        "color": "#A1b2C3",
    })
    assert good.status_code == 200
    row = clean_db.execute("SELECT color FROM accounts WHERE id=?",
                           (good.json()["id"],)).fetchone()
    assert row["color"] == "#A1b2C3"

    # 编辑路径同样规范化
    r = client.put(f"/api/accounts/{good.json()['id']}", json={
        "name": "合法颜色", "provider": "custom", "email": "good@example.com",
        "imap_host": "imap.example.com", "imap_port": 993, "color": "url(javascript:1)",
    })
    assert r.status_code == 200
    row = clean_db.execute("SELECT color FROM accounts WHERE id=?",
                           (good.json()["id"],)).fetchone()
    assert row["color"] == "#38bdf8"


def test_normalize_color_accepts_only_rrggbb():
    assert oauth_auth.normalize_color("#38bdf8") == "#38bdf8"
    assert oauth_auth.normalize_color("#ABCdef") == "#ABCdef"
    assert oauth_auth.normalize_color("") == "#38bdf8"
    assert oauth_auth.normalize_color("#12345") == "#38bdf8"
    assert oauth_auth.normalize_color("red") == "#38bdf8"
    assert oauth_auth.normalize_color("#38bdf8; color:red") == "#38bdf8"


# ---------------- 5. Bearer 分支补齐会话字段 ----------------

def test_bearer_revoke_all_returns_session_details(clean_db, client):
    login = client.post("/api/login", json={"password": PASSWORD,
                                            "client": "android", "device_name": "Pixel"})
    assert login.status_code == 200
    token = login.json()["token"]
    native = TestClient(mailhub_app.app, base_url="https://testserver")
    headers = {"Authorization": f"Bearer {token}"}

    r = native.post("/api/sessions/revoke-all", headers=headers)
    assert r.status_code == 200
    data = r.json()
    assert data["ok"] is True and "revoked" in data
    assert len(data["session_id"]) == 16
    assert data["session_id"] == sessions.session_id(data["token"])
    assert data["expires_ts"] > time.time()
    # 新令牌可用，旧令牌已失效
    assert native.get("/api/me",
                      headers={"Authorization": f"Bearer {data['token']}"}).status_code == 200
    assert native.get("/api/me", headers=headers).status_code == 401


def test_bearer_password_change_returns_session_details(clean_db, client):
    token = client.post("/api/login", json={"password": PASSWORD, "client": "android"}).json()["token"]
    native = TestClient(mailhub_app.app, base_url="https://testserver")
    r = native.put("/api/settings", json={"old_password": PASSWORD,
                                          "new_password": "a-brand-new-password"},
                   headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200
    data = r.json()
    assert len(data["session_id"]) == 16
    assert data["expires_ts"] > time.time()
    assert native.get("/api/me",
                      headers={"Authorization": f"Bearer {data['token']}"}).status_code == 200


# ---------------- 6. 邮件列表字段与 /api/changes 重置信号 ----------------

def test_message_list_items_carry_risk_level_and_display_category(clean_db, client):
    acc = _mk_account(clean_db)
    _insert(clean_db, acc, 1, category="账单", bill_tier="重要", risk_level="high")
    items = client.get("/api/messages").json()["items"]
    assert len(items) == 1
    item = items[0]
    assert item["risk_level"] == "high"
    assert item["display_category"] == "重要账单"
    # 网页端依赖的旧字段仍在
    assert {"id", "subject", "category", "bill_tier", "account_name"} <= set(item)


def test_changes_reports_reset_when_cursor_is_ahead(clean_db, client):
    acc = _mk_account(clean_db)
    now = time.time()
    ids = [_insert(clean_db, acc, i + 1, created_ts=now) for i in range(3)]
    max_id = max(ids)

    ahead = client.get("/api/changes", params={"after_id": max_id + 100}).json()
    assert ahead["reset"] is True
    assert ahead["items"] == []
    assert ahead["cursor"] == max_id

    init = client.get("/api/changes", params={"after_id": 0}).json()
    assert init["reset"] is False
    assert init["items"] == []
    assert init["cursor"] == max_id

    normal = client.get("/api/changes", params={"after_id": ids[0]}).json()
    assert normal["reset"] is False
    assert [i["id"] for i in normal["items"]] == [ids[1], ids[2]]
    assert normal["cursor"] == max_id
