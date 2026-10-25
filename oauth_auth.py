"""邮箱 OAuth 登录、令牌刷新与统一账户接入。"""
import asyncio
import base64
import hashlib
import html
import json
import os
import re
import secrets
import sqlite3
import threading
import time
from urllib.parse import urlencode, urlparse

import httpx
from fastapi import APIRouter, HTTPException
from fastapi.responses import HTMLResponse
from pydantic import BaseModel

import mail_client
from db import get_conn, get_setting, set_setting
from security import decrypt, encrypt

router = APIRouter()

_EMAIL_RE = re.compile(r"^[^@\s]{1,64}@[^@\s]{1,189}$")
_COLOR_RE = re.compile(r"^#[0-9a-fA-F]{6}$")
_PUBLIC_URL = "https://email.11451405.xyz"
_BROWSER_TTL = 600
_NETEASE_TOKEN_TTL = 3650 * 86400
_OUTLOOK_NETEASE_APP_ID = "rjg1fubwqzie5unhx6"
_OUTLOOK_GOOGLE_CLIENT_ID = (
    "445112211283-sk04feuogpcjd3dq8eshrdnr4bpm1sfk.apps.googleusercontent.com"
)
_OUTLOOK_OAUTH_HOST = "olmoauth.outlook.com"
# Outlook APK 里网易通道的两个回调形态（authspecificconfigs/NetEase.kt）。
_OUTLOOK_NETEASE_RELAY = "https://olmoauth.outlook.com/api/neteaseoauthredir"
_OUTLOOK_NETEASE_RELAY_CUSTOM_TAB = (
    "https://olmoauth.outlook.com/api/neteaseoauthredir"
    "/outlook-oauth://{package}/android/netease/oauth2redirect"
)
_refresh_locks: dict[int, threading.Lock] = {}
_refresh_locks_guard = threading.Lock()

_PROVIDER_META = {
    "qq": {
        "domains": ["qq.com", "foxmail.com"],
        "auth_modes": ["app_password"],
        "guided_auth": "qq_app_password",
        "secret_label": "QQ 邮箱授权码",
        "setup_url": "https://mail.qq.com/",
        "setup_label": "登录 QQ 邮箱并开启 IMAP",
    },
    # 企业邮使用企业自有域名，无法按域名识别；主机取自企业微信邮件模块的 IMAP 配置。
    "exmail": {
        "domains": [],
        "auth_modes": ["app_password"],
        "secret_label": "企业邮客户端专用密码",
        "setup_url": "https://exmail.qq.com/",
        "setup_label": "登录腾讯企业邮并生成客户端专用密码",
    },
    # 网易企业邮（qiye.163.com）同样使用自有域名，主机来自网易邮箱大师的 IMAP 主机表。
    "qiye163": {
        "domains": [],
        "auth_modes": ["app_password"],
        "secret_label": "企业邮客户端授权密码",
        "setup_url": "https://qiye.163.com/",
        "setup_label": "登录网易企业邮并生成客户端授权密码",
    },
    "163": {
        "domains": ["163.com"],
        "auth_modes": ["app_password"],
        "guided_auth": "netease_app_password",
        "secret_label": "网易客户端授权密码",
        "setup_url": "https://mail.163.com/",
        "setup_label": "登录网易邮箱并获取客户端授权密码",
    },
    "126": {
        "domains": ["126.com"],
        "auth_modes": ["app_password"],
        "guided_auth": "netease_app_password",
        "secret_label": "网易客户端授权密码",
        "setup_url": "https://mail.126.com/",
        "setup_label": "登录网易邮箱并获取客户端授权密码",
    },
    "yeah": {
        "domains": ["yeah.net"],
        "auth_modes": ["app_password"],
        "guided_auth": "netease_app_password",
        "secret_label": "网易客户端授权密码",
        "setup_url": "https://mail.yeah.net/",
        "setup_label": "登录网易邮箱并获取客户端授权密码",
    },
    "gmail": {
        "domains": ["gmail.com", "googlemail.com"],
        "auth_modes": ["oauth", "app_password"],
        "secret_label": "Google 应用专用密码",
    },
    "outlook": {
        "domains": ["outlook.com", "hotmail.com", "live.com", "msn.com"],
        "auth_modes": ["oauth"],
        "secret_label": "",
    },
    "custom": {
        "domains": [],
        "auth_modes": ["password"],
        "secret_label": "邮箱密码或服务商要求的应用专用密码",
    },
}

_OAUTH_SPECS = {
    "outlook": {
        "authorize_url": "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
        "token_url": "https://login.microsoftonline.com/common/oauth2/v2.0/token",
        "device_url": "https://login.microsoftonline.com/common/oauth2/v2.0/devicecode",
        "client_id_env": "MICROSOFT_CLIENT_ID",
        "client_secret_env": "MICROSOFT_CLIENT_SECRET",
        "settings_prefix": "microsoft",
        "scope": "https://outlook.office.com/IMAP.AccessAsUser.All offline_access",
        "prompt": "select_account",
    },
    "gmail": {
        "authorize_url": "https://accounts.google.com/o/oauth2/v2/auth",
        "token_url": "https://oauth2.googleapis.com/token",
        "client_id_env": "GOOGLE_CLIENT_ID",
        "client_secret_env": "GOOGLE_CLIENT_SECRET",
        "settings_prefix": "google",
        "scope": "https://mail.google.com/",
        "prompt": "consent select_account",
    },
}

