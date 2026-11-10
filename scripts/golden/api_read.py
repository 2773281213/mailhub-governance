"""导出 `LocalApi` 读接口的端到端金标语料：用 FastAPI 的 TestClient 跑真服务器。

流程与 Kotlin 的 `ApiReadGoldenTest` 完全一致：

1. 从 `android/core/src/test/resources/api/read_seed.json` 读合成种子数据
   （账户、邮件、设置、审计、晨报、同步状态，以及请求序列里的固定时间与时区）；
2. 建一个临时库，`db.init_db()` + `oauth_auth.init_oauth_db()`，按种子插入数据；
3. 把 `time.time` 打桩成种子里的固定时间，时区固定 `Asia/Shanghai`
   （与 Kotlin 侧注入的 `Clock` / `ZoneId` 同值）；
4. 用网页方式登录（`https://testserver` + `/api/login`，写法同 `tests/test_mobile_api.py`）；
5. 按 [REQUESTS] 的顺序逐个发请求，记录方法、路径、查询参数、状态码、要比较的响应头
   和响应 JSON；需要把上一条响应的值（例如翻页游标）传给下一条时用 `capture` /
   `${name}`（两侧都按同一规则取值与替换）；
6. 序列跑完后导出 `messages` 的 `id / unread / flag_push`，比较写的副作用。

    python scripts/golden/api_read.py           # 重新生成 JSON
    python scripts/golden/api_read.py --check   # 检查是否与当前 Python 实现一致

本机模式独有的行为（`/api/changes` 的两处差异、`/api/sessions`、`/api/oauth/providers`、
不校验令牌）不在这里：它们与服务器不同，由 `LocalApiTest` 做普通单元测试。
"""
from __future__ import annotations

import base64
import json
import os
import sys
import tempfile
from datetime import datetime
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _common  # noqa: E402

# 时区、公网地址与 OAuth 环境变量都要固定：`sync.TZ` 在导入时读取 MAILHUB_TZ，
# `oauth_auth` 的回调地址由 MAILHUB_PUBLIC_URL 拼出来，两边（Python / Kotlin）必须同值。
os.environ["MAILHUB_TZ"] = "Asia/Shanghai"
os.environ["MAILHUB_PUBLIC_URL"] = "https://local.mailhub.invalid"
for _name in ("GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET", "MICROSOFT_CLIENT_ID",
              "MICROSOFT_CLIENT_SECRET", "GMAIL_REDIRECT_URI", "OUTLOOK_REDIRECT_URI",
              "NETEASE_CLIENT_ID", "NETEASE_CLIENT_SECRET"):
    os.environ.pop(_name, None)

_common.prepare_imports()

import db  # noqa: E402
import oauth_auth  # noqa: E402
import sessions  # noqa: E402
import sync  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from security import hash_password  # noqa: E402

import app as mailhub_app  # noqa: E402

SEED_PATH = _common.REPO_ROOT / "android" / "core" / "src" / "test" / "resources" / "api" / "read_seed.json"
PASSWORD = "correct horse battery"


def _b64(text: str) -> str:
    """与 `_decode_cursor` 配套：把任意文本编成不带填充的 urlsafe base64 游标。"""
    return base64.urlsafe_b64encode(text.encode()).decode().rstrip("=")


