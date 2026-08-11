"""后台同步引擎：定时拉取各账户新邮件 → 规则分类 → AI 补充分类 → 每日摘要 / 自动清理"""
import threading
import time
import traceback
from datetime import datetime
from zoneinfo import ZoneInfo

import ai
import governance
import mail_client
import notify
import oauth_auth
import policy
import rules
import security_scan
from db import audit, get_conn, get_setting, set_setting
from security import decrypt

TZ = ZoneInfo("Asia/Shanghai")

# account_id -> {"state": idle|syncing|error, "msg": str, "ts": float, "new": int}
STATUS: dict = {}
_status_lock = threading.Lock()
_sync_locks: dict = {}          # 防止同一账户并发同步
_force_queue: set = set()       # 手动触发的账户 id


def _set_status(acc_id: int, state: str, msg: str = "", new: int = 0):
    with _status_lock:
        STATUS[acc_id] = {"state": state, "msg": msg, "ts": time.time(), "new": new}


def get_status() -> dict:
    with _status_lock:
        return {str(k): dict(v) for k, v in STATUS.items()}


def _is_duplicate(conn, acc_id: int, msg_id: str) -> bool:
    """跨账户 Message-ID 判重。

    同账户内靠 UNIQUE(account_id, uid) 已能拦住重复拉取；这里解决的是
    「A 邮箱转发到 B 邮箱」时同一封信在两个账户各存一份的问题——
    仅当已存在的那条属于**其他**账户时才判为重复，避免误伤本账户重同步。
    """
    if not msg_id:
        return False
    row = conn.execute(
        "SELECT account_id FROM messages WHERE msg_id=? LIMIT 1", (msg_id,)).fetchone()
    return bool(row) and row["account_id"] != acc_id


def resolve_secret(acc: dict) -> str:
    """取得可用登录凭据：密码直接解密；OAuth 按服务商刷新。"""
    if acc["auth_type"] != "oauth":
        return decrypt(acc["secret_enc"])
    return oauth_auth.resolve_oauth_access_token(acc)