# NetEase 品牌表。域名、授权入口、IMAP 主机全部来自 Outlook Android 5.2606.0
# 的 authspecificconfigs/NetEase.kt，不要凭记忆改动其中的主机名。
_NETEASE_BRANDS = {
    "163": {
        "domain": "163.com", "web": "https://mail.163.com",
        "imap": "imapmail.163.com", "setup": "https://mail.163.com/",
    },
    "126": {
        "domain": "126.com", "web": "https://mail.126.com",
        "imap": "imapmail.126.com", "setup": "https://mail.126.com/",
    },
    "yeah": {
        "domain": "yeah.net", "web": "https://mail.yeah.net",
        "imap": "imapmail.yeah.net", "setup": "https://mail.yeah.net/",
    },
    "188": {
        "domain": "188.com", "web": "https://mail.188.com",
        "imap": "imapmail.188.com", "setup": "https://mail.188.com/",
    },
    "vip163": {
        "domain": "vip.163.com", "web": "https://vip.163.com",
        "imap": "imapmail.vip.163.com", "setup": "https://vip.163.com/",
    },
    "vip126": {
        "domain": "vip.126.com", "web": "https://vip.126.com",
        "imap": "imapmail.vip.126.com", "setup": "https://vip.126.com/",
    },
    # APK 只给出 vip.188.com 的授权入口，IMAP 仍复用 188 的 imapmail 主机。
    "vip188": {
        "domain": "vip.188.com", "web": "https://vip.188.com",
        "imap": "imapmail.188.com", "setup": "https://vip.188.com/",
    },
}
_NETEASE_OAUTH_ENDPOINTS = {
    brand: f"{meta['web']}/fgw/mailsrv-oauth2-fapi/oauth2/authorize/entry"
    for brand, meta in _NETEASE_BRANDS.items()
}
_NETEASE_OAUTH_IMAP_HOSTS = {brand: meta["imap"] for brand, meta in _NETEASE_BRANDS.items()}

for _brand, _meta in _NETEASE_BRANDS.items():
    _PROVIDER_META.setdefault(_brand, {
        "domains": [_meta["domain"]],
        "auth_modes": ["app_password"],
        "guided_auth": "netease_app_password",
        "secret_label": "网易客户端授权密码",
        "setup_url": _meta["setup"],
        "setup_label": "登录网易邮箱并获取客户端授权密码",
    })
del _brand, _meta


class OAuthError(RuntimeError):
    def __init__(self, message: str, *, reauth_required: bool = False):
        super().__init__(message)
        self.reauth_required = reauth_required


class OAuthStartBody(BaseModel):
    provider: str
    email: str
    name: str = ""
    color: str = "#38bdf8"
    poll_interval: int = 300
    account_id: int = 0


class DevicePollBody(BaseModel):
    transaction_id: str


def normalize_account_identity(provider: str, email_addr: str) -> tuple[str, str]:
    """规范化并校验服务商和邮箱地址。"""
    normalized_provider = provider.strip().lower()
    normalized_email = email_addr.strip().lower()
    if normalized_provider not in mail_client.PROVIDERS:
        raise HTTPException(status_code=400, detail="不支持的邮箱服务商")
    if not _EMAIL_RE.fullmatch(normalized_email):
        raise HTTPException(status_code=400, detail="邮箱地址格式不正确")
    return normalized_provider, normalized_email


def _validate_reauth_target(account_id: int, provider: str, email_addr: str):
    if not account_id:
        return
    row = get_conn().execute("SELECT * FROM accounts WHERE id=?", (account_id,)).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="账户不存在")
    same_identity = (
        row["provider"] == provider
        and row["email"].strip().lower() == email_addr
    )
    if not same_identity:
        raise HTTPException(
            status_code=400,
            detail="OAuth 账户的服务商和邮箱地址不能直接修改，请重新添加账户",
        )


def init_oauth_db():
    """创建 OAuth 临时事务表，并为旧账户补充兼容列。"""
    conn = get_conn()
    conn.execute("""CREATE TABLE IF NOT EXISTS oauth_transactions(
        id TEXT PRIMARY KEY,
        state_hash TEXT DEFAULT '',
        provider TEXT NOT NULL,
        flow TEXT NOT NULL,
        email TEXT NOT NULL,
        display_name TEXT DEFAULT '',
        color TEXT DEFAULT '#38bdf8',
        sync_interval INTEGER DEFAULT 300,
        account_id INTEGER DEFAULT 0,
        client_id TEXT DEFAULT '',
        scope TEXT DEFAULT '',
        redirect_uri TEXT DEFAULT '',
        verifier_enc TEXT DEFAULT '',
        device_code_enc TEXT DEFAULT '',
        expires_ts REAL NOT NULL,
        next_poll_ts REAL DEFAULT 0,
        oauth_interval INTEGER DEFAULT 5,
        created_ts REAL NOT NULL
    )""")
    conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_oauth_state ON oauth_transactions(state_hash) WHERE state_hash != ''")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_oauth_expiry ON oauth_transactions(expires_ts)")
    for sql in (
        "ALTER TABLE accounts ADD COLUMN oauth_client_id TEXT DEFAULT ''",
        "ALTER TABLE accounts ADD COLUMN oauth_scope TEXT DEFAULT ''",
        "ALTER TABLE accounts ADD COLUMN oauth_reauth_required INTEGER DEFAULT 0",
    ):
        try:
            conn.execute(sql)
        except sqlite3.OperationalError as exc:
            if "duplicate column" not in str(exc).lower():
                raise
    conn.commit()


def _normalize_start(body: OAuthStartBody, expected_provider: str = "") -> dict:
    provider, email_addr = normalize_account_identity(body.provider, body.email)
    if expected_provider and provider != expected_provider:
        raise HTTPException(status_code=400, detail="认证服务商不匹配")
    account_id = max(0, int(body.account_id or 0))
    _validate_reauth_target(account_id, provider, email_addr)
    return {
        "provider": provider,
        "email": email_addr,
        "name": (body.name or email_addr).strip()[:100],
        "color": body.color if _COLOR_RE.fullmatch(body.color or "") else "#38bdf8",
        "poll_interval": min(86400, max(60, int(body.poll_interval or 300))),
        "account_id": account_id,
    }


def detect_provider(email_addr: str) -> str:
    domain = email_addr.strip().lower().rpartition("@")[2]
    for provider, meta in _PROVIDER_META.items():
        if domain and domain in meta["domains"]:
            return provider
    return "custom"


