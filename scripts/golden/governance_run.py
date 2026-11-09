"""导出 governance.py 端到端场景（AI 关闭）的金标语料。

流程与 Kotlin 的 `GovernanceRunGoldenTest` 完全一致：

1. 从 `android/core/src/test/resources/governance/*.json` 读合成场景
   （账户、邮件行、设置、自定义规则、账单偏好）；
2. 建一个临时库并 `db.init_db()`，按场景插入数据；
3. 把 `time.time` 打桩成场景里的固定时间（`governance.py` 与 `db.py` 都用它）；
4. 关闭 AI（`ai_enabled=0`），按场景里的 `runs` 依次调用 `run_governance()`
   （可带 `force` / `since_days` / `limit`）；
5. 按场景里的 `post` 依次执行运行后的维护动作（改设置、`backfill_template_keys`、
   `retier_bills`），覆盖账单例行次数 / 偏好查询和模板键回填；
6. 导出治理后的邮件列、`governance_decisions`、`governance_runs`、`audit_log` 与
   `governance_status()` 的关键字段。

自增 id 只出现在 `governance_runs` 与 `governance_decisions` 里：运行用 `run_index`
（第几次运行）表示，邮件用显式写入的 `id` 关联，所以不依赖自增 id。

    python scripts/golden/governance_run.py           # 重新生成 JSON
    python scripts/golden/governance_run.py --check   # 检查是否与当前 Python 实现一致

场景里的 `preclassify=true` 会先跑一遍 `rules.classify_detailed`（自定义规则的命中路径），
模拟同步入库后的分类结果，再交给治理；`load_custom_rules` 因此也在本脚本的覆盖范围内。
"""
from __future__ import annotations

import copy
import json
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _common  # noqa: E402

_common.prepare_imports()

import db  # noqa: E402
import governance  # noqa: E402
import rules  # noqa: E402

SCENARIO_DIR = _common.REPO_ROOT / "android" / "core" / "src" / "test" / "resources" / "governance"

# 插入时显式给出的列；两边（Python 脚本与 Kotlin 测试）必须完全一致。
ACCOUNT_COLUMNS = [
    "id", "name", "provider", "email", "imap_host", "imap_port", "auth_type",
    "enabled", "created_ts",
]
ACCOUNT_DEFAULTS = {
    "name": "",
    "provider": "custom",
    "email": "",
    "imap_host": "",
    "imap_port": 993,
    "auth_type": "password",
    "enabled": 1,
    "created_ts": 0.0,
}

MESSAGE_COLUMNS = [
    "id", "account_id", "uid", "msg_id", "subject", "sender_name", "sender_addr",
    "date_ts", "snippet", "body_text", "body_html", "category", "otp_code",
    "importance", "summary", "confidence", "ai_done", "unread", "has_attach",
    "unsubscribe", "created_ts", "governance_version", "governance_ministry",
    "governance_action", "governance_source", "governance_reason", "governance_ts",
    "deleted_ts", "risk_level", "risk_reasons", "needs_review", "ai_reason",
    "ai_retry_count", "ai_retry_ts", "header_signals", "alert_ts", "template_key",
    "bill_tier", "bill_facets", "bill_tier_locked", "flag_push",
]
MESSAGE_DEFAULTS = {
    "msg_id": "",
    "subject": "",
    "sender_name": "",
    "sender_addr": "",
    "date_ts": 0.0,
    "snippet": "",
    "body_text": "",
    "body_html": "",
    "category": "未分类",
    "otp_code": "",
    "importance": 0,
    "summary": "",
    "confidence": 0.0,
    "ai_done": 0,
    "unread": 1,
    "has_attach": 0,
    "unsubscribe": 0,
    "created_ts": 0.0,
    "governance_version": "",
    "governance_ministry": "",
    "governance_action": "",
    "governance_source": "",
    "governance_reason": "",
    "governance_ts": 0.0,
    "deleted_ts": 0.0,
    "risk_level": "none",
    "risk_reasons": "",
    "needs_review": 0,
    "ai_reason": "",
    "ai_retry_count": 0,
    "ai_retry_ts": 0.0,
    "header_signals": "{}",
    "alert_ts": 0.0,
    "template_key": "",
    "bill_tier": "",
    "bill_facets": "{}",
    "bill_tier_locked": 0,
    "flag_push": 0,
}

# 导出的邮件列：治理会改写或影响到的字段。
EXPORT_MESSAGE_COLUMNS = [
    "id", "category", "importance", "summary", "confidence", "ai_reason",
    "needs_review", "ai_done", "risk_level", "governance_version",
    "governance_ministry", "governance_action", "governance_source",
    "governance_reason", "governance_ts", "ai_retry_count", "ai_retry_ts",
    "bill_tier", "bill_facets", "alert_ts", "template_key",
]


def _insert(conn, table: str, row: dict, columns: list[str], defaults: dict) -> None:
    values = [row.get(column, defaults.get(column)) for column in columns]
    placeholders = ",".join("?" * len(columns))
    conn.execute(
        f"INSERT INTO {table}({','.join(columns)}) VALUES({placeholders})", values)