def sync_account(acc_id: int) -> int:
    """同步单个账户，返回新邮件数；线程内调用"""
    lock = _sync_locks.setdefault(acc_id, threading.Lock())
    if not lock.acquire(blocking=False):
        return 0
    conn = get_conn()
    try:
        row = conn.execute("SELECT * FROM accounts WHERE id=? AND enabled=1", (acc_id,)).fetchone()
        if not row:
            return 0
        acc = dict(row)
        _set_status(acc_id, "syncing", "连接中")
        secret = resolve_secret(acc)
        sess = mail_client.open_session(acc, secret)
        try:
            uidvalidity = sess.select_inbox()
            last_uid = acc["last_uid"]
            if uidvalidity and acc["uidvalidity"] and uidvalidity != acc["uidvalidity"]:
                last_uid = 0  # UIDVALIDITY 变化说明服务器重建了邮箱，UID 全部作废
            uids = sess.search_uids(last_uid)
            new_count = 0
            fresh: list[dict] = []   # 本轮新入库的邮件，用于推送通知
            if uids:
                _set_status(acc_id, "syncing", f"拉取 {len(uids)} 封")
                custom = rules.load_custom_rules(conn)
                msgs = sess.fetch_messages(uids)
                for m in msgs:
                    # —— 去重：同一封信可能经转发从多个账户到达，用 Message-ID 二次判重 ——
                    if m.get("msg_id") and _is_duplicate(conn, acc_id, m["msg_id"]):
                        continue

                    # —— 安全扫描：静态分析，绝不访问其中的链接或附件 ——
                    scan = security_scan.scan(
                        m["subject"], m["body_text"], m["sender_name"], m["sender_addr"],
                        reply_to=m.get("reply_to", ""), attachments=m.get("attach_names") or [])

                    cat, otp = rules.classify(
                        m["subject"], m["body_text"], m["sender_addr"], bool(m["unsubscribe"]), custom)
                    # 中/高风险立即保守标记「可疑」；后续三省六部流水线会补齐证据与审计。
                    # 这里只打本地标签，不访问链接、附件，也不执行移动或删除。
                    if security_scan.risk_at_least(scan["risk"], security_scan.RISK_MEDIUM):
                        cat, otp = "可疑", ""

                    try:
                        cur = conn.execute(
                            """INSERT OR IGNORE INTO messages
                               (account_id, uid, msg_id, subject, sender_name, sender_addr, to_addr,
                                date_ts, snippet, body_text, body_html, category, otp_code, unread,
                                has_attach, unsubscribe, created_ts, risk_level, risk_reasons,
                                needs_review)
                               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                            (acc_id, m["uid"], m["msg_id"], m["subject"], m["sender_name"],
                             m["sender_addr"], m.get("to_addr", ""), m["date_ts"], m["snippet"],
                             m["body_text"], m["body_html"], cat, otp, m["unread"], m["has_attach"],
                             m["unsubscribe"], time.time(), scan["risk"],
                              " · ".join(scan["reasons"])[:500], 0),
                        )
                        if cur.rowcount:
                            new_count += 1
                            fresh.append({"subject": m["subject"], "sender_addr": m["sender_addr"],
                                          "category": cat, "otp_code": otp, "importance": 0,
                                          "risk": scan["risk"]})
                    except Exception:
                        continue
                max_uid = max(uids)
                conn.execute(
                    "UPDATE accounts SET last_uid=?, uidvalidity=?, last_sync=?, last_error='' WHERE id=?",
                    (max(max_uid, last_uid), uidvalidity, time.time(), acc_id),
                )
            else:
                conn.execute(
                    "UPDATE accounts SET uidvalidity=?, last_sync=?, last_error='' WHERE id=?",
                    (uidvalidity, time.time(), acc_id),
                )
            conn.commit()
            _set_status(acc_id, "idle", "", new_count)
            if fresh:
                notify.notify_new_messages(fresh, acc["name"])
            return new_count
        finally:
            sess.close()
    except Exception as e:
        err = str(e)[:200]
        try:
            conn.execute("UPDATE accounts SET last_error=?, last_sync=? WHERE id=?",
                         (err, time.time(), acc_id))
            conn.commit()
        except Exception:
            pass
        _set_status(acc_id, "error", err)
        return 0
    finally:
        lock.release()


def maybe_burst_sync(acc_id: int, min_interval: int = 8):
    """突发同步：外部 API（注册机取码）轮询时调用，距上次同步超过 min_interval 秒才真正触发。
    注册机每 3 秒查一次邮件，由查询本身驱动同步节奏，验证码 10~20 秒内可达。"""
    conn = get_conn()
    row = conn.execute("SELECT last_sync FROM accounts WHERE id=? AND enabled=1", (acc_id,)).fetchone()
    if not row:
        return
    if time.time() - (row["last_sync"] or 0) < min_interval:
        return
    def run():
        count = sync_account(acc_id)
        if count:
            ai_classify_pending(max(30, count))

    threading.Thread(target=run, daemon=True).start()


def trim_old_bodies():
    """正文瘦身：超过保留天数的邮件清空 body（保留头信息/摘要/验证码），防 SQLite 无限膨胀"""
    days = int(get_setting("body_keep_days", "90") or 90)
    if days <= 0:
        return
    cutoff = time.time() - days * 86400
    conn = get_conn()
    cur = conn.execute(
        "UPDATE messages SET body_text='', body_html='' "
        "WHERE date_ts < ? AND (body_text != '' OR body_html != '')",
        (cutoff,),
    )
    conn.commit()
    if cur.rowcount > 500:
        # 大批量瘦身后回收磁盘空间（库不大，秒级完成）
        conn.execute("VACUUM")


def ai_classify_pending(limit: int = 30):
    """兼容旧调用名：执行完整自动三省六部治理，而不是创建人工复核队列。"""
    return governance.run_governance(limit=limit, trigger="sync")


def generate_digest(force: bool = False) -> str:
    """生成今日晨报（过去 24h 邮件）；force=True 覆盖已有"""
    conn = get_conn()
    day = datetime.now(TZ).strftime("%Y-%m-%d")
    if not force:
        row = conn.execute("SELECT content FROM digests WHERE day=?", (day,)).fetchone()
        if row:
            return row["content"]
    since = time.time() - 86400
    rows = conn.execute(
        """SELECT subject, sender_addr, category FROM messages
           WHERE date_ts > ? AND deleted_ts=0 ORDER BY date_ts DESC LIMIT 120""",
        (since,),
    ).fetchall()
    mails = [{"subject": r["subject"], "sender": r["sender_addr"], "category": r["category"]}
             for r in rows]
    content = ai.make_digest(mails)
    conn.execute(
        "INSERT INTO digests(day, content, created_ts) VALUES(?,?,?) "
        "ON CONFLICT(day) DO UPDATE SET content=excluded.content, created_ts=excluded.created_ts",
        (day, content, time.time()),
    )
    conn.commit()
    return content


def auto_clean():
    """定时清理过期验证码邮件（默认关闭）。

    这是唯一的无人值守破坏性动作，因此约束最严：
      - 只处理「验证码」分类，且逐封过 policy.evaluate
      - 默认软删除进回收站，可撤销
      - 只有用户显式开启 clean_server 才连服务器一起删（视为用户已建立规则）
      - 全程写审计日志
    """
    days = int(get_setting("clean_otp_days", "0") or 0)
    if days <= 0:
        return
    server_too = get_setting("clean_server", "0") == "1"
    cutoff = time.time() - days * 86400
    conn = get_conn()
    rows = conn.execute(
        """SELECT id, account_id, uid, category, has_attach, importance, sender_addr
           FROM messages WHERE category='验证码' AND date_ts < ? AND deleted_ts=0""",
        (cutoff,),
    ).fetchall()
    if not rows:
        return

    action = "purge" if server_too else "soft_delete"
    targets, blocked = [], []
    for r in rows:
        m = dict(r)
        # user_rule=True：用户在设置里显式启用了这条清理策略，等价于建立了规则
        allowed, tier, reason = policy.evaluate(
            action, m, actor="user", user_rule=True, confirmed=server_too)
        (targets.append(m) if allowed else blocked.append(reason))

    if not targets:
        if blocked:
            audit(action, actor="system", target_ids=[], allowed=False,
                  reason=f"自动清理全部被拦截：{blocked[0]}")
        return

    ids = [m["id"] for m in targets]
    if server_too:
        by_acc: dict = {}
        for m in targets:
            by_acc.setdefault(m["account_id"], []).append(m["uid"])
        for acc_id, uids in by_acc.items():
            try:
                acc_row = conn.execute("SELECT * FROM accounts WHERE id=?", (acc_id,)).fetchone()
                if not acc_row:
                    continue
                acc = dict(acc_row)
                sess = mail_client.open_session(acc, resolve_secret(acc))
                sess.select_inbox()
                sess.delete(uids)
                sess.close()
            except Exception:
                continue
        conn.executemany("DELETE FROM messages WHERE id=?", [(i,) for i in ids])
        undo = None
    else:
        ts = time.time()
        conn.executemany("UPDATE messages SET deleted_ts=? WHERE id=?", [(ts, i) for i in ids])
        undo = {"ids": ids}
    conn.commit()
    audit(action, actor="system", tier=policy.ACTION_TIER.get(action, "C"), target_ids=ids,
          reason=f"自动清理 {days} 天前的验证码邮件，{len(blocked)} 封被策略保护",
          reversible=undo is not None, undo_payload=undo)


class SyncEngine:
    """轻量调度器：30s 心跳，按账户 poll_interval 触发同步；每天定时晨报与清理"""

    def __init__(self):
        self._stop = threading.Event()
        self._workers = 0
        self._workers_lock = threading.Lock()

    def start(self):
        threading.Thread(target=self._loop, daemon=True, name="sync-loop").start()

    def stop(self):
        self._stop.set()

    def trigger(self, acc_id: int):
        _force_queue.add(acc_id)

    def trigger_all(self):
        conn = get_conn()
        for r in conn.execute("SELECT id FROM accounts WHERE enabled=1").fetchall():
            _force_queue.add(r["id"])

    def _spawn(self, acc_id: int):
        with self._workers_lock:
            if self._workers >= 3:  # 小内存机器，限制并发
                return False
            self._workers += 1

        def run():
            try:
                n = sync_account(acc_id)
                if n:
                    try:
                        ai_classify_pending(max(30, n))
                    except Exception:
                        pass
            finally:
                with self._workers_lock:
                    self._workers -= 1

        threading.Thread(target=run, daemon=True).start()
        return True

    def _loop(self):
        time.sleep(5)  # 等应用完全启动
        last_daily = ""
        while not self._stop.is_set():
            try:
                conn = get_conn()
                now = time.time()
                # 手动触发优先
                while _force_queue:
                    acc_id = _force_queue.pop()
                    self._spawn(acc_id)
                for r in conn.execute(
                    "SELECT id, poll_interval, last_sync FROM accounts WHERE enabled=1"
                ).fetchall():
                    if now - (r["last_sync"] or 0) >= max(60, r["poll_interval"] or 300):
                        self._spawn(r["id"])
                # 多账户并发同步时，先完成的治理任务可能看不到稍后入库的邮件；
                # 心跳会自动捞起剩余待治理项，不要求用户逐封处理。
                try:
                    if governance.governance_status()["pending"]:
                        ai_classify_pending(120)
                except Exception:
                    traceback.print_exc()
                # 每日任务：晨报 + 清理（本地时区到点后执行一次）
                local = datetime.now(TZ)
                digest_hour = int(get_setting("digest_hour", "8") or 8)
                day_key = local.strftime("%Y-%m-%d")
                if local.hour >= digest_hour and last_daily != day_key:
                    last_daily = day_key
                    if ai.ai_config()["enabled"]:
                        try:
                            generate_digest()
                        except Exception:
                            traceback.print_exc()
                    try:
                        auto_clean()
                    except Exception:
                        traceback.print_exc()
                    try:
                        trim_old_bodies()
                    except Exception:
                        traceback.print_exc()
            except Exception:
                traceback.print_exc()
            self._stop.wait(30)


engine = SyncEngine()
