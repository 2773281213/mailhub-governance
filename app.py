"""MailHub 邮箱管理中心 —— FastAPI 主应用
聚合多邮箱（IMAP）+ 规则/AI 分类 + 验证码看板 + 每日摘要
仅监听 127.0.0.1，经 nginx 反向代理对外提供 HTTPS
"""
import asyncio
import json
import logging
import os
import re
import secrets as pysecrets
import time
from contextlib import asynccontextmanager
from datetime import datetime
from urllib.parse import urlparse

from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

import ai
import billing
import governance
import mail_client
import notify
import oauth_auth
import policy
import providers
import rules as rules_mod
import sync
from db import (audit, get_conn, get_setting, init_db, remove_external_api_settings,
                set_setting)
import db
import sessions
from security import (decrypt, encrypt, hash_password, rotate_encrypted_values,
                      verify_password)

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
COOKIE_NAME = "mailhub_session"
API_VERSION = 1          # 移动端据此判断接口能力，见 /api/meta

# 登录失败限流：按来源 IP 指数退避，另有全局上限（见 sessions.LoginThrottle）
login_throttle = sessions.LoginThrottle()


def _initial_password_path() -> str:
    return os.path.join(os.path.dirname(os.path.abspath(db.DB_PATH)), "initial-admin-password.txt")


def _write_initial_password(password: str) -> str:
    """首次启动生成的管理密码写入数据目录下的 0600 文件，日志里只出现文件路径。"""
    path = _initial_password_path()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(password + "\n")
    return path


def _remove_initial_password_file():
    try:
        os.remove(_initial_password_path())
    except FileNotFoundError:
        pass


def _setup_logging():
    """mailhub.* 的 INFO 日志（如账单定级理由）输出到标准错误，由 systemd 收进 journal。"""
    logger = logging.getLogger("mailhub")
    if logger.handlers:
        return
    handler = logging.StreamHandler()
    handler.setFormatter(logging.Formatter("%(levelname)s %(name)s: %(message)s"))
    logger.addHandler(handler)
    logger.setLevel(logging.INFO)
    logger.propagate = False


@asynccontextmanager
async def lifespan(_app: FastAPI):
    _setup_logging()
    init_db()
    oauth_auth.init_oauth_db()
    # 旧版本把 Bark 地址和 Telegram Token 明文存在 settings 里，先迁到加密键
    notify.migrate_plaintext_settings()
    # 用旧密钥（MAILHUB_SECRET_PREVIOUS）或旧派生方式加密的凭据改为当前密钥加密
    rotate_encrypted_values(get_conn())
    # 首次启动生成随机管理密码。密码只写进数据目录下的 0600 文件，不进日志；
    # 用户在设置里改密码后该文件会被删除。
    if not get_setting("admin_hash"):
        pwd = pysecrets.token_urlsafe(12)
        set_setting("admin_hash", hash_password(pwd))
        path = _write_initial_password(pwd)
        print(f"[MailHub] 已生成初始管理密码，保存在 {path}（仅文件所有者可读）", flush=True)
    # 旧版外部取码 API 已移除：清掉它留下的令牌和别名基座设置
    remove_external_api_settings()
    # 账单分级升级回填：先修正只有「浏览器查看」占位的正文，再补模板键和等级（不调用 AI）
    sync.backfill_readable_bodies()
    governance.backfill_template_keys(get_conn())
    governance.retier_bills(get_conn(), only_missing=True)
    sync.engine.start()
    yield
    sync.engine.stop()


app = FastAPI(title="MailHub", lifespan=lifespan, docs_url=None, redoc_url=None)
app.include_router(oauth_auth.router)


# ---------- 鉴权 ----------

_UNSAFE_METHODS = frozenset({"POST", "PUT", "PATCH", "DELETE"})

# 对所有响应生效、几乎不会影响页面的安全头；页面自己设置过的同名头不覆盖
_BASE_SECURITY_HEADERS = {
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "same-origin",
    "Content-Security-Policy": "object-src 'none'; base-uri 'self'; form-action 'self'",
    # 完整策略先以 Report-Only 运行：前端仍有内联事件处理器，
    # 在浏览器控制台确认没有违规后，再把它并入上面的强制策略。
    "Content-Security-Policy-Report-Only": (
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
        "img-src 'self' data: https:; font-src 'self'; connect-src 'self'; frame-src 'self'; "
        "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"
    ),
}


def _is_same_origin(request: Request) -> bool:
    """CSRF 防护：会改变状态的请求必须来自本站页面。

    浏览器对跨站的 POST/PUT/DELETE 一定会带 Origin；没有 Origin 时再看
    Sec-Fetch-Site。两者都没有的是 curl 等非浏览器客户端，不受 CSRF 影响。
    """
    origin = request.headers.get("origin")
    if origin is None:
        return request.headers.get("sec-fetch-site") in (None, "same-origin", "none")
    if origin == "null":
        return False
    netloc = urlparse(origin).netloc.lower()
    allowed = {request.headers.get("host", "").lower(),
               urlparse(oauth_auth._public_base_url()).netloc.lower()}
    return bool(netloc) and netloc in allowed


def _request_token(request: Request | None) -> tuple[str | None, str]:
    if not request:
        return None, ""
    auth = request.headers.get("authorization", "").strip()
    if auth.lower().startswith("bearer "):
        token = auth[7:].strip()
        if token:
            return token, "bearer"
    cookie_token = request.cookies.get(COOKIE_NAME)
    if cookie_token:
        return cookie_token, "cookie"
    return None, ""


@app.middleware("http")
async def auth_middleware(request: Request, call_next):
    path = request.url.path
    token, source = _request_token(request)
    if path.startswith("/api/") and request.method in _UNSAFE_METHODS:
        skip_csrf = (path != "/api/login" and source == "bearer" and COOKIE_NAME not in request.cookies)
        if not skip_csrf and not _is_same_origin(request):
            return JSONResponse({"detail": "跨站请求已拒绝"}, status_code=403)
    if path.startswith("/api/") and path not in ("/api/login", "/api/health"):
        if not sessions.verify_session(token):
            return JSONResponse({"detail": "未登录"}, status_code=401)
    return await call_next(request)


# 最后注册的中间件在最外层，401/403 等提前返回的响应也会带上安全头
@app.middleware("http")
async def security_headers_middleware(request: Request, call_next):
    response = await call_next(request)
    for name, value in _BASE_SECURITY_HEADERS.items():
        response.headers.setdefault(name, value)
    return response


def _client_ip(request: Request | None) -> str:
    # uvicorn 以 --proxy-headers --forwarded-allow-ips=127.0.0.1 运行，
    # 这里拿到的已经是 nginx 转发的真实来源地址
    return (request.client.host if request and request.client else "") or "unknown"


def _set_session_cookie(response: Response, token: str):
    response.set_cookie(
        COOKIE_NAME, token, max_age=sessions.SESSION_TTL,
        httponly=True, secure=True, samesite="lax",
    )


class LoginBody(BaseModel):
    password: str
    client: str = "web"
    device_name: str = ""


@app.post("/api/login")
async def login(body: LoginBody, request: Request, response: Response):
    ip = _client_ip(request)
    wait = login_throttle.retry_after(ip)
    if wait:
        raise HTTPException(status_code=429, detail=f"尝试过多，请 {-(-wait // 60)} 分钟后再试",
                            headers={"Retry-After": str(wait)})
    client_type = (body.client or "web").strip().lower()
    if client_type not in ("web", "android"):
        raise HTTPException(status_code=400, detail="不支持的客户端类型")
    if not await asyncio.to_thread(verify_password, body.password, get_setting("admin_hash")):

        login_throttle.record_failure(ip)
        raise HTTPException(status_code=401, detail="密码错误")
    login_throttle.record_success(ip)

    label = (body.device_name or "").strip()[:60]
    if client_type == "web":
        token = sessions.create_session(client="web", label=label)
        _set_session_cookie(response, token)
        return {"ok": True}
    else:
        token = sessions.create_session(client="android", label=label)
        sid = sessions.session_id(token)
        row = get_conn().execute(
            "SELECT expires_ts FROM sessions WHERE id_hash=?",
            (sessions._hash(token),)
        ).fetchone()
        expires_ts = row["expires_ts"] if row else time.time() + sessions.SESSION_TTL
        return {
            "ok": True,
            "token": token,
            "session_id": sid,
            "expires_ts": expires_ts,
        }


