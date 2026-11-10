"""导出 `LocalApi` 写接口的端到端金标语料：用 FastAPI 的 TestClient 跑真服务器。

流程与 Kotlin 的 `ApiWriteGoldenTest` 完全一致：

1. 从 `android/core/src/test/resources/api/write_seed.json` 读合成种子数据
   （账户、邮件、设置、审计，以及固定时间与时区）；
2. 建一个临时库，`db.init_db()` + `oauth_auth.init_oauth_db()`，按种子插入数据；
3. 把 `time.time` 打桩成种子里的固定时间，时区固定 `Asia/Shanghai`；
4. 用网页方式登录（`https://testserver` + `/api/login`，写法同 `tests/test_mobile_api.py`）；
5. 外部依赖全部打桩：`oauth_auth.probe_connection`、`sync.engine.trigger`、
   `sync.engine.trigger_all`、`sync.forget_account`、`sync.apply_server_action`、
   `sync.push_alerts`。每条用例记录这些桩被怎么调用（调用记录里**不含凭据明文**），
   Kotlin 用假的 `SyncBackend` 回放并比较同样内容；
6. 每个用例组跑完后导出数据库状态（`accounts`、`messages`、`audit_log` 与相关的
   `settings` 键）；加密列只比「是否非空」，不比密文。

    python scripts/golden/api_write.py           # 重新生成 JSON
    python scripts/golden/api_write.py --check   # 检查是否与当前 Python 实现一致

本机模式独有的行为（`/api/login` 等返回 400、`PUT /api/settings` 拒绝改密码与凭据类字段、
删除账户时清 `local_initial_import_<id>`、`/api/governance/run` 的 busy 判定）不在这里：
它们与服务器不同，由 `LocalApiWriteTest` 做普通单元测试。
"""
from __future__ import annotations

import imaplib
import json
import os
import socket
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

SEED_PATH = (_common.REPO_ROOT / "android" / "core" / "src" / "test" / "resources"
             / "api" / "write_seed.json")
PASSWORD = "correct horse battery"

# ---------------- 外部依赖的桩 ----------------

# 当前用例的桩模式：{"probe": <模式>, "server_action": "all_ok"|"partial"|"all_fail"}
#
# 探测失败有两种抛法，对应服务器两个接口对异常文字的不同处理：
# - 添加 / 修改账户（`_probe_password_account`）把 `str(exc)` 直接拼进 400 的 detail。
#   Kotlin 移植按任务要求走 `connectionErrorText`（中文映射），而 Python 这里是原始异常文本，
#   两边不会自动一致，所以这组桩抛的异常**文本就是 Kotlin 侧映射出来的中文**：`text_*`。
# - 「测试连接」（`test_account`）走 `mail_client.connection_error_text`，是按异常类型映射的，
#   所以这组桩抛真实类型的异常（`refused` / `auth` / `timeout` / `io`），Kotlin 侧抛同义的
#   Java 异常，映射结果一致。
STUBS: dict = {}
# 本次请求里桩被调用的记录（Kotlin 侧的假 SyncBackend 记同样的内容）。
CALLS: list = []


def _probe_stub(account, credential):
    """`oauth_auth.probe_connection` 的桩：按用例成功或抛指定异常。

    记录里不含凭据明文，只记「有没有填」。
    """
    CALLS.append({
        "kind": "probe",
        "provider": account.get("provider", ""),
        "email": account.get("email", ""),
        "host": account.get("imap_host", ""),
        "port": int(account.get("imap_port") or 993),
        "auth_type": account.get("auth_type", ""),
        "credential_set": bool(credential),
    })
    mode = STUBS.get("probe", "ok")
    if mode == "ok":
        return None
    # 添加 / 修改账户：异常文本 = Kotlin `connectionErrorText` 的结果
    if mode == "text_refused":
        raise RuntimeError("邮箱服务器拒绝连接，请检查服务器地址和端口")
    if mode == "text_auth":
        # 带 LOGIN 才能让两侧的 is_auth_failure 都为真（服务器原文本来就长这样）
        raise RuntimeError("邮箱服务器拒绝登录（LOGIN failed），请检查授权码或密码")
    if mode == "text_timeout":
        raise RuntimeError("连接邮箱服务器超时")
    # 测试连接：真实异常类型，按类型映射
    if mode == "refused":
        raise ConnectionRefusedError(111, "Connection refused")
    if mode == "auth":
        raise imaplib.IMAP4.error("登录被拒绝")
    if mode == "timeout":
        raise socket.timeout("timed out")
    if mode == "io":
        raise OSError("unexpected EOF")
    raise RuntimeError(f"未知的探测模式 {mode}")