# 请求序列。元组：(用例 id, 方法, 路径, 查询参数, 需要记录的响应值)。
# 查询参数的值里可以写 `${名字}`，用同一用例里 capture 记下的值替换。
REQUESTS = [
    ("meta", "GET", "/api/meta", {}, {}),
    ("status", "GET", "/api/status", {}, {}),
    ("accounts", "GET", "/api/accounts", {}, {}),
    ("governance_status", "GET", "/api/governance/status", {}, {}),
    ("overview", "GET", "/api/overview", {}, {}),
    ("settings", "GET", "/api/settings", {}, {}),

    ("messages_default", "GET", "/api/messages", {}, {}),
    ("messages_page1", "GET", "/api/messages", {"page_size": "10"},
     {"cursor1_token": "next_cursor.token", "cursor1_ts": "next_cursor.before_ts",
      "cursor1_id": "next_cursor.before_id"}),
    ("messages_page2_cursor", "GET", "/api/messages",
     {"page_size": "10", "cursor": "${cursor1_token}"}, {}),
    ("messages_legacy_before", "GET", "/api/messages",
     {"page_size": "10", "before_ts": "${cursor1_ts}", "before_id": "${cursor1_id}"}, {}),
    ("messages_page3", "GET", "/api/messages", {"page": "3", "page_size": "10"}, {}),
    ("messages_page_size_low", "GET", "/api/messages", {"page_size": "1"}, {}),
    ("messages_page_size_high", "GET", "/api/messages", {"page_size": "500"}, {}),
    ("messages_category_otp", "GET", "/api/messages", {"category": "验证码"}, {}),
    ("messages_account2", "GET", "/api/messages", {"account": "2"}, {}),
    ("messages_unread1", "GET", "/api/messages", {"unread": "1"}, {}),
    ("messages_unread0", "GET", "/api/messages", {"unread": "0"}, {}),
    ("messages_bill_important", "GET", "/api/messages", {"bill_tier": "重要"}, {}),
    ("messages_bill_minor", "GET", "/api/messages", {"bill_tier": "一般"}, {}),
    ("messages_bill_unknown", "GET", "/api/messages", {"bill_tier": "紧急"}, {}),
    ("messages_search_fts", "GET", "/api/messages", {"q": "限时折扣"}, {}),
    ("messages_search_like_short", "GET", "/api/messages", {"q": "折扣"}, {}),
    ("messages_search_percent_fts", "GET", "/api/messages", {"q": "50%"}, {}),
    ("messages_search_percent_like", "GET", "/api/messages", {"q": "%"}, {}),
    ("messages_search_underscore_like", "GET", "/api/messages", {"q": "_b"}, {}),
    ("messages_search_underscore_fts", "GET", "/api/messages", {"q": "a_b"}, {}),
    ("messages_search_quote_fts", "GET", "/api/messages", {"q": "5\" 屏"}, {}),
    ("messages_search_sender_like", "GET", "/api/messages", {"q": "财务"}, {}),
    ("messages_search_none", "GET", "/api/messages", {"q": "zzz无匹配"}, {}),
    ("messages_cursor_not_base64", "GET", "/api/messages", {"cursor": "abc"}, {}),
    ("messages_cursor_only_padding", "GET", "/api/messages", {"cursor": "===="}, {}),
    ("messages_cursor_zero_id", "GET", "/api/messages",
     {"cursor": _b64("1792951496.5|0")}, {}),
    ("messages_cursor_no_fields", "GET", "/api/messages", {"cursor": _b64("|")}, {}),
    ("unknown_route", "GET", "/api/nope", {}, {}),
    ("method_not_allowed", "POST", "/api/messages", {}, {}),

    ("message_preview", "GET", "/api/messages/1", {"mark_read": "false"}, {}),
    ("message_mark_read", "GET", "/api/messages/1", {}, {}),
    ("message_already_read", "GET", "/api/messages/1", {}, {}),
    ("message_missing", "GET", "/api/messages/9999", {}, {}),
    ("message_deleted", "GET", "/api/messages/16", {}, {}),

    ("changes_after10", "GET", "/api/changes", {"after_id": "10"}, {}),
    ("changes_barrier", "GET", "/api/changes", {"after_id": "3"}, {}),
    ("changes_barrier_empty", "GET", "/api/changes", {"after_id": "5"}, {}),
    ("changes_reset", "GET", "/api/changes", {"after_id": "99"}, {}),
    ("changes_limit_zero", "GET", "/api/changes", {"after_id": "10", "limit": "0"}, {}),
    ("changes_limit_high", "GET", "/api/changes",
     {"after_id": "10", "limit": "500"}, {}),
    ("changes_at_max", "GET", "/api/changes", {"after_id": "27"}, {}),

    ("otp_default", "GET", "/api/otp", {}, {}),
    ("otp_limit", "GET", "/api/otp", {"limit": "2"}, {}),
    ("otp_limit_zero", "GET", "/api/otp", {"limit": "0"}, {}),
    ("review_default", "GET", "/api/review", {}, {}),
    ("review_limit", "GET", "/api/review", {"limit": "2"}, {}),
    ("trash_default", "GET", "/api/trash", {}, {}),
    ("trash_limit", "GET", "/api/trash", {"limit": "1"}, {}),
    ("audit_default", "GET", "/api/audit", {}, {}),
    ("audit_page", "GET", "/api/audit", {"limit": "2", "offset": "1"}, {}),
    ("audit_clamped", "GET", "/api/audit", {"limit": "0", "offset": "-5"}, {}),
]

