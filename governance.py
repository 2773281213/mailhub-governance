"""邮件自动「三省六部」治理。

三省是同一条自动流水线，而不是三个需要用户点击的审批页：
  - 中书省：汇总本地规则、静态安全扫描、AI 与邮件元数据形成提案；
  - 门下省：自动驳回冲突、低置信度和被污染的 AI 结果；
  - 尚书省：只落地标签/优先级/摘要并写审计，不执行删除、转发或回复。

启用 AI 后，AI 读邮件内容做主判，本地规则是提示和兜底；判定范围由设置项
ai_scope 决定。六部是证据视角，不替代现有邮件分类。持久化证据只含枚举和布尔
信号，不保存邮件正文、主题、发件地址或凭据。
"""
from __future__ import annotations

import json
import re
import threading
import time
from collections import Counter

import ai
import policy
import rules
from db import audit, get_conn


VERSION = "mailhub-three-six-v2"
MINISTRIES = ("吏部", "户部", "礼部", "兵部", "刑部", "工部")
# 每次 AI 调用的邮件数：只发主题/发件人时 24 封；带正文时 8 封，控制单次输入长度
AI_BATCH_SIZE = 24
AI_BATCH_SIZE_WITH_BODY = 8
RUN_STALE_SECONDS = 15 * 60
# 每次从数据库读入并处理的邮件数；正文只读前 BODY_SCAN_CHARS 个字符做注入扫描
CHUNK_SIZE = 200
BODY_SCAN_CHARS = 20000
# AI 临时失败（网络、超时、漏答）后的重试：5 分钟起指数退避，最长 6 小时，最多 5 次
MAX_AI_RETRIES = 5
AI_RETRY_BASE_SECONDS = 300
AI_RETRY_MAX_SECONDS = 6 * 3600
# ai_scope=uncertain 时，规则置信度低于此值才交 AI
UNCERTAIN_BELOW = 0.8
# 把邮件移出这些分类（受保护类和验证码）、或判为「可疑」，AI 置信度必须达到此值
STICKY_CATEGORIES = frozenset(policy.PROTECTED_CATEGORIES | {"验证码"})
HIGH_CONFIDENCE = 0.85
# 只为入库不久的邮件推送；重新治理历史邮件不会补推
ALERT_FRESH_SECONDS = 2 * 3600

_RUN_LOCK = threading.Lock()
_RISK_RANK = {"none": 0, "low": 1, "medium": 2, "high": 3}
_AUTO_SENDER = re.compile(
    r"(^|[._-])(no[-_]?reply|notify|notification|mailer|robot|system)([._@+-]|$)"
    r"|updates?@|news@|info@|support@",
    re.I,
)
_FINANCE_HINT = re.compile(r"账单|发票|订单|支付|扣款|invoice|receipt|billing|payment|order", re.I)
_SUBSCRIPTION_HINT = re.compile(
    r"订阅|退订|周刊|简报|优惠|newsletter|unsubscribe|digest|promotion|coupon", re.I
)
_SECURITY_HINT = re.compile(
    r"异常登录|安全提醒|风险|密码|冻结|security|suspicious|password|two[- ]?factor|2fa", re.I
)


def _safe_category(value) -> str:
    return value if isinstance(value, str) and value in rules.CATEGORIES else "其他"


def _risk_at_least(value: str, threshold: str) -> bool:
    return _RISK_RANK.get(str(value or "none").lower(), 0) >= _RISK_RANK[threshold]


def _default_importance(category: str) -> int:
    return {
        "重要": 5,
        "安全": 5,
        "可疑": 5,
        "账单": 3,
        "验证码": 2,
        "其他": 2,
        "订阅": 1,
        "通知": 1,
    }.get(category, 2)