def _trigger_stub(acc_id: int):
    CALLS.append({"kind": "trigger", "id": int(acc_id)})


def _trigger_all_stub():
    rows = db.get_conn().execute("SELECT id FROM accounts WHERE enabled=1").fetchall()
    CALLS.append({"kind": "trigger_all", "ids": sorted(int(r["id"]) for r in rows)})


def _forget_stub(acc_id: int):
    CALLS.append({"kind": "forget", "id": int(acc_id)})


def _server_action_stub(message_ids, action):
    ids = [int(i) for i in message_ids]
    CALLS.append({"kind": "apply_server_action", "action": action, "ids": ids})
    mode = STUBS.get("server_action", "all_ok")
    if mode == "all_ok":
        return ids, []
    if mode == "partial":
        return ids[:-1], ["账户1: 邮件服务器拒绝连接"]
    if mode == "all_fail":
        return [], ["账户1: 邮件服务器拒绝连接"]
    raise RuntimeError(f"未知的服务器动作模式 {mode}")


def _push_alerts_stub(_alerts=None):
    """`sync.push_alerts`：空操作。推送清单不随接口返回，也不属于本金标的比较范围。"""


# ---------------- 建库 ----------------

def _load_seed() -> dict:
    return json.loads(SEED_PATH.read_text(encoding="utf-8"))


def _day_start(seed: dict) -> float:
    tz = sync.TZ
    now = datetime.fromtimestamp(seed["now"], tz)
    return now.replace(hour=0, minute=0, second=0, microsecond=0).timestamp()


def _prepare_database(seed: dict) -> None:
    """建库并按种子插数据；显式写 id，保证两边关联一致。"""
    db.DB_PATH = str(Path(tempfile.mkdtemp(prefix="mailhub-golden-api-write-")) / "mailhub.db")
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


def _insert(conn, table: str, row: dict, columns: list, defaults: dict) -> None:
    values = [row.get(column, defaults.get(column)) for column in columns]
    placeholders = ",".join("?" * len(columns))
    conn.execute(f"INSERT INTO {table}({','.join(columns)}) VALUES({placeholders})", values)


def _seed_sync_status(seed: dict) -> None:
    """把种子里的同步状态放进 `sync.STATUS`（写接口金标里是空的）。"""
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


# ---------------- 用例 ----------------

def _account(name="", provider="auto", email="", secret="", imap_host="", imap_port=993,
             poll_interval=300, color="#38bdf8", enabled=True, **extra) -> dict:
    """`AccountBody` 的构造函数；`extra` 用来造非法值（例如把 imap_port 写成字符串）。"""
    body = {"name": name, "provider": provider, "email": email, "secret": secret,
            "imap_host": imap_host, "imap_port": imap_port,
            "poll_interval": poll_interval, "color": color, "enabled": enabled}
    body.update(extra)
    return body


