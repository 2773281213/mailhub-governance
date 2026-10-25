import asyncio
import base64
import hashlib
import sys
import time
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import app as mailhub_app
import db
import mail_client
import oauth_auth
from security import decrypt, encrypt


@pytest.fixture
def isolated_db(tmp_path, monkeypatch):
    old = getattr(db._local, "conn", None)
    if old is not None:
        old.close()
        del db._local.conn
    monkeypatch.setattr(db, "DB_PATH", str(tmp_path / "mailhub.db"))
    db.init_db()
    oauth_auth.init_oauth_db()
    yield db.get_conn()
    current = getattr(db._local, "conn", None)
    if current is not None:
        current.close()
        del db._local.conn


def _tx(**overrides):
    data = {
        "provider": "gmail",
        "email": "user@gmail.com",
        "display_name": "Gmail",
        "color": "#38bdf8",
        "sync_interval": 300,
        "account_id": 0,
        "client_id": "client-id",
        "scope": "https://mail.google.com/",
    }
    data.update(overrides)
    return data


def test_provider_detection():
    assert oauth_auth.detect_provider("one@qq.com") == "qq"
    assert oauth_auth.detect_provider("two@163.com") == "163"
    assert oauth_auth.detect_provider("three@126.com") == "126"
    assert oauth_auth.detect_provider("User@Yeah.Net") == "yeah"
    assert oauth_auth.detect_provider("four@gmail.com") == "gmail"
    assert oauth_auth.detect_provider("five@hotmail.com") == "outlook"
    assert oauth_auth.detect_provider("six@example.org") == "custom"


def test_pkce_challenge_matches_verifier():
    verifier, challenge = oauth_auth._pkce_pair()
    expected = base64.urlsafe_b64encode(
        hashlib.sha256(verifier.encode("ascii")).digest()
    ).rstrip(b"=").decode("ascii")
    assert 43 <= len(verifier) <= 128
    assert challenge == expected


def test_public_provider_metadata_never_exposes_oauth_credentials(isolated_db, monkeypatch):
    monkeypatch.setenv("GOOGLE_CLIENT_ID", "visible-only-to-server")
    monkeypatch.setenv("GOOGLE_CLIENT_SECRET", "must-not-leak")
    result = oauth_auth.public_providers()
    rendered = repr(result)
    assert "must-not-leak" not in rendered
    assert "visible-only-to-server" not in rendered
    assert result["gmail"]["oauth"]["browser"] is True
    assert result["outlook"]["oauth"]["device"] is False
    assert result["qq"]["setup_url"].startswith("https://mail.qq.com/")
    assert result["163"]["setup_url"].startswith("https://mail.163.com/")
    assert result["163"]["guided_auth"] == "netease_app_password"
    assert result["126"]["guided_auth"] == "netease_app_password"
    assert result["yeah"]["guided_auth"] == "netease_app_password"
    assert result["gmail"]["guided_auth"] == ""
    assert result["126"]["host"] == "imap.126.com"
    assert result["yeah"]["host"] == "imap.yeah.net"
    assert result["yeah"]["domains"] == ["yeah.net"]


def test_account_body_auto_detects_provider_and_rejects_mismatch():
    auto = mailhub_app.AccountBody(email="User@Yeah.Net", secret="authorization-code")
    mailhub_app._normalize_account_body(auto)
    assert auto.provider == "yeah"
    assert auto.email == "user@yeah.net"

    mismatch = mailhub_app.AccountBody(
        provider="163", email="user@126.com", secret="authorization-code",
    )
    with pytest.raises(mailhub_app.HTTPException, match="已识别为"):
        mailhub_app._normalize_account_body(mismatch)


def test_google_browser_config_falls_back_to_encrypted_settings(isolated_db, monkeypatch):
    monkeypatch.delenv("GOOGLE_CLIENT_ID", raising=False)
    monkeypatch.delenv("GOOGLE_CLIENT_SECRET", raising=False)
    db.set_setting("google_oauth_client_id", "stored-client")
    db.set_setting("google_oauth_client_secret_enc", encrypt("stored-secret"))
    config = oauth_auth._browser_config("gmail")
    assert config["client_id"] == "stored-client"
    assert config["client_secret"] == "stored-secret"
    assert config["source"] == "settings"
    info = oauth_auth.browser_config_info("gmail")
    assert info["configured"] is True
    assert info["secret_set"] is True
    assert "stored-secret" not in repr(info)


def test_complete_environment_pair_overrides_stored_google_config(isolated_db, monkeypatch):
    db.set_setting("google_oauth_client_id", "stored-client")
    db.set_setting("google_oauth_client_secret_enc", encrypt("stored-secret"))
    monkeypatch.setenv("GOOGLE_CLIENT_ID", "env-client")
    monkeypatch.setenv("GOOGLE_CLIENT_SECRET", "env-secret")
    config = oauth_auth._browser_config("gmail")
    assert config["client_id"] == "env-client"
    assert config["client_secret"] == "env-secret"
    assert config["source"] == "environment"


