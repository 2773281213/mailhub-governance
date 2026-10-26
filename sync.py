"""后台同步引擎：定时拉取各账户新邮件 → 规则分类 → AI 补充分类 → 每日摘要 / 自动清理"""
import json
import threading
import time
import traceback
from datetime import datetime
from zoneinfo import ZoneInfo

import ai
import billing
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
_force_lock = threading.Lock()


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


SERVER_ACTIONS = ("delete", "read", "unread")


def apply_server_action(message_ids: list[int], action: str) -> tuple[list[int], list[str]]:
    """把动作按账户分组回放到 IMAP，返回 (服务器确认成功的本地 id, 错误)。

    在调用线程内取自己的 SQLite 连接：get_conn() 是线程本地的，
    不能把别的线程（例如 asyncio 事件循环）的连接传进来。
    """
    if action not in SERVER_ACTIONS:
        raise ValueError(f"不支持的服务器动作 {action}")
    if not message_ids:
        return [], []
    conn = get_conn()
    ph = ",".join("?" * len(message_ids))
    rows = conn.execute(
        f"SELECT id, account_id, uid FROM messages WHERE id IN ({ph})", list(message_ids)
    ).fetchall()
    by_acc: dict[int, list[tuple[int, int]]] = {}
    errors: list[str] = []
    for r in rows:
        if r["uid"] <= 0:
            # UIDVALIDITY 变化后解绑的旧记录，在服务器上已没有对应 UID
            errors.append(f"邮件{r['id']}: 服务器上已找不到对应邮件")
            continue
        by_acc.setdefault(r["account_id"], []).append((r["id"], r["uid"]))

    succeeded: list[int] = []
    for acc_id, items in by_acc.items():
        sess = None
        try:
            acc_row = conn.execute("SELECT * FROM accounts WHERE id=?", (acc_id,)).fetchone()
            if not acc_row:
                raise RuntimeError("账户不存在")
            acc = dict(acc_row)
            sess = mail_client.open_session(acc, resolve_secret(acc))
            sess.select_inbox()
            uids = [uid for _mid, uid in items]
            if action == "delete":
                sess.delete(uids)
            else:
                sess.mark_read(uids, action == "read")
            succeeded.extend(mid for mid, _uid in items)
        except Exception as exc:
            errors.append(f"账户{acc_id}: {str(exc)[:100]}")
        finally:
            if sess is not None:
                sess.close()
    return succeeded, errors


def _advance_watermark(last_uid: int, requested: list[int], failed) -> int:
    """计算新的同步水位：只推进到第一个失败 UID 之前。

    水位之后的 UID 下一轮会重新拉取；已入库的邮件靠 UNIQUE(account_id, uid)
    幂等跳过，所以宁可重拉也不能跳过失败的邮件。
    """
    failed = set(failed)
    watermark = last_uid
    for uid in sorted(requested):
        if uid in failed:
            break
        watermark = max(watermark, uid)
    return watermark


def _unbind_stale_uids(conn, acc_id: int, uidvalidity: int):
    """UIDVALIDITY 变化：旧记录保留在本地，但把 UID 改成负数让出唯一键。

    负 UID 不会与服务器上的任何 UID 冲突，服务器动作也会跳过它们。
    与新的 uidvalidity、归零的水位放在同一个事务里提交，中途失败也不会重复解绑。
    """
    conn.execute("UPDATE messages SET uid=-id WHERE account_id=? AND uid>0", (acc_id,))
    conn.execute("UPDATE accounts SET last_uid=0, uidvalidity=? WHERE id=?", (uidvalidity, acc_id))
    conn.commit()


