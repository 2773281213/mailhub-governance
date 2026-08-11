"""MailHub 邮箱管理中心 —— FastAPI 主应用
聚合多邮箱（IMAP）+ 规则/AI 分类 + 验证码看板 + 每日摘要
仅监听 127.0.0.1，经 nginx (email.11451405.xyz) 对外提供 HTTPS
"""
import asyncio
import json
import os
import re
import secrets as pysecrets
import time
from contextlib import asynccontextmanager
from datetime import datetime, timedelta

from fastapi import Cookie, FastAPI, HTTPException, Request, Response
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

import ai
import governance
import mail_client
import notify
import oauth_auth
import policy
import rules as rules_mod
import sync
from db import audit, get_conn, get_setting, init_db, set_setting
from security import (decrypt, encrypt, hash_password, make_session,
                      verify_password, verify_session)

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
COOKIE_NAME = "mailhub_session"

# 登录失败限流：连续 5 次失败锁 5 分钟
_login_fails: list[float] = []


@asynccontextmanager
async def lifespan(_app: FastAPI):
    init_db()
    oauth_auth.init_oauth_db()
    # 首次启动生成随机管理密码，打印到日志（journalctl 可见），用户可在设置里修改
    if not get_setting("admin_hash"):
        pwd = pysecrets.token_urlsafe(9)
        set_setting("admin_hash", hash_password(pwd))
        print(f"[MailHub] 初始管理密码: {pwd}", flush=True)
    # 外部 API 令牌（注册机取码用），设置页可查看/重置
    if not get_setting("ext_token"):
        set_setting("ext_token", "mh-" + pysecrets.token_urlsafe(24))
    sync.engine.start()
    yield
    sync.engine.stop()


app = FastAPI(title="MailHub", lifespan=lifespan, docs_url=None, redoc_url=None)
app.include_router(oauth_auth.router)


# ---------- 鉴权 ----------

def require_auth(token: str | None):
    if not token or not verify_session(token):
        raise HTTPException(status_code=401, detail="未登录")


@app.middleware("http")
async def auth_middleware(request: Request, call_next):
    path = request.url.path
    if path.startswith("/api/") and path not in ("/api/login", "/api/health"):
        token = request.cookies.get(COOKIE_NAME)
        if not token or not verify_session(token):
            return JSONResponse({"detail": "未登录"}, status_code=401)
    return await call_next(request)


class LoginBody(BaseModel):
    password: str


@app.post("/api/login")
async def login(body: LoginBody, response: Response):
    now = time.time()
    recent = [t for t in _login_fails if now - t < 300]
    if len(recent) >= 5:
        raise HTTPException(status_code=429, detail="尝试过多，请 5 分钟后再试")
    if not verify_password(body.password, get_setting("admin_hash")):
        _login_fails.append(now)
        raise HTTPException(status_code=401, detail="密码错误")
    _login_fails.clear()
    response.set_cookie(
        COOKIE_NAME, make_session(), max_age=30 * 86400,
        httponly=True, secure=True, samesite="lax",
    )
    return {"ok": True}


@app.post("/api/logout")
async def logout(response: Response):
    response.delete_cookie(COOKIE_NAME)
    return {"ok": True}


@app.get("/api/health")
async def health():
    return {"ok": True, "ts": time.time()}


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


@app.get("/api/accounts")
async def list_accounts():
    conn = get_conn()
    rows = conn.execute("SELECT * FROM accounts ORDER BY id").fetchall()
    out = []
    for r in rows:
        d = _acc_public(r)
        d["total"] = conn.execute(
            "SELECT COUNT(*) c FROM messages WHERE account_id=?", (r["id"],)).fetchone()["c"]
        d["unread"] = conn.execute(
            "SELECT COUNT(*) c FROM messages WHERE account_id=? AND unread=1", (r["id"],)).fetchone()["c"]
        out.append(d)
    return out