def test_partial_sources_are_never_mixed(isolated_db, monkeypatch):
    db.set_setting("google_oauth_client_id", "")
    db.set_setting("google_oauth_client_secret_enc", encrypt("stored-secret"))
    monkeypatch.setenv("GOOGLE_CLIENT_ID", "env-client")
    monkeypatch.delenv("GOOGLE_CLIENT_SECRET", raising=False)
    assert oauth_auth._browser_config("gmail") is None


def test_outlook_private_google_identity_is_rejected(isolated_db, monkeypatch):
    monkeypatch.delenv("GOOGLE_REDIRECT_URI", raising=False)
    monkeypatch.setenv("GOOGLE_CLIENT_ID", oauth_auth._OUTLOOK_GOOGLE_CLIENT_ID)
    monkeypatch.setenv("GOOGLE_CLIENT_SECRET", "copied-secret")
    assert oauth_auth._browser_config("gmail") is None
    info = oauth_auth.browser_config_info("gmail")
    assert info["configured"] is False
    assert "Outlook 私有" in info["configuration_error"]

    monkeypatch.delenv("GOOGLE_CLIENT_ID", raising=False)
    monkeypatch.delenv("GOOGLE_CLIENT_SECRET", raising=False)
    with pytest.raises(mailhub_app.HTTPException, match="Outlook 私有"):
        asyncio.run(mailhub_app.put_settings(mailhub_app.SettingsBody(
            google_oauth_client_id=oauth_auth._OUTLOOK_GOOGLE_CLIENT_ID,
            google_oauth_client_secret="copied-secret",
        )))
    assert db.get_setting("google_oauth_client_id", "") == ""


def test_outlook_oauth_relay_redirect_is_rejected(isolated_db, monkeypatch):
    monkeypatch.setenv("GOOGLE_CLIENT_ID", "mailhub-client")
    monkeypatch.setenv("GOOGLE_CLIENT_SECRET", "mailhub-secret")
    monkeypatch.setenv(
        "GMAIL_REDIRECT_URI",
        "https://olmoauth.outlook.com/api/googleoauthredir/outlook-oauth://private",
    )
    assert oauth_auth._browser_config("gmail") is None
    assert "中转回调" in oauth_auth.browser_config_info("gmail")["configuration_error"]


def test_partial_environment_does_not_fall_back_to_stored_config(isolated_db, monkeypatch):
    db.set_setting("microsoft_oauth_client_id", "stored-client")
    db.set_setting("microsoft_oauth_client_secret_enc", encrypt("stored-secret"))
    monkeypatch.setenv("MICROSOFT_CLIENT_ID", "environment-client")
    monkeypatch.delenv("MICROSOFT_CLIENT_SECRET", raising=False)
    assert oauth_auth._browser_config("outlook") is None
    info = oauth_auth.browser_config_info("outlook")
    assert info["configured"] is False
    assert info["source"] == "environment"
    assert info["client_id"] == "environment-client"
    assert info["secret_set"] is False


def test_microsoft_settings_enable_browser_and_device_flows(isolated_db, monkeypatch):
    monkeypatch.delenv("MICROSOFT_CLIENT_ID", raising=False)
    monkeypatch.delenv("MICROSOFT_CLIENT_SECRET", raising=False)
    asyncio.run(mailhub_app.put_settings(mailhub_app.SettingsBody(
        microsoft_oauth_client_id="mailhub-client",
        microsoft_oauth_client_secret="mailhub-secret",
    )))
    assert decrypt(db.get_setting("microsoft_oauth_client_secret_enc")) == "mailhub-secret"
    assert oauth_auth._device_client_id() == "mailhub-client"
    config = oauth_auth._browser_config("outlook")
    assert config["client_id"] == "mailhub-client"
    assert config["client_secret"] == "mailhub-secret"
    public = asyncio.run(mailhub_app.get_settings())
    assert public["microsoft_oauth_configured"] is True
    assert public["microsoft_oauth_device_configured"] is True
    assert "mailhub-secret" not in repr(public)


def test_microsoft_client_id_only_enables_device_flow(isolated_db, monkeypatch):
    monkeypatch.delenv("MICROSOFT_CLIENT_ID", raising=False)
    monkeypatch.delenv("MICROSOFT_CLIENT_SECRET", raising=False)
    db.set_setting("microsoft_oauth_client_id", "public-mailhub-client")
    assert oauth_auth._browser_config("outlook") is None
    assert oauth_auth._device_client_id() == "public-mailhub-client"
    providers = oauth_auth.public_providers()
    assert providers["outlook"]["oauth"] == {"browser": False, "device": True}


def test_outlook_new_login_has_no_hardcoded_client_fallback(isolated_db, monkeypatch):
    monkeypatch.delenv("MICROSOFT_CLIENT_ID", raising=False)
    monkeypatch.delenv("MICROSOFT_CLIENT_SECRET", raising=False)
    assert oauth_auth._device_client_id() == ""
    assert oauth_auth.public_providers()["outlook"]["oauth"]["device"] is False
    with pytest.raises(mailhub_app.HTTPException, match="Microsoft OAuth 客户端未配置"):
        asyncio.run(oauth_auth.outlook_device_start(oauth_auth.OAuthStartBody(
            provider="outlook", email="user@outlook.com",
        )))