# 请求用例：(用例 id, 方法, 路径, 请求体, 桩模式)。
# 请求体为 None 表示不带 body；桩模式为空表示全部用默认（探测成功、服务器动作全部成功）。
REQUESTS = [
    # ---- 账户：创建 ----
    ("account_create_ok", "POST", "/api/accounts",
     _account(name="新账户", provider="custom", email=" New@Example.com ", secret="app-pass-123",
              imap_host=" imap.new.example ", poll_interval=120, color="#22c55e"), {"probe": "ok"}),
    ("account_create_auto_provider", "POST", "/api/accounts",
     _account(email="auto@qq.com", secret="qq-pass", provider="auto"), {"probe": "ok"}),
    ("account_create_poll_clamped_low", "POST", "/api/accounts",
     _account(email="low@163.com", secret="p", provider="163", poll_interval=10), {"probe": "ok"}),
    ("account_create_poll_clamped_high", "POST", "/api/accounts",
     _account(email="high@163.com", secret="p", provider="163", poll_interval=999999),
     {"probe": "ok"}),
    ("account_create_color_invalid", "POST", "/api/accounts",
     _account(email="color@163.com", secret="p", provider="163", color="red"), {"probe": "ok"}),
    ("account_create_missing_secret", "POST", "/api/accounts",
     _account(email="nosecret@example.com", provider="custom", imap_host="imap.example"),
     {}),
    ("account_create_missing_host", "POST", "/api/accounts",
     _account(email="nohost@example.com", provider="custom", secret="p"), {}),
    ("account_create_probe_refused", "POST", "/api/accounts",
     _account(email="refused@163.com", provider="163", secret="p"), {"probe": "text_refused"}),
    ("account_create_probe_auth_failure", "POST", "/api/accounts",
     _account(email="autherr@qq.com", provider="qq", secret="web-password"),
     {"probe": "text_auth"}),
    ("account_create_probe_auth_failure_custom", "POST", "/api/accounts",
     _account(email="autherr@example.com", provider="custom", secret="web-password",
              imap_host="imap.example"), {"probe": "text_auth"}),
    ("account_create_probe_timeout", "POST", "/api/accounts",
     _account(email="slow@163.com", provider="163", secret="p"), {"probe": "text_timeout"}),
    ("account_create_duplicate", "POST", "/api/accounts",
     _account(email="work@corp.example", provider="exmail", secret="p"), {"probe": "ok"}),
    ("account_create_provider_mismatch", "POST", "/api/accounts",
     _account(email="someone@gmail.com", provider="qq", secret="p"), {"probe": "ok"}),
    ("account_create_unknown_provider", "POST", "/api/accounts",
     _account(email="who@example.com", provider="nope", secret="p"), {"probe": "ok"}),
    ("account_create_bad_email", "POST", "/api/accounts",
     _account(email="not-an-email", provider="custom", secret="p", imap_host="imap.example"), {}),
    ("account_create_oauth_only_provider", "POST", "/api/accounts",
     _account(email="someone@outlook.com", provider="outlook", secret="p"), {"probe": "ok"}),
    ("account_create_missing_email", "POST", "/api/accounts", {"provider": "custom"}, {}),
    ("account_create_bad_port", "POST", "/api/accounts",
     _account(email="port@example.com", provider="custom", imap_host="imap.example",
              imap_port="abc"), {}),
    ("account_create_float_port", "POST", "/api/accounts",
     _account(email="port2@example.com", provider="custom", imap_host="imap.example",
              imap_port=993.5), {}),
    ("account_create_bad_bool", "POST", "/api/accounts",
     _account(email="bool@example.com", provider="custom", imap_host="imap.example",
              enabled="maybe"), {}),
    ("account_create_null_name", "POST", "/api/accounts",
     _account(email="nullname@example.com", provider="custom", imap_host="imap.example",
              name=None), {}),
    ("account_create_empty_body", "POST", "/api/accounts", {}, {}),

    # ---- 账户：更新 ----
    ("account_update_password", "PUT", "/api/accounts/1",
     _account(name="工作邮箱（改名）", provider="exmail", email="work@corp.example",
              secret="new-app-pass"), {"probe": "ok"}),
    ("account_update_host", "PUT", "/api/accounts/1",
     _account(name="工作邮箱", provider="exmail", email="work@corp.example",
              secret="newer-app-pass", imap_host="imap2.corp.example"), {"probe": "ok"}),
    ("account_update_host_without_secret", "PUT", "/api/accounts/3",
     _account(name="停用邮箱", provider="custom", email="off@example.com",
              imap_host="imap.other.example"), {}),
    ("account_update_same_identity_no_secret", "PUT", "/api/accounts/1",
     _account(name="工作邮箱", provider="exmail", email="WORK@corp.example"), {}),
    ("account_update_probe_fail", "PUT", "/api/accounts/1",
     _account(name="工作邮箱", provider="exmail", email="work@corp.example",
              secret="bad-pass"), {"probe": "text_refused"}),
    ("account_update_probe_auth_failure", "PUT", "/api/accounts/1",
     _account(name="工作邮箱", provider="exmail", email="work@corp.example",
              secret="web-password"), {"probe": "text_auth"}),
    ("account_update_oauth_identity_change", "PUT", "/api/accounts/2",
     _account(name="OAuth 账户", provider="gmail", email="other@example.com"), {}),
    ("account_update_oauth_same_identity", "PUT", "/api/accounts/2",
     _account(name="OAuth 账户（改名）", provider="gmail", email="OAuth@Example.com"), {}),
    ("account_update_oauth_with_secret", "PUT", "/api/accounts/2",
     _account(provider="outlook", email="oauth@example.com", secret="p"), {"probe": "ok"}),
    ("account_update_missing_email", "PUT", "/api/accounts/1", {"name": "x"}, {}),
    ("account_update_not_found", "PUT", "/api/accounts/999",
     _account(email="gone@example.com", provider="custom", imap_host="imap.example",
              secret="p"), {}),

    # ---- 同步与连接测试 ----
    ("account_test_ok", "POST", "/api/accounts/1/test", None, {"probe": "ok"}),
    ("account_test_refused", "POST", "/api/accounts/1/test", None, {"probe": "refused"}),
    ("account_test_timeout", "POST", "/api/accounts/1/test", None, {"probe": "timeout"}),
    ("account_test_io", "POST", "/api/accounts/3/test", None, {"probe": "io"}),
    ("account_test_not_found", "POST", "/api/accounts/999/test", None, {}),
    ("account_sync_now", "POST", "/api/accounts/1/sync", None, {}),
    ("account_sync_now_unknown", "POST", "/api/accounts/999/sync", None, {}),
    ("sync_all", "POST", "/api/sync-all", None, {}),

    # ---- 批量：纯本地 ----
    ("batch_read", "POST", "/api/messages/batch",
     {"ids": [1, 6], "action": "read"}, {}),
    ("batch_read_again", "POST", "/api/messages/batch",
     {"ids": [1], "action": "read"}, {}),
    ("batch_unread", "POST", "/api/messages/batch",
     {"ids": [1], "action": "unread"}, {}),
    ("batch_read_already_read", "POST", "/api/messages/batch",
     {"ids": [2], "action": "read"}, {}),
    ("batch_soft_delete", "POST", "/api/messages/batch",
     {"ids": [2], "action": "delete"}, {}),
    ("batch_soft_delete_missing_id", "POST", "/api/messages/batch",
     {"ids": [999], "action": "delete"}, {}),
    ("batch_protected", "POST", "/api/messages/batch",
     {"ids": [3], "action": "delete"}, {}),
    ("batch_partial_blocked", "POST", "/api/messages/batch",
     {"ids": [5, 3], "action": "delete", "confirmed": True}, {}),
    ("batch_protected_confirmed", "POST", "/api/messages/batch",
     {"ids": [3], "action": "delete", "confirmed": True, "override_protection": True}, {}),
    ("batch_two_ids_ignore_override", "POST", "/api/messages/batch",
     {"ids": [3, 4], "action": "delete", "confirmed": True, "override_protection": True}, {}),
    ("batch_single_override", "POST", "/api/messages/batch",
     {"ids": [4], "action": "delete", "confirmed": True, "override_protection": True}, {}),
    ("batch_bad_action", "POST", "/api/messages/batch",
     {"ids": [1], "action": "nope"}, {}),
    ("batch_empty_ids", "POST", "/api/messages/batch",
     {"ids": [], "action": "read"}, {}),
    ("batch_missing_action", "POST", "/api/messages/batch", {"ids": [1]}, {}),
    ("batch_bad_ids", "POST", "/api/messages/batch",
     {"ids": ["x"], "action": "read"}, {}),
    ("batch_ids_not_list", "POST", "/api/messages/batch",
     {"ids": 5, "action": "read"}, {}),

    # ---- 批量：server=true 走 IMAP ----
    ("batch_server_read_all_ok", "POST", "/api/messages/batch",
     {"ids": [9], "action": "read", "server": True}, {"server_action": "all_ok"}),
    ("batch_server_read_partial", "POST", "/api/messages/batch",
     {"ids": [8, 9], "action": "read", "server": True}, {"server_action": "partial"}),
    ("batch_server_delete_all_fail", "POST", "/api/messages/batch",
     {"ids": [8], "action": "delete", "server": True, "confirmed": True},
     {"server_action": "all_fail"}),
    ("batch_server_delete_partial", "POST", "/api/messages/batch",
     {"ids": [8, 9], "action": "delete", "server": True, "confirmed": True},
     {"server_action": "partial"}),

    # ---- 回收站与撤销 ----
    ("trash_restore", "POST", "/api/trash/restore", {"ids": [7, 2]}, {}),
    ("trash_restore_again", "POST", "/api/trash/restore", {"ids": [7]}, {}),
    ("trash_restore_empty", "POST", "/api/trash/restore", {"ids": []}, {}),
    ("trash_restore_missing_ids", "POST", "/api/trash/restore", {}, {}),
    ("undo_missing", "POST", "/api/audit/999/undo", None, {}),
    ("undo_already_undone", "POST", "/api/audit/4/undo", None, {}),
    ("undo_not_reversible", "POST", "/api/audit/3/undo", None, {}),
    ("undo_read", "POST", "/api/audit/2/undo", None, {}),
    ("undo_soft_delete", "POST", "/api/audit/1/undo", None, {}),
    ("undo_twice", "POST", "/api/audit/1/undo", None, {}),
]