@app.post("/api/logout")
async def logout(request: Request, response: Response):
    """只吊销当前设备的会话。"""
    token, source = _request_token(request)
    if token:
        sessions.revoke_session(token)
    if source == "cookie":
        response.delete_cookie(COOKIE_NAME)
    return {"ok": True}


@app.post("/api/sessions/revoke-all")
async def revoke_all_sessions(response: Response, request: Request = None):
    """退出所有设备：吊销全部会话，再给当前设备签发一个新会话。"""
    token, source = _request_token(request)
    orig_client, orig_label = "web", ""
    if token:
        row = get_conn().execute(
            "SELECT client, label FROM sessions WHERE id_hash=?",
            (sessions._hash(token),)
        ).fetchone()
        if row:
            orig_client = row["client"] or "web"
            orig_label = row["label"] or ""
    count = sessions.revoke_all_sessions()
    if source == "bearer":
        new_token = sessions.create_session(client=orig_client, label=orig_label)
        audit("revoke_sessions", actor="user", tier="A", reason=f"退出所有设备，吊销 {count} 个会话")
        return {"ok": True, "revoked": count, "token": new_token}
    else:
        new_token = sessions.create_session()
        _set_session_cookie(response, new_token)
        audit("revoke_sessions", actor="user", tier="A", reason=f"退出所有设备，吊销 {count} 个会话")
        return {"ok": True, "revoked": count}


@app.get("/api/sessions")
async def get_sessions(request: Request):
    token, _ = _request_token(request)
    return {"items": sessions.list_sessions(token)}


@app.delete("/api/sessions/{sid}")
async def delete_session(sid: str):
    n = sessions.revoke_session_id(sid)
    if n == 0:
        raise HTTPException(status_code=404, detail="会话不存在")
    audit("revoke_session", actor="user", tier="A", reason=f"吊销设备会话 {sid}")
    return {"ok": True, "revoked": n}


@app.get("/api/health")
async def health():
    return {"ok": True, "ts": time.time(), "api_version": API_VERSION}


@app.get("/api/meta")
async def meta():
    """App 启动时读一次：版本、能力开关、时区和各类枚举。"""
    return {
        "ok": True,
        "api_version": API_VERSION,
        "server_time": time.time(),
        "tz": sync.TZ_NAME,
        "categories": list(rules_mod.CATEGORIES),
        "display_categories": list(DISPLAY_CATEGORIES),
        "bill_tiers": list(billing.TIERS),
        "ministries": list(governance.MINISTRIES),
        "governance_version": governance.VERSION,
        "features": {"token_auth": True, "cursor": True, "changes": True, "sessions": True},
    }


@app.get("/api/me")
async def me():
    conn = get_conn()
    n = conn.execute("SELECT COUNT(*) c FROM accounts").fetchone()["c"]
    return {"ok": True, "accounts": n, "providers": oauth_auth.public_providers(),
            "categories": rules_mod.CATEGORIES}


# ---------- 账户管理 ----------

class AccountBody(BaseModel):
    name: str = ""
    provider: str = "auto"
    email: str
    secret: str = ""          # 授权码/应用密码（OAuth 账户不用）
    imap_host: str = ""
    imap_port: int = 993
    poll_interval: int = 300
    color: str = "#38bdf8"
    enabled: bool = True


def _acc_public(row) -> dict:
    d = dict(row)
    for k in ("secret_enc", "oauth_refresh_enc", "oauth_access_enc", "oauth_client_id"):
        d.pop(k, None)
    d["has_secret"] = bool(row["secret_enc"] or row["oauth_refresh_enc"])
    st = sync.get_status().get(str(row["id"]))
    d["sync_state"] = st or {"state": "idle", "msg": "", "ts": 0, "new": 0}
    return d


def _list_accounts_sync():
    conn = get_conn()
    rows = conn.execute("SELECT * FROM accounts ORDER BY id").fetchall()
    # 一条聚合查询代替每个账户两条 COUNT；回收站里的邮件不计入
    counts = {
        r["account_id"]: (r["total"] or 0, r["unread"] or 0)
        for r in conn.execute(
            """SELECT account_id, COUNT(*) AS total, SUM(unread) AS unread
               FROM messages WHERE deleted_ts=0 GROUP BY account_id""").fetchall()
    }
    out = []
    for r in rows:
        d = _acc_public(r)
        d["total"], d["unread"] = counts.get(r["id"], (0, 0))
        out.append(d)
    return out


@app.get("/api/accounts")
async def list_accounts():
    return await asyncio.to_thread(_list_accounts_sync)


def _resolve_host(body: AccountBody) -> tuple[str, int]:
    """密码类账户的 IMAP 主机：自定义邮箱用用户填写的，其他用服务商表里的。"""
    meta = providers.get(body.provider)
    if body.provider == "custom" or meta is None:
        host, port = body.imap_host.strip(), body.imap_port or 993
    else:
        host, port = meta.host_for("password"), meta.imap_port
    if not host:
        raise HTTPException(status_code=400, detail="缺少 IMAP 服务器地址")
    return host, port


def _normalize_account_body(body: AccountBody):
    requested = (body.provider or "auto").strip().lower()
    normalized_email = body.email.strip().lower()
    detected = oauth_auth.detect_provider(normalized_email)
    if requested in ("", "auto"):
        requested = detected
    elif detected != "custom" and requested != detected:
        label = providers.get(detected).label
        raise HTTPException(
            status_code=400,
            detail=f"邮箱域名已识别为「{label}」，请使用自动识别或选择匹配的服务商",
        )
    body.provider, body.email = oauth_auth.normalize_account_identity(requested, normalized_email)
    body.poll_interval = min(86400, max(60, body.poll_interval or 300))


def _require_secret_method(provider: str):
    meta = providers.get(provider)
    if meta is not None and meta.secret is None:
        raise HTTPException(status_code=400,
                            detail=f"{meta.label} 只能通过 OAuth 登录添加，不支持授权码或密码")


def _resolve_update_host(row, body: AccountBody) -> tuple[str, int]:
    if row["auth_type"] == "oauth" and not body.secret:
        same_identity = (
            body.provider == row["provider"]
            and body.email == row["email"].strip().lower()
        )
        if not same_identity:
            raise HTTPException(
                status_code=400,
                detail="OAuth 账户的服务商和邮箱地址不能直接修改，请重新登录",
            )
        return row["imap_host"], row["imap_port"]
    host, port = _resolve_host(body)
    changed_login = (
        body.provider != row["provider"]
        or body.email != row["email"].strip().lower()
        or host != row["imap_host"]
        or port != row["imap_port"]
    )
    if changed_login and not body.secret:
        raise HTTPException(status_code=400, detail="修改邮箱登录信息时必须重新填写凭据")
    return host, port


async def _probe_password_account(body: AccountBody, host: str, port: int):
    if not body.secret:
        return
    probe = {
        "provider": body.provider,
        "auth_type": "password",
        "imap_host": host,
        "imap_port": port,
        "email": body.email,
    }
    try:
        await asyncio.to_thread(oauth_auth.probe_connection, probe, body.secret)
    except Exception as exc:
        detail = f"连接测试失败: {str(exc)[:150]}"
        meta = providers.get(body.provider)
        # 最常见的错误是填了网页登录密码：明确提示这里要的是哪种密码
        if meta and meta.secret and meta.secret.kind == providers.APP_PASSWORD \
                and mail_client.is_auth_failure(exc):
            detail += f"。请确认填写的是「{meta.secret.input_label}」，不是网页登录密码"
        raise HTTPException(status_code=400, detail=detail) from exc