def test_google_settings_encrypt_secret_and_never_return_it(isolated_db, monkeypatch):
    monkeypatch.delenv("GOOGLE_CLIENT_ID", raising=False)
    monkeypatch.delenv("GOOGLE_CLIENT_SECRET", raising=False)
    asyncio.run(mailhub_app.put_settings(mailhub_app.SettingsBody(
        google_oauth_client_id="web-client",
        google_oauth_client_secret="web-secret",
    )))
    stored = db.get_setting("google_oauth_client_secret_enc")
    assert stored != "web-secret"
    assert decrypt(stored) == "web-secret"
    public = asyncio.run(mailhub_app.get_settings())
    assert public["google_oauth_configured"] is True
    assert public["google_oauth_client_secret_set"] is True
    assert "web-secret" not in repr(public)


def test_blank_google_secret_preserves_old_value_and_explicit_clear_removes_it(isolated_db):
    db.set_setting("google_oauth_client_id", "web-client")
    db.set_setting("google_oauth_client_secret_enc", encrypt("old-secret"))
    asyncio.run(mailhub_app.put_settings(mailhub_app.SettingsBody(
        google_oauth_client_id="web-client",
        google_oauth_client_secret="",
    )))
    assert decrypt(db.get_setting("google_oauth_client_secret_enc")) == "old-secret"
    asyncio.run(mailhub_app.put_settings(mailhub_app.SettingsBody(
        clear_google_oauth_client_secret=True,
    )))
    assert db.get_setting("google_oauth_client_secret_enc") == ""


def test_browser_start_contains_state_and_pkce_but_no_secret(isolated_db, monkeypatch):
    monkeypatch.setenv("GOOGLE_CLIENT_ID", "google-client")
    monkeypatch.setenv("GOOGLE_CLIENT_SECRET", "google-secret")
    result = asyncio.run(oauth_auth.oauth_start(oauth_auth.OAuthStartBody(
        provider="gmail",
        email="user@gmail.com",
    )))
    parsed = urlparse(result["authorization_url"])
    query = parse_qs(parsed.query)
    assert parsed.netloc == "accounts.google.com"
    assert query["client_id"] == ["google-client"]
    assert query["response_type"] == ["code"]
    assert query["code_challenge_method"] == ["S256"]
    assert query["state"][0]
    assert query["code_challenge"][0]
    assert query["access_type"] == ["offline"]
    assert "consent" in query["prompt"][0]
    assert query["scope"] == ["https://mail.google.com/"]
    assert "calendar" not in query["scope"][0]
    assert "contacts" not in query["scope"][0]
    assert "drive" not in query["scope"][0]
    assert "client_secret" not in query
    assert "google-secret" not in result["authorization_url"]


def test_browser_start_uses_google_settings_config(isolated_db, monkeypatch):
    monkeypatch.delenv("GOOGLE_CLIENT_ID", raising=False)
    monkeypatch.delenv("GOOGLE_CLIENT_SECRET", raising=False)
    db.set_setting("google_oauth_client_id", "settings-client")
    db.set_setting("google_oauth_client_secret_enc", encrypt("settings-secret"))
    result = asyncio.run(oauth_auth.oauth_start(oauth_auth.OAuthStartBody(
        provider="gmail",
        email="user@gmail.com",
    )))
    query = parse_qs(urlparse(result["authorization_url"]).query)
    assert query["client_id"] == ["settings-client"]
    assert "settings-secret" not in result["authorization_url"]


def test_netease_oauth_rejects_outlook_private_identity(isolated_db, monkeypatch):
    monkeypatch.setenv("NETEASE_CLIENT_ID", oauth_auth._OUTLOOK_NETEASE_APP_ID)
    monkeypatch.setenv("NETEASE_DEVICE_ID", "mailhub-installation")
    monkeypatch.setenv("NETEASE_163_REDIRECT_URI", "https://olmoauth.outlook.com/api/neteaseoauthredir")
    assert oauth_auth._netease_config("163") is None
    assert oauth_auth.public_providers()["163"]["oauth"]["browser"] is False


def test_netease_start_matches_apk_parameter_contract(isolated_db, monkeypatch):
    monkeypatch.setenv("NETEASE_CLIENT_ID", "approved-mailhub-client")
    monkeypatch.setenv("NETEASE_DEVICE_ID", "mailhub-installation")
    monkeypatch.setenv(
        "NETEASE_163_REDIRECT_URI", "https://email.example.test/api/oauth/163/callback",
    )
    result = asyncio.run(oauth_auth.oauth_start(oauth_auth.OAuthStartBody(
        provider="163",
        email="user@163.com",
    )))
    parsed = urlparse(result["authorization_url"])
    query = parse_qs(parsed.query)
    assert parsed.netloc == "mail.163.com"
    assert parsed.path.endswith("/mailsrv-oauth2-fapi/oauth2/authorize/entry")
    assert query["uid"] == ["user@163.com"]
    assert query["appid"] == ["approved-mailhub-client"]
    assert query["device_id"] == ["mailhub-installation"]
    assert query["scope"] == ["imap"]
    assert query["responseType"] == ["token"]
    assert query["redirectUrl"] == ["https://email.example.test/api/oauth/163/callback"]
    assert query["state"][0]
    assert "client_secret" not in query