def _resolve_host(body: AccountBody) -> tuple[str, int]:
    preset = mail_client.PROVIDERS.get(body.provider, {})
    host = body.imap_host or preset.get("host", "")
    port = body.imap_port or preset.get("port", 993)
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
        label = mail_client.PROVIDERS.get(detected, {}).get("label", detected)
        raise HTTPException(
            status_code=400,
            detail=f"邮箱域名已识别为「{label}」，请使用自动识别或选择匹配的服务商",
        )
    body.provider, body.email = oauth_auth.normalize_account_identity(requested, normalized_email)
    body.poll_interval = min(86400, max(60, body.poll_interval or 300))


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
        raise HTTPException(status_code=400, detail=f"连接测试失败: {str(exc)[:150]}") from exc


@app.post("/api/accounts")
async def create_account(body: AccountBody):
    _normalize_account_body(body)
    if body.provider == "outlook":
        raise HTTPException(status_code=400, detail="Outlook 请走 OAuth 授权流程")
    if not body.secret:
        raise HTTPException(status_code=400, detail="缺少授权码/密码")
    host, port = _resolve_host(body)
    await _probe_password_account(body, host, port)
    conn = get_conn()
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
    conn.execute("DELETE FROM accounts WHERE id=?", (acc_id,))
    conn.commit()
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


# ---------- Outlook OAuth 设备码 ----------

class DevicePollBody(BaseModel):
    device_code: str
    email: str
    name: str = ""
    color: str = "#0ea5e9"


@app.post("/api/outlook/devicecode")
async def outlook_devicecode():
    try:
        data = await asyncio.to_thread(mail_client.ms_device_code)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"请求微软授权失败: {str(e)[:150]}")
    return {
        "device_code": data["device_code"],
        "user_code": data["user_code"],
        "verification_uri": data.get("verification_uri", "https://microsoft.com/devicelogin"),
        "interval": data.get("interval", 5),
        "expires_in": data.get("expires_in", 900),
    }