def oauth_client_identity_error(provider: str, client_id: str) -> str:
    """Reject OAuth registrations that are bound to Outlook's app or backend."""
    normalized_id = client_id.strip().lower()
    if provider == "gmail" and normalized_id == _OUTLOOK_GOOGLE_CLIENT_ID.lower():
        return "不能使用 Outlook 私有 Google Client ID，请创建本项目自己的 Google OAuth Web 客户端"
    redirect_host = (urlparse(_redirect_uri(provider)).hostname or "").lower()
    if redirect_host == _OUTLOOK_OAUTH_HOST:
        return "不能使用 Outlook 的 OAuth 中转回调，请将回调地址登记为本项目域名"
    return ""


def _browser_config(provider: str) -> dict | None:
    spec = _OAUTH_SPECS.get(provider)
    if not spec:
        return None
    env_id = os.environ.get(spec["client_id_env"], "").strip()
    env_secret = os.environ.get(spec["client_secret_env"], "").strip()
    if env_id or env_secret:
        if env_id and env_secret and not oauth_client_identity_error(provider, env_id):
            return {**spec, "client_id": env_id, "client_secret": env_secret, "source": "environment"}
        return None
    prefix = spec["settings_prefix"]
    stored_id = get_setting(f"{prefix}_oauth_client_id", "").strip()
    stored_secret = decrypt(get_setting(f"{prefix}_oauth_client_secret_enc", ""))
    if stored_id and stored_secret and not oauth_client_identity_error(provider, stored_id):
        return {**spec, "client_id": stored_id, "client_secret": stored_secret, "source": "settings"}
    return None


def _netease_channel() -> str:
    """网易 OAuth 通道：own=自有 appid，outlook=复用 Outlook APK 逆向出的合作方身份。"""
    channel = os.environ.get("NETEASE_CHANNEL", "own").strip().lower()
    return channel if channel in {"own", "outlook"} else "own"


def _netease_redirect_style() -> str:
    style = os.environ.get("NETEASE_REDIRECT_STYLE", "web").strip().lower()
    return style if style in {"web", "olmoauth"} else "web"


def _netease_device_id() -> str:
    """设备标识必须长期稳定，首次使用时生成并持久化。"""
    env_value = os.environ.get("NETEASE_DEVICE_ID", "").strip()
    if env_value:
        return env_value
    stored = get_setting("netease_device_id", "").strip()
    if stored:
        return stored
    generated = "mailhub-" + secrets.token_hex(16)
    set_setting("netease_device_id", generated)
    return generated


def _netease_redirect_uri(provider: str) -> str:
    style = _netease_redirect_style()
    package = os.environ.get(
        "NETEASE_OLMOAUTH_PACKAGE", "com.microsoft.office.outlook",
    ).strip() or "com.microsoft.office.outlook"
    if style == "olmoauth":
        return _OUTLOOK_NETEASE_RELAY_CUSTOM_TAB.format(package=package)
    explicit = os.environ.get(f"NETEASE_{provider.upper()}_REDIRECT_URI", "").strip()
    return explicit or _redirect_uri(provider)


def _netease_config(provider: str) -> dict | None:
    """解析网易 OAuth 配置；own 通道拒绝 Outlook 身份，outlook 通道显式复用。"""
    if provider not in _NETEASE_OAUTH_ENDPOINTS:
        return None
    channel = _netease_channel()
    env_client_id = os.environ.get("NETEASE_CLIENT_ID", "").strip()
    if channel == "outlook":
        client_id = env_client_id or _OUTLOOK_NETEASE_APP_ID
        source = "environment" if env_client_id else "outlook"
    else:
        if not env_client_id or env_client_id == _OUTLOOK_NETEASE_APP_ID:
            return None
        client_id = env_client_id
        source = "environment"
    device_id = _netease_device_id()
    if not device_id:
        return None
    redirect_uri = _netease_redirect_uri(provider)
    if channel != "outlook" and "olmoauth.outlook.com" in redirect_uri.lower():
        return None
    return {
        "authorize_url": _NETEASE_OAUTH_ENDPOINTS[provider],
        "client_id": client_id,
        "device_id": device_id,
        "scope": "imap",
        "redirect_uri": redirect_uri,
        "channel": channel,
        "app_id_source": source,
        "redirect_style": _netease_redirect_style(),
    }


def deployment_status() -> dict:
    """部署自检：各登录通道是否可用、回调地址、待重新授权账户。"""
    base = _public_base_url()
    conn = get_conn()
    reauth = [dict(r) for r in conn.execute(
        "SELECT id, provider, email, last_error FROM accounts"
        " WHERE auth_type='oauth' AND oauth_reauth_required=1 ORDER BY id"
    ).fetchall()]
    oauth_total = conn.execute(
        "SELECT COUNT(*) c FROM accounts WHERE auth_type='oauth'"
    ).fetchone()["c"]

    channels = {}
    published = public_providers()
    for key in mail_client.PROVIDERS:
        meta = _PROVIDER_META.get(key, _PROVIDER_META["custom"])
        netease = _netease_config(key)
        browser = _browser_config(key) or netease
        entry = {
            "label": mail_client.PROVIDERS[key]["label"],
            "oauth_browser": browser is not None,
            "oauth_device": key == "outlook" and bool(_device_client_id()),
            "auth_modes": published[key]["auth_modes"],
            "callback_url": netease["redirect_uri"] if netease else _redirect_uri(key),
            "secret_label": meta["secret_label"],
            "requires_vendor_approval": key in _NETEASE_OAUTH_ENDPOINTS,
        }
        if key in _NETEASE_OAUTH_ENDPOINTS:
            entry["channel"] = netease["channel"] if netease else _netease_channel()
            entry["app_id_source"] = netease["app_id_source"] if netease else ""
            entry["redirect_style"] = netease["redirect_style"] if netease else _netease_redirect_style()
        channels[key] = entry

    checks = []

    def check(name: str, ok: bool, detail: str = "", optional: bool = False):
        checks.append({"name": name, "ok": bool(ok), "detail": detail, "optional": optional})

    local_dev = base.startswith("http://127.0.0.1") or base.startswith("http://localhost")
    check("公开地址使用 HTTPS", base.startswith("https://") or local_dev,
          base if base.startswith("https://") else f"{base}（本地开发地址；生产环境必须是 HTTPS）")
    netease_ready = [brand for brand in _NETEASE_BRANDS if _netease_config(brand)]
    channel = _netease_channel()
    check(
        "网易 OAuth 通道",
        bool(netease_ready),
        (f"通道 {channel}；已启用品牌: " + ", ".join(netease_ready)) if netease_ready
        else f"通道 {channel} 未生效，网易将回退客户端授权密码"
             "（自有 appid 或 NETEASE_CHANNEL=outlook 均可启用）",
        optional=True,
    )
    check("Gmail OAuth 已配置", _browser_config("gmail") is not None,
          "未配置时 Gmail 回退应用专用密码", optional=True)
    check("Outlook OAuth 已配置",
          _browser_config("outlook") is not None or bool(_device_client_id()),
          "未配置 Microsoft 客户端时不能新增 Outlook 账户", optional=True)
    check("没有待重新授权的账户", not reauth,
          ", ".join(f"{r['provider']}:{r['email']}" for r in reauth) or "全部有效")
    return {
        "public_url": base,
        "channels": channels,
        "accounts": {"oauth_total": oauth_total, "reauth_required": reauth},
        "checks": checks,
        "ok": all(item["ok"] for item in checks if not item["optional"]),
    }