def test_netease_callback_validates_uid_and_stores_long_lived_token(
        isolated_db, monkeypatch):
    monkeypatch.setenv("NETEASE_CLIENT_ID", "approved-mailhub-client")
    monkeypatch.setenv("NETEASE_DEVICE_ID", "mailhub-installation")
    started = asyncio.run(oauth_auth.oauth_start(oauth_auth.OAuthStartBody(
        provider="163", email="user@163.com",
    )))
    state = parse_qs(urlparse(started["authorization_url"]).query)["state"][0]
    probes = []
    monkeypatch.setattr(oauth_auth, "probe_connection", lambda account, token: probes.append((account, token)))
    monkeypatch.setattr(oauth_auth, "_upsert_oauth_account", lambda tx, token: 42)
    response = asyncio.run(oauth_auth.oauth_callback(
        "163", state=state, uid="user@163.com", access_token="netease-token",
    ))
    assert b'"ok": true' in response.body
    assert probes[0][0]["auth_type"] == "oauth"
    assert probes[0][0]["imap_host"] == "imapmail.163.com"
    assert probes[0][1] == "netease-token"

    reused = asyncio.run(oauth_auth.oauth_callback(
        "163", state=state, uid="user@163.com", access_token="netease-token",
    ))
    assert b'"ok": false' in reused.body


def test_netease_callback_rejects_mismatched_mailbox(isolated_db, monkeypatch):
    monkeypatch.setenv("NETEASE_CLIENT_ID", "approved-mailhub-client")
    monkeypatch.setenv("NETEASE_DEVICE_ID", "mailhub-installation")
    started = asyncio.run(oauth_auth.oauth_start(oauth_auth.OAuthStartBody(
        provider="163", email="user@163.com",
    )))
    state = parse_qs(urlparse(started["authorization_url"]).query)["state"][0]
    response = asyncio.run(oauth_auth.oauth_callback(
        "163", state=state, uid="attacker@163.com", access_token="netease-token",
    ))
    assert b'"ok": false' in response.body
    assert isolated_db.execute("SELECT COUNT(*) c FROM accounts").fetchone()["c"] == 0


def test_callback_page_removes_sensitive_query_from_browser_history():
    response = oauth_auth._callback_html({
        "type": "mailhub-oauth", "ok": True, "message": "ok",
    })
    assert b'history.replaceState(null,"",location.pathname)' in response.body
    assert response.headers["cache-control"] == "no-store"


def test_device_start_keeps_device_code_server_side(isolated_db, monkeypatch):
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

    monkeypatch.setattr(oauth_auth.httpx, "post", lambda *args, **kwargs: Response())
    monkeypatch.setenv("MICROSOFT_CLIENT_ID", "mailhub-public-client")
    result = asyncio.run(oauth_auth.outlook_device_start(oauth_auth.OAuthStartBody(
        provider="outlook",
        email="user@outlook.com",
    )))
    assert "device_code" not in result
    row = isolated_db.execute(
        "SELECT device_code_enc FROM oauth_transactions WHERE id=?",
        (result["transaction_id"],),
    ).fetchone()
    assert row["device_code_enc"] != "private-device-code"
    assert decrypt(row["device_code_enc"]) == "private-device-code"


def test_browser_transaction_is_single_use(isolated_db):
    data = {
        "provider": "gmail",
        "email": "user@gmail.com",
        "name": "User",
        "color": "#38bdf8",
        "poll_interval": 300,
        "account_id": 0,
    }
    _tx_id, state = oauth_auth._create_browser_transaction(
        data, "client-id", "scope", "https://example.test/callback", "verifier",
    )
    transaction = oauth_auth._take_browser_transaction(state, "gmail")
    assert decrypt(transaction["verifier_enc"]) == "verifier"
    with pytest.raises(oauth_auth.OAuthError):
        oauth_auth._take_browser_transaction(state, "gmail")


def test_expired_browser_transaction_is_rejected(isolated_db):
    data = {
        "provider": "gmail",
        "email": "user@gmail.com",
        "name": "User",
        "color": "#38bdf8",
        "poll_interval": 300,
        "account_id": 0,
    }
    tx_id, state = oauth_auth._create_browser_transaction(
        data, "client-id", "scope", "https://example.test/callback", "verifier",
    )
    isolated_db.execute("UPDATE oauth_transactions SET expires_ts=? WHERE id=?", (time.time() - 1, tx_id))
    isolated_db.commit()
    with pytest.raises(oauth_auth.OAuthError, match="过期"):
        oauth_auth._take_browser_transaction(state, "gmail")