# 中途改设置的用例：与 Kotlin 侧一样直接写 settings 表。
ACTIONS = [
    ("enable_read_state_sync", "settings", {"sync_read_state": "1"}),
]

# 打开「已读状态双向同步」之后的批量用例：纯本地改动要登记待推送，server=true 不登记。
REQUESTS_AFTER = [
    ("batch_unread_flag_push", "POST", "/api/messages/batch",
     {"ids": [6], "action": "unread"}, {}),
    ("batch_read_flag_push", "POST", "/api/messages/batch",
     {"ids": [6], "action": "read"}, {}),
    ("batch_server_read_no_flag_push", "POST", "/api/messages/batch",
     {"ids": [9], "action": "read", "server": True}, {"server_action": "all_ok"}),
]

# 设置接口：`SettingsUpdate` 实际会发送的每个字段的合法与非法值。
SETTINGS_REQUESTS = [
    ("settings_ai_enabled_true", {"ai_enabled": True}),
    ("settings_ai_enabled_false", {"ai_enabled": False}),
    ("settings_ai_send_body_false", {"ai_send_body": False}),
    ("settings_ai_scope_uncertain", {"ai_scope": "uncertain"}),
    ("settings_ai_scope_other", {"ai_scope": "other"}),
    ("settings_ai_scope_invalid", {"ai_scope": "nope"}),
    ("settings_ai_backfill_days_120", {"ai_backfill_days": 120}),
    ("settings_ai_backfill_days_high", {"ai_backfill_days": 100000}),
    ("settings_ai_backfill_days_negative", {"ai_backfill_days": -5}),
    ("settings_ai_backfill_days_invalid", {"ai_backfill_days": "many"}),
    ("settings_clean_otp_days_14", {"clean_otp_days": 14}),
    ("settings_clean_otp_days_negative", {"clean_otp_days": -1}),
    ("settings_clean_server_true", {"clean_server": True}),
    ("settings_clean_bill_minor_days_7", {"clean_bill_minor_days": 7}),
    ("settings_sync_read_state_false", {"sync_read_state": False}),
    ("settings_digest_hour_23", {"digest_hour": 23}),
    ("settings_digest_hour_high", {"digest_hour": 99}),
    ("settings_digest_hour_negative", {"digest_hour": -2}),
    ("settings_digest_hour_invalid", {"digest_hour": "早上"}),
    ("settings_notify_important_false", {"notify_important": False}),
    ("settings_notify_otp_true", {"notify_otp": True}),
    ("settings_body_keep_days_0", {"body_keep_days": 0}),
    ("settings_body_keep_days_negative", {"body_keep_days": -30}),
    ("settings_old_password_alone", {"old_password": "whatever"}),
    ("settings_unknown_field_ignored", {"ai_enabled": True, "unknown_field": 1}),
    ("settings_combined", {"ai_enabled": False, "ai_scope": "all", "clean_otp_days": 3,
                           "digest_hour": 6, "notify_important": True, "notify_otp": False,
                           "body_keep_days": 60, "clean_bill_minor_days": 0}),
    ("settings_null_all", {"ai_enabled": None, "digest_hour": None}),
    ("settings_bool_invalid", {"clean_server": "perhaps"}),
    ("settings_empty_body", {}),
]