def _load_scenario(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _prepare_database(scenario: dict) -> None:
    """建库并插入场景数据（显式写 id，保证两边关联一致）。"""
    db.DB_PATH = str(Path(tempfile.mkdtemp(prefix="mailhub-golden-run-")) / "mailhub.db")
    db.init_db()
    conn = db.get_conn()
    for account in scenario.get("accounts", []):
        _insert(conn, "accounts", account, ACCOUNT_COLUMNS, ACCOUNT_DEFAULTS)
    for message in scenario.get("messages", []):
        _insert(conn, "messages", message, MESSAGE_COLUMNS, MESSAGE_DEFAULTS)
    for key, value in scenario.get("settings", {}).items():
        conn.execute("INSERT INTO settings(k,v) VALUES(?,?)", (key, value))
    for rule in scenario.get("rules", []):
        conn.execute(
            """INSERT INTO rules(name, field, pattern, category, priority, enabled)
               VALUES(?,?,?,?,?,?)""",
            (rule.get("name", ""), rule["field"], rule["pattern"], rule["category"],
             rule.get("priority", 100), rule.get("enabled", 1)),
        )
    for pref in scenario.get("bill_prefs", []):
        conn.execute(
            "INSERT INTO bill_prefs(template_key, tier, created_ts) VALUES(?,?,?)",
            (pref["template_key"], pref["tier"], pref.get("created_ts", 0.0)),
        )
    conn.commit()


def _preclassify(conn) -> None:
    """模拟同步入库后的规则分类（自定义规则的命中路径）。"""
    custom = rules.load_custom_rules(conn)
    rows = conn.execute(
        """SELECT id, subject, body_text, sender_addr, unsubscribe, header_signals
           FROM messages ORDER BY id"""
    ).fetchall()
    for row in rows:
        signals = json.loads(row["header_signals"] or "{}")
        result = rules.classify_detailed(
            row["subject"] or "",
            row["body_text"] or "",
            row["sender_addr"] or "",
            bool(row["unsubscribe"]),
            custom,
            signals,
        )
        conn.execute(
            "UPDATE messages SET category=?, otp_code=?, confidence=? WHERE id=?",
            (result["category"], result["otp"], result["confidence"], row["id"]),
        )
    conn.commit()


def _apply_post_step(conn, step: dict) -> None:
    """运行后的维护动作：改设置、回填模板键、按已存画像重新定级。"""
    op = step["op"]
    if op == "set_settings":
        for key, value in step.get("values", {}).items():
            db.set_setting(key, value)
    elif op == "backfill_template_keys":
        governance.backfill_template_keys(conn, batch=step.get("batch", 500))
    elif op == "retier_bills":
        governance.retier_bills(
            conn,
            template=step.get("template", ""),
            only_missing=step.get("only_missing", False),
        )
    else:
        raise ValueError(f"未知的 post 动作：{op}")


def _export(conn, scenario: dict) -> dict:
    run_rows = [dict(r) for r in conn.execute(
        "SELECT * FROM governance_runs ORDER BY id").fetchall()]
    run_index = {row["id"]: index for index, row in enumerate(run_rows)}
    runs = []
    for row in run_rows:
        item = {key: value for key, value in row.items() if key != "id"}
        item["ministry_counts"] = json.loads(item["ministry_counts"] or "{}")
        item["category_counts"] = json.loads(item["category_counts"] or "{}")
        runs.append(item)

    messages = []
    for row in conn.execute(
        f"SELECT {','.join(EXPORT_MESSAGE_COLUMNS)} FROM messages ORDER BY id"
    ).fetchall():
        item = dict(row)
        item["bill_facets"] = json.loads(item["bill_facets"] or "{}")
        messages.append(item)

    decisions = []
    for row in conn.execute(
        """SELECT run_id, message_id, ts, version, ministry, category_before,
                  category_after, confidence, source, fallback, reason, evidence
           FROM governance_decisions ORDER BY id"""
    ).fetchall():
        item = dict(row)
        item["run_index"] = run_index[item.pop("run_id")]
        item["evidence"] = json.loads(item["evidence"] or "{}")
        decisions.append(item)

    audit = []
    for row in conn.execute(
        """SELECT ts, actor, action, tier, target_count, target_ids, reason,
                  allowed, reversible, undone
           FROM audit_log ORDER BY id"""
    ).fetchall():
        item = dict(row)
        item["target_ids"] = json.loads(item["target_ids"] or "[]")
        audit.append(item)

    status = governance.governance_status()
    return {
        "scenario": scenario["name"],
        "runs": runs,
        "messages": messages,
        "decisions": decisions,
        "audit": audit,
        "status": {
            "version": status["version"],
            "running": status["running"],
            "pending": status["pending"],
            "ministries": status["ministries"],
        },
    }


def run_scenario(path: Path) -> dict:
    scenario = _load_scenario(path)
    _prepare_database(scenario)
    conn = db.get_conn()
    if scenario.get("preclassify"):
        _preclassify(conn)

    real_time = time.time
    time.time = lambda: scenario["now"]
    try:
        for spec in scenario.get("runs", []):
            governance.run_governance(
                limit=spec.get("limit", scenario.get("limit", 0)),
                force=spec.get("force", False),
                trigger=spec.get("trigger", "system"),
                since_days=spec.get("since_days", 0),
            )
        for step in scenario.get("post", []):
            _apply_post_step(conn, step)
        return _export(conn, scenario)
    finally:
        time.time = real_time


def build_cases() -> list[dict]:
    cases = []
    for path in sorted(SCENARIO_DIR.glob("*.json")):
        scenario = _load_scenario(path)
        cases.append({
            "id": scenario["name"],
            "fn": "scenario",
            "args": {"scenario": path.name},
            "expected": copy.deepcopy(run_scenario(path)),
        })
    return cases


if __name__ == "__main__":
    _common.run("governance_run", build_cases)