def test_probe_always_closes_session(monkeypatch):
    class Session:
        closed = False

        def select_inbox(self):
            raise RuntimeError("probe failed")

        def close(self):
            self.closed = True

    session = Session()
    monkeypatch.setattr(mail_client, "open_session", lambda *_: session)
    with pytest.raises(RuntimeError, match="probe failed"):
        oauth_auth.probe_connection({}, "credential")
    assert session.closed is True


def test_legacy_outlook_login_endpoints_are_disabled(isolated_db):
    with pytest.raises(mailhub_app.HTTPException) as exc_info:
        asyncio.run(mailhub_app.outlook_devicecode())
    assert exc_info.value.status_code == 410
    with pytest.raises(mailhub_app.HTTPException) as exc_info:
        asyncio.run(mailhub_app.outlook_poll(mailhub_app.DevicePollBody(
            device_code="legacy-code", email="claimed@outlook.com",
        )))
    assert exc_info.value.status_code == 410
    assert isolated_db.execute("SELECT COUNT(*) c FROM accounts").fetchone()["c"] == 0


def test_netease_probe_order_is_login_then_id_then_select_and_close(monkeypatch):
    events = []

    class Session:
        def __init__(self, host, port):
            events.append(("connect", host, port))

        def login_password(self, user, password):
            events.append(("login", user, password))

        def send_id(self):
            events.append(("id",))

        def select_inbox(self):
            events.append(("select",))

        def close(self):
            events.append(("close",))

    monkeypatch.setattr(mail_client, "ImapSession", Session)
    oauth_auth.probe_connection({
        "provider": "163", "auth_type": "password", "email": "user@163.com",
        "imap_host": "imap.163.com", "imap_port": 993,
    }, "authorization-code")
    assert [event[0] for event in events] == ["connect", "login", "id", "select", "close"]


def test_imap_id_uses_official_fields_and_rejects_bad_response():
    calls = []

    class Connection:
        response = ("OK", [b"accepted"])

        def xatom(self, command, payload):
            calls.append((command, payload))
            return self.response

    session = object.__new__(mail_client.ImapSession)
    session.conn = Connection()
    session.send_id()
    command, payload = calls[0]
    assert command == "ID"
    assert '"name" "MailHub"' in payload
    assert '"support-email"' in payload
    assert '"contact"' not in payload

    session.conn.response = ("BAD", [b"Unsafe Login"])
    with pytest.raises(RuntimeError, match="客户端身份声明"):
        session.send_id()


def test_netease_login_failure_closes_session_and_shows_actionable_help(monkeypatch):
    events = []

    class Session:
        def __init__(self, *_args):
            pass

        def login_password(self, *_args):
            raise RuntimeError("LOGIN authentication failed")

        def close(self):
            events.append("close")

    monkeypatch.setattr(mail_client, "ImapSession", Session)
    with pytest.raises(RuntimeError, match="客户端授权密码") as exc_info:
        mail_client.open_session({
            "provider": "126", "auth_type": "password", "email": "user@126.com",
            "imap_host": "imap.126.com", "imap_port": 993,
        }, "wrong-password")
    assert events == ["close"]
    assert "完整邮箱地址" in str(exc_info.value)
    assert "IMAP/SMTP" in str(exc_info.value)


def test_oauth_upsert_is_idempotent_and_encrypts_tokens(isolated_db):
    token = {"access_token": "access-one", "refresh_token": "refresh-one", "expires_in": 3600}
    first = oauth_auth._upsert_oauth_account(_tx(), token)
    second = oauth_auth._upsert_oauth_account(
        _tx(display_name="Updated"),
        {"access_token": "access-two", "refresh_token": "refresh-two", "expires_in": 3600},
    )
    assert second == first
    row = isolated_db.execute("SELECT * FROM accounts WHERE id=?", (first,)).fetchone()
    assert isolated_db.execute("SELECT COUNT(*) c FROM accounts").fetchone()["c"] == 1
    assert row["name"] == "Updated"
    assert row["auth_type"] == "oauth"
    assert row["secret_enc"] == ""
    assert row["oauth_access_enc"] != "access-two"
    assert decrypt(row["oauth_access_enc"]) == "access-two"
    assert decrypt(row["oauth_refresh_enc"]) == "refresh-two"


def test_refresh_dispatch_updates_rotated_token(isolated_db, monkeypatch):
    cur = isolated_db.execute("""INSERT INTO accounts(
        name, provider, email, imap_host, imap_port, auth_type, oauth_refresh_enc,
        oauth_access_enc, oauth_expires, oauth_client_id, oauth_scope, created_ts)
        VALUES(?,?,?,?,?,'oauth',?,?,?,?,?,?)""",
        ("Gmail", "gmail", "user@gmail.com", "imap.gmail.com", 993,
         encrypt("old-refresh"), encrypt("expired-access"), 0,
         "client-id", "https://mail.google.com/", time.time()))
    isolated_db.commit()
    monkeypatch.setattr(oauth_auth, "refresh_provider_token", lambda *args: {
        "access_token": "new-access",
        "refresh_token": "new-refresh",
        "expires_in": 7200,
    })
    account = dict(isolated_db.execute("SELECT * FROM accounts WHERE id=?", (cur.lastrowid,)).fetchone())
    assert oauth_auth.resolve_oauth_access_token(account) == "new-access"
    row = isolated_db.execute("SELECT * FROM accounts WHERE id=?", (cur.lastrowid,)).fetchone()
    assert decrypt(row["oauth_refresh_enc"]) == "new-refresh"
    assert row["oauth_reauth_required"] == 0