@router.get("/api/oauth/status")
async def oauth_status():
    return deployment_status()


def browser_config_info(provider: str) -> dict:
    config = _browser_config(provider)
    spec = _OAUTH_SPECS.get(provider)
    if not spec:
        return {"configured": False, "source": "", "client_id": "", "callback_url": ""}
    prefix = spec["settings_prefix"]
    env_id = os.environ.get(spec["client_id_env"], "").strip()
    env_secret = os.environ.get(spec["client_secret_env"], "").strip()
    stored_id = get_setting(f"{prefix}_oauth_client_id", "").strip()
    stored_secret = decrypt(get_setting(f"{prefix}_oauth_client_secret_enc", ""))
    selected_source = "environment" if env_id or env_secret else ("settings" if stored_id or stored_secret else "")
    selected_id = env_id if selected_source == "environment" else stored_id
    return {
        "configured": config is not None,
        "source": selected_source,
        "client_id": selected_id,
        "secret_set": bool(env_secret) if selected_source == "environment" else bool(stored_secret),
        "callback_url": _redirect_uri(provider),
        "configuration_error": oauth_client_identity_error(provider, selected_id),
    }


def _device_client_id() -> str:
    env_id = os.environ.get("MICROSOFT_CLIENT_ID", "").strip()
    if env_id:
        return env_id
    if os.environ.get("MICROSOFT_CLIENT_SECRET", "").strip():
        return ""
    return get_setting("microsoft_oauth_client_id", "").strip()


def public_providers() -> dict:
    result = {}
    for key, preset in mail_client.PROVIDERS.items():
        meta = _PROVIDER_META.get(key, _PROVIDER_META["custom"])
        netease = _netease_config(key)
        browser_enabled = _browser_config(key) is not None or netease is not None
        auth_modes = list(meta["auth_modes"])
        if netease is not None and "oauth" not in auth_modes:
            auth_modes.insert(0, "oauth")
        oauth_info = {
            "browser": browser_enabled,
            "device": key == "outlook" and bool(_device_client_id()),
        }
        if key in _NETEASE_OAUTH_ENDPOINTS:
            oauth_info["channel"] = netease["channel"] if netease else _netease_channel()
            oauth_info["redirect_style"] = netease["redirect_style"] if netease else _netease_redirect_style()
        result[key] = {
            "label": preset["label"],
            "host": preset["host"],
            "port": preset["port"],
            "help": preset["help"],
            "domains": meta["domains"],
            "auth_modes": auth_modes,
            "secret_label": meta["secret_label"],
            "setup_url": meta.get("setup_url", ""),
            "setup_label": meta.get("setup_label", ""),
            "guided_auth": meta.get("guided_auth", ""),
            "oauth": oauth_info,
        }
    return result


def _public_base_url() -> str:
    return os.environ.get("MAILHUB_PUBLIC_URL", _PUBLIC_URL).strip().rstrip("/")


def _redirect_uri(provider: str) -> str:
    env_key = f"{provider.upper()}_REDIRECT_URI"
    return os.environ.get(env_key, "").strip() or f"{_public_base_url()}/api/oauth/{provider}/callback"


def _pkce_pair() -> tuple[str, str]:
    verifier = secrets.token_urlsafe(64)
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    challenge = base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")
    return verifier, challenge


def _state_hash(state: str) -> str:
    return hashlib.sha256(state.encode("utf-8")).hexdigest()


def _oauth_message(data: dict, default: str) -> str:
    message = data.get("error_description") or data.get("error") or default
    return re.sub(r"[\r\n]+", " ", str(message))[:300]


def _post_token(url: str, payload: dict) -> dict:
    try:
        response = httpx.post(url, data=payload, timeout=20)
        data = response.json()
    except Exception as exc:
        raise OAuthError(f"认证服务暂时不可用: {str(exc)[:160]}") from exc
    if response.status_code >= 400 or data.get("error"):
        code = str(data.get("error", ""))
        raise OAuthError(
            _oauth_message(data, "认证失败"),
            reauth_required=code in {"invalid_grant", "interaction_required", "invalid_token"},
        )
    return data


def exchange_code(provider: str, code: str, verifier: str, redirect_uri: str,
                  client_id: str) -> dict:
    config = _browser_config(provider)
    if not config or config["client_id"] != client_id:
        raise OAuthError("OAuth 客户端配置已变化，请重新发起登录")
    payload = {
        "client_id": client_id,
        "grant_type": "authorization_code",
        "code": code,
        "redirect_uri": redirect_uri,
        "code_verifier": verifier,
    }
    if config["client_secret"]:
        payload["client_secret"] = config["client_secret"]
    return _post_token(config["token_url"], payload)