def _rebind_by_message_id(conn, acc_id: int, message: dict) -> bool:
    """把重建邮箱后的新 UID 绑回同一封信原来的本地记录，保留分类与回收站状态。"""
    msg_id = message.get("msg_id")
    if not msg_id:
        return False
    if conn.execute("SELECT 1 FROM messages WHERE account_id=? AND uid=?",
                    (acc_id, message["uid"])).fetchone():
        return False   # 这个 UID 已经入库，交给后面的 INSERT OR IGNORE 幂等处理
    row = conn.execute(
        "SELECT id FROM messages WHERE account_id=? AND uid<0 AND msg_id=? ORDER BY id LIMIT 1",
        (acc_id, msg_id),
    ).fetchone()
    if not row:
        return False
    conn.execute("UPDATE messages SET uid=? WHERE id=?", (message["uid"], row["id"]))
    return True


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
                # UIDVALIDITY 变化说明服务器重建了邮箱，旧 UID 全部作废
                _unbind_stale_uids(conn, acc_id, uidvalidity)
                last_uid = 0
            uids = sess.search_uids(last_uid)
            new_count = 0
            fresh: list[dict] = []   # 本轮新入库的邮件，用于推送通知
            if uids:
                _set_status(acc_id, "syncing", f"拉取 {len(uids)} 封")
                custom = rules.load_custom_rules(conn)
                msgs, failed = sess.fetch_messages(uids)
                insert_error = ""
                for m in msgs:
                    # —— UIDVALIDITY 变化后，同一封信按 Message-ID 认回原来的本地记录 ——
                    if _rebind_by_message_id(conn, acc_id, m):
                        continue
                    # —— 去重：同一封信可能经转发从多个账户到达，用 Message-ID 二次判重 ——
                    if m.get("msg_id") and _is_duplicate(conn, acc_id, m["msg_id"]):
                        continue

                    # —— 安全扫描：静态分析，绝不访问其中的链接或附件 ——
                    scan = security_scan.scan(
                        m["subject"], m["body_text"], m["sender_name"], m["sender_addr"],
                        reply_to=m.get("reply_to", ""), attachments=m.get("attach_names") or [],
                        html=m.get("body_html", ""), auth_results=m.get("auth_results", ""))

                    signals = m.get("header_signals") or {}
                    ruled = rules.classify_detailed(
                        m["subject"], m["body_text"], m["sender_addr"], bool(m["unsubscribe"]),
                        custom, signals)
                    cat, otp, confidence = ruled["category"], ruled["otp"], ruled["confidence"]
                    # 中/高风险立即保守标记「可疑」；后续三省六部流水线会补齐证据与审计。
                    # 这里只打本地标签，不访问链接、附件，也不执行移动或删除。
                    if security_scan.risk_at_least(scan["risk"], security_scan.RISK_MEDIUM):
                        cat, otp, confidence = "可疑", "", 1.0

                    try:
                        cur = conn.execute(
                            """INSERT OR IGNORE INTO messages
                               (account_id, uid, msg_id, subject, sender_name, sender_addr, to_addr,
                                date_ts, snippet, body_text, body_html, category, otp_code, unread,
                                has_attach, unsubscribe, created_ts, risk_level, risk_reasons,
                                needs_review, confidence, header_signals, template_key)
                               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                            (acc_id, m["uid"], m["msg_id"], m["subject"], m["sender_name"],
                             m["sender_addr"], m.get("to_addr", ""), m["date_ts"], m["snippet"],
                             m["body_text"], m["body_html"], cat, otp, m["unread"], m["has_attach"],
                             m["unsubscribe"], time.time(), scan["risk"],
                             " · ".join(scan["reasons"])[:500], 0, confidence,
                             json.dumps(signals, sort_keys=True),
                             billing.template_key(m["sender_addr"], m["subject"])),
                        )
                        if cur.rowcount:
                            new_count += 1
                            # 验证码在这里立即推送（规则提取，保证时效）；重要/安全邮件
                            # 等治理裁决后再推送，避免按规则初判误推
                            if otp:
                                fresh.append({"subject": m["subject"],
                                              "sender_addr": m["sender_addr"],
                                              "category": cat, "otp_code": otp, "importance": 0,
                                              "risk": scan["risk"]})
                    except Exception as exc:
                        # 入库失败不能被水位跳过，记为失败，下次同步重试
                        failed.add(m["uid"])
                        insert_error = insert_error or str(exc)[:120]
                last_error = ""
                if failed:
                    last_error = f"{len(failed)} 封邮件拉取或入库失败，下次同步重试"
                    if insert_error:
                        last_error += f"（{insert_error}）"
                conn.execute(
                    "UPDATE accounts SET last_uid=?, uidvalidity=?, last_sync=?, last_error=? WHERE id=?",
                    (_advance_watermark(last_uid, uids, failed), uidvalidity, time.time(),
                     last_error[:200], acc_id),
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


def backfill_readable_bodies(batch: int = 200) -> int:
    """一次性：旧邮件的纯文本若只是「在浏览器中查看」占位，改存 HTML 转出的文字，
    让规则、AI 和账单分级都能读到真正的内容。返回修正的邮件数。"""
    if get_setting("body_readable_v1") == "1":
        return 0
    conn = get_conn()
    last_id, fixed = 0, 0
    while True:
        rows = conn.execute(
            "SELECT id, body_text, body_html FROM messages WHERE id > ? AND body_html != '' "
            "ORDER BY id LIMIT ?", (last_id, batch)).fetchall()
        if not rows:
            break
        for r in rows:
            last_id = r["id"]
            text = mail_client.readable_body(r["body_text"], r["body_html"])
            if text == r["body_text"]:
                continue
            text = mail_client.tidy_body(text)
            conn.execute("UPDATE messages SET body_text=?, snippet=? WHERE id=?",
                         (text, mail_client.make_snippet(text), r["id"]))
            fixed += 1
        conn.commit()
    set_setting("body_readable_v1", "1")
    return fixed


def push_alerts(alerts) -> None:
    """推送治理裁决后的重要/安全新邮件；按账户分组，失败不影响治理结果。"""
    by_account: dict[str, list[dict]] = {}
    for item in alerts or []:
        by_account.setdefault(str(item.get("account_name") or ""), []).append(item)
    for account_name, items in by_account.items():
        try:
            notify.notify_new_messages(items, account_name)
        except Exception:
            traceback.print_exc()


def ai_classify_pending(limit: int = 30):
    """兼容旧调用名：执行完整自动三省六部治理，而不是创建人工复核队列。"""
    result = governance.run_governance(limit=limit, trigger="sync")
    push_alerts(result.pop("alerts", None))
    return result


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
        """SELECT subject, sender_addr, category, bill_tier FROM messages
           WHERE date_ts > ? AND deleted_ts=0 ORDER BY date_ts DESC LIMIT 120""",
        (since,),
    ).fetchall()
    mails = [{"subject": r["subject"], "sender": r["sender_addr"],
              "category": governance.display_category(r["category"], r["bill_tier"])}
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
    """定时清理过期验证码邮件和一般账单（各自独立开关，默认关闭）。

    这是唯一的无人值守破坏性动作，因此约束最严：
      - 只处理「验证码」和「一般账单」，且逐封过 policy.evaluate
      - 默认软删除进回收站，可撤销
      - 只有用户显式开启 clean_server 才连服务器一起删（视为用户已建立规则）
      - 全程写审计日志
    """
    otp_days = int(get_setting("clean_otp_days", "0") or 0)
    bill_days = int(get_setting("clean_bill_minor_days", "0") or 0)
    server_too = get_setting("clean_server", "0") == "1"
    conn = get_conn()
    now_ts = time.time()
    rows = []
    if otp_days > 0:
        rows += conn.execute(
            """SELECT id, account_id, uid, category, bill_tier, has_attach, importance, sender_addr
               FROM messages WHERE category='验证码' AND date_ts < ? AND deleted_ts=0""",
            (now_ts - otp_days * 86400,),
        ).fetchall()
    if bill_days > 0:
        # 一般账单（收入到账、零金额、例行流水等）；带附件或重要度高的仍由策略保护
        rows += conn.execute(
            """SELECT id, account_id, uid, category, bill_tier, has_attach, importance, sender_addr
               FROM messages WHERE category='账单' AND bill_tier=? AND date_ts < ? AND deleted_ts=0""",
            (billing.TIER_MINOR, now_ts - bill_days * 86400),
        ).fetchall()
    if not rows:
        return

    action = "purge" if server_too else "soft_delete"
    targets, blocked = [], []
    for r in rows:
        m = dict(r)
        # user_rule=True：用户在设置里显式启用了这条清理策略，等价于建立了规则。
        # 这是无人值守的系统任务，受保护邮件（附件/重要/账单/安全）一律不动。
        allowed, tier, reason = policy.evaluate(
            action, m, actor="system", user_rule=True, confirmed=server_too)
        (targets.append(m) if allowed else blocked.append(reason))

    if not targets:
        if blocked:
            audit(action, actor="system", target_ids=[], allowed=False,
                  reason=f"自动清理全部被拦截：{blocked[0]}")
        return

    ids = [m["id"] for m in targets]
    errors: list[str] = []
    if server_too:
        # 只有服务器确认删除成功的邮件才移除本地记录；失败的留待下次重试
        ids, errors = apply_server_action(ids, "delete")
        conn.executemany("DELETE FROM messages WHERE id=?", [(i,) for i in ids])
        undo = None
    else:
        ts = time.time()
        conn.executemany("UPDATE messages SET deleted_ts=? WHERE id=?", [(ts, i) for i in ids])
        undo = {"ids": ids}
    conn.commit()
    scopes = []
    if otp_days > 0:
        scopes.append(f"{otp_days} 天前的验证码邮件")
    if bill_days > 0:
        scopes.append(f"{bill_days} 天前的一般账单")
    reason = f"自动清理{'和'.join(scopes)}，{len(blocked)} 封被策略保护"
    if errors:
        reason += f"；服务器删除失败 {len(errors)} 项：{errors[0]}"
    audit(action, actor="system", tier=policy.ACTION_TIER.get(action, "C"), target_ids=ids,
          reason=reason, reversible=undo is not None, undo_payload=undo)


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
        with _force_lock:
            _force_queue.add(acc_id)

    def trigger_all(self):
        conn = get_conn()
        rows = conn.execute("SELECT id FROM accounts WHERE enabled=1").fetchall()
        with _force_lock:
            for r in rows:
                _force_queue.add(r["id"])

    def _drain_force_queue(self) -> list[int]:
        with _force_lock:
            pending = sorted(_force_queue)
            _force_queue.clear()

        requeued = []
        for i, acc_id in enumerate(pending):
            if not self._spawn(acc_id):
                requeued = pending[i:]
                with _force_lock:
                    _force_queue.update(requeued)
                break
        return requeued

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
                self._drain_force_queue()
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