def _insert_oauth_account(conn, provider, email, client_id=""):
    cur = conn.execute("""INSERT INTO accounts(
        name, provider, email, imap_host, imap_port, auth_type, oauth_refresh_enc,
        oauth_access_enc, oauth_expires, oauth_client_id, created_ts)
        VALUES(?,?,?,?,?,'oauth',?,?,0,?,?)""",
        (provider, provider, email, "imap.example.test", 993,
         encrypt("legacy-refresh"), encrypt("expired-access"), client_id, time.time()))
    conn.commit()
    return cur.lastrowid


def test_legacy_outlook_account_without_own_client_requires_reauth(isolated_db):
    """旧版设备码登录借用了 Thunderbird 的 Client ID：不再刷新，要求重新授权"""
    acc = _insert_oauth_account(isolated_db, "outlook", "user@outlook.com")
    account = dict(isolated_db.execute("SELECT * FROM accounts WHERE id=?", (acc,)).fetchone())

    with pytest.raises(oauth_auth.OAuthError) as exc_info:
        oauth_auth.resolve_oauth_access_token(account)

    assert exc_info.value.reauth_required is True
    row = isolated_db.execute("SELECT oauth_reauth_required FROM accounts WHERE id=?", (acc,)).fetchone()
    assert row["oauth_reauth_required"] == 1
    assert not hasattr(mail_client, "ms_refresh")
    assert not hasattr(mail_client, "MS_CLIENT_ID")


def test_startup_marks_borrowed_identity_accounts_for_reauth(isolated_db):
    legacy_outlook = _insert_oauth_account(isolated_db, "outlook", "old@outlook.com")
    own_outlook = _insert_oauth_account(isolated_db, "outlook", "new@outlook.com", "own-client")
    borrowed_netease = _insert_oauth_account(
        isolated_db, "163", "user@163.com", oauth_auth._OUTLOOK_NETEASE_APP_ID)
    own_netease = _insert_oauth_account(isolated_db, "126", "user@126.com", "approved-client")

    oauth_auth.init_oauth_db()
    oauth_auth.init_oauth_db()   # 幂等

    flags = {r["id"]: r["oauth_reauth_required"] for r in isolated_db.execute(
        "SELECT id, oauth_reauth_required FROM accounts").fetchall()}
    assert flags == {legacy_outlook: 1, own_outlook: 0, borrowed_netease: 1, own_netease: 0}
    error = isolated_db.execute(
        "SELECT last_error FROM accounts WHERE id=?", (legacy_outlook,)).fetchone()["last_error"]
    assert "重新登录" in error


def test_netease_brand_catalog_matches_apk(isolated_db):
    providers = oauth_auth.public_providers()
    expected = {
        "163": ("163.com", "imapmail.163.com"),
        "126": ("126.com", "imapmail.126.com"),
        "yeah": ("yeah.net", "imapmail.yeah.net"),
        "188": ("188.com", "imapmail.188.com"),
        "vip163": ("vip.163.com", "imapmail.vip.163.com"),
        "vip126": ("vip.126.com", "imapmail.vip.126.com"),
        "vip188": ("vip.188.com", "imapmail.188.com"),
    }
    for brand, (domain, host) in expected.items():
        assert providers[brand]["domains"] == [domain]
        assert oauth_auth._NETEASE_OAUTH_IMAP_HOSTS[brand] == host
        assert oauth_auth.detect_provider(f"user@{domain}") == brand
        assert oauth_auth._NETEASE_OAUTH_ENDPOINTS[brand].endswith(
            "/fgw/mailsrv-oauth2-fapi/oauth2/authorize/entry"
        )
    assert oauth_auth._NETEASE_OAUTH_ENDPOINTS["vip163"].startswith("https://vip.163.com/")