def refresh_provider_token(provider: str, refresh_token: str, client_id: str, scope: str) -> dict:
    config = _browser_config(provider)
    if provider == "outlook" and not client_id:
        return mail_client.ms_refresh(refresh_token)
    if not config:
        raise OAuthError("该服务商的 OAuth 客户端未配置", reauth_required=True)
    if config["client_id"] != client_id:
        raise OAuthError("OAuth 客户端配置已变化，请重新登录", reauth_required=True)
    payload = {
        "client_id": client_id,
        "grant_type": "refresh_token",
        "refresh_token": refresh_token,
    }
    if scope and provider == "outlook":
        payload["scope"] = scope
    if config["client_secret"]:
        payload["client_secret"] = config["client_secret"]
    return _post_token(config["token_url"], payload)


def probe_connection(account: dict, credential: str):
    """在同一工作线程完成登录、选择收件箱和关闭，避免跨线程操作 imaplib。"""
    session = None
    try:
        session = mail_client.open_session(account, credential)
        session.select_inbox()
    finally:
        if session is not None:
            session.close()


def _cleanup_transactions(conn):
    conn.execute("DELETE FROM oauth_transactions WHERE expires_ts < ?", (time.time(),))


def _create_browser_transaction(data: dict, client_id: str, scope: str,
                                redirect_uri: str, verifier: str) -> tuple[str, str]:
    tx_id = secrets.token_urlsafe(24)
    state = secrets.token_urlsafe(32)
    now = time.time()
    conn = get_conn()
    _cleanup_transactions(conn)
    conn.execute("""INSERT INTO oauth_transactions(
        id, state_hash, provider, flow, email, display_name, color, sync_interval,
        account_id, client_id, scope, redirect_uri, verifier_enc, expires_ts, created_ts)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        (tx_id, _state_hash(state), data["provider"], "browser", data["email"], data["name"],
         data["color"], data["poll_interval"], data["account_id"], client_id, scope,
         redirect_uri, encrypt(verifier), now + _BROWSER_TTL, now))
    conn.commit()
    return tx_id, state


def _take_browser_transaction(state: str, provider: str) -> dict:
    conn = get_conn()
    row = conn.execute(
        "SELECT * FROM oauth_transactions WHERE state_hash=? AND provider=? AND flow='browser'",
        (_state_hash(state), provider),
    ).fetchone()
    if not row:
        raise OAuthError("登录状态无效或已使用")
    tx = dict(row)
    deleted = conn.execute("DELETE FROM oauth_transactions WHERE id=?", (tx["id"],)).rowcount
    conn.commit()
    if not deleted or tx["expires_ts"] < time.time():
        raise OAuthError("登录状态已过期，请重新发起登录")
    return tx


def _store_device_transaction(data: dict, device: dict, client_id: str, scope: str) -> str:
    tx_id = secrets.token_urlsafe(24)
    now = time.time()
    interval = max(5, int(device.get("interval", 5)))
    expires = min(1800, max(60, int(device.get("expires_in", 900))))
    conn = get_conn()
    _cleanup_transactions(conn)
    conn.execute("""INSERT INTO oauth_transactions(
        id, provider, flow, email, display_name, color, sync_interval, account_id,
        client_id, scope, device_code_enc, expires_ts, next_poll_ts, oauth_interval, created_ts)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        (tx_id, "outlook", "device", data["email"], data["name"], data["color"],
         data["poll_interval"], data["account_id"], client_id, scope,
         encrypt(device["device_code"]), now + expires, now + interval, interval, now))
    conn.commit()
    return tx_id


def _upsert_oauth_account(tx: dict, token_data: dict) -> int:
    provider = tx["provider"]
    preset = mail_client.PROVIDERS[provider]
    conn = get_conn()
    row = None
    if int(tx.get("account_id") or 0):
        row = conn.execute("SELECT * FROM accounts WHERE id=?", (tx["account_id"],)).fetchone()
    if row is None:
        row = conn.execute(
            "SELECT * FROM accounts WHERE provider=? AND lower(email)=lower(?) ORDER BY id LIMIT 1",
            (provider, tx["email"]),
        ).fetchone()
    refresh = token_data.get("refresh_token", "")
    if not refresh and row is not None:
        refresh = decrypt(row["oauth_refresh_enc"])
    if not refresh:
        raise OAuthError("服务商未返回长期登录凭据，请重新登录并允许离线访问")
    access = token_data.get("access_token", "")
    if not access:
        raise OAuthError("服务商未返回访问令牌")
    expires = time.time() + max(60, int(token_data.get("expires_in", 3600)))
    imap_host = _NETEASE_OAUTH_IMAP_HOSTS.get(provider, preset["host"])
    common_values = (
        tx["display_name"] or tx["email"], provider, tx["email"], imap_host, preset["port"],
        encrypt(refresh), encrypt(access), expires, tx["client_id"], tx["scope"],
        int(tx["sync_interval"]), tx["color"],
    )
    if row is None:
        cur = conn.execute("""INSERT INTO accounts(
            name, provider, email, imap_host, imap_port, auth_type, secret_enc,
            oauth_refresh_enc, oauth_access_enc, oauth_expires, oauth_client_id, oauth_scope,
            oauth_reauth_required, poll_interval, color, enabled, last_error, created_ts)
            VALUES(?,?,?,?,?,'oauth','',?,?,?,?,?,0,?,?,1,'',?)""",
            common_values + (time.time(),))
        account_id = cur.lastrowid
    else:
        account_id = row["id"]
        conn.execute("""UPDATE accounts SET
            name=?, provider=?, email=?, imap_host=?, imap_port=?, auth_type='oauth', secret_enc='',
            oauth_refresh_enc=?, oauth_access_enc=?, oauth_expires=?, oauth_client_id=?, oauth_scope=?,
            oauth_reauth_required=0, poll_interval=?, color=?, enabled=1, last_error=''
            WHERE id=?""", common_values + (account_id,))
    conn.commit()
    import sync
    sync.engine.trigger(account_id)
    return account_id