# 中途改设置的用例：与 Kotlin 侧一样直接写 settings 表，用来覆盖「已读状态同步」的两个分支。
ACTIONS = [
    ("enable_read_state_sync", "settings", {"sync_read_state": "1"}),
]

# 改完设置后的请求（此时 `sync.read_state_sync_enabled()` 为真，详情会登记 flag_push）。
REQUESTS_AFTER = [
    ("message_push_register", "GET", "/api/messages/2", {}, {}),
    ("message_push_preview", "GET", "/api/messages/3", {"mark_read": "false"}, {}),
    ("message_push_again", "GET", "/api/messages/2", {}, {}),
    ("message_push_second", "GET", "/api/messages/7", {}, {}),
    ("overview_after_writes", "GET", "/api/overview", {}, {}),
    ("messages_unread_after_writes", "GET", "/api/messages", {"unread": "1"}, {}),
]


def _load_seed() -> dict:
    return json.loads(SEED_PATH.read_text(encoding="utf-8"))


def _day_start(seed: dict) -> float:
    tz = sync.TZ
    now = datetime.fromtimestamp(seed["now"], tz)
    return now.replace(hour=0, minute=0, second=0, microsecond=0).timestamp()


def _prepare_database(seed: dict) -> None:
    """建库并按种子插数据；显式写 id，保证两边关联一致。"""
    db.DB_PATH = str(Path(tempfile.mkdtemp(prefix="mailhub-golden-api-")) / "mailhub.db")
    db.init_db()
    oauth_auth.init_oauth_db()
    conn = db.get_conn()
    day_start = _day_start(seed)
    now = seed["now"]
    for account in seed["accounts"]:
        _insert(conn, "accounts", account, seed["account_columns"], seed["account_defaults"])
    for message in seed["messages"]:
        row = dict(message)
        hours = row.pop("hours")
        created_hours = row.pop("created_hours", -48.0)
        deleted_hours = row.pop("deleted_hours", None)
        row["date_ts"] = day_start + hours * 3600.0
        row["created_ts"] = now + created_hours * 3600.0
        row["deleted_ts"] = 0.0 if deleted_hours is None else now + deleted_hours * 3600.0
        _insert(conn, "messages", row, seed["message_columns"], seed["message_defaults"])
    for key, value in seed["settings"].items():
        conn.execute("INSERT INTO settings(k,v) VALUES(?,?)", (key, value))
    for entry in seed["audit"]:
        row = dict(entry)
        ts = now + row.pop("ts_offset_hours") * 3600.0
        conn.execute(
            """INSERT INTO audit_log(ts, actor, action, tier, target_count, target_ids,
                                     reason, allowed, reversible, undone, undo_payload)
               VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
            (ts, row["actor"], row["action"], row["tier"], row["target_count"],
             row["target_ids"], row["reason"], row["allowed"], row["reversible"],
             row["undone"], row["undo_payload"]),
        )
    for entry in seed["digests"]:
        conn.execute("INSERT INTO digests(day, content, created_ts) VALUES(?,?,?)",
                     (entry["day"], entry["content"], day_start + entry["hours"] * 3600.0))
    conn.commit()


def _insert(conn, table: str, row: dict, columns: list[str], defaults: dict) -> None:
    values = [row.get(column, defaults.get(column)) for column in columns]
    placeholders = ",".join("?" * len(columns))
    conn.execute(f"INSERT INTO {table}({','.join(columns)}) VALUES({placeholders})", values)


def _seed_sync_status(seed: dict) -> None:
    """把种子里的同步状态放进 `sync.STATUS`（对应 Kotlin 侧注入的假 `SyncStatusSource`）。"""
    sync.STATUS.clear()
    for key, entry in seed["sync_status"].items():
        sync.STATUS[int(key)] = {
            "state": entry["state"],
            "msg": entry["msg"],
            "ts": seed["now"] + entry["ts_offset_seconds"],
            "new": entry["new"],
        }


def _login(client: TestClient) -> None:
    db.set_setting("admin_hash", hash_password(PASSWORD))
    sessions.revoke_all_sessions()
    mailhub_app.login_throttle = sessions.LoginThrottle()
    response = client.post("/api/login", json={"password": PASSWORD})
    if response.status_code != 200:
        raise SystemExit(f"登录失败：{response.status_code} {response.text}")


def _lookup(payload, path: str):
    """按 `a.b.c` 从响应 JSON 里取值。"""
    current = payload
    for part in path.split("."):
        current = current[part]
    return current


def _substitute(query: dict, captured: dict) -> dict:
    resolved = {}
    for key, value in query.items():
        if isinstance(value, str) and value.startswith("${") and value.endswith("}"):
            name = value[2:-1]
            resolved[key] = str(captured[name])
        else:
            resolved[key] = value
    return resolved


def _record(client: TestClient, case_id: str, method: str, path: str, query: dict,
            capture: dict, captured: dict) -> dict:
    resolved = _substitute(query, captured)
    response = client.request(method, path, params=resolved or None)
    try:
        payload = response.json()
    except ValueError:
        payload = response.text
    for name, dotted in capture.items():
        captured[name] = _lookup(payload, dotted)
    args = {"method": method, "path": path, "query": resolved}
    if capture:
        args["capture"] = capture
    return {
        "id": case_id,
        "fn": "request",
        "args": args,
        "expected": {
            "status": response.status_code,
            "headers": _pick_headers(response),
            "json": payload,
        },
    }


def _pick_headers(response) -> dict:
    """只比较 App 真正依赖的响应头；其余（date、server 等）每次都不同。"""
    headers = {}
    for name in ("content-type", "x-mailhub-protected"):
        value = response.headers.get(name)
        if value is not None:
            headers[name] = value
    return headers


def _export_db(db_conn) -> dict:
    rows = []
    for row in db_conn.execute(
            "SELECT id, unread, flag_push FROM messages ORDER BY id").fetchall():
        rows.append({"id": row["id"], "unread": row["unread"], "flag_push": row["flag_push"]})
    return {"messages": rows}


def _fixed_datetime(now: float):
    """把 `app.datetime.now(tz)` 钉在种子里的固定时间上。

    `/api/overview` 走的是 `_overview_sync(now=None)`，它用 `datetime.now(sync.TZ)` 取当天零点，
    打桩 `time.time` 影响不到它；不固定的话「今日统计」和近 14 天会随真实日期漂移。
    """

    class _FixedDatetime(datetime):
        @classmethod
        def now(cls, tz=None):
            return datetime.fromtimestamp(now, tz)

    return _FixedDatetime


def build_cases() -> list[dict]:
    seed = _load_seed()
    _prepare_database(seed)
    captured: dict = {}
    cases: list[dict] = []
    with mock.patch("time.time", lambda: seed["now"]), \
            mock.patch("app.datetime", _fixed_datetime(seed["now"])):
        _seed_sync_status(seed)
        client = TestClient(mailhub_app.app, base_url="https://testserver")
        _login(client)
        for case_id, method, path, query, capture in REQUESTS:
            cases.append(_record(client, case_id, method, path, query, capture, captured))
        for case_id, action, values in ACTIONS:
            if action != "settings":
                raise ValueError(f"未知的动作：{action}")
            for key, value in values.items():
                db.set_setting(key, value)
            cases.append({"id": case_id, "fn": "settings",
                          "args": {"settings": values}, "expected": {"ok": True}})
        for case_id, method, path, query, capture in REQUESTS_AFTER:
            cases.append(_record(client, case_id, method, path, query, capture, captured))
        cases.append({"id": "db_state", "fn": "db_state", "args": {},
                      "expected": _export_db(db.get_conn())})
    return cases


if __name__ == "__main__":
    _common.run("api_read", build_cases)