def _set_stubs(stubs: dict) -> None:
    STUBS.clear()
    STUBS.update(stubs)


def _pick_headers(response) -> dict:
    """只比较 App 真正依赖的响应头；其余（date、server 等）每次都不同。"""
    headers = {}
    for name in ("content-type", "x-mailhub-protected"):
        value = response.headers.get(name)
        if value is not None:
            headers[name] = value
    return headers


def _record(client: TestClient, case_id: str, method: str, path: str, body, stubs: dict) -> dict:
    _set_stubs(stubs)
    CALLS.clear()
    kwargs = {}
    if body is not None:
        kwargs["json"] = body
    response = client.request(method, path, **kwargs)
    try:
        payload = response.json()
    except ValueError:
        payload = response.text
    calls = list(CALLS)
    return {
        "id": case_id,
        "fn": "request",
        # stubs 随用例下发：Kotlin 用假 SyncBackend 按同样的模式成功 / 抛异常 / 返回部分成功
        "args": {"method": method, "path": path, "body": body, "stubs": stubs},
        "expected": {
            "status": response.status_code,
            "headers": _pick_headers(response),
            "json": payload,
            "calls": calls,
        },
    }


SETTINGS_KEYS = (
    "ai_enabled", "ai_base_url", "ai_model", "ai_send_body", "ai_scope",
    "ai_backfill_days", "clean_otp_days", "clean_server", "clean_bill_minor_days",
    "sync_read_state", "digest_hour", "notify_important", "notify_otp", "body_keep_days",
    "bill_amount_threshold", "bill_routine_count", "bill_auto_renew", "bill_refund",
    "notify_tg_chat", "notify_bark_url_enc", "notify_bark_url", "notify_tg_token_enc",
    "notify_tg_token", "notify_onepush_enc", "ai_key_enc", "admin_hash",
    "google_oauth_client_id", "google_oauth_client_secret_enc",
    "microsoft_oauth_client_id", "microsoft_oauth_client_secret_enc",
)