def _refresh_lock(account_id: int) -> threading.Lock:
    with _refresh_locks_guard:
        return _refresh_locks.setdefault(account_id, threading.Lock())


def _mark_reauth_required(account_id: int, message: str):
    conn = get_conn()
    conn.execute(
        "UPDATE accounts SET oauth_reauth_required=1, last_error=? WHERE id=?",
        (message[:200], account_id),
    )
    conn.commit()


def _refresh_account_token(current: dict, refresh_token: str) -> dict:
    if current["provider"] in _NETEASE_OAUTH_ENDPOINTS:
        raise OAuthError("网易邮箱授权已到期，请重新登录", reauth_required=True)
    if current["provider"] != "outlook" or current.get("oauth_client_id"):
        return refresh_provider_token(
            current["provider"],
            refresh_token,
            current.get("oauth_client_id", ""),
            current.get("oauth_scope", ""),
        )
    try:
        return mail_client.ms_refresh(refresh_token)
    except Exception as exc:
        raise OAuthError("Microsoft 登录已失效，请重新登录", reauth_required=True) from exc


def _save_refreshed_token(account_id: int, refresh_token: str, data: dict) -> str:
    access_token = data.get("access_token", "")
    if not access_token:
        raise OAuthError("刷新响应缺少访问令牌")
    conn = get_conn()
    conn.execute(
        """UPDATE accounts SET oauth_access_enc=?, oauth_refresh_enc=?, oauth_expires=?,
           oauth_reauth_required=0, last_error='' WHERE id=?""",
        (
            encrypt(access_token),
            encrypt(data.get("refresh_token", refresh_token)),
            time.time() + max(60, int(data.get("expires_in", 3600))),
            account_id,
        ),
    )
    conn.commit()
    return access_token


def resolve_oauth_access_token(account: dict) -> str:
    """返回可用 access token；同一账户只允许一个线程刷新。"""
    account_id = int(account["id"])
    with _refresh_lock(account_id):
        row = get_conn().execute("SELECT * FROM accounts WHERE id=?", (account_id,)).fetchone()
        if not row:
            raise OAuthError("账户不存在")
        current = dict(row)
        cached = decrypt(current["oauth_access_enc"])
        if current["oauth_expires"] - 120 > time.time() and cached:
            return cached
        refresh_token = decrypt(current["oauth_refresh_enc"])
        if not refresh_token:
            message = "OAuth 凭据缺失，请重新登录"
            _mark_reauth_required(account_id, message)
            raise OAuthError(message, reauth_required=True)
        try:
            data = _refresh_account_token(current, refresh_token)
        except OAuthError as exc:
            if exc.reauth_required:
                _mark_reauth_required(account_id, str(exc))
            raise
        return _save_refreshed_token(account_id, refresh_token, data)


def _fragment_capture_page(provider: str, message: str = "") -> HTMLResponse:
    """NetEase 用 responseType=token，令牌只出现在 URL fragment 里。

    浏览器不会把 fragment 发给服务器，所以回调页先渲染这段脚本，由前端把
    fragment 参数 POST 回 /api/oauth/{provider}/fragment，再通知打开它的窗口。
    """
    endpoint = f"/api/oauth/{provider}/fragment"
    hint = html.escape(message or "正在读取网易返回的登录结果…")
    body = (
        '<!doctype html><html lang="zh-CN"><meta charset="utf-8">'
        f"<title>网易邮箱登录</title><body><p id=\"msg\">{hint}</p><script>\n"
        "const ENDPOINT=" + json.dumps(endpoint) + ";\n"
        "const raw = location.hash.startsWith('#') ? location.hash.slice(1) : '';\n"
        "const params = new URLSearchParams(raw);\n"
        "const query = new URLSearchParams(location.search);\n"
        "const payload = {\n"
        "  state: params.get('state') || query.get('state') || '',\n"
        "  uid: params.get('uid') || params.get('username') || '',\n"
        "  access_token: params.get('accessToken') || params.get('access_token') || '',\n"
        "  error: params.get('error') || '',\n"
        "  error_description: params.get('error_description') || ''\n"
        "};\n"
        "history.replaceState(null, '', location.pathname);\n"
        "const done = (result) => {\n"
        "  document.getElementById('msg').textContent = result.ok\n"
        "    ? '登录成功，窗口可关闭' : (result.message || '邮箱登录失败');\n"
        "  const out = Object.assign({type: 'mailhub-oauth'}, result);\n"
        "  if (window.opener) window.opener.postMessage(out, location.origin);\n"
        "  setTimeout(() => window.close(), result.ok ? 300 : 2500);\n"
        "};\n"
        "if (!payload.state && !payload.access_token && !payload.error) {\n"
        "  done({ok: false, message: '登录回调缺少 state 或令牌'});\n"
        "} else {\n"
        "  fetch(ENDPOINT, {\n"
        "    method: 'POST', credentials: 'same-origin',\n"
        "    headers: {'Content-Type': 'application/json'},\n"
        "    body: JSON.stringify(payload)\n"
        "  }).then(async (response) => {\n"
        "    const data = await response.json().catch(() => ({}));\n"
        "    if (!response.ok) return {ok: false, message: data.detail || '邮箱登录失败'};\n"
        "    return data;\n"
        "  }).then(done).catch(() => done({ok: false, message: '网络错误，无法完成登录'}));\n"
        "}\n"
        "</script></body></html>"
    )
    return HTMLResponse(body, headers={
        "Cache-Control": "no-store",
        "Content-Security-Policy": (
            "default-src 'none'; script-src 'unsafe-inline'; connect-src 'self'"
        ),
        "Referrer-Policy": "no-referrer",
    })