def _baseline_confidence(message: dict) -> float:
    """规则（或上一版治理）给出的置信度；旧数据没有时按原来的默认值。"""
    category = _safe_category(message.get("category"))
    try:
        value = float(message.get("confidence") or 0)
    except (TypeError, ValueError):
        value = 0.0
    if value <= 0:
        value = 0.9 if category != "其他" else 0.55
    return max(0.0, min(1.0, value))


def _signals(message: dict) -> dict:
    raw = message.get("header_signals")
    if isinstance(raw, dict):
        return raw
    try:
        value = json.loads(raw or "{}")
    except (TypeError, ValueError):
        return {}
    return value if isinstance(value, dict) else {}


def build_evidence(message: dict) -> dict:
    """生成六部共享的最小证据集合；返回值不得包含邮件原文或身份信息。"""
    category = _safe_category(message.get("category"))
    subject = str(message.get("subject") or "")
    snippet = str(message.get("snippet") or "")
    # 正文在本机做注入特征扫描；它不进入 evidence。发给 AI 的正文摘录另经遮蔽和截断。
    body_text = str(message.get("body_text") or "")
    sender = str(message.get("sender_addr") or "")
    text = f"{subject}\n{snippet}"
    risk = str(message.get("risk_level") or "none").lower()
    if risk not in _RISK_RANK:
        risk = "none"

    automated = bool(_AUTO_SENDER.search(sender))
    injection = ai.has_injection(subject, sender, snippet, body_text)
    return {
        "sender_kind": "automated" if automated else "person_or_unknown",
        "important_relationship": category == "重要",
        "financial": category == "账单" or bool(_FINANCE_HINT.search(text)),
        "subscription": category in ("订阅", "通知") or bool(_SUBSCRIPTION_HINT.search(text)),
        "security": category == "安全" or bool(_SECURITY_HINT.search(text)),
        "risk_level": risk,
        "prompt_injection": injection,
        "otp": category == "验证码" or bool(message.get("otp_code")),
        "attachment": bool(message.get("has_attach")),
        "unsubscribe": bool(message.get("unsubscribe")),
        "bulk": rules.is_bulk(_signals(message), bool(message.get("unsubscribe"))),
        "rule_category": category,
    }


def zhongshu_propose(message: dict, ai_result: dict | None, *, ai_enabled: bool) -> dict:
    """中书省：聚合证据形成提案，不在这一阶段执行任何动作。"""
    return {
        "message": message,
        "baseline_category": _safe_category(message.get("category")),
        "evidence": build_evidence(message),
        "ai_enabled": bool(ai_enabled),
        "ai_result": ai_result,
    }


_CATEGORY_MINISTRY = {
    "安全": "兵部",
    "账单": "户部",
    "验证码": "工部",
    "重要": "吏部",
    "订阅": "礼部",
    "通知": "礼部",
}


def _primary_ministry(evidence: dict, category: str) -> str:
    """按最终分类选择主责部；「其他」才参考关键词证据。其他部的证据仍完整保存在 decision 中。"""
    if evidence["prompt_injection"] or _risk_at_least(evidence["risk_level"], "medium") \
            or category == "可疑":
        return "刑部"
    if category in _CATEGORY_MINISTRY:
        return _CATEGORY_MINISTRY[category]
    if evidence["security"]:
        return "兵部"
    if evidence["financial"]:
        return "户部"
    if evidence["otp"]:
        return "工部"
    if evidence["important_relationship"]:
        return "吏部"
    if evidence["subscription"] or evidence["unsubscribe"]:
        return "礼部"
    if evidence["attachment"] or evidence["sender_kind"] == "automated":
        return "工部"
    return "吏部"