@app.post("/api/outlook/poll")
async def outlook_poll(body: DevicePollBody):
    _provider, email_addr = oauth_auth.normalize_account_identity("outlook", body.email)
    try:
        data = await asyncio.to_thread(mail_client.ms_poll_token, body.device_code)
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e)[:200])
    if data.get("pending"):
        return {"pending": True}
    preset = mail_client.PROVIDERS["outlook"]
    probe = {
        "provider": "outlook", "auth_type": "oauth", "email": email_addr,
        "imap_host": preset["host"], "imap_port": preset["port"],
    }
    try:
        await asyncio.to_thread(oauth_auth.probe_connection, probe, data["access_token"])
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"邮箱身份验证失败: {str(exc)[:180]}") from exc
    conn = get_conn()
    cur = conn.execute(
        """INSERT INTO accounts(name, provider, email, imap_host, imap_port, auth_type,
                                oauth_refresh_enc, oauth_access_enc, oauth_expires,
                                poll_interval, color, enabled, created_ts)
           VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        (body.name or email_addr, "outlook", email_addr, preset["host"], preset["port"],
         "oauth", encrypt(data.get("refresh_token", "")), encrypt(data["access_token"]),
         time.time() + int(data.get("expires_in", 3600)), 300, body.color, 1, time.time()),
    )
    conn.commit()
    sync.engine.trigger(cur.lastrowid)
    return {"ok": True, "id": cur.lastrowid}


# ---------- 邮件 ----------

@app.get("/api/messages")
async def list_messages(category: str = "", account: int = 0, unread: int = -1,
                        q: str = "", page: int = 1, page_size: int = 40):
    conn = get_conn()
    where, args = ["1=1"], []
    if category:
        where.append("m.category=?"); args.append(category)
    if account:
        where.append("m.account_id=?"); args.append(account)
    if unread in (0, 1):
        where.append("m.unread=?"); args.append(unread)
    if q:
        where.append("(m.subject LIKE ? OR m.sender_addr LIKE ? OR m.sender_name LIKE ? OR m.snippet LIKE ?)")
        like = f"%{q}%"
        args += [like, like, like, like]
    where.append("m.deleted_ts=0")   # 回收站内容不出现在收件箱
    cond = " AND ".join(where)
    total = conn.execute(f"SELECT COUNT(*) c FROM messages m WHERE {cond}", args).fetchone()["c"]
    page_size = min(100, max(10, page_size))
    offset = (max(1, page) - 1) * page_size
    rows = conn.execute(
        f"""SELECT m.id, m.account_id, m.uid, m.subject, m.sender_name, m.sender_addr,
                   m.date_ts, m.snippet, m.category, m.otp_code, m.importance, m.summary,
                   m.unread, m.has_attach, a.name AS account_name, a.color AS account_color
            FROM messages m JOIN accounts a ON a.id=m.account_id
            WHERE {cond} ORDER BY m.date_ts DESC LIMIT ? OFFSET ?""",
        args + [page_size, offset],
    ).fetchall()
    return {"total": total, "page": page, "items": [dict(r) for r in rows]}


@app.get("/api/messages/{msg_id}")
async def get_message(msg_id: int):
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
    # 打开即本地标记已读（服务器端标记由前端显式调用）
    if d["unread"]:
        conn.execute("UPDATE messages SET unread=0 WHERE id=?", (msg_id,))
        conn.commit()
    return d


class BatchBody(BaseModel):
    ids: list[int]
    action: str                 # read / unread / delete
    server: bool = False        # 是否同步操作 IMAP 服务器（= 不可逆的 purge）
    confirmed: bool = False     # 前端已弹窗确认（C 类动作必需）


def _server_op(conn, ids: list[int], action: str) -> tuple[list[int], list[str]]:
    """把批量操作按账户分组回放到 IMAP，返回成功的本地 ID 和错误。"""
    if not ids:
        return [], []
    rows = conn.execute(
        f"SELECT id, account_id, uid FROM messages WHERE id IN ({','.join('?' * len(ids))})", ids
    ).fetchall()
    by_acc: dict = {}
    for r in rows:
        by_acc.setdefault(r["account_id"], []).append((r["id"], r["uid"]))
    errors, succeeded = [], []
    for acc_id, messages in by_acc.items():
        sess = None
        try:
            acc_row = conn.execute("SELECT * FROM accounts WHERE id=?", (acc_id,)).fetchone()
            if not acc_row:
                raise RuntimeError("账户不存在")
            acc = dict(acc_row)
            sess = mail_client.open_session(acc, sync.resolve_secret(acc))
            sess.select_inbox()
            uids = [uid for _msg_id, uid in messages]
            if action == "delete":
                sess.delete(uids)
            elif action == "read":
                sess.mark_read(uids, True)
            elif action == "unread":
                sess.mark_read(uids, False)
            succeeded.extend(msg_id for msg_id, _uid in messages)
        except Exception as e:
            errors.append(f"账户{acc_id}: {str(e)[:100]}")
        finally:
            if sess is not None:
                sess.close()
    return succeeded, errors


def _load_for_policy(conn, ids: list[int]) -> list[dict]:
    if not ids:
        return []
    ph = ",".join("?" * len(ids))
    rows = conn.execute(
        f"""SELECT id, category, has_attach, importance, sender_addr, unread, deleted_ts
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

    msgs = _load_for_policy(conn, ids)
    blocked, allowed_ids = [], []
    for m in msgs:
        ok, tier, reason = policy.evaluate(
            action, m, actor="user", confirmed=body.confirmed)
        (allowed_ids if ok else blocked).append(m["id"] if ok else {"id": m["id"], "reason": reason})

    if not allowed_ids:
        first = blocked[0]["reason"] if blocked else "无可执行目标"
        audit(action, actor="user", target_ids=ids, allowed=False, reason=first)
        raise HTTPException(status_code=403, detail=f"操作被安全策略拦截：{first}")

    errors = []
    undo_payload = None
    if action == "purge":
        # 不可逆：先在服务器删，再从本地移除，审计记录标注不可撤销
        succeeded, errors = await asyncio.to_thread(_server_op, conn, allowed_ids, "delete")
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
            succeeded, errors = await asyncio.to_thread(_server_op, conn, allowed_ids, body.action)
            allowed_ids = succeeded
        prev = {m["id"]: m["unread"] for m in msgs if m["id"] in set(allowed_ids)}
        if allowed_ids:
            ph = ",".join("?" * len(allowed_ids))
            conn.execute(f"UPDATE messages SET unread=? WHERE id IN ({ph})",
                         [0 if action == "read" else 1] + allowed_ids)
            undo_payload = {"unread": prev}
    conn.commit()

    audit_id = audit(action, actor="user", tier=policy.ACTION_TIER.get(action, "C"),
                     target_ids=allowed_ids,
                     allowed=bool(allowed_ids),
                     reason=(f"用户批量操作；{len(errors)} 个账户执行失败" if errors else
                             f"用户批量操作，{len(blocked)} 封被策略拦截" if blocked else "用户批量操作"),
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
        succeeded, errors = await asyncio.to_thread(_server_op, conn, ids, "delete")
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

@app.get("/api/overview")
async def overview():
    conn = get_conn()
    now = time.time()
    day_start = datetime.now(sync.TZ).replace(hour=0, minute=0, second=0, microsecond=0).timestamp()

    def q1(sql, *args):
        return conn.execute(sql, args).fetchone()["c"]

    stats = {
        "total": q1("SELECT COUNT(*) c FROM messages WHERE deleted_ts=0"),
        "unread": q1("SELECT COUNT(*) c FROM messages WHERE unread=1 AND deleted_ts=0"),
        "today": q1("SELECT COUNT(*) c FROM messages WHERE date_ts >= ? AND deleted_ts=0", day_start),
        "today_otp": q1("SELECT COUNT(*) c FROM messages WHERE date_ts >= ? AND category='验证码' AND deleted_ts=0", day_start),
        "important_unread": q1(
            "SELECT COUNT(*) c FROM messages WHERE unread=1 AND deleted_ts=0 "
            "AND (category IN ('重要','安全','可疑') OR importance >= 4)"),
    }
    # 分类分布
    cat_rows = conn.execute(
        "SELECT category, COUNT(*) c FROM messages WHERE deleted_ts=0 GROUP BY category").fetchall()
    stats["by_category"] = {r["category"]: r["c"] for r in cat_rows}
    # 近 14 天每日邮件量（按分类堆叠）
    days = []
    daily = []
    for i in range(13, -1, -1):
        d0 = day_start - i * 86400
        d1 = d0 + 86400
        label = datetime.fromtimestamp(d0, sync.TZ).strftime("%m-%d")
        days.append(label)
        row = {"day": label}
        for cat in rules_mod.CATEGORIES:
            row[cat] = q1("SELECT COUNT(*) c FROM messages WHERE date_ts>=? AND date_ts<? AND category=? AND deleted_ts=0",
                          d0, d1, cat)
        daily.append(row)
    # 重要邮件（未读优先）
    important = conn.execute(
        """SELECT m.id, m.subject, m.sender_addr, m.date_ts, m.category, m.importance,
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
        "categories": rules_mod.CATEGORIES,
        "important": [dict(r) for r in important],
        "digest": dict(digest) if digest else None,
        "ai_enabled": ai.ai_config()["enabled"],
    }


# ---------- 摘要 ----------

@app.get("/api/digests")
async def list_digests(limit: int = 7):
    conn = get_conn()
    rows = conn.execute("SELECT day, content, created_ts FROM digests ORDER BY day DESC LIMIT ?",
                        (limit,)).fetchall()
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


async def _run_governance_api(trigger: str):
    result = await asyncio.to_thread(
        governance.run_governance, limit=0, trigger=trigger
    )
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


@app.post("/api/governance/run")
async def governance_run():
    """一键执行完整自动治理，不删除、转发、回复或访问邮件中的外部内容。"""
    try:
        return await _run_governance_api("user-one-click")
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
    clean_otp_days: int | None = None
    clean_server: bool | None = None
    digest_hour: int | None = None
    old_password: str | None = None
    new_password: str | None = None
    alias_account_id: int | None = None
    notify_bark_url: str | None = None
    notify_tg_token: str | None = None
    notify_tg_chat: str | None = None
    notify_important: bool | None = None
    notify_otp: bool | None = None
    body_keep_days: int | None = None
    google_oauth_client_id: str | None = None
    google_oauth_client_secret: str | None = None
    clear_google_oauth_client_secret: bool | None = None


@app.get("/api/settings")
async def get_settings():
    cfg = ai.ai_config()
    conn = get_conn()
    alias_accounts = [dict(r) for r in conn.execute(
        "SELECT id, name, email FROM accounts WHERE enabled=1 AND provider IN ('gmail','outlook')"
    ).fetchall()]
    google_oauth = oauth_auth.browser_config_info("gmail")
    return {
        "ai_enabled": cfg["enabled"],
        "ai_base_url": cfg["base_url"],
        "ai_key_set": bool(cfg["api_key"]),
        "ai_model": cfg["model"],
        "ai_send_body": cfg["send_body"],
        "clean_otp_days": int(get_setting("clean_otp_days", "0") or 0),
        "clean_server": get_setting("clean_server", "0") == "1",
        "digest_hour": int(get_setting("digest_hour", "8") or 8),
        "ext_token": get_setting("ext_token", ""),
        "alias_account_id": int(get_setting("alias_account_id", "0") or 0),
        "alias_accounts": alias_accounts,
        "notify_bark_url": get_setting("notify_bark_url", ""),
        "notify_tg_token_set": bool(get_setting("notify_tg_token", "")),
        "notify_tg_chat": get_setting("notify_tg_chat", ""),
        "notify_important": get_setting("notify_important", "1") == "1",
        "notify_otp": get_setting("notify_otp", "0") == "1",
        "body_keep_days": int(get_setting("body_keep_days", "90") or 90),
        "google_oauth_configured": google_oauth["configured"],
        "google_oauth_source": google_oauth["source"],
        "google_oauth_client_id": google_oauth["client_id"],
        "google_oauth_client_secret_set": google_oauth["secret_set"],
        "google_oauth_callback_url": google_oauth["callback_url"],
    }


def _save_google_oauth_settings(body: SettingsBody):
    if body.google_oauth_client_id is not None:
        new_id = body.google_oauth_client_id.strip()
        if len(new_id) > 500:
            raise HTTPException(status_code=400, detail="Google Client ID 过长")
        old_id = get_setting("google_oauth_client_id", "").strip()
        set_setting("google_oauth_client_id", new_id)
        if old_id and old_id != new_id:
            conn = get_conn()
            conn.execute(
                """UPDATE accounts SET oauth_reauth_required=1,
                   last_error='Google OAuth 客户端配置已变化，请重新登录'
                   WHERE provider='gmail' AND auth_type='oauth'"""
            )
            conn.commit()
    if body.clear_google_oauth_client_secret:
        set_setting("google_oauth_client_secret_enc", "")
    elif body.google_oauth_client_secret:
        secret = body.google_oauth_client_secret.strip()
        if len(secret) > 2000:
            raise HTTPException(status_code=400, detail="Google Client Secret 过长")
        set_setting(
            "google_oauth_client_secret_enc",
            encrypt(secret),
        )


@app.put("/api/settings")
async def put_settings(body: SettingsBody):
    _save_google_oauth_settings(body)
    if body.new_password is not None:
        if not verify_password(body.old_password or "", get_setting("admin_hash")):
            raise HTTPException(status_code=400, detail="原密码错误")
        if len(body.new_password) < 8:
            raise HTTPException(status_code=400, detail="新密码至少 8 位")
        set_setting("admin_hash", hash_password(body.new_password))
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
    if body.clean_otp_days is not None:
        set_setting("clean_otp_days", str(max(0, body.clean_otp_days)))
    if body.clean_server is not None:
        set_setting("clean_server", "1" if body.clean_server else "0")
    if body.digest_hour is not None:
        set_setting("digest_hour", str(min(23, max(0, body.digest_hour))))
    if body.alias_account_id is not None:
        set_setting("alias_account_id", str(max(0, body.alias_account_id)))
    if body.notify_bark_url is not None:
        set_setting("notify_bark_url", body.notify_bark_url.strip())
    if body.notify_tg_token:
        set_setting("notify_tg_token", body.notify_tg_token.strip())
    if body.notify_tg_chat is not None:
        set_setting("notify_tg_chat", body.notify_tg_chat.strip())
    if body.notify_important is not None:
        set_setting("notify_important", "1" if body.notify_important else "0")
    if body.notify_otp is not None:
        set_setting("notify_otp", "1" if body.notify_otp else "0")
    if body.body_keep_days is not None:
        set_setting("body_keep_days", str(max(0, body.body_keep_days)))
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


# ---------- 外部 API：注册机取码（cloudflare_temp_email 兼容协议） ----------
# 注册机「Cloudflare Worker 自建」通道直接指向本站即可：
#   API 地址 https://email.11451405.xyz，管理员令牌填设置页的外部 API Token
# POST /admin/new_address 分配加号别名（基于 Gmail/Outlook 账户）
# GET  /admin/mails?address= 查询该别名邮件（查询自动触发突发同步）

import hmac as _hmac


def _check_ext(request: Request):
    token = get_setting("ext_token", "")
    provided = request.headers.get("x-admin-auth", "") \
        or request.headers.get("Authorization", "").removeprefix("Bearer ").strip() \
        or request.query_params.get("token", "")
    if not token or not provided or not _hmac.compare_digest(token, provided):
        raise HTTPException(status_code=401, detail="无效的外部 API 令牌")


def _alias_base_account(conn, domain: str = ""):
    """选择别名基座账户：显式 domain 参数 > 设置指定 > 第一个支持加号别名的账户"""
    if domain:
        row = conn.execute(
            "SELECT * FROM accounts WHERE enabled=1 AND email LIKE ? ORDER BY id LIMIT 1",
            (f"%@{domain}",)).fetchone()
        if row:
            return row
    aid = int(get_setting("alias_account_id", "0") or 0)
    if aid:
        row = conn.execute("SELECT * FROM accounts WHERE id=? AND enabled=1", (aid,)).fetchone()
        if row:
            return row
    return conn.execute(
        "SELECT * FROM accounts WHERE enabled=1 AND provider IN ('gmail','outlook') "
        "ORDER BY id LIMIT 1").fetchone()


@app.post("/admin/new_address")
async def ext_new_address(request: Request):
    _check_ext(request)
    try:
        body = await request.json()
    except Exception:
        body = {}
    name = re.sub(r"[^a-z0-9]", "", str(body.get("name", "")).lower()) or pysecrets.token_hex(5)
    conn = get_conn()
    acc = _alias_base_account(conn, str(body.get("domain", "")).strip())
    if not acc:
        raise HTTPException(status_code=400,
                            detail="没有可用的别名基座账户（需要已启用的 Gmail/Outlook 账户）")
    local, _, dom = acc["email"].partition("@")
    local = local.split("+")[0]
    alias = f"{local}+{name}@{dom}"
    conn.execute(
        "INSERT OR IGNORE INTO aliases(alias, account_id, name, created_ts) VALUES(?,?,?,?)",
        (alias, acc["id"], name, time.time()))
    conn.commit()
    # 同时给出 email/address 与 token/jwt 两种键名，兼容不同版本的取码客户端
    return {"email": alias, "address": alias, "token": alias, "jwt": alias}


def _pseudo_raw(row) -> str:
    """把入库邮件还原成近似 RFC822 文本：取码客户端在 \r\n\r\n 之后的部分里抠验证码"""
    body = row["body_html"] or row["body_text"] or ""
    return (f"Subject: {row['subject']}\r\nFrom: {row['sender_addr']}\r\n"
            f"To: {row['to_addr']}\r\n\r\n{body}")


@app.get("/admin/mails")
async def ext_mails(request: Request, address: str = "", limit: int = 20, offset: int = 0):
    _check_ext(request)
    address = address.strip().lower()
    if not address:
        raise HTTPException(status_code=400, detail="缺少 address 参数")
    conn = get_conn()
    arow = conn.execute("SELECT * FROM aliases WHERE alias=?", (address,)).fetchone()
    if arow:
        acc_id = arow["account_id"]
        conn.execute("UPDATE aliases SET last_query_ts=? WHERE id=?", (time.time(), arow["id"]))
        conn.commit()
        cond, args = "account_id=? AND to_addr LIKE ?", [acc_id, f"%{address}%"]
    else:
        # 也允许直接查真实账户地址
        acc = conn.execute("SELECT * FROM accounts WHERE email=? AND enabled=1", (address,)).fetchone()
        if not acc:
            return {"results": [], "count": 0}
        acc_id = acc["id"]
        cond, args = "account_id=?", [acc_id]
    # 查询即触发突发同步：注册机 3 秒一轮询，同步节奏由查询驱动
    sync.maybe_burst_sync(acc_id)
    rows = conn.execute(
        f"""SELECT id, subject, sender_addr, to_addr, body_text, body_html, date_ts
            FROM messages WHERE {cond} AND deleted_ts=0 ORDER BY date_ts DESC LIMIT ? OFFSET ?""",
        args + [min(50, limit), offset]).fetchall()
    results = [{
        "id": r["id"],
        "address": address,
        "source": r["sender_addr"],
        "subject": r["subject"],
        "raw": _pseudo_raw(r),
        "created_at": datetime.fromtimestamp(r["date_ts"], sync.TZ).isoformat(),
    } for r in rows]
    return {"results": results, "count": len(results)}


@app.get("/ext/otp/latest")
async def ext_otp_latest(request: Request, address: str = "", sender: str = "", max_age: int = 300):
    """通用取码接口（自有脚本用）：返回最新验证码。address 可为别名或真实账户地址"""
    _check_ext(request)
    conn = get_conn()
    where, args = ["otp_code != ''"], []
    address = address.strip().lower()
    if address:
        arow = conn.execute("SELECT * FROM aliases WHERE alias=?", (address,)).fetchone()
        if arow:
            where.append("account_id=? AND to_addr LIKE ?")
            args += [arow["account_id"], f"%{address}%"]
            sync.maybe_burst_sync(arow["account_id"])
        else:
            acc = conn.execute("SELECT id FROM accounts WHERE email=?", (address,)).fetchone()
            if acc:
                where.append("account_id=?")
                args.append(acc["id"])
                sync.maybe_burst_sync(acc["id"])
    else:
        for r in conn.execute("SELECT id FROM accounts WHERE enabled=1").fetchall():
            sync.maybe_burst_sync(r["id"])
    if sender:
        where.append("sender_addr LIKE ?")
        args.append(f"%{sender}%")
    where.append("deleted_ts=0")
    where.append("date_ts > ?")
    args.append(time.time() - max(30, max_age))
    row = conn.execute(
        f"SELECT otp_code, subject, sender_addr, date_ts FROM messages "
        f"WHERE {' AND '.join(where)} ORDER BY date_ts DESC LIMIT 1", args).fetchone()
    if not row:
        return JSONResponse({"found": False, "hint": "暂无符合条件的验证码，稍后重试（查询已触发同步）"},
                            status_code=404)
    return {"found": True, "code": row["otp_code"], "subject": row["subject"],
            "sender": row["sender_addr"], "ts": row["date_ts"]}


@app.post("/api/ext/regen-token")
async def regen_ext_token():
    token = "mh-" + pysecrets.token_urlsafe(24)
    set_setting("ext_token", token)
    return {"ok": True, "token": token}


@app.get("/api/ext/aliases")
async def list_aliases(limit: int = 20):
    conn = get_conn()
    rows = conn.execute(
        """SELECT al.alias, al.created_ts, al.last_query_ts, a.name AS account_name
           FROM aliases al JOIN accounts a ON a.id=al.account_id
           ORDER BY al.id DESC LIMIT ?""", (limit,)).fetchall()
    total = conn.execute("SELECT COUNT(*) c FROM aliases").fetchone()["c"]
    return {"total": total, "items": [dict(r) for r in rows]}


@app.post("/api/notify/test")
async def notify_test():
    try:
        msg = await asyncio.to_thread(notify.test_push)
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