def test_netease_fragment_callback_completes_implicit_flow(isolated_db, monkeypatch):
    monkeypatch.setenv("NETEASE_CLIENT_ID", "approved-mailhub-client")
    monkeypatch.setenv("NETEASE_DEVICE_ID", "mailhub-installation")
    started = asyncio.run(oauth_auth.oauth_start(oauth_auth.OAuthStartBody(
        provider="vip163", email="user@vip.163.com",
    )))
    state = parse_qs(urlparse(started["authorization_url"]).query)["state"][0]
    probes = []
    monkeypatch.setattr(
        oauth_auth, "probe_connection",
        lambda account, token: probes.append((account, token)),
    )
    monkeypatch.setattr(oauth_auth, "_upsert_oauth_account", lambda tx, token: 7)

    result = asyncio.run(oauth_auth.oauth_fragment_callback(
        "vip163",
        oauth_auth.FragmentCallbackBody(
            state=state, uid="user@vip.163.com", accessToken="camel-token",
        ),
    ))
    assert result["ok"] is True and result["account_id"] == 7
    assert probes[0][0]["imap_host"] == "imapmail.vip.163.com"
    assert probes[0][1] == "camel-token"

    with pytest.raises(mailhub_app.HTTPException) as exc_info:
        asyncio.run(oauth_auth.oauth_fragment_callback(
            "vip163",
            oauth_auth.FragmentCallbackBody(
                state=state, uid="user@vip.163.com", accessToken="camel-token",
            ),
        ))
    assert exc_info.value.status_code == 400
    assert len(probes) == 1


def test_netease_fragment_callback_rejects_other_provider(isolated_db):
    with pytest.raises(mailhub_app.HTTPException) as exc_info:
        asyncio.run(oauth_auth.oauth_fragment_callback(
            "gmail", oauth_auth.FragmentCallbackBody(state="x"),
        ))
    assert exc_info.value.status_code == 404


def test_netease_callback_without_state_renders_fragment_capture_page():
    response = asyncio.run(oauth_auth.oauth_callback("163"))
    body = response.body.decode("utf-8")
    assert "URLSearchParams" in body
    assert "/api/oauth/163/fragment" in body
    assert "history.replaceState" in body
    assert response.headers["cache-control"] == "no-store"
    assert "connect-src 'self'" in response.headers["content-security-policy"]


def test_netease_query_callback_accepts_camel_case_token(isolated_db, monkeypatch):
    monkeypatch.setenv("NETEASE_CLIENT_ID", "approved-mailhub-client")
    monkeypatch.setenv("NETEASE_DEVICE_ID", "mailhub-installation")
    started = asyncio.run(oauth_auth.oauth_start(oauth_auth.OAuthStartBody(
        provider="163", email="user@163.com",
    )))
    state = parse_qs(urlparse(started["authorization_url"]).query)["state"][0]
    probes = []
    monkeypatch.setattr(
        oauth_auth, "probe_connection",
        lambda account, token: probes.append((account, token)),
    )
    monkeypatch.setattr(oauth_auth, "_upsert_oauth_account", lambda tx, token: 11)
    response = asyncio.run(oauth_auth.oauth_callback(
        "163", state=state, uid="user@163.com", accessToken="query-camel-token",
    ))
    assert b'"ok": true' in response.body
    assert probes[0][1] == "query-camel-token"

def test_exmail_provider_preset_matches_wecom_imap_stack(isolated_db):
    providers = oauth_auth.public_providers()
    assert providers["exmail"]["label"] == "腾讯企业邮"
    assert providers["exmail"]["host"] == "imap.exmail.qq.com"
    assert providers["exmail"]["port"] == 993
    assert providers["exmail"]["auth_modes"] == ["app_password"]
    assert providers["exmail"]["oauth"]["browser"] is False
    # 企业邮是自有域名，只能按域名猜出 custom，由用户显式选择服务商。
    assert oauth_auth.detect_provider("user@company.example") == "custom"
    assert oauth_auth.normalize_account_identity("exmail", "user@company.example") == (
        "exmail", "user@company.example",
    )
    assert mail_client.PROVIDERS["exmail"]["host"] == "imap.exmail.qq.com"

def test_netease_password_hosts_follow_mail_master(isolated_db):
    # 密码/授权码账户使用 imap.vip.*（邮箱大师主机表），OAuth 账户仍用 imapmail.*。
    assert mail_client.PROVIDERS["vip163"]["host"] == "imap.vip.163.com"
    assert mail_client.PROVIDERS["vip126"]["host"] == "imap.vip.126.com"
    assert mail_client.PROVIDERS["vip188"]["host"] == "imap.vip.188.com"
    assert mail_client.PROVIDERS["188"]["host"] == "imap.188.com"
    assert oauth_auth._NETEASE_OAUTH_IMAP_HOSTS["vip163"] == "imapmail.vip.163.com"
    assert "vip163" in mail_client.NETEASE_PROVIDERS


def test_netease_qiye_provider_preset(isolated_db):
    providers = oauth_auth.public_providers()
    assert providers["qiye163"]["label"] == "网易企业邮"
    assert providers["qiye163"]["host"] == "imap.qiye.163.com"
    assert providers["qiye163"]["auth_modes"] == ["app_password"]
    assert providers["qiye163"]["oauth"]["browser"] is False
    assert "qiye163" in mail_client.NETEASE_PROVIDERS
    # 企业自有域名只能落到 custom，由用户显式选择企业邮。
    assert oauth_auth.detect_provider("user@corp.example") == "custom"
    assert oauth_auth.normalize_account_identity("qiye163", "user@corp.example") == (
        "qiye163", "user@corp.example",
    )