def _ai_guardrail(baseline: str, ai_category: str, ai_confidence: float, message: dict) -> str:
    """门下省对有效 AI 结果的护栏；返回驳回理由，空串表示可以采纳。"""
    if ai_confidence < policy.MIN_CONFIDENCE_GUARDED:
        return (f"门下省自动驳回低置信度 AI（{ai_confidence:.0%}），"
                f"保留本地分类「{baseline}」")
    if ai_category == "可疑" and ai_confidence < HIGH_CONFIDENCE:
        return (f"AI 判为可疑但置信度 {ai_confidence:.0%} 不足 {HIGH_CONFIDENCE:.0%}，"
                f"保留本地分类「{baseline}」")
    if ai_category == "验证码" and not message.get("otp_code"):
        return f"AI 判为验证码但本地未提取到验证码，保留本地分类「{baseline}」"
    if baseline in STICKY_CATEGORIES and ai_category != baseline \
            and ai_confidence < HIGH_CONFIDENCE:
        return (f"把邮件移出「{baseline}」需要 AI 置信度达到 {HIGH_CONFIDENCE:.0%}"
                f"（实际 {ai_confidence:.0%}），保留本地分类")
    return ""


def menxia_arbitrate(proposal: dict) -> dict:
    """门下省：全自动安全裁决；失败时保留本地规则，不创建人工队列。"""
    message = proposal["message"]
    baseline = proposal["baseline_category"]
    evidence = proposal["evidence"]
    result = proposal.get("ai_result")

    category = baseline
    source = "rules"
    fallback = False
    retry = False
    confidence = _baseline_confidence(message)
    importance = int(message.get("importance") or _default_importance(baseline))
    summary = str(message.get("summary") or "")[:120]
    reason = f"本地规则确定为「{baseline}」"

    # 刑部/兵部安全证据拥有最高优先级。中风险也保守标为可疑，但仅打标签。
    if evidence["prompt_injection"]:
        category, source, fallback, confidence = "可疑", "security", True, 1.0
        importance = max(importance, 5)
        reason = "刑部检出疑似提示注入，自动标记可疑"
    elif _risk_at_least(evidence["risk_level"], "medium"):
        category, source, confidence = "可疑", "security", 1.0
        importance = max(importance, 5 if evidence["risk_level"] == "high" else 4)
        reason = f"刑部依据静态扫描 {evidence['risk_level']} 风险自动标记可疑"
    elif result is None:
        if not proposal["ai_enabled"]:
            if baseline == "其他":
                fallback = True
                source = "ai_disabled"
                reason = "AI 未启用，门下省采用本地保守分类「其他」"
        else:
            reason = f"本地规则确定为「{baseline}」（不在 AI 判定范围内）"
    elif result.get("needs_review"):
        fallback = True
        source = "fallback"
        # 网络失败、超时、漏答属于临时问题，稍后自动重试；注入、越界等不重试
        retry = bool(result.get("transient"))
        detail = str(result.get("reason") or "AI 未返回有效判定")[:120]
        reason = f"门下省自动驳回不可信 AI 结果：{detail}"
        if retry:
            reason += "（稍后自动重试）"
    else:
        try:
            ai_confidence = max(0.0, min(1.0, float(result.get("confidence", 0))))
        except (TypeError, ValueError):
            ai_confidence = 0.0
        ai_category = _safe_category(result.get("category"))
        rejected = _ai_guardrail(baseline, ai_category, ai_confidence, message)
        if rejected:
            fallback = True
            source = "fallback"
            if baseline == "其他":
                # 规则本来就没有把握，记录 AI 的真实置信度
                confidence = ai_confidence
            reason = rejected
        else:
            category = ai_category
            source = "ai_risk" if ai_category == "可疑" else "ai"
            confidence = ai_confidence
            try:
                importance = max(1, min(5, int(result.get("importance", importance))))
            except (TypeError, ValueError):
                pass
            if ai_category == "可疑":
                importance = max(importance, 4)
            summary = str(result.get("summary") or summary)[:120]
            why = str(result.get("risk_reason") or result.get("reason") or "语义判定")[:100]
            reason = f"门下省核准 AI 分类「{category}」：{why}"

    ministry = _primary_ministry(evidence, category)
    return {
        "category": category,
        "ministry": ministry,
        "action": "label_only",
        "source": source,
        "fallback": fallback,
        "retry": retry,
        "confidence": max(0.0, min(1.0, confidence)),
        "importance": max(1, min(5, importance)),
        "summary": summary,
        "reason": reason[:500],
        "evidence": dict(evidence, ai_consulted=result is not None),
    }