SECRET_KEYS = ("admin_hash", "ai_key_enc", "notify_bark_url_enc", "notify_bark_url",
               "notify_tg_token_enc", "notify_tg_token", "notify_onepush_enc",
               "google_oauth_client_secret_enc", "microsoft_oauth_client_secret_enc")


def _export_db() -> dict:
    """导出请求之后的库状态：加密列只记「是否非空」。"""
    conn = db.get_conn()
    accounts = []
    for row in conn.execute(
            """SELECT id, name, provider, email, imap_host, imap_port, auth_type, secret_enc,
                      oauth_refresh_enc, oauth_expires, enabled, poll_interval, last_uid,
                      uidvalidity, last_error, color, created_ts
               FROM accounts ORDER BY id""").fetchall():
        accounts.append({
            "id": row["id"], "name": row["name"], "provider": row["provider"],
            "email": row["email"], "imap_host": row["imap_host"],
            "imap_port": row["imap_port"], "auth_type": row["auth_type"],
            "secret_set": bool(row["secret_enc"] or row["oauth_refresh_enc"]),
            "oauth_expires": row["oauth_expires"], "enabled": row["enabled"],
            "poll_interval": row["poll_interval"], "last_uid": row["last_uid"],
            "uidvalidity": row["uidvalidity"], "last_error": row["last_error"],
            "color": row["color"], "created_ts": row["created_ts"],
        })
    messages = []
    for row in conn.execute(
            """SELECT id, account_id, uid, category, bill_tier, unread, deleted_ts, flag_push
               FROM messages ORDER BY id""").fetchall():
        messages.append({
            "id": row["id"], "account_id": row["account_id"], "uid": row["uid"],
            "category": row["category"], "bill_tier": row["bill_tier"],
            "unread": row["unread"], "deleted_ts": row["deleted_ts"],
            "flag_push": row["flag_push"],
        })
    audit = []
    for row in conn.execute(
            """SELECT id, ts, actor, action, tier, target_count, target_ids, reason,
                      allowed, reversible, undone, undo_payload
               FROM audit_log ORDER BY id""").fetchall():
        audit.append({
            "id": row["id"], "ts": row["ts"], "actor": row["actor"],
            "action": row["action"], "tier": row["tier"],
            "target_count": row["target_count"],
            "target_ids": json.loads(row["target_ids"] or "[]"),
            "reason": row["reason"], "allowed": row["allowed"],
            "reversible": row["reversible"], "undone": row["undone"],
            "undo_payload": json.loads(row["undo_payload"]) if row["undo_payload"] else None,
        })
    settings = {}
    for key in SETTINGS_KEYS:
        row = conn.execute("SELECT v FROM settings WHERE k=?", (key,)).fetchone()
        if row is None:
            continue
        value = row["v"] or ""
        if key in SECRET_KEYS:
            settings[key] = bool(value)
        else:
            settings[key] = value
    return {"accounts": accounts, "messages": messages, "audit": audit, "settings": settings}


