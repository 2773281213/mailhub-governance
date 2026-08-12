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
    assert query["code_challenge_method"] == ["S256"]
    assert query["state"][0]
    assert query["code_challenge"][0]
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


def test_existing_outlook_account_keeps_legacy_refresh_path(isolated_db, monkeypatch):
    cur = isolated_db.execute("""INSERT INTO accounts(
        name, provider, email, imap_host, imap_port, auth_type, oauth_refresh_enc,
        oauth_access_enc, oauth_expires, created_ts)
        VALUES(?,?,?,?,?,'oauth',?,?,0,?)""",
        ("Outlook", "outlook", "user@outlook.com", "outlook.office365.com", 993,
         encrypt("legacy-refresh"), encrypt("expired-access"), time.time()))
    isolated_db.commit()
    called = []

    def legacy(refresh):
        called.append(refresh)
        return {"access_token": "legacy-access", "expires_in": 3600}

    monkeypatch.setattr(mail_client, "ms_refresh", legacy)
    account = dict(isolated_db.execute("SELECT * FROM accounts WHERE id=?", (cur.lastrowid,)).fetchone())
    assert oauth_auth.resolve_oauth_access_token(account) == "legacy-access"
    assert called == ["legacy-refresh"]