def _next_retry(message: dict, verdict: dict, ts: float) -> tuple[int, float]:
    """返回 (ai_retry_count, ai_retry_ts)。ai_retry_ts=0 表示不再重试。"""
    if not verdict.get("retry"):
        return 0, 0.0
    count = int(message.get("ai_retry_count") or 0) + 1
    if count > MAX_AI_RETRIES:
        return count, 0.0
    delay = min(AI_RETRY_MAX_SECONDS, AI_RETRY_BASE_SECONDS * 2 ** (count - 1))
    return count, ts + delay


def shangshu_apply(conn, run_id: int, message: dict, verdict: dict, *, ts: float) -> bool:
    """尚书省：仅写入分类元数据和审计轨迹，绝不调用邮箱或外部动作。"""
    mid = int(message["id"])
    retry_count, retry_ts = _next_retry(message, verdict, ts)
    cur = conn.execute(
        """UPDATE messages
           SET category=?, importance=?, summary=?, confidence=?, ai_reason=?,
               needs_review=0, ai_done=1, governance_version=?, governance_ministry=?,
               governance_action=?, governance_source=?, governance_reason=?, governance_ts=?,
               ai_retry_count=?, ai_retry_ts=?
           WHERE id=? AND deleted_ts=0""",
        (
            verdict["category"], verdict["importance"], verdict["summary"],
            verdict["confidence"], verdict["reason"][:200], VERSION,
            verdict["ministry"], verdict["action"], verdict["source"],
            verdict["reason"], ts, retry_count, retry_ts, mid,
        ),
    )
    # 邮件可能在中书省选取后被并发移入回收站；此时不伪造“已治理”决策。
    if cur.rowcount != 1:
        return False
    conn.execute(
        """INSERT INTO governance_decisions
           (run_id, message_id, ts, version, ministry, category_before, category_after,
            confidence, source, fallback, reason, evidence)
           VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
        (
            run_id, mid, ts, VERSION, verdict["ministry"],
            _safe_category(message.get("category")), verdict["category"],
            verdict["confidence"], verdict["source"], int(verdict["fallback"]),
            verdict["reason"], json.dumps(verdict["evidence"], ensure_ascii=False, sort_keys=True),
        ),
    )
    return True


def _wants_alert(message: dict, verdict: dict, ts: float) -> bool:
    """重要/安全（或重要度 ≥4）的新邮件在裁决后推送一次；可疑邮件不推送。"""
    if verdict["category"] == "可疑":
        return False
    if verdict["category"] not in ("重要", "安全") and verdict["importance"] < 4:
        return False
    if float(message.get("alert_ts") or 0) > 0:
        return False
    return float(message.get("created_ts") or 0) >= ts - ALERT_FRESH_SECONDS


def _queue_alert(conn, message: dict, verdict: dict, ts: float) -> dict | None:
    """把推送标记写进库里（只标记一次），返回交给调用方推送的条目。"""
    if not _wants_alert(message, verdict, ts):
        return None
    cur = conn.execute("UPDATE messages SET alert_ts=? WHERE id=? AND alert_ts=0",
                       (ts, int(message["id"])))
    if cur.rowcount != 1:
        return None
    return {
        "account_name": message.get("account_name") or "",
        "subject": message.get("subject") or "",
        "sender_addr": message.get("sender_addr") or "",
        "category": verdict["category"],
        "otp_code": "",
        "importance": verdict["importance"],
    }


# 待治理：未治理或旧版本、旧的人工复核标记、AI 临时失败且已到重试时间
_PENDING_SQL = ("(COALESCE(m.governance_version,'') != ? OR m.needs_review=1"
                " OR (m.ai_retry_ts > 0 AND m.ai_retry_ts <= ?))")


def _select_ids(conn, *, limit: int, force: bool, now: float) -> list[int]:
    where = "m.deleted_ts=0"
    args: list = []
    if not force:
        where += " AND " + _PENDING_SQL
        args += [VERSION, now]
    sql = f"SELECT m.id FROM messages m WHERE {where} ORDER BY m.date_ts DESC, m.id DESC"
    if limit > 0:
        sql += " LIMIT ?"
        args.append(min(10000, max(1, int(limit))))
    return [int(row["id"]) for row in conn.execute(sql, args).fetchall()]


def _load_messages(conn, ids: list[int]) -> list[dict]:
    """按 id 读入一批邮件；正文截断后用于本机注入扫描和（遮蔽后的）AI 正文摘录。"""
    if not ids:
        return []
    ph = ",".join("?" * len(ids))
    rows = conn.execute(
        f"""SELECT m.id, m.category, m.subject, m.sender_addr, m.snippet,
                   substr(m.body_text, 1, {BODY_SCAN_CHARS}) AS body_text,
                   m.otp_code, m.importance, m.summary, m.confidence,
                   m.has_attach, m.unsubscribe, m.risk_level, m.needs_review, m.ai_retry_count,
                   m.date_ts, m.created_ts, m.header_signals, m.governance_version, m.alert_ts,
                   a.name AS account_name
            FROM messages m LEFT JOIN accounts a ON a.id=m.account_id
            WHERE m.id IN ({ph}) AND m.deleted_ts=0""",
        ids,
    ).fetchall()
    order = {mid: i for i, mid in enumerate(ids)}
    return sorted((dict(r) for r in rows), key=lambda r: order[int(r["id"])])


def _select_messages(conn, *, limit: int, force: bool) -> list[dict]:
    """兼容旧调用：一次性读入全部待治理邮件。run_governance 改为分批读取。"""
    return _load_messages(conn, _select_ids(conn, limit=limit, force=force, now=time.time()))


def _wants_ai(row: dict, evidence: dict, cfg: dict, now: float) -> bool:
    """这封邮件是否交给 AI 判定。注入、中高风险和已判可疑的邮件永不外送。"""
    if evidence["prompt_injection"] or _risk_at_least(evidence["risk_level"], "medium"):
        return False
    category = _safe_category(row.get("category"))
    if category == "可疑":
        return False
    scope = cfg.get("scope", "all")
    if scope == "other":
        if category != "其他" or not rules.allows_cloud_ai(category):
            return False
    elif scope == "uncertain":
        if category != "其他" and _baseline_confidence(row) >= UNCERTAIN_BELOW:
            return False
    # 回溯窗口只限制「上一版治理过的历史邮件」：新邮件和本版的重试不受影响
    try:
        backfill_days = int(cfg.get("backfill_days", ai.DEFAULT_BACKFILL_DAYS) or 0)
    except (TypeError, ValueError):
        backfill_days = ai.DEFAULT_BACKFILL_DAYS
    old_version = str(row.get("governance_version") or "")
    if backfill_days > 0 and old_version and old_version != VERSION \
            and float(row.get("date_ts") or 0) < now - backfill_days * 86400:
        return False
    return True


def _signal_tags(evidence: dict) -> list[str]:
    tags = []
    if evidence["bulk"]:
        tags.append("群发")
    if evidence["attachment"]:
        tags.append("附件")
    if evidence["sender_kind"] == "automated":
        tags.append("自动发件人")
    return tags


def _ai_results(rows: list[dict], *, enabled: bool, cfg: dict | None = None,
                now: float | None = None) -> tuple[dict[int, dict], int]:
    if not enabled:
        return {}, 0
    cfg = cfg or {}
    now = time.time() if now is None else now
    candidates = []
    for row in rows:
        evidence = build_evidence(row)
        if not _wants_ai(row, evidence, cfg, now):
            continue
        subject = str(row.get("subject") or "")
        body = str(row.get("body_text") or "") or str(row.get("snippet") or "")
        stored_otp = str(row.get("otp_code") or "")
        candidates.append({
            "id": row["id"],
            "subject": subject,
            "sender": row.get("sender_addr", ""),
            "snippet": row.get("snippet", ""),
            "body": body,
            "rule_category": _safe_category(row.get("category")),
            "signals": _signal_tags(evidence),
            # 发送前遮蔽：已存的验证码，或规则漏判时在正文里找到的码
            "otp_code": stored_otp or rules.extract_otp(subject, body),
            "has_otp": bool(stored_otp),
        })

    size = AI_BATCH_SIZE_WITH_BODY if cfg.get("send_body", True) else AI_BATCH_SIZE
    results: dict[int, dict] = {}
    for start in range(0, len(candidates), size):
        batch = candidates[start:start + size]
        try:
            returned = ai.classify_batch(batch)
        except Exception as exc:  # 自定义适配器异常也必须自动回退
            returned = [
                {"id": item["id"], "needs_review": True, "transient": True,
                 "reason": f"AI 分类异常：{str(exc)[:80]}"}
                for item in batch
            ]
        batch_ids = {int(item["id"]) for item in batch}
        for item in returned or []:
            try:
                mid = int(item.get("id"))
            except (AttributeError, TypeError, ValueError):
                continue
            if mid in batch_ids:
                results[mid] = item
        for item in batch:
            results.setdefault(
                int(item["id"]),
                {"id": int(item["id"]), "needs_review": True, "transient": True,
                 "reason": "AI 漏答"},
            )
    return results, len(candidates)


def _claim_run(conn, *, trigger: str, started: float) -> int:
    """用 SQLite 写锁领取运行权，避免多 worker 同时治理同一批邮件。"""
    conn.execute("BEGIN IMMEDIATE")
    conn.execute(
        """UPDATE governance_runs
           SET status='failed', finished_ts=?, error_count=1,
               error='运行进程中断，锁已自动回收'
           WHERE status='running' AND started_ts < ?""",
        (started, started - RUN_STALE_SECONDS),
    )
    active = conn.execute(
        "SELECT id FROM governance_runs WHERE status='running' ORDER BY id DESC LIMIT 1"
    ).fetchone()
    if active:
        conn.commit()
        return 0
    cur = conn.execute(
        """INSERT INTO governance_runs(version, trigger, status, started_ts)
           VALUES(?,?, 'running', ?)""",
        (VERSION, str(trigger or "system")[:40], started),
    )
    conn.commit()
    return int(cur.lastrowid)


def run_governance(*, limit: int = 0, force: bool = False, trigger: str = "system") -> dict:
    """一键运行完整自动治理；重复运行只处理未治理/旧版本邮件。

    返回值里的 alerts 是裁决后需要推送的新邮件，由调用方（sync.push_alerts）发送；
    除 AI 判定请求外，本模块不发起任何对外请求。
    """
    if not _RUN_LOCK.acquire(blocking=False):
        status = governance_status()
        status.update({"ok": False, "busy": True})
        return status

    conn = get_conn()
    run_id = 0
    started = time.time()
    try:
        run_id = _claim_run(conn, trigger=trigger, started=started)
        if not run_id:
            status = governance_status()
            status.update({"ok": False, "busy": True})
            return status

        ids = _select_ids(conn, limit=limit, force=force, now=started)
        try:
            cfg = dict(ai.ai_config() or {})
        except Exception:
            cfg = {}
        enabled = bool(cfg.get("enabled"))

        ministry_counts: Counter = Counter()
        category_counts: Counter = Counter()
        fallback_count = 0
        suspicious_count = 0
        ai_count = 0
        processed_ids = []
        alerts: list[dict] = []
        decision_ts = time.time()

        # 分批读取和处理：首次治理大邮箱时不会把所有正文一次性读进内存
        for start in range(0, len(ids), CHUNK_SIZE):
            rows = _load_messages(conn, ids[start:start + CHUNK_SIZE])
            ai_by_id, chunk_ai = _ai_results(rows, enabled=enabled, cfg=cfg, now=started)
            ai_count += chunk_ai
            for row in rows:
                proposal = zhongshu_propose(
                    row, ai_by_id.get(int(row["id"])), ai_enabled=enabled
                )
                verdict = menxia_arbitrate(proposal)
                if not shangshu_apply(conn, run_id, row, verdict, ts=decision_ts):
                    continue
                alert = _queue_alert(conn, row, verdict, decision_ts)
                if alert:
                    alerts.append(alert)
                processed_ids.append(int(row["id"]))
                ministry_counts[verdict["ministry"]] += 1
                category_counts[verdict["category"]] += 1
                fallback_count += int(verdict["fallback"])
                suspicious_count += int(verdict["category"] == "可疑")
            conn.commit()

        finished = time.time()
        conn.execute(
            """UPDATE governance_runs
               SET status='completed', finished_ts=?, selected_count=?, processed_count=?,
                   ai_count=?, fallback_count=?, suspicious_count=?, error_count=0,
                   ministry_counts=?, category_counts=?
               WHERE id=?""",
            (
                finished, len(ids), len(processed_ids), ai_count, fallback_count,
                suspicious_count,
                json.dumps({m: ministry_counts.get(m, 0) for m in MINISTRIES}, ensure_ascii=False),
                json.dumps(dict(category_counts), ensure_ascii=False), run_id,
            ),
        )
        conn.commit()
        audit(
            "governance_run", actor="system", tier="A", target_ids=processed_ids,
            reason=(f"三省六部自动分拣完成：{len(processed_ids)} 封，"
                    f"自动回退 {fallback_count} 封，可疑 {suspicious_count} 封"),
        )
        result = governance_status()
        # 本函数返回后才释放进程内锁；对调用者而言本次运行已经完成。
        result.update({"ok": True, "busy": False, "running": False, "alerts": alerts})
        return result
    except Exception as exc:
        conn.rollback()
        if run_id:
            conn.execute(
                """UPDATE governance_runs
                   SET status='failed', finished_ts=?, error_count=1, error=? WHERE id=?""",
                (time.time(), str(exc)[:500], run_id),
            )
            conn.commit()
        audit(
            "governance_run", actor="system", tier="A", target_ids=[], allowed=False,
            reason=f"三省六部自动分拣失败：{str(exc)[:300]}",
        )
        raise
    finally:
        _RUN_LOCK.release()


def governance_status() -> dict:
    """返回最近运行和待治理数量，供一键入口展示。"""
    conn = get_conn()
    pending = conn.execute(
        f"SELECT COUNT(*) AS c FROM messages m WHERE m.deleted_ts=0 AND {_PENDING_SQL}",
        (VERSION, time.time()),
    ).fetchone()["c"]
    row = conn.execute(
        "SELECT * FROM governance_runs ORDER BY id DESC LIMIT 1"
    ).fetchone()
    last = dict(row) if row else None
    if last:
        for key in ("ministry_counts", "category_counts"):
            try:
                last[key] = json.loads(last.get(key) or "{}")
            except (TypeError, json.JSONDecodeError):
                last[key] = {}
        last["ministry_counts"] = {
            ministry: int(last["ministry_counts"].get(ministry, 0))
            for ministry in MINISTRIES
        }
    db_running = bool(
        last and last.get("status") == "running"
        and float(last.get("started_ts") or 0) >= time.time() - RUN_STALE_SECONDS
    )
    return {
        "version": VERSION,
        "running": _RUN_LOCK.locked() or db_running,
        "pending": int(pending),
        "ministries": list(MINISTRIES),
        "last_run": last,
    }