@app.post("/api/accounts")
async def create_account(body: AccountBody):
    _normalize_account_body(body)
    _require_secret_method(body.provider)
    if not body.secret:
        raise HTTPException(status_code=400, detail="缺少授权码/密码")
    conn = get_conn()
    existing = conn.execute(
        "SELECT id FROM accounts WHERE provider=? AND lower(email)=?",
        (body.provider, body.email)).fetchone()
    if existing:
        raise HTTPException(status_code=409,
                            detail="这个邮箱已经添加过了，请在账户列表里编辑或重新授权")
    host, port = _resolve_host(body)
    await _probe_password_account(body, host, port)
    cur = conn.execute(
        """INSERT INTO accounts(name, provider, email, imap_host, imap_port, auth_type,
                                secret_enc, poll_interval, color, enabled, created_ts)
           VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
        (body.name or body.email, body.provider, body.email, host, port, "password",
         encrypt(body.secret), body.poll_interval, body.color, int(body.enabled), time.time()),
    )
    conn.commit()
    sync.engine.trigger(cur.lastrowid)
    return {"ok": True, "id": cur.lastrowid}


@app.put("/api/accounts/{acc_id}")
async def update_account(acc_id: int, body: AccountBody):
    conn = get_conn()
    row = conn.execute("SELECT * FROM accounts WHERE id=?", (acc_id,)).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="账户不存在")
    _normalize_account_body(body)
    if body.secret:
        _require_secret_method(body.provider)
    host, port = _resolve_update_host(row, body)
    await _probe_password_account(body, host, port)
    conn.execute(
        """UPDATE accounts SET name=?, provider=?, email=?, imap_host=?, imap_port=?,
           poll_interval=?, color=?, enabled=? WHERE id=?""",
        (body.name or body.email, body.provider, body.email, host, port,
         body.poll_interval, body.color, int(body.enabled), acc_id),
    )
    if body.secret:
        conn.execute("""UPDATE accounts SET auth_type='password', secret_enc=?,
                     oauth_refresh_enc='', oauth_access_enc='', oauth_expires=0,
                     oauth_client_id='', oauth_scope='', oauth_reauth_required=0, last_error=''
                     WHERE id=?""", (encrypt(body.secret), acc_id))
    conn.commit()
    return {"ok": True}


@app.delete("/api/accounts/{acc_id}")
async def delete_account(acc_id: int):
    conn = get_conn()
    row = conn.execute("SELECT email FROM accounts WHERE id=?", (acc_id,)).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="账户不存在")
    email = row["email"]
    conn.execute("DELETE FROM accounts WHERE id=?", (acc_id,))
    conn.commit()
    # 同步引擎还挂着这个账户的状态、并发锁和排队中的手动同步，一起清掉
    sync.forget_account(acc_id)
    audit("delete_account", actor="user", tier="C", target_ids=[acc_id],
          reason=f"删除账户 {email}，本地邮件一并删除")
    return {"ok": True}


@app.post("/api/accounts/{acc_id}/sync")
async def sync_now(acc_id: int):
    sync.engine.trigger(acc_id)
    return {"ok": True}


@app.post("/api/accounts/{acc_id}/test")
async def test_account(acc_id: int):
    conn = get_conn()
    row = conn.execute("SELECT * FROM accounts WHERE id=?", (acc_id,)).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="账户不存在")
    acc = dict(row)
    try:
        secret = await asyncio.to_thread(sync.resolve_secret, acc)
        await asyncio.to_thread(oauth_auth.probe_connection, acc, secret)
        return {"ok": True, "msg": "连接正常"}
    except Exception as e:
        return {"ok": False, "msg": str(e)[:200]}


@app.post("/api/sync-all")
async def sync_all():
    sync.engine.trigger_all()
    return {"ok": True}


@app.get("/api/status")
async def sync_status():
    return sync.get_status()


# ---------- 邮件 ----------

def _escape_like(text: str) -> str:
    """把 LIKE 的通配符转义成普通字符，搜索 % 时不会匹配到所有邮件。"""
    return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def _message_filter(category: str, account: int, unread: int, q: str,
                    bill_tier: str) -> tuple[str, list]:
    """收件箱筛选条件；列表和「选择全部」共用，保证两者选中的是同一批邮件。"""
    where, args = ["1=1"], []
    if category:
        where.append("m.category=?"); args.append(category)
    if bill_tier in billing.TIERS:
        where.append("m.bill_tier=?"); args.append(bill_tier)
    if account:
        where.append("m.account_id=?"); args.append(account)
    if unread in (0, 1):
        where.append("m.unread=?"); args.append(unread)
    if q:
        where.append("(m.subject LIKE ? ESCAPE '\\' OR m.sender_addr LIKE ? ESCAPE '\\'"
                     " OR m.sender_name LIKE ? ESCAPE '\\' OR m.snippet LIKE ? ESCAPE '\\')")
        like = f"%{_escape_like(q)}%"
        args += [like, like, like, like]
    where.append("m.deleted_ts=0")   # 回收站内容不出现在收件箱
    return " AND ".join(where), args


# 「选择全部」一次最多取这么多封；批量接口每次处理 500 封，前端分批提交
SELECT_ALL_LIMIT = 5000


@app.get("/api/messages/ids")
async def list_message_ids(category: str = "", account: int = 0, unread: int = -1,
                           q: str = "", bill_tier: str = ""):
    """当前筛选条件下全部邮件的 id（跨页全选用）。"""
    cond, args = _message_filter(category, account, unread, q, bill_tier)
    conn = get_conn()
    total = conn.execute(f"SELECT COUNT(*) c FROM messages m WHERE {cond}", args).fetchone()["c"]
    rows = conn.execute(
        f"SELECT m.id FROM messages m WHERE {cond} ORDER BY m.date_ts DESC, m.id DESC LIMIT ?",
        args + [SELECT_ALL_LIMIT]).fetchall()
    return {"total": total, "ids": [r["id"] for r in rows], "limit": SELECT_ALL_LIMIT}


def _list_messages_sync(category: str = "", account: int = 0, unread: int = -1,
                        q: str = "", page: int = 1, page_size: int = 40, bill_tier: str = "",
                        before_ts: float = 0, before_id: int = 0):
    conn = get_conn()
    cond, args = _message_filter(category, account, unread, q, bill_tier)
    page_size = min(100, max(10, page_size))
    # 游标分页：按 (date_ts, id) 取下一页，不用 OFFSET，也不统计总数
    cursor_mode = before_id > 0
    if cursor_mode:
        cond += " AND (m.date_ts < ? OR (m.date_ts = ? AND m.id < ?))"
        args = args + [before_ts, before_ts, before_id]
        total = None
        tail = "LIMIT ?"
        tail_args = [page_size]
    else:
        total = conn.execute(f"SELECT COUNT(*) c FROM messages m WHERE {cond}", args).fetchone()["c"]
        tail = "LIMIT ? OFFSET ?"
        tail_args = [page_size, (max(1, page) - 1) * page_size]
    rows = conn.execute(
        f"""SELECT m.id, m.account_id, m.uid, m.subject, m.sender_name, m.sender_addr,
                   m.date_ts, m.snippet, m.category, m.otp_code, m.importance, m.summary,
                   m.unread, m.has_attach, m.bill_tier, a.name AS account_name, a.color AS account_color
            FROM messages m JOIN accounts a ON a.id=m.account_id
            WHERE {cond} ORDER BY m.date_ts DESC, m.id DESC {tail}""",
        args + tail_args,
    ).fetchall()
    items = [dict(r) for r in rows]
    next_cursor = None
    if len(items) == page_size:
        last = items[-1]
        next_cursor = {"before_ts": last["date_ts"], "before_id": last["id"]}
    return {"total": total, "page": page, "items": items, "next_cursor": next_cursor}


@app.get("/api/messages")
async def list_messages(category: str = "", account: int = 0, unread: int = -1,
                        q: str = "", page: int = 1, page_size: int = 40, bill_tier: str = "",
                        before_ts: float = 0, before_id: int = 0):
    return await asyncio.to_thread(_list_messages_sync, category, account, unread, q, page,
                                   page_size, bill_tier, before_ts, before_id)


@app.get("/api/messages/{msg_id}")
async def get_message(msg_id: int, mark_read: bool = True):
    conn = get_conn()
    row = conn.execute(
        """SELECT m.*, a.name AS account_name, a.color AS account_color
           FROM messages m JOIN accounts a ON a.id=m.account_id
           WHERE m.id=? AND m.deleted_ts=0""",
        (msg_id,),
    ).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="邮件不存在")
    d = dict(row)
    # 打开即本地标记已读（服务器端标记由前端显式调用）；
    # App 预览用 mark_read=false 只看内容，不改未读状态
    if mark_read and d["unread"]:
        conn.execute("UPDATE messages SET unread=0 WHERE id=?", (msg_id,))
        conn.commit()
    return d


# ---------- 增量变更（App 后台通知） ----------

CHANGES_MAX_LIMIT = 200


def _changes_sync(after_id: int = 0, limit: int = 50):
    conn = get_conn()
    limit = min(CHANGES_MAX_LIMIT, max(1, limit))
    now = time.time()
    max_id = int(conn.execute(
        "SELECT COALESCE(MAX(id), 0) AS m FROM messages").fetchone()["m"])
    if after_id <= 0:
        # 初始化调用：只告诉客户端当前水位，不补发历史邮件
        return {"items": [], "cursor": max_id}
    # 屏障：还没治理完的新邮件先挡住它和它之后的邮件，避免客户端看到待定级的旧标签。
    # 超过 1 小时仍未治理的邮件不再挡路，治理任务挂掉也不会让通知永久卡住。
    barrier_row = conn.execute(
        f"""SELECT MIN(m.id) AS b FROM messages m
            WHERE m.id > ? AND m.deleted_ts=0 AND m.created_ts >= ?
              AND {governance._PENDING_SQL}""",
        (after_id, now - 3600, governance.VERSION, now),
    ).fetchone()
    barrier = barrier_row["b"] if barrier_row and barrier_row["b"] is not None else 0
    cond, args = "m.id > ? AND m.deleted_ts=0", [after_id]
    if barrier:
        cond += " AND m.id < ?"
        args.append(barrier)
    rows = conn.execute(
        f"""SELECT m.id, m.account_id, a.name AS account_name, m.subject, m.sender_name,
                   m.sender_addr, m.date_ts, m.category, m.bill_tier, m.importance,
                   m.otp_code, m.risk_level, m.unread
            FROM messages m LEFT JOIN accounts a ON a.id=m.account_id
            WHERE {cond} ORDER BY m.id LIMIT ?""",
        args + [limit],
    ).fetchall()
    items = []
    for r in rows:
        d = dict(r)
        d["display_category"] = _display_category(d["category"], d["bill_tier"])
        items.append(d)
    if items:
        cursor = int(items[-1]["id"])
    elif barrier:
        cursor = barrier - 1
    else:
        cursor = max_id
    return {"items": items, "cursor": max(cursor, after_id)}


@app.get("/api/changes")
async def list_changes(after_id: int = 0, limit: int = 50):
    return await asyncio.to_thread(_changes_sync, after_id, limit)


class BillTierBody(BaseModel):
    tier: str
    similar: bool = True        # 同类邮件（同发件域名 + 同标题模板）以后都按这个等级


@app.post("/api/messages/{msg_id}/bill-tier")
async def set_bill_tier(msg_id: int, body: BillTierBody):
    """用户把一封账单改为重要/一般；勾选 similar 时记住偏好并立即应用到同类账单。"""
    if body.tier not in billing.TIERS:
        raise HTTPException(status_code=400, detail="等级只能是「重要」或「一般」")
    conn = get_conn()
    row = conn.execute(
        "SELECT id, category, importance, template_key, bill_facets FROM messages "
        "WHERE id=? AND deleted_ts=0", (msg_id,)).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="邮件不存在")
    if row["category"] != "账单":
        raise HTTPException(status_code=400, detail="只有账单可以设置重要/一般")
    try:
        facets = json.loads(row["bill_facets"] or "{}")
    except (TypeError, ValueError):
        facets = {}
    importance = billing.tier_importance(body.tier, facets, int(row["importance"] or 0))
    conn.execute("UPDATE messages SET bill_tier=?, bill_tier_locked=1, importance=? WHERE id=?",
                 (body.tier, importance, msg_id))
    similar = 0
    key = row["template_key"] or ""
    if body.similar and key:
        conn.execute(
            "INSERT INTO bill_prefs(template_key, tier, created_ts) VALUES(?,?,?) "
            "ON CONFLICT(template_key) DO UPDATE SET tier=excluded.tier, created_ts=excluded.created_ts",
            (key, body.tier, time.time()))
        conn.commit()
        similar = governance.retier_bills(conn, template=key)
    conn.commit()
    audit("bill_tier", actor="user", tier="A", target_ids=[msg_id],
          reason=f"设为{body.tier}账单" + ("，同类邮件以后都按此等级" if body.similar else ""))
    return {"ok": True, "tier": body.tier, "similar_updated": similar}


class BatchBody(BaseModel):
    ids: list[int]
    action: str                 # read / unread / delete
    server: bool = False        # 是否同步操作 IMAP 服务器（= 不可逆的 purge）
    confirmed: bool = False     # 前端已弹窗确认（C 类动作必需）
    # 用户对一封受保护邮件做了单独的二次确认；只在单封操作时生效
    override_protection: bool = False


def _server_op(ids: list[int], action: str) -> tuple[list[int], list[str]]:
    """把批量操作回放到 IMAP，返回服务器确认成功的本地 ID 和错误。

    通过 asyncio.to_thread 在工作线程执行，并在该线程内取自己的 SQLite 连接。
    """
    return sync.apply_server_action(ids, action)


def _load_for_policy(conn, ids: list[int]) -> list[dict]:
    if not ids:
        return []
    ph = ",".join("?" * len(ids))
    rows = conn.execute(
        f"""SELECT id, category, bill_tier, has_attach, importance, sender_addr, unread, deleted_ts
            FROM messages WHERE deleted_ts=0 AND id IN ({ph})""", ids).fetchall()
    return [dict(r) for r in rows]


@app.post("/api/messages/batch")
async def batch_messages(body: BatchBody):
    """批量操作。删除走软删除（可撤销）；勾选 server 则升级为不可逆 purge，需显式确认。"""
    if not body.ids:
        return {"ok": True, "count": 0}
    ids = body.ids[:500]
    conn = get_conn()

    # 把外部动作名映射到策略引擎的动作名
    action = {"delete": "purge" if body.server else "soft_delete",
              "read": "read", "unread": "unread"}.get(body.action)
    if not action:
        raise HTTPException(status_code=400, detail=f"不支持的动作 {body.action}")

    # 受保护邮件只能逐封处理：批量请求即使带了 override 也不生效
    override = body.override_protection and len(set(ids)) == 1

    msgs = _load_for_policy(conn, ids)
    blocked, allowed_ids = [], []
    for m in msgs:
        ok, _tier, reason = policy.evaluate(
            action, m, actor="user", confirmed=body.confirmed,
            override_protection=override)
        if ok:
            allowed_ids.append(m["id"])
        else:
            blocked.append({"id": m["id"], "reason": reason,
                            "protected": action in policy.DESTRUCTIVE_ACTIONS
                            and policy.is_protected(m)[0]})

    if not allowed_ids:
        first = blocked[0]["reason"] if blocked else "无可执行目标"
        audit(action, actor="user", target_ids=ids, allowed=False, reason=first)
        headers = {"X-MailHub-Protected": "1"} if blocked and blocked[0]["protected"] else None
        raise HTTPException(status_code=403, detail=f"操作被安全策略拦截：{first}",
                            headers=headers)

    errors = []
    undo_payload = None
    if action == "purge":
        # 不可逆：先在服务器删，再从本地移除，审计记录标注不可撤销
        succeeded, errors = await asyncio.to_thread(_server_op, allowed_ids, "delete")
        allowed_ids = succeeded
        if allowed_ids:
            ph = ",".join("?" * len(allowed_ids))
            conn.execute(f"DELETE FROM messages WHERE id IN ({ph})", allowed_ids)
    elif action == "soft_delete":
        ph = ",".join("?" * len(allowed_ids))
        conn.execute(f"UPDATE messages SET deleted_ts=? WHERE id IN ({ph})",
                     [time.time()] + allowed_ids)
        undo_payload = {"ids": allowed_ids}
    elif action in ("read", "unread"):
        if body.server:
            succeeded, errors = await asyncio.to_thread(_server_op, allowed_ids, body.action)
            allowed_ids = succeeded
        prev = {m["id"]: m["unread"] for m in msgs if m["id"] in set(allowed_ids)}
        if allowed_ids:
            ph = ",".join("?" * len(allowed_ids))
            conn.execute(f"UPDATE messages SET unread=? WHERE id IN ({ph})",
                         [0 if action == "read" else 1] + allowed_ids)
            undo_payload = {"unread": prev}
    conn.commit()

    overrode = override and action in policy.DESTRUCTIVE_ACTIONS and any(
        policy.is_protected(m)[0] for m in msgs if m["id"] in set(allowed_ids))
    reason = ("用户对受保护邮件单封二次确认" if overrode else "用户批量操作")
    if errors:
        reason += f"；{len(errors)} 个账户执行失败"
    elif blocked:
        reason += f"，{len(blocked)} 封被策略拦截"
    audit_id = audit(action, actor="user", tier=policy.ACTION_TIER.get(action, "C"),
                     target_ids=allowed_ids,
                     allowed=bool(allowed_ids),
                     reason=reason,
                     reversible=undo_payload is not None, undo_payload=undo_payload)
    return {"ok": not errors, "count": len(allowed_ids), "blocked": blocked,
            "errors": errors, "audit_id": audit_id,
            "reversible": undo_payload is not None}


class CleanBody(BaseModel):
    category: str = "验证码"
    older_days: int = 0     # 0 = 全部
    server: bool = False
    confirmed: bool = False


@app.post("/api/clean")
async def clean_messages(body: CleanBody):
    """批量清理。只允许白名单分类；默认软删除，server=True 才是不可逆清除且需确认。"""
    ok, why = policy.filter_cleanable(body.category)
    if not ok:
        audit("clean", actor="user", target_ids=[], allowed=False, reason=why)
        raise HTTPException(status_code=403, detail=why)
    if body.server and not body.confirmed:
        raise HTTPException(status_code=403,
                            detail="从邮箱服务器永久删除属 C 类动作，需前端显式确认")

    conn = get_conn()
    cutoff = time.time() - body.older_days * 86400 if body.older_days > 0 else time.time() + 1
    rows = conn.execute(
        "SELECT id FROM messages WHERE category=? AND date_ts < ? AND deleted_ts=0",
        (body.category, cutoff),
    ).fetchall()
    candidate = [r["id"] for r in rows]
    if not candidate:
        return {"ok": True, "count": 0, "errors": [], "blocked": []}

    # 逐封过受保护判定（例如带附件的验证码邮件也会被挡下）
    action = "purge" if body.server else "soft_delete"
    msgs = _load_for_policy(conn, candidate)
    ids, blocked = [], []
    for m in msgs:
        allowed, _tier, reason = policy.evaluate(
            action, m, actor="user", confirmed=body.confirmed)
        (ids.append(m["id"]) if allowed else blocked.append({"id": m["id"], "reason": reason}))

    if not ids:
        audit_id = audit(action, actor="user", target_ids=candidate, allowed=False,
                         reason="所有候选邮件均被安全策略拦截")
        return {"ok": True, "count": 0, "errors": [], "blocked": blocked,
                "audit_id": audit_id}

    errors, undo_payload = [], None
    ph = ",".join("?" * len(ids))
    if body.server:
        succeeded, errors = await asyncio.to_thread(_server_op, ids, "delete")
        ids = succeeded
        if ids:
            ph = ",".join("?" * len(ids))
            conn.execute(f"DELETE FROM messages WHERE id IN ({ph})", ids)
    else:
        conn.execute(f"UPDATE messages SET deleted_ts=? WHERE id IN ({ph})", [time.time()] + ids)
        undo_payload = {"ids": ids}
    conn.commit()

    audit_id = audit(action, actor="user", tier=policy.ACTION_TIER.get(action, "C"),
                     target_ids=ids, allowed=bool(ids),
                     reason=(f"批量清理分类「{body.category}」；{len(errors)} 个账户执行失败"
                             if errors else f"批量清理分类「{body.category}」"),
                     reversible=undo_payload is not None, undo_payload=undo_payload)
    return {"ok": not errors, "count": len(ids), "errors": errors, "blocked": blocked,
            "audit_id": audit_id, "reversible": undo_payload is not None}


# ---------- 回收站与撤销 ----------

@app.get("/api/trash")
async def list_trash(limit: int = 100):
    conn = get_conn()
    rows = conn.execute(
        """SELECT m.id, m.subject, m.sender_addr, m.date_ts, m.category, m.deleted_ts,
                  a.name AS account_name, a.color AS account_color
           FROM messages m JOIN accounts a ON a.id=m.account_id
           WHERE m.deleted_ts > 0 ORDER BY m.deleted_ts DESC LIMIT ?""",
        (min(500, limit),)).fetchall()
    return [dict(r) for r in rows]


class IdsBody(BaseModel):
    ids: list[int]


@app.post("/api/trash/restore")
async def restore_trash(body: IdsBody):
    if not body.ids:
        return {"ok": True, "count": 0}
    ids = body.ids[:500]
    conn = get_conn()
    ph = ",".join("?" * len(ids))
    cur = conn.execute(f"UPDATE messages SET deleted_ts=0 WHERE id IN ({ph})", ids)
    conn.commit()
    audit("restore", actor="user", tier="A", target_ids=ids, reason="从回收站恢复")
    return {"ok": True, "count": cur.rowcount}


@app.get("/api/audit")
async def list_audit(limit: int = 50, offset: int = 0):
    conn = get_conn()
    rows = conn.execute(
        """SELECT id, ts, actor, action, tier, target_count, reason,
                  allowed, reversible, undone
           FROM audit_log ORDER BY ts DESC LIMIT ? OFFSET ?""",
        (min(200, limit), max(0, offset))).fetchall()
    total = conn.execute("SELECT COUNT(*) c FROM audit_log").fetchone()["c"]
    return {"total": total, "items": [dict(r) for r in rows]}


@app.post("/api/audit/{audit_id}/undo")
async def undo_action(audit_id: int):
    """撤销一条可逆的审计记录。不可逆动作（purge/发送类）一律拒绝。"""
    conn = get_conn()
    row = conn.execute("SELECT * FROM audit_log WHERE id=?", (audit_id,)).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="审计记录不存在")
    if row["undone"]:
        raise HTTPException(status_code=400, detail="该操作已撤销过")
    if not row["reversible"] or not row["undo_payload"]:
        raise HTTPException(status_code=400,
                            detail=f"动作「{row['action']}」不可撤销（已在邮箱服务器上永久生效）")

    payload = json.loads(row["undo_payload"])
    restored = 0
    if row["action"] == "soft_delete":
        ids = payload.get("ids", [])[:2000]
        if ids:
            ph = ",".join("?" * len(ids))
            restored = conn.execute(
                f"UPDATE messages SET deleted_ts=0 WHERE id IN ({ph})", ids).rowcount
    elif row["action"] in ("read", "unread"):
        for mid, prev in (payload.get("unread") or {}).items():
            restored += conn.execute(
                "UPDATE messages SET unread=? WHERE id=?", (int(prev), int(mid))).rowcount
    conn.execute("UPDATE audit_log SET undone=1 WHERE id=?", (audit_id,))
    conn.commit()
    audit("undo", actor="user", tier="A", target_ids=[audit_id],
          reason=f"撤销审计 #{audit_id}（{row['action']}），恢复 {restored} 封")
    return {"ok": True, "restored": restored}


# ---------- 自动风险观察（/api/review 保留为兼容别名） ----------

@app.get("/api/review")
async def review_queue(limit: int = 50):
    """展示已由系统自动标记的可疑邮件；这里只读观察，不要求逐封确认。"""
    conn = get_conn()
    rows = conn.execute(
        """SELECT m.id, m.subject, m.sender_addr, m.sender_name, m.date_ts, m.category,
                   m.confidence, m.ai_reason, m.risk_level, m.risk_reasons, m.needs_review,
                   m.governance_ministry, m.governance_source, m.governance_reason,
                   a.name AS account_name, a.color AS account_color
            FROM messages m JOIN accounts a ON a.id=m.account_id
            WHERE m.deleted_ts=0 AND (m.category='可疑' OR m.risk_level IN ('medium','high'))
            ORDER BY (m.risk_level='high') DESC, m.date_ts DESC LIMIT ?""",
        (max(1, min(200, limit)),)).fetchall()
    return [dict(r) for r in rows]


class ResolveBody(BaseModel):
    id: int
    category: str


@app.post("/api/review/resolve")
async def resolve_review(body: ResolveBody):
    """旧客户端兼容端点：人工复核流程已由自动三省六部治理取代。"""
    raise HTTPException(
        status_code=410,
        detail="人工复核已停用，请使用一键三省六部分拣",
    )


# ---------- 验证码看板 ----------

@app.get("/api/otp")
async def otp_list(limit: int = 24):
    conn = get_conn()
    rows = conn.execute(
        """SELECT m.id, m.subject, m.sender_addr, m.sender_name, m.date_ts, m.otp_code,
                  m.unread, a.name AS account_name, a.color AS account_color, a.email AS account_email
           FROM messages m JOIN accounts a ON a.id=m.account_id
           WHERE m.category='验证码' AND m.otp_code != '' AND m.deleted_ts=0
           ORDER BY m.date_ts DESC LIMIT ?""",
        (min(100, limit),),
    ).fetchall()
    return [dict(r) for r in rows]


# ---------- 仪表盘 ----------

# 界面展示的分类：账单按等级拆成两项
DISPLAY_CATEGORIES = [c for cat in rules_mod.CATEGORIES
                      for c in ([f"{t}账单" for t in billing.TIERS] if cat == "账单" else [cat])]


def _display_category(category: str, bill_tier: str) -> str:
    name = governance.display_category(category, bill_tier or "")
    # 尚未定级的旧账单按「重要账单」展示，与分级的保守默认一致
    return "重要账单" if name == "账单" else name


def _overview_sync(now: float | None = None):
    conn = get_conn()
    local_now = datetime.now(sync.TZ) if now is None else datetime.fromtimestamp(now, sync.TZ)
    day_start = local_now.replace(hour=0, minute=0, second=0, microsecond=0).timestamp()

    # 5 个统计数合成一条查询；空表时 SUM 返回 NULL，统一按 0 处理
    row = conn.execute(
        """SELECT SUM(CASE WHEN deleted_ts=0 THEN 1 ELSE 0 END) AS total,
                  SUM(CASE WHEN unread=1 AND deleted_ts=0 THEN 1 ELSE 0 END) AS unread,
                  SUM(CASE WHEN date_ts >= ? AND deleted_ts=0 THEN 1 ELSE 0 END) AS today,
                  SUM(CASE WHEN date_ts >= ? AND category='验证码' AND deleted_ts=0 THEN 1 ELSE 0 END) AS today_otp,
                  SUM(CASE WHEN unread=1 AND deleted_ts=0
                            AND (category IN ('重要','安全','可疑') OR importance >= 4)
                           THEN 1 ELSE 0 END) AS important_unread
           FROM messages""",
        (day_start, day_start),
    ).fetchone()
    stats = {k: int(row[k] or 0) for k in ("total", "unread", "today", "today_otp",
                                           "important_unread")}
    # 分类分布
    cat_rows = conn.execute(
        "SELECT category, bill_tier, COUNT(*) c FROM messages WHERE deleted_ts=0 "
        "GROUP BY category, bill_tier").fetchall()
    by_category: dict[str, int] = {}
    for r in cat_rows:
        name = _display_category(r["category"], r["bill_tier"])
        by_category[name] = by_category.get(name, 0) + r["c"]
    stats["by_category"] = by_category
    # 近 14 天每日邮件量（按分类堆叠）：一条查询取出全部日期，再在 Python 里分桶
    start = day_start - 13 * 86400
    end = day_start + 86400
    buckets: list[dict[str, int]] = [{} for _ in range(14)]
    for r in conn.execute(
            """SELECT CAST((date_ts - ?) / 86400 AS INTEGER) AS d, category, bill_tier, COUNT(*) AS c
               FROM messages
               WHERE date_ts >= ? AND date_ts < ? AND deleted_ts = 0
               GROUP BY d, category, bill_tier""",
            (start, start, end)).fetchall():
        d = int(r["d"])
        if not 0 <= d < 14:
            continue
        name = _display_category(r["category"], r["bill_tier"])
        buckets[d][name] = buckets[d].get(name, 0) + r["c"]
    days = []
    daily = []
    for i in range(13, -1, -1):
        d0 = day_start - i * 86400
        label = datetime.fromtimestamp(d0, sync.TZ).strftime("%m-%d")
        days.append(label)
        row_d = {"day": label}
        row_d.update(buckets[13 - i])   # 只出现当天有邮件的分类，不补 0
        daily.append(row_d)
    # 重要邮件（未读优先）
    important = conn.execute(
        """SELECT m.id, m.subject, m.sender_addr, m.date_ts, m.category, m.bill_tier, m.importance,
                  m.summary, m.unread, a.name AS account_name, a.color AS account_color
           FROM messages m JOIN accounts a ON a.id=m.account_id
           WHERE m.deleted_ts=0 AND (m.category IN ('重要','安全','可疑') OR m.importance >= 4)
           ORDER BY m.unread DESC, m.date_ts DESC LIMIT 8""").fetchall()
    # 最新晨报
    digest = conn.execute("SELECT day, content, created_ts FROM digests ORDER BY day DESC LIMIT 1").fetchone()
    return {
        "stats": stats,
        "days": days,
        "daily": daily,
        "categories": DISPLAY_CATEGORIES,
        "important": [dict(r) for r in important],
        "digest": dict(digest) if digest else None,
        "ai_enabled": ai.ai_config()["enabled"],
    }


@app.get("/api/overview")
async def overview():
    return await asyncio.to_thread(_overview_sync)


# ---------- 摘要 ----------

@app.get("/api/digests")
async def list_digests(limit: int = 7):
    conn = get_conn()
    rows = conn.execute("SELECT day, content, created_ts FROM digests ORDER BY day DESC LIMIT ?",
                        (min(30, max(1, limit)),)).fetchall()
    return [dict(r) for r in rows]


@app.post("/api/digest/generate")
async def gen_digest():
    if not ai.ai_config()["enabled"]:
        raise HTTPException(status_code=400, detail="AI 未启用，请先在设置中配置")
    try:
        content = await asyncio.to_thread(sync.generate_digest, True)
        return {"ok": True, "content": content}
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"生成失败: {str(e)[:200]}")


async def _run_governance_api(trigger: str, since_days: int = 0):
    result = await asyncio.to_thread(
        governance.run_governance, limit=0, trigger=trigger, since_days=since_days
    )
    # 裁决后才推送重要/安全新邮件；推送条目不随接口返回
    sync.push_alerts(result.pop("alerts", None))
    if result.get("busy"):
        raise HTTPException(status_code=409, detail="已有自动分拣任务正在运行")
    return result


@app.post("/api/ai/classify-now")
async def classify_now():
    """旧入口兼容别名；AI 未启用时也会完成本地规则与安全治理。"""
    return await _run_governance_api("legacy-ai-button")


@app.get("/api/governance/status")
async def governance_status():
    return governance.governance_status()


class GovernanceRunBody(BaseModel):
    # 0 = 只处理待分拣的邮件；1-90 = 重新分拣最近这些天的全部邮件
    days: int = Field(default=0, ge=0, le=90)


@app.post("/api/governance/run")
async def governance_run(body: GovernanceRunBody | None = None):
    """一键执行完整自动治理，不删除、转发、回复或访问邮件中的外部内容。"""
    days = body.days if body else 0
    try:
        return await _run_governance_api("user-resort" if days else "user-one-click", since_days=days)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"自动分拣失败：{str(e)[:200]}")


# ---------- 设置 ----------

class SettingsBody(BaseModel):
    ai_enabled: bool | None = None
    ai_base_url: str | None = None
    ai_key: str | None = None
    ai_model: str | None = None
    ai_send_body: bool | None = None
    ai_scope: str | None = None             # all / uncertain / other
    ai_backfill_days: int | None = None     # 0 = 不限
    clean_otp_days: int | None = None
    clean_server: bool | None = None
    clean_bill_minor_days: int | None = None
    bill_amount_threshold: float | None = None
    bill_routine_count: int | None = None
    bill_auto_renew: str | None = None      # 重要 / 一般
    bill_refund: str | None = None          # 重要 / 一般
    digest_hour: int | None = None
    old_password: str | None = None
    new_password: str | None = None
    notify_bark_url: str | None = None
    clear_notify_bark: bool | None = None
    notify_tg_token: str | None = None
    clear_notify_tg_token: bool | None = None
    notify_tg_chat: str | None = None
    notify_onepush_config: str | None = None    # Onepush YAML；留空不修改
    clear_notify_onepush: bool | None = None
    notify_important: bool | None = None
    notify_otp: bool | None = None
    body_keep_days: int | None = None
    google_oauth_client_id: str | None = None
    google_oauth_client_secret: str | None = None
    clear_google_oauth_client_secret: bool | None = None
    microsoft_oauth_client_id: str | None = None
    microsoft_oauth_client_secret: str | None = None
    clear_microsoft_oauth_client_secret: bool | None = None


@app.get("/api/settings")
async def get_settings():
    cfg = ai.ai_config()
    google_oauth = oauth_auth.browser_config_info("gmail")
    microsoft_oauth = oauth_auth.browser_config_info("outlook")
    # Bark 地址里带设备 key，只回显是否已设置和主机名
    notify_cfg = notify._cfg()
    bark_url = notify_cfg["bark_url"]
    try:
        bark_host = urlparse(bark_url).hostname or ""
    except ValueError:
        bark_host = ""
    return {
        "ai_enabled": cfg["enabled"],
        "ai_base_url": cfg["base_url"],
        "ai_key_set": bool(cfg["api_key"]),
        "ai_model": cfg["model"],
        "ai_send_body": cfg["send_body"],
        "ai_scope": cfg["scope"],
        "ai_backfill_days": cfg["backfill_days"],
        "clean_otp_days": int(get_setting("clean_otp_days", "0") or 0),
        "clean_server": get_setting("clean_server", "0") == "1",
        "clean_bill_minor_days": int(get_setting("clean_bill_minor_days", "0") or 0),
        "bill_amount_threshold": float(get_setting("bill_amount_threshold", "")
                                       or billing.DEFAULTS["amount_threshold"]),
        "bill_routine_count": int(get_setting("bill_routine_count", "")
                                  or billing.DEFAULTS["routine_count"]),
        "bill_auto_renew": get_setting("bill_auto_renew", "") or billing.DEFAULTS["auto_renew"],
        "bill_refund": get_setting("bill_refund", "") or billing.DEFAULTS["refund"],
        "digest_hour": int(get_setting("digest_hour", "8") or 8),
        "notify_bark_set": bool(bark_url),
        "notify_bark_host": bark_host,
        "notify_tg_token_set": bool(notify_cfg["tg_token"]),
        "notify_tg_chat": get_setting("notify_tg_chat", ""),
        # Onepush 配置含令牌，只返回是否已设置和服务商名
        "notify_onepush_set": bool(get_setting(notify.ONEPUSH_SETTING, "")),
        "notify_onepush_provider": notify.onepush_provider(
            decrypt(get_setting(notify.ONEPUSH_SETTING, ""))),
        "notify_important": get_setting("notify_important", "1") == "1",
        "notify_otp": get_setting("notify_otp", "0") == "1",
        "body_keep_days": int(get_setting("body_keep_days", "90") or 90),
        "google_oauth_configured": google_oauth["configured"],
        "google_oauth_source": google_oauth["source"],
        "google_oauth_client_id": google_oauth["client_id"],
        "google_oauth_client_secret_set": google_oauth["secret_set"],
        "google_oauth_callback_url": google_oauth["callback_url"],
        "google_oauth_configuration_error": google_oauth["configuration_error"],
        "microsoft_oauth_configured": microsoft_oauth["configured"],
        "microsoft_oauth_device_configured": bool(oauth_auth._device_client_id()),
        "microsoft_oauth_source": microsoft_oauth["source"],
        "microsoft_oauth_client_id": microsoft_oauth["client_id"],
        "microsoft_oauth_client_secret_set": microsoft_oauth["secret_set"],
        "microsoft_oauth_callback_url": microsoft_oauth["callback_url"],
    }


def _save_oauth_settings(body: SettingsBody, *, prefix: str, provider: str, label: str):
    client_id = getattr(body, f"{prefix}_oauth_client_id")
    client_secret = getattr(body, f"{prefix}_oauth_client_secret")
    clear_secret = getattr(body, f"clear_{prefix}_oauth_client_secret")
    if client_id is not None:
        new_id = client_id.strip()
        if len(new_id) > 500:
            raise HTTPException(status_code=400, detail=f"{label} Client ID 过长")
        identity_error = oauth_auth.oauth_client_identity_error(provider, new_id)
        if new_id and identity_error:
            raise HTTPException(status_code=400, detail=identity_error)
        old_id = get_setting(f"{prefix}_oauth_client_id", "").strip()
        set_setting(f"{prefix}_oauth_client_id", new_id)
        if old_id and old_id != new_id:
            conn = get_conn()
            conn.execute(
                """UPDATE accounts SET oauth_reauth_required=1,
                   last_error=?
                   WHERE provider=? AND auth_type='oauth' AND oauth_client_id=?""",
                (f"{label} OAuth 客户端配置已变化，请重新登录", provider, old_id),
            )
            conn.commit()
    if clear_secret:
        set_setting(f"{prefix}_oauth_client_secret_enc", "")
    elif client_secret:
        secret = client_secret.strip()
        if len(secret) > 2000:
            raise HTTPException(status_code=400, detail=f"{label} Client Secret 过长")
        set_setting(f"{prefix}_oauth_client_secret_enc", encrypt(secret))


@app.put("/api/settings")
async def put_settings(body: SettingsBody, request: Request = None):
    # 先校验密码，避免密码错误时其他设置已被部分保存
    if body.new_password is not None:
        if not await asyncio.to_thread(verify_password, body.old_password or "", get_setting("admin_hash")):
            raise HTTPException(status_code=400, detail="原密码错误")
        if len(body.new_password) < 8:
            raise HTTPException(status_code=400, detail="新密码至少 8 位")
    if body.ai_scope is not None and body.ai_scope not in ai.AI_SCOPES:
        raise HTTPException(status_code=400, detail="AI 判定范围必须是 all / uncertain / other")
    for value in (body.bill_auto_renew, body.bill_refund):
        if value is not None and value not in billing.TIERS:
            raise HTTPException(status_code=400, detail="账单等级只能是「重要」或「一般」")
    onepush_text = (body.notify_onepush_config or "").strip()
    if onepush_text and not body.clear_notify_onepush:
        try:
            notify.validate_onepush_config(onepush_text)
        except notify.OnepushConfigError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        except ImportError as exc:
            raise HTTPException(status_code=400,
                                detail="服务器未安装 onepush/PyYAML，请先 pip install -r requirements.txt") from exc
    # 推送凭据也是设置：先校验，校验不过时一条设置都不会写入
    bark_url = (body.notify_bark_url or "").strip()
    if bark_url and not body.clear_notify_bark:
        if not bark_url.lower().startswith(("http://", "https://")):
            raise HTTPException(status_code=400,
                                detail="Bark 推送地址必须以 http:// 或 https:// 开头")
        if len(bark_url) > 500:
            raise HTTPException(status_code=400, detail="Bark 推送地址过长（上限 500 字符）")
    tg_token = (body.notify_tg_token or "").strip()
    _save_oauth_settings(body, prefix="google", provider="gmail", label="Google")
    _save_oauth_settings(body, prefix="microsoft", provider="outlook", label="Microsoft")
    if body.new_password is not None:
        new_hash = await asyncio.to_thread(hash_password, body.new_password)
        set_setting("admin_hash", new_hash)
        _remove_initial_password_file()
        # 改密码让所有旧会话失效，当前设备换发新会话继续使用
        token, source = _request_token(request)
        orig_client, orig_label = "web", ""
        if token:
            row = get_conn().execute(
                "SELECT client, label FROM sessions WHERE id_hash=?",
                (sessions._hash(token),)
            ).fetchone()
            if row:
                orig_client = row["client"] or "web"
                orig_label = row["label"] or ""
        count = sessions.revoke_all_sessions()
        new_session = sessions.create_session(client=orig_client, label=orig_label)
        audit("change_password", actor="user", tier="A",
              reason=f"修改管理密码，吊销 {count} 个会话")
    if body.ai_enabled is not None:
        set_setting("ai_enabled", "1" if body.ai_enabled else "0")
    if body.ai_base_url is not None:
        set_setting("ai_base_url", body.ai_base_url.strip())
    if body.ai_key:
        set_setting("ai_key_enc", encrypt(body.ai_key.strip()))
    if body.ai_model is not None:
        set_setting("ai_model", body.ai_model.strip())
    if body.ai_send_body is not None:
        set_setting("ai_send_body", "1" if body.ai_send_body else "0")
    if body.ai_scope is not None:
        set_setting("ai_scope", body.ai_scope)
    if body.ai_backfill_days is not None:
        set_setting("ai_backfill_days", str(min(365, max(0, body.ai_backfill_days))))
    if body.clean_otp_days is not None:
        set_setting("clean_otp_days", str(max(0, body.clean_otp_days)))
    if body.clean_server is not None:
        set_setting("clean_server", "1" if body.clean_server else "0")
    if body.digest_hour is not None:
        set_setting("digest_hour", str(min(23, max(0, body.digest_hour))))
    # 推送凭据只存加密键，旧明文键顺手清掉；clear 优先于同一请求里的新值
    if body.clear_notify_bark or bark_url:
        conn = get_conn()
        set_setting(notify.BARK_SETTING, encrypt("" if body.clear_notify_bark else bark_url))
        conn.execute("DELETE FROM settings WHERE k=?", (notify.BARK_LEGACY_SETTING,))
        conn.commit()
    if body.clear_notify_tg_token or tg_token:
        conn = get_conn()
        set_setting(notify.TG_TOKEN_SETTING,
                    encrypt("" if body.clear_notify_tg_token else tg_token))
        conn.execute("DELETE FROM settings WHERE k=?", (notify.TG_TOKEN_LEGACY_SETTING,))
        conn.commit()
    if body.notify_tg_chat is not None:
        set_setting("notify_tg_chat", body.notify_tg_chat.strip())
    if body.clear_notify_onepush:
        set_setting(notify.ONEPUSH_SETTING, "")
    elif onepush_text:
        set_setting(notify.ONEPUSH_SETTING, encrypt(onepush_text))
    if body.notify_important is not None:
        set_setting("notify_important", "1" if body.notify_important else "0")
    if body.notify_otp is not None:
        set_setting("notify_otp", "1" if body.notify_otp else "0")
    if body.body_keep_days is not None:
        set_setting("body_keep_days", str(max(0, body.body_keep_days)))
    if body.clean_bill_minor_days is not None:
        set_setting("clean_bill_minor_days", str(max(0, body.clean_bill_minor_days)))
    bill_changed = False
    for key, value in (
        ("bill_amount_threshold", None if body.bill_amount_threshold is None
         else f"{min(1_000_000.0, max(0.0, body.bill_amount_threshold)):g}"),
        ("bill_routine_count", None if body.bill_routine_count is None
         else str(min(1000, max(0, body.bill_routine_count)))),
        ("bill_auto_renew", body.bill_auto_renew),
        ("bill_refund", body.bill_refund),
    ):
        if value is not None and value != get_setting(key, ""):
            set_setting(key, value)
            bill_changed = True
    if bill_changed:
        # 策略变了：按已存的交易画像重新定级（不调用 AI，用户手动设定的不动）
        governance.retier_bills(get_conn())
    if body.new_password is not None:
        if source == "bearer":
            return {"ok": True, "token": new_session}
        # 当前设备换发新会话继续使用；其他设备的会话已在上面全部吊销
        response = JSONResponse({"ok": True})
        _set_session_cookie(response, new_session)
        return response
    return {"ok": True}


@app.get("/api/ai/models")
async def ai_models():
    try:
        return await asyncio.to_thread(ai.list_models)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"获取模型失败: {str(e)[:150]}")


@app.post("/api/ai/test")
async def ai_test():
    try:
        reply = await asyncio.to_thread(
            ai.chat, [{"role": "user", "content": "回复两个字：正常"}], 20)
        return {"ok": True, "reply": reply.strip()[:50]}
    except Exception as e:
        return {"ok": False, "msg": str(e)[:200]}


class NotifyTestBody(BaseModel):
    # 设置页里尚未保存的 Onepush 配置；为空时测试已保存的全部通道
    onepush_config: str = ""


@app.post("/api/notify/test")
async def notify_test(body: NotifyTestBody | None = None):
    try:
        msg = await asyncio.to_thread(notify.test_push, (body.onepush_config if body else ""))
        return {"ok": True, "msg": msg}
    except Exception as e:
        return {"ok": False, "msg": str(e)[:200]}


# ---------- 自定义规则 ----------

class RuleBody(BaseModel):
    name: str = ""
    field: str            # subject / sender / body
    pattern: str
    category: str
    priority: int = 100
    enabled: bool = True


@app.get("/api/rules")
async def list_rules():
    conn = get_conn()
    return [dict(r) for r in conn.execute("SELECT * FROM rules ORDER BY priority, id").fetchall()]


@app.post("/api/rules")
async def create_rule(body: RuleBody):
    if body.field not in ("subject", "sender", "body"):
        raise HTTPException(status_code=400, detail="field 必须是 subject/sender/body")
    if body.category not in rules_mod.CATEGORIES:
        raise HTTPException(status_code=400, detail="非法分类")
    try:
        re.compile(body.pattern)
    except re.error as e:
        raise HTTPException(status_code=400, detail=f"正则不合法: {e}")
    conn = get_conn()
    cur = conn.execute(
        "INSERT INTO rules(name, field, pattern, category, priority, enabled) VALUES(?,?,?,?,?,?)",
        (body.name, body.field, body.pattern, body.category, body.priority, int(body.enabled)),
    )
    conn.commit()
    return {"ok": True, "id": cur.lastrowid}


@app.delete("/api/rules/{rule_id}")
async def delete_rule(rule_id: int):
    conn = get_conn()
    conn.execute("DELETE FROM rules WHERE id=?", (rule_id,))
    conn.commit()
    return {"ok": True}


# ---------- 静态资源 ----------

app.mount("/static", StaticFiles(directory=os.path.join(BASE_DIR, "static")), name="static")
app.mount(
    "/vendor",
    StaticFiles(directory=os.path.join(BASE_DIR, "static", "vendor"), check_dir=False),
    name="vendor",
)


@app.get("/")
async def index():
    return FileResponse(os.path.join(BASE_DIR, "static", "index.html"))


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8018)