def _callback_html(payload: dict) -> HTMLResponse:
    safe_payload = json.dumps(payload, ensure_ascii=False).replace("<", "\\u003c")
    title = "登录成功" if payload.get("ok") else "登录失败"
    detail = html.escape(str(payload.get("message", "窗口可关闭")))
    body = f"""<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<title>{title}</title><body><p>{detail}</p><script>
const payload={safe_payload};
history.replaceState(null,"",location.pathname);
if(window.opener) window.opener.postMessage(payload, window.location.origin);
setTimeout(()=>window.close(), payload.ok ? 300 : 2500);
</script></body></html>"""
    return HTMLResponse(body, headers={
        "Cache-Control": "no-store",
        "Content-Security-Policy": "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'",
        "Referrer-Policy": "no-referrer",
    })


@router.get("/api/oauth/providers")
async def providers():
    return {"providers": public_providers()}


@router.post("/api/oauth/start")
async def oauth_start(body: OAuthStartBody):
    data = _normalize_start(body)
    if data["provider"] in _NETEASE_OAUTH_ENDPOINTS:
        return _netease_oauth_start(data)
    config = _browser_config(data["provider"])
    if not config:
        raise HTTPException(status_code=503, detail="该服务商尚未配置网页 OAuth 登录")
    verifier, challenge = _pkce_pair()
    redirect_uri = _redirect_uri(data["provider"])
    tx_id, state = _create_browser_transaction(
        data, config["client_id"], config["scope"], redirect_uri, verifier,
    )
    params = {
        "client_id": config["client_id"],
        "response_type": "code",
        "redirect_uri": redirect_uri,
        "scope": config["scope"],
        "state": state,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "login_hint": data["email"],
        "prompt": config["prompt"],
    }
    if data["provider"] == "gmail":
        params.update({"access_type": "offline", "include_granted_scopes": "true"})
    return {
        "ok": True,
        "transaction_id": tx_id,
        "authorization_url": f"{config['authorize_url']}?{urlencode(params)}",
        "expires_in": _BROWSER_TTL,
    }


def _netease_oauth_start(data: dict) -> dict:
    config = _netease_config(data["provider"])
    if not config:
        raise HTTPException(status_code=503, detail="网易合作方 OAuth 客户端尚未配置")
    tx_id, state = _create_browser_transaction(
        data, config["client_id"], config["scope"], config["redirect_uri"], "",
    )
    params = {
        "uid": data["email"],
        "appid": config["client_id"],
        "device_id": config["device_id"],
        "scope": config["scope"],
        "responseType": "token",
        "redirectUrl": config["redirect_uri"],
        "state": state,
    }
    return {
        "ok": True,
        "transaction_id": tx_id,
        "authorization_url": f"{config['authorize_url']}?{urlencode(params)}",
        "expires_in": _BROWSER_TTL,
    }


def _netease_probe_account(provider: str, email_addr: str) -> dict:
    preset = mail_client.PROVIDERS[provider]
    return {
        "provider": provider,
        "auth_type": "oauth",
        "imap_host": _NETEASE_OAUTH_IMAP_HOSTS.get(provider, preset["host"]),
        "imap_port": preset["port"],
        "email": email_addr,
    }


def _complete_netease_login(provider: str, tx: dict, uid: str, token: str) -> int:
    """按 NetEaseAuthDelegate 的顺序校验 uid、state 绑定和令牌，再建账户。"""
    config = _netease_config(provider)
    if not config or config["client_id"] != tx["client_id"]:
        raise OAuthError("网易 OAuth 客户端配置已变化，请重新发起登录")
    if uid.strip().lower() != tx["email"]:
        raise OAuthError("网易返回的邮箱地址与登录账户不一致")
    if not token:
        raise OAuthError("网易未返回邮箱访问令牌")
    probe_connection(_netease_probe_account(provider, tx["email"]), token)
    # APK 把同一个 token 同时当作 access/refresh，并写入 3650 天有效期。
    return _upsert_oauth_account(tx, {
        "access_token": token,
        "refresh_token": token,
        "expires_in": _NETEASE_TOKEN_TTL,
    })


@router.get("/api/oauth/{provider}/callback")
async def oauth_callback(provider: str, state: str = "", code: str = "",
                         error: str = "", error_description: str = "", uid: str = "",
                         access_token: str = "", accessToken: str = ""):
    provider = provider.strip().lower()
    # APK 读的是 accessToken（驼峰），这里同时接受两种写法，避免令牌被静默丢弃。
    netease_token = (access_token or accessToken or "").strip()
    try:
        if provider not in _OAUTH_SPECS and provider not in _NETEASE_OAUTH_ENDPOINTS:
            raise OAuthError("无效的 OAuth 回调")
        if (provider in _NETEASE_OAUTH_ENDPOINTS
                and not (code or netease_token or error)):
            # 隐式 flow：令牌只在 fragment 里，交给回调页读出来再提交。
            return _fragment_capture_page(provider)
        if not state:
            raise OAuthError("无效的 OAuth 回调")
        tx = _take_browser_transaction(state, provider)
        if error:
            raise OAuthError(error_description or error)
        if provider in _NETEASE_OAUTH_ENDPOINTS:
            account_id = await asyncio.to_thread(
                _complete_netease_login, provider, tx, uid, netease_token,
            )
        else:
            if not code:
                raise OAuthError("服务商未返回授权码")
            verifier = decrypt(tx["verifier_enc"])
            if not verifier:
                raise OAuthError("登录校验凭据已损坏，请重新发起登录")
            token_data = await asyncio.to_thread(
                exchange_code, provider, code, verifier, tx["redirect_uri"], tx["client_id"],
            )
            preset = mail_client.PROVIDERS[provider]
            probe = {
                "provider": provider,
                "auth_type": "oauth",
                "imap_host": preset["host"],
                "imap_port": preset["port"],
                "email": tx["email"],
            }
            await asyncio.to_thread(probe_connection, probe, token_data["access_token"])
            account_id = await asyncio.to_thread(_upsert_oauth_account, tx, token_data)
        return _callback_html({
            "type": "mailhub-oauth",
            "ok": True,
            "account_id": account_id,
            "message": "邮箱登录成功",
        })
    except Exception as exc:
        return _callback_html({
            "type": "mailhub-oauth",
            "ok": False,
            "message": str(exc)[:240],
        })


