"""邮件自动「三省六部」治理。

三省是同一条自动流水线，而不是三个需要用户点击的审批页：
  - 中书省：汇总本地规则、静态安全扫描、AI 与邮件元数据形成提案；
  - 门下省：自动驳回冲突、低置信度和被污染的 AI 结果；
  - 尚书省：只落地标签/优先级/摘要并写审计，不执行删除、转发或回复。

六部是证据视角，不替代现有邮件分类。持久化证据只含枚举和布尔信号，
不保存邮件正文、主题、发件地址或凭据。
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


VERSION = "mailhub-three-six-v1"
MINISTRIES = ("吏部", "户部", "礼部", "兵部", "刑部", "工部")
AI_BATCH_SIZE = 24
RUN_STALE_SECONDS = 15 * 60

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


def build_evidence(message: dict) -> dict:
    """生成六部共享的最小证据集合；返回值不得包含邮件原文或身份信息。"""
    category = _safe_category(message.get("category"))
    subject = str(message.get("subject") or "")
    snippet = str(message.get("snippet") or "")
    # 正文只在本机做注入特征扫描：不进入 evidence，也不会发送给 AI（AI 仍只拿 snippet）。
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


def _primary_ministry(evidence: dict, category: str) -> str:
    """按安全优先级选择主责部；其他部的证据仍完整保存在 decision 中。"""
    if evidence["prompt_injection"] or _risk_at_least(evidence["risk_level"], "medium") \
            or category == "可疑":
        return "刑部"
    if category == "安全" or evidence["security"]:
        return "兵部"
    if category == "账单" or evidence["financial"]:
        return "户部"
    if category == "验证码" or evidence["otp"]:
        return "工部"
    if category == "重要" or evidence["important_relationship"]:
        return "吏部"
    if category in ("订阅", "通知") or evidence["subscription"] or evidence["unsubscribe"]:
        return "礼部"
    if evidence["attachment"] or evidence["sender_kind"] == "automated":
        return "工部"
    return "吏部"


def menxia_arbitrate(proposal: dict) -> dict:
    """门下省：全自动安全裁决；失败时保留本地规则，不创建人工队列。"""
    message = proposal["message"]
    baseline = proposal["baseline_category"]
    evidence = proposal["evidence"]
    result = proposal.get("ai_result")

    category = baseline
    source = "rules"
    fallback = False
    confidence = float(message.get("confidence") or (0.9 if baseline != "其他" else 0.55))
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
    elif baseline == "其他":
        if not proposal["ai_enabled"]:
            fallback = True
            source = "ai_disabled"
            reason = "AI 未启用，门下省采用本地保守分类「其他」"
        elif not result or result.get("needs_review"):
            fallback = True
            source = "fallback"
            detail = str((result or {}).get("reason") or "AI 未返回有效判定")[:120]
            reason = f"门下省自动驳回不可信 AI 结果：{detail}"
        else:
            try:
                ai_confidence = max(0.0, min(1.0, float(result.get("confidence", 0))))
            except (TypeError, ValueError):
                ai_confidence = 0.0
            ai_category = _safe_category(result.get("category"))
            if ai_confidence < policy.MIN_CONFIDENCE_GUARDED:
                fallback = True
                source = "fallback"
                confidence = ai_confidence
                reason = (
                    f"门下省自动驳回低置信度 AI（{ai_confidence:.0%}），"
                    "保留本地分类「其他」"
                )
            else:
                category = ai_category
                source = "ai"
                confidence = ai_confidence
                try:
                    importance = max(1, min(5, int(result.get("importance", importance))))
                except (TypeError, ValueError):
                    pass
                summary = str(result.get("summary") or summary)[:120]
                reason = f"门下省核准 AI 分类「{category}」：{str(result.get('reason') or '语义判定')[:100]}"

    ministry = _primary_ministry(evidence, category)
    return {
        "category": category,
        "ministry": ministry,
        "action": "label_only",
        "source": source,
        "fallback": fallback,
        "confidence": max(0.0, min(1.0, confidence)),
        "importance": max(1, min(5, importance)),
        "summary": summary,
        "reason": reason[:500],
        "evidence": evidence,
    }


def shangshu_apply(conn, run_id: int, message: dict, verdict: dict, *, ts: float) -> bool:
    """尚书省：仅写入分类元数据和审计轨迹，绝不调用邮箱或外部动作。"""
    mid = int(message["id"])
    cur = conn.execute(
        """UPDATE messages
           SET category=?, importance=?, summary=?, confidence=?, ai_reason=?,
               needs_review=0, ai_done=1, governance_version=?, governance_ministry=?,
               governance_action=?, governance_source=?, governance_reason=?, governance_ts=?
           WHERE id=? AND deleted_ts=0""",
        (
            verdict["category"], verdict["importance"], verdict["summary"],
            verdict["confidence"], verdict["reason"][:200], VERSION,
            verdict["ministry"], verdict["action"], verdict["source"],
            verdict["reason"], ts, mid,
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


def _select_messages(conn, *, limit: int, force: bool) -> list[dict]:
    where = "m.deleted_ts=0"
    args: list = []
    if not force:
        where += " AND (COALESCE(m.governance_version,'') != ? OR m.needs_review=1)"
        args.append(VERSION)
    sql = f"""SELECT m.id, m.category, m.subject, m.sender_addr, m.snippet, m.body_text,
                     m.otp_code, m.importance, m.summary, m.confidence,
                     m.has_attach, m.unsubscribe, m.risk_level, m.needs_review
              FROM messages m WHERE {where}
              ORDER BY m.date_ts DESC, m.id DESC"""
    if limit > 0:
        sql += " LIMIT ?"
        args.append(min(10000, max(1, int(limit))))
    return [dict(row) for row in conn.execute(sql, args).fetchall()]


def _ai_results(rows: list[dict], *, enabled: bool) -> tuple[dict[int, dict], int]:
    if not enabled:
        return {}, 0
    candidates = []
    for row in rows:
        evidence = build_evidence(row)
        if _safe_category(row.get("category")) != "其他":
            continue
        if evidence["prompt_injection"] or _risk_at_least(evidence["risk_level"], "medium"):
            continue
        if not rules.allows_cloud_ai("其他"):
            continue
        candidates.append({
            "id": row["id"],
            "subject": row.get("subject", ""),
            "sender": row.get("sender_addr", ""),
            "snippet": row.get("snippet", ""),
        })

    results: dict[int, dict] = {}
    for start in range(0, len(candidates), AI_BATCH_SIZE):
        batch = candidates[start:start + AI_BATCH_SIZE]
        try:
            returned = ai.classify_batch(batch)
        except Exception as exc:  # 自定义适配器异常也必须自动回退
            returned = [
                {"id": item["id"], "needs_review": True,
                 "reason": f"AI 分类异常：{str(exc)[:80]}"}
                for item in batch
            ]
        for item in returned or []:
            try:
                mid = int(item.get("id"))
            except (AttributeError, TypeError, ValueError):
                continue
            if any(int(candidate["id"]) == mid for candidate in batch):
                results[mid] = item
        for item in batch:
            results.setdefault(
                int(item["id"]),
                {"id": int(item["id"]), "needs_review": True, "reason": "AI 漏答"},
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
    """一键运行完整自动治理；重复运行只处理未治理/旧版本邮件。"""
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

        rows = _select_messages(conn, limit=limit, force=force)
        try:
            enabled = bool(ai.ai_config().get("enabled"))
        except Exception:
            enabled = False
        ai_by_id, ai_count = _ai_results(rows, enabled=enabled)

        ministry_counts: Counter = Counter()
        category_counts: Counter = Counter()
        fallback_count = 0
        suspicious_count = 0
        processed_ids = []
        decision_ts = time.time()

        for row in rows:
            proposal = zhongshu_propose(
                row, ai_by_id.get(int(row["id"])), ai_enabled=enabled
            )
            verdict = menxia_arbitrate(proposal)
            if not shangshu_apply(conn, run_id, row, verdict, ts=decision_ts):
                continue
            processed_ids.append(int(row["id"]))
            ministry_counts[verdict["ministry"]] += 1
            category_counts[verdict["category"]] += 1
            fallback_count += int(verdict["fallback"])
            suspicious_count += int(verdict["category"] == "可疑")

        finished = time.time()
        conn.execute(
            """UPDATE governance_runs
               SET status='completed', finished_ts=?, selected_count=?, processed_count=?,
                   ai_count=?, fallback_count=?, suspicious_count=?, error_count=0,
                   ministry_counts=?, category_counts=?
               WHERE id=?""",
            (
                finished, len(rows), len(processed_ids), ai_count, fallback_count,
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
        result.update({"ok": True, "busy": False, "running": False})
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
        """SELECT COUNT(*) AS c FROM messages
           WHERE deleted_ts=0 AND (COALESCE(governance_version,'') != ? OR needs_review=1)""",
        (VERSION,),
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