def test_qq_provider_uses_guided_auth_code_flow(isolated_db):
    providers = oauth_auth.public_providers()
    qq = providers["qq"]
    assert qq["guided_auth"] == "qq_app_password"
    assert qq["auth_modes"] == ["app_password"]
    assert qq["oauth"]["browser"] is False
    assert mail_client.PROVIDERS["qq"]["host"] == "imap.qq.com"
    assert "QQ 互联" in mail_client.PROVIDERS["qq"]["help"]
    assert oauth_auth.detect_provider("user@foxmail.com") == "qq"

def test_deployment_status_reports_channels_and_reauth(isolated_db, monkeypatch):
    monkeypatch.setenv("MAILHUB_PUBLIC_URL", "https://mail.example.test")
    monkeypatch.delenv("NETEASE_CLIENT_ID", raising=False)
    monkeypatch.delenv("NETEASE_DEVICE_ID", raising=False)
    status = oauth_auth.deployment_status()
    assert status["public_url"] == "https://mail.example.test"
    assert status["ok"] is True
    assert status["channels"]["163"]["oauth_browser"] is False
    assert status["channels"]["qq"]["auth_modes"] == ["app_password"]
    assert status["channels"]["qq"]["callback_url"].endswith("/api/oauth/qq/callback")
    names = {c["name"]: c for c in status["checks"]}
    assert names["公开地址使用 HTTPS"]["ok"] is True
    assert names["网易 OAuth 通道"]["optional"] is True
    assert names["没有待重新授权的账户"]["ok"] is True


def test_deployment_status_lists_reauth_accounts(isolated_db, monkeypatch):
    monkeypatch.setenv("MAILHUB_PUBLIC_URL", "https://mail.example.test")
    monkeypatch.setenv("NETEASE_CLIENT_ID", "approved-mailhub-client")
    monkeypatch.setenv("NETEASE_DEVICE_ID", "mailhub-installation")
    isolated_db.execute("""INSERT INTO accounts(
        name, provider, email, imap_host, imap_port, auth_type, oauth_refresh_enc,
        oauth_access_enc, oauth_expires, oauth_reauth_required, last_error, created_ts)
        VALUES(?,?,?,?,?,'oauth',?,?,0,1,?,?)""",
        ("163", "163", "user@163.com", "imapmail.163.com", 993,
         encrypt("t"), encrypt("t"), "授权已过期", time.time()))
    isolated_db.commit()
    status = oauth_auth.deployment_status()
    assert status["ok"] is False
    assert status["accounts"]["oauth_total"] == 1
    assert status["accounts"]["reauth_required"][0]["email"] == "user@163.com"
    assert status["channels"]["163"]["oauth_browser"] is True
    assert status["channels"]["163"]["requires_vendor_approval"] is True
    assert status["channels"]["163"]["callback_url"] == "https://mail.example.test/api/oauth/163/callback"

def test_netease_never_uses_outlook_partner_identity(isolated_db, monkeypatch):
    """设置旧的 NETEASE_CHANNEL=outlook 也不会再借用 Outlook 的合作方 appid"""
    monkeypatch.setenv("NETEASE_CHANNEL", "outlook")
    monkeypatch.delenv("NETEASE_CLIENT_ID", raising=False)
    monkeypatch.setenv("NETEASE_DEVICE_ID", "mailhub-installation")
    assert oauth_auth._netease_config("163") is None
    assert oauth_auth.public_providers()["163"]["oauth"]["browser"] is False

    monkeypatch.setenv("NETEASE_CLIENT_ID", oauth_auth._OUTLOOK_NETEASE_APP_ID)
    assert oauth_auth._netease_config("163") is None


def test_netease_rejects_olmoauth_redirect(isolated_db, monkeypatch):
    monkeypatch.setenv("NETEASE_CLIENT_ID", "approved-mailhub-client")
    monkeypatch.setenv("NETEASE_DEVICE_ID", "mailhub-installation")
    monkeypatch.setenv("NETEASE_REDIRECT_STYLE", "olmoauth")
    monkeypatch.setenv("MAILHUB_PUBLIC_URL", "https://mail.example.test")
    cfg = oauth_auth._netease_config("163")
    assert cfg["redirect_uri"] == "https://mail.example.test/api/oauth/163/callback"
    assert set(cfg) == {"authorize_url", "client_id", "device_id", "scope", "redirect_uri"}

    monkeypatch.setenv(
        "NETEASE_163_REDIRECT_URI",
        "https://olmoauth.outlook.com/api/neteaseoauthredir/outlook-oauth://x/android/netease",
    )
    assert oauth_auth._netease_config("163") is None


def test_netease_device_id_is_generated_once_and_persisted(isolated_db, monkeypatch):
    monkeypatch.delenv("NETEASE_DEVICE_ID", raising=False)
    first = oauth_auth._netease_device_id()
    assert first.startswith("mailhub-")
    assert oauth_auth._netease_device_id() == first
    stored = isolated_db.execute(
        "SELECT v FROM settings WHERE k='netease_device_id'").fetchone()["v"]
    assert stored == first
