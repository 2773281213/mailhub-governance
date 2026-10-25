"""服务商注册表与账户接口：认证方式只在 providers.py 定义一次。"""
import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest  # noqa: E402

import app as mailhub_app  # noqa: E402
import db  # noqa: E402
import oauth_auth  # noqa: E402
import providers  # noqa: E402


@pytest.fixture
def clean_accounts(monkeypatch):
    db.init_db()
    oauth_auth.init_oauth_db()
    conn = db.get_conn()
    conn.execute("DELETE FROM accounts")
    conn.commit()
    probes = []
    monkeypatch.setattr(oauth_auth, "probe_connection",
                        lambda account, secret: probes.append(account))
    monkeypatch.setattr(mailhub_app.sync.engine, "trigger", lambda _id: None)
    for name in ("GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET", "MICROSOFT_CLIENT_ID",
                 "MICROSOFT_CLIENT_SECRET", "NETEASE_CLIENT_ID"):
        monkeypatch.delenv(name, raising=False)
    yield probes
    conn.execute("DELETE FROM accounts")
    conn.commit()


# ---------------- 注册表 ----------------

def test_every_provider_has_a_consistent_definition():
    for key, meta in providers.PROVIDERS.items():
        assert meta.key == key and meta.label
        assert meta.secret is not None or meta.oauth, key
        if meta.secret:
            assert meta.secret.kind in providers.SECRET_METHODS
            assert meta.secret.input_label
            if meta.secret.kind == providers.APP_PASSWORD:
                assert meta.secret.setup_url.startswith("https://"), key
                assert meta.secret.steps, key
        if key != "custom":
            assert meta.imap_host, key
        if meta.oauth == "netease":
            assert meta.oauth_imap_host.startswith("imapmail.") and meta.send_imap_id


def test_domains_are_unique():
    seen = {}
    for meta in providers.PROVIDERS.values():
        for domain in meta.domains:
            assert domain not in seen, (domain, seen.get(domain), meta.key)
            seen[domain] = meta.key


@pytest.mark.parametrize("email, expected", [
    ("a@QQ.com", "qq"), ("a@foxmail.com", "qq"), ("a@163.com", "163"),
    ("a@vip.126.com", "vip126"), ("a@googlemail.com", "gmail"), ("a@hotmail.com", "outlook"),
    ("a@company.example", "custom"), ("not-an-email", "custom"),
])
def test_detect(email, expected):
    assert providers.detect(email) == expected


def test_hosts_by_auth_type():
    assert providers.get("163").host_for("password") == "imap.163.com"
    assert providers.get("163").host_for("oauth") == "imapmail.163.com"
    assert providers.get("gmail").host_for("oauth") == "imap.gmail.com"


# ---------------- 对前端公开的认证方式 ----------------

def test_methods_follow_deployment_configuration(clean_accounts, monkeypatch):
    public = oauth_auth.public_providers()
    assert [m["type"] for m in public["gmail"]["methods"]] == ["app_password"]
    assert public["outlook"]["methods"] == []
    assert "MICROSOFT_CLIENT_ID" in public["outlook"]["unavailable"]

    monkeypatch.setenv("GOOGLE_CLIENT_ID", "g-id")
    monkeypatch.setenv("GOOGLE_CLIENT_SECRET", "g-secret")
    monkeypatch.setenv("MICROSOFT_CLIENT_ID", "ms-id")
    monkeypatch.setenv("NETEASE_CLIENT_ID", "approved-appid")
    public = oauth_auth.public_providers()
    assert [m["type"] for m in public["gmail"]["methods"]] == ["oauth_browser", "app_password"]
    assert [m["type"] for m in public["outlook"]["methods"]] == ["oauth_device"]
    assert [m["type"] for m in public["163"]["methods"]] == ["oauth_browser", "app_password"]
    assert public["outlook"]["unavailable"] == ""
    assert "g-secret" not in repr(public) and "g-id" not in repr(public)


def test_secret_method_carries_its_own_guide(clean_accounts):
    method = oauth_auth.public_providers()["exmail"]["methods"][0]
    assert method["type"] == "app_password"
    assert method["input_label"] == "企业邮客户端专用密码"
    assert method["setup_url"] == "https://exmail.qq.com/"
    assert method["steps"]


# ---------------- 账户接口 ----------------

def _body(**kw):
    data = {"email": "user@163.com", "secret": "auth-code"}
    data.update(kw)
    return mailhub_app.AccountBody(**data)


def test_password_account_uses_registry_host(clean_accounts):
    result = asyncio.run(mailhub_app.create_account(_body(imap_host="evil.example")))
    row = db.get_conn().execute("SELECT * FROM accounts WHERE id=?", (result["id"],)).fetchone()
    assert row["provider"] == "163" and row["imap_host"] == "imap.163.com"
    assert clean_accounts[0]["imap_host"] == "imap.163.com"


def test_custom_account_requires_host(clean_accounts):
    with pytest.raises(mailhub_app.HTTPException, match="IMAP 服务器"):
        asyncio.run(mailhub_app.create_account(_body(email="me@company.example")))
    result = asyncio.run(mailhub_app.create_account(
        _body(email="me@company.example", imap_host="mail.company.example", imap_port=1993)))
    row = db.get_conn().execute("SELECT * FROM accounts WHERE id=?", (result["id"],)).fetchone()
    assert (row["imap_host"], row["imap_port"]) == ("mail.company.example", 1993)


def test_oauth_only_provider_rejects_passwords(clean_accounts):
    with pytest.raises(mailhub_app.HTTPException, match="只能通过 OAuth") as exc_info:
        asyncio.run(mailhub_app.create_account(_body(email="me@outlook.com")))
    assert exc_info.value.status_code == 400


def test_duplicate_account_is_rejected(clean_accounts):
    asyncio.run(mailhub_app.create_account(_body()))
    with pytest.raises(mailhub_app.HTTPException) as exc_info:
        asyncio.run(mailhub_app.create_account(_body(email="User@163.com")))
    assert exc_info.value.status_code == 409
    count = db.get_conn().execute("SELECT COUNT(*) c FROM accounts").fetchone()["c"]
    assert count == 1