def _db_state(case_id: str) -> dict:
    return {"id": case_id, "fn": "db_state", "args": {}, "expected": _export_db()}


def _fixed_datetime(now: float):
    """把 `app.datetime.now(tz)` 钉在种子里的固定时间上（与 Kotlin 侧注入的时钟同值）。"""

    class _FixedDatetime(datetime):
        @classmethod
        def now(cls, tz=None):
            return datetime.fromtimestamp(now, tz)

    return _FixedDatetime


def build_cases() -> list:
    seed = _load_seed()
    _prepare_database(seed)
    cases: list = []
    with mock.patch("time.time", lambda: seed["now"]), \
            mock.patch("app.datetime", _fixed_datetime(seed["now"])), \
            mock.patch.object(oauth_auth, "probe_connection", _probe_stub), \
            mock.patch.object(sync.engine, "trigger", _trigger_stub), \
            mock.patch.object(sync.engine, "trigger_all", _trigger_all_stub), \
            mock.patch.object(sync, "forget_account", _forget_stub), \
            mock.patch.object(sync, "apply_server_action", _server_action_stub), \
            mock.patch.object(sync, "push_alerts", _push_alerts_stub):
        _seed_sync_status(seed)
        client = TestClient(mailhub_app.app, base_url="https://testserver")
        _login(client)
        for case_id, method, path, body, stubs in REQUESTS:
            cases.append(_record(client, case_id, method, path, body, stubs))
        cases.append(_db_state("db_state_after_accounts_and_batch"))
        for case_id, action, values in ACTIONS:
            if action != "settings":
                raise ValueError(f"未知的动作：{action}")
            for key, value in values.items():
                db.set_setting(key, value)
            cases.append({"id": case_id, "fn": "settings",
                          "args": {"settings": values}, "expected": {"ok": True}})
        for case_id, method, path, body, stubs in REQUESTS_AFTER:
            cases.append(_record(client, case_id, method, path, body, stubs))
        cases.append(_db_state("db_state_after_read_state"))
        for case_id, body in SETTINGS_REQUESTS:
            cases.append(_record(client, case_id, "PUT", "/api/settings", body, {}))
        cases.append(_db_state("db_state_after_settings"))
        cases.append(_record(client, "governance_run", "POST", "/api/governance/run", None, {}))
        cases.append(_record(client, "governance_run_empty_body", "POST",
                             "/api/governance/run", {}, {}))
        cases.append(_record(client, "governance_run_days", "POST",
                             "/api/governance/run", {"days": 7}, {}))
        cases.append(_record(client, "governance_run_days_too_big", "POST",
                             "/api/governance/run", {"days": 200}, {}))
        cases.append(_record(client, "governance_run_days_negative", "POST",
                             "/api/governance/run", {"days": -1}, {}))
        cases.append(_db_state("db_state_final"))
        # busy 用例放最后：Python 侧手动占住进程内运行锁，Kotlin 侧用一个卡住的治理线程。
        governance = sys.modules["governance"]
        governance._RUN_LOCK.acquire()
        try:
            cases.append(_record(client, "governance_run_busy", "POST",
                                 "/api/governance/run", {}, {}))
        finally:
            governance._RUN_LOCK.release()
        # 删除账户也放最后：它按外键级联删掉账户名下的邮件，前面的用例都还要用这些数据。
        # 本机独有的两步收尾（清 `local_initial_import_<id>`、通知协调器忘掉账户）
        # 不属于服务器行为，由 `LocalApiWriteTest` 覆盖。
        cases.append(_record(client, "account_delete", "DELETE", "/api/accounts/3", None, {}))
        cases.append(_record(client, "account_delete_missing", "DELETE",
                             "/api/accounts/999", None, {}))
        cases.append(_db_state("db_state_after_account_delete"))
    return cases


if __name__ == "__main__":
    _common.run("api_write", build_cases)