class FragmentCallbackBody(BaseModel):
    state: str = ""
    uid: str = ""
    access_token: str = ""
    accessToken: str = ""
    error: str = ""
    error_description: str = ""


@router.post("/api/oauth/{provider}/fragment")
async def oauth_fragment_callback(provider: str, body: FragmentCallbackBody):
    """接收回调页从 URL fragment 里读出的网易令牌（implicit flow）。"""
    provider = provider.strip().lower()
    if provider not in _NETEASE_OAUTH_ENDPOINTS:
        raise HTTPException(status_code=404, detail="该服务商不使用片段回调")
    token = (body.access_token or body.accessToken or "").strip()
    try:
        if not body.state:
            raise OAuthError("登录状态无效或已使用")
        tx = _take_browser_transaction(body.state, provider)
        if body.error:
            raise OAuthError(body.error_description or body.error)
        account_id = await asyncio.to_thread(
            _complete_netease_login, provider, tx, body.uid, token,
        )
    except OAuthError as exc:
        raise HTTPException(status_code=400, detail=str(exc)[:240]) from exc
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(
            status_code=400,
            detail=f"邮箱身份验证失败: {str(exc)[:180]}",
        ) from exc
    return {"ok": True, "account_id": account_id, "message": "邮箱登录成功"}


@router.post("/api/oauth/outlook/device/start")
async def outlook_device_start(body: OAuthStartBody):
    data = _normalize_start(body, "outlook")
    client_id = _device_client_id()
    if not client_id:
        raise HTTPException(status_code=503, detail="Microsoft OAuth 客户端未配置")
    spec = _OAUTH_SPECS["outlook"]
    try:
        response = httpx.post(spec["device_url"], data={
            "client_id": client_id,
            "scope": spec["scope"],
        }, timeout=20)
        device = response.json()
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"请求 Microsoft 登录失败: {str(exc)[:160]}") from exc
    if response.status_code >= 400 or device.get("error"):
        raise HTTPException(status_code=502, detail=_oauth_message(device, "请求 Microsoft 登录失败"))
    tx_id = _store_device_transaction(data, device, client_id, spec["scope"])
    return {
        "ok": True,
        "transaction_id": tx_id,
        "user_code": device["user_code"],
        "verification_uri": device.get("verification_uri", "https://microsoft.com/devicelogin"),
        "verification_uri_complete": device.get("verification_uri_complete", ""),
        "interval": max(5, int(device.get("interval", 5))),
        "expires_in": min(1800, max(60, int(device.get("expires_in", 900)))),
    }


def _delete_transaction(transaction_id: str):
    conn = get_conn()
    conn.execute("DELETE FROM oauth_transactions WHERE id=?", (transaction_id,))
    conn.commit()


def _load_device_transaction(transaction_id: str) -> tuple[dict, dict | None]:
    conn = get_conn()
    row = conn.execute(
        "SELECT * FROM oauth_transactions WHERE id=? AND provider='outlook' AND flow='device'",
        (transaction_id,),
    ).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="登录事务不存在或已完成")
    tx = dict(row)
    now = time.time()
    if tx["expires_ts"] < now:
        _delete_transaction(tx["id"])
        raise HTTPException(status_code=400, detail="登录已过期，请重新发起")
    if tx["next_poll_ts"] > now:
        return tx, {"pending": True, "interval": max(1, int(tx["next_poll_ts"] - now))}
    interval = max(5, int(tx["oauth_interval"] or 5))
    conn.execute("UPDATE oauth_transactions SET next_poll_ts=? WHERE id=?", (now + interval, tx["id"]))
    conn.commit()
    return tx, None


def _request_device_token(tx: dict) -> dict:
    device_code = decrypt(tx["device_code_enc"])
    if not device_code:
        raise HTTPException(status_code=400, detail="登录事务凭据已损坏")
    spec = _OAUTH_SPECS["outlook"]
    try:
        response = httpx.post(spec["token_url"], data={
            "client_id": tx["client_id"],
            "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
            "device_code": device_code,
        }, timeout=20)
        token_data = response.json()
    except Exception as exc:
        raise HTTPException(
            status_code=502,
            detail=f"查询 Microsoft 登录状态失败: {str(exc)[:160]}",
        ) from exc
    token_data["_http_status"] = response.status_code
    return token_data


def _device_token_result(tx: dict, token_data: dict) -> dict | None:
    code = token_data.get("error", "")
    interval = max(5, int(tx["oauth_interval"] or 5))
    if code not in {"authorization_pending", "slow_down"}:
        if token_data.pop("_http_status", 200) >= 400 or code:
            _delete_transaction(tx["id"])
            raise HTTPException(
                status_code=400,
                detail=_oauth_message(token_data, "Microsoft 登录失败"),
            )
        return None
    if code == "slow_down":
        interval += 5
        conn = get_conn()
        conn.execute(
            "UPDATE oauth_transactions SET oauth_interval=?, next_poll_ts=? WHERE id=?",
            (interval, time.time() + interval, tx["id"]),
        )
        conn.commit()
    return {"pending": True, "interval": interval}


@router.post("/api/oauth/outlook/device/poll")
async def outlook_device_poll(body: DevicePollBody):
    tx, pending = _load_device_transaction(body.transaction_id)
    if pending:
        return pending
    token_data = await asyncio.to_thread(_request_device_token, tx)
    pending = _device_token_result(tx, token_data)
    if pending:
        return pending
    _delete_transaction(tx["id"])
    preset = mail_client.PROVIDERS["outlook"]
    probe = {
        "provider": "outlook",
        "auth_type": "oauth",
        "imap_host": preset["host"],
        "imap_port": preset["port"],
        "email": tx["email"],
    }
    try:
        await asyncio.to_thread(probe_connection, probe, token_data["access_token"])
        account_id = await asyncio.to_thread(_upsert_oauth_account, tx, token_data)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"邮箱身份验证失败: {str(exc)[:180]}") from exc
    return {"ok": True, "id": account_id}
