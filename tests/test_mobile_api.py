"""移动端 API：/api/meta、游标分页、增量变更、总览与账户列表聚合。

全部合成数据，用临时 SQLite 库（见 tests/conftest.py）。
"""
import os
import random
import sys
import time
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

import app as mailhub_app  # noqa: E402
import billing  # noqa: E402
import db  # noqa: E402
import governance  # noqa: E402
import oauth_auth  # noqa: E402
import rules  # noqa: E402
import sessions  # noqa: E402
import sync  # noqa: E402
from security import hash_password  # noqa: E402

PASSWORD = "correct horse battery"
# 固定的「现在」：2026-10-20 12:00 上海时间
NOW = datetime(2026, 10, 20, 12, 0, 0, tzinfo=sync.TZ).timestamp()


# ---------------- 夹具 ----------------

@pytest.fixture(autouse=True)
def clean_db():
    """每个用例前清空业务表（照 tests/test_pipeline.py 的 fresh_db）。"""
    db.init_db()
    oauth_auth.init_oauth_db()
    conn = db.get_conn()
    for t in ("governance_decisions", "governance_runs", "messages", "accounts",
              "audit_log", "aliases", "rules", "digests"):
        conn.execute(f"DELETE FROM {t}")
    conn.commit()
    yield conn


@pytest.fixture
def client(clean_db):
    """用网页方式登录的 TestClient（secure cookie 只会在 https 上回传）。"""
    db.set_setting("admin_hash", hash_password(PASSWORD))
    sessions.revoke_all_sessions()
    mailhub_app.login_throttle = sessions.LoginThrottle()
    c = TestClient(mailhub_app.app, base_url="https://testserver")
    assert c.post("/api/login", json={"password": PASSWORD}).status_code == 200
    return c


def _mk_account(conn, name="acc", email="a@example.com"):
    cur = conn.execute(
        """INSERT INTO accounts(name, provider, email, imap_host, imap_port,
                                auth_type, enabled, created_ts)
           VALUES(?,?,?,?,?,?,1,0)""",
        (name, "custom", email, "imap.example.com", 993, "password"))
    conn.commit()
    return cur.lastrowid


def _insert(conn, acc_id, uid, **kw):
    row = {
        "subject": f"邮件 {uid}",
        "sender_name": "发件人",
        "sender_addr": "sender@example.com",
        "date_ts": 1_700_000_000.0 + uid,
        "category": "通知",
        "unread": 1,
        "deleted_ts": 0,
        "created_ts": 0,
        "governance_version": governance.VERSION,
        "bill_tier": "",
        "importance": 0,
        "snippet": "",
        "risk_level": "none",
        "otp_code": "",
        "summary": "",
    }
    row.update(kw)
    row["account_id"] = acc_id
    row["uid"] = uid
    cols = list(row)
    conn.execute(
        f"INSERT INTO messages({','.join(cols)}) VALUES({','.join('?' * len(cols))})",
        [row[c] for c in cols])
    conn.commit()
    return conn.execute("SELECT id FROM messages WHERE account_id=? AND uid=?",
                        (acc_id, uid)).fetchone()["id"]


# ---------------- 1. /api/meta ----------------

def test_meta_returns_capabilities_and_timezone(client):
    body = client.get("/api/meta")
    assert body.status_code == 200
    data = body.json()
    assert data["ok"] is True
    assert data["api_version"] == 1
    assert isinstance(data["server_time"], float) and data["server_time"] > 0
    assert data["tz"] == "Asia/Shanghai"
    assert data["categories"] == rules.CATEGORIES
    assert data["display_categories"] == mailhub_app.DISPLAY_CATEGORIES
    assert data["bill_tiers"] == list(billing.TIERS)
    assert data["ministries"] == list(governance.MINISTRIES)
    assert data["governance_version"] == governance.VERSION
    assert data["features"] == {"token_auth": True, "cursor": True,
                                "changes": True, "sessions": True}


def test_health_reports_api_version_and_meta_needs_login(clean_db):
    anon = TestClient(mailhub_app.app, base_url="https://testserver")
    health = anon.get("/api/health")
    assert health.status_code == 200
    assert health.json()["api_version"] == 1
    assert anon.get("/api/meta").status_code == 401
    assert anon.get("/api/changes").status_code == 401


# ---------------- 2./3. 游标分页 ----------------

def test_cursor_paging_walks_all_messages_without_gaps(clean_db, client):
    acc = _mk_account(clean_db)
    base = 1_700_000_000.0
    # 每 10 封共享一个 date_ts，制造大量同一时间戳的并列
    stamps = [base - (i // 10) * 3600 for i in range(95)]
    order = list(range(95))
    random.Random(20261020).shuffle(order)
    inserted = {}
    for i, slot in enumerate(order):
        inserted[_insert(clean_db, acc, i + 1, date_ts=stamps[slot])] = stamps[slot]

    expected = [mid for mid, ts in
                sorted(inserted.items(), key=lambda kv: (kv[1], kv[0]), reverse=True)]

    first = client.get("/api/messages", params={"page_size": 20}).json()
    assert first["total"] == 95 and first["page"] == 1
    assert len(first["items"]) == 20
    seen = [m["id"] for m in first["items"]]
    pairs = [(m["date_ts"], m["id"]) for m in first["items"]]
    cursor = first["next_cursor"]

    while cursor:
        body = client.get("/api/messages", params={"page_size": 20, **cursor}).json()
        assert body["total"] is None
        assert body["next_cursor"] is not None or len(body["items"]) < 20
        seen.extend(m["id"] for m in body["items"])
        pairs.extend((m["date_ts"], m["id"]) for m in body["items"])
        cursor = body["next_cursor"]

    assert len(seen) == len(set(seen)) == 95
    assert seen == expected
    assert all(a > b for a, b in zip(pairs, pairs[1:]))


def test_cursor_ignores_page_and_last_page_has_no_cursor(clean_db, client):
    acc = _mk_account(clean_db)
    for i in range(25):
        _insert(clean_db, acc, i + 1, date_ts=1_700_000_000.0 + i)

    page1 = client.get("/api/messages", params={"page_size": 20}).json()
    assert page1["total"] == 25
    cursor = page1["next_cursor"]
    assert cursor is not None

    # 带游标时 page 被忽略：page=7 也不会跳过任何邮件
    tail = client.get("/api/messages", params={"page_size": 20, "page": 7, **cursor}).json()
    assert tail["total"] is None
    assert [m["date_ts"] for m in tail["items"]] == [1_700_000_000.0 + i for i in range(4, -1, -1)]
    assert tail["next_cursor"] is None
    assert {m["id"] for m in page1["items"]}.isdisjoint({m["id"] for m in tail["items"]})


def test_web_paging_still_returns_total_and_page(clean_db, client):
    acc = _mk_account(clean_db)
    for i in range(45):
        _insert(clean_db, acc, i + 1, date_ts=1_700_000_000.0 + i)

    page2 = client.get("/api/messages", params={"page": 2, "page_size": 20}).json()
    page1 = client.get("/api/messages", params={"page": 1, "page_size": 20}).json()
    assert page2["total"] == 45 and page2["page"] == 2
    assert len(page2["items"]) == 20 and len(page1["items"]) == 20
    assert {m["id"] for m in page1["items"]}.isdisjoint({m["id"] for m in page2["items"]})


# ---------------- 4. 搜索词转义 ----------------

def test_search_treats_like_wildcards_as_literals(clean_db, client):
    acc = _mk_account(clean_db)
    percent = _insert(clean_db, acc, 1, subject="限时折扣 50% off")
    plain = _insert(clean_db, acc, 2, subject="普通通知")
    underscore = _insert(clean_db, acc, 3, subject="a_b 报表")
    near = _insert(clean_db, acc, 4, subject="axb 报表")

    body = client.get("/api/messages", params={"q": "%"}).json()
    assert {m["id"] for m in body["items"]} == {percent}
    body = client.get("/api/messages", params={"q": "a_b"}).json()
    assert {m["id"] for m in body["items"]} == {underscore}
    assert plain not in {m["id"] for m in body["items"]}
    assert near not in {m["id"] for m in body["items"]}
    # 「选择全部」走同一个筛选函数，结果一致
    ids = client.get("/api/messages/ids", params={"q": "a_b"}).json()
    assert ids["ids"] == [underscore] and ids["total"] == 1


def test_message_ids_order_matches_list(clean_db, client):
    acc = _mk_account(clean_db)
    for i in range(6):
        _insert(clean_db, acc, i + 1, date_ts=1_700_000_000.0 + (i % 3))
    listed = [m["id"] for m in client.get("/api/messages").json()["items"]]
    ids = client.get("/api/messages/ids").json()["ids"]
    assert ids == listed


# ---------------- 5. mark_read ----------------

def test_get_message_can_skip_marking_read(clean_db, client):
    acc = _mk_account(clean_db)
    mid = _insert(clean_db, acc, 1, unread=1)

    preview = client.get(f"/api/messages/{mid}", params={"mark_read": "false"})
    assert preview.status_code == 200
    assert preview.json()["unread"] == 1
    assert clean_db.execute("SELECT unread FROM messages WHERE id=?", (mid,)).fetchone()["unread"] == 1

    assert client.get(f"/api/messages/{mid}").status_code == 200
    assert clean_db.execute("SELECT unread FROM messages WHERE id=?", (mid,)).fetchone()["unread"] == 0


# ---------------- 6. /api/changes ----------------

def test_changes_waits_for_pending_governance_then_catches_up(clean_db, client):
    acc = _mk_account(clean_db)
    now = time.time()
    after_id = _insert(clean_db, acc, 1, created_ts=now - 86400)
    first = _insert(clean_db, acc, 2, created_ts=now)
    pending = _insert(clean_db, acc, 3, created_ts=now, governance_version="")
    last = _insert(clean_db, acc, 4, created_ts=now)

    init = client.get("/api/changes", params={"after_id": 0}).json()
    assert init["items"] == []
    assert init["cursor"] == last

    body = client.get("/api/changes", params={"after_id": after_id}).json()
    assert [i["id"] for i in body["items"]] == [first]
    assert body["cursor"] == pending - 1
    item = body["items"][0]
    assert item["account_name"] == "acc"
    assert item["display_category"] == "通知"
    assert set(item) >= {"id", "account_id", "account_name", "subject", "sender_name",
                         "sender_addr", "date_ts", "category", "bill_tier", "importance",
                         "otp_code", "risk_level", "unread", "display_category"}

    clean_db.execute(
        "UPDATE messages SET governance_version=?, needs_review=0, ai_retry_ts=0 WHERE id=?",
        (governance.VERSION, pending))
    clean_db.commit()
    body = client.get("/api/changes", params={"after_id": after_id}).json()
    assert [i["id"] for i in body["items"]] == [first, pending, last]
    assert body["cursor"] == last

    # 超过 1 小时还没治理的邮件不再挡路
    clean_db.execute("UPDATE messages SET governance_version='', created_ts=? WHERE id=?",
                     (now - 7200, pending))
    clean_db.commit()
    body = client.get("/api/changes", params={"after_id": after_id}).json()
    assert [i["id"] for i in body["items"]] == [first, pending, last]
    assert body["cursor"] == last

    # limit 限制在 1–200
    one = client.get("/api/changes", params={"after_id": after_id, "limit": 0}).json()
    assert [i["id"] for i in one["items"]] == [first]
    assert one["cursor"] == first


# ---------------- 7. 总览黄金测试 ----------------

def _legacy_overview(conn, day_start):
    """T02 之前的 overview 查询逻辑，原样复制自提交 5b0b0e7 的 app.overview。

    只用于对照，不参与生产代码。分类展示名仍调用同一个 _display_category
    （本卡没有改动它）。
    """
    display = mailhub_app._display_category

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
    cat_rows = conn.execute(
        "SELECT category, bill_tier, COUNT(*) c FROM messages WHERE deleted_ts=0 "
        "GROUP BY category, bill_tier").fetchall()
    by_category: dict[str, int] = {}
    for r in cat_rows:
        name = display(r["category"], r["bill_tier"])
        by_category[name] = by_category.get(name, 0) + r["c"]
    stats["by_category"] = by_category
    days = []
    daily = []
    for i in range(13, -1, -1):
        d0 = day_start - i * 86400
        d1 = d0 + 86400
        label = datetime.fromtimestamp(d0, sync.TZ).strftime("%m-%d")
        days.append(label)
        row = {"day": label}
        for r in conn.execute(
                "SELECT category, bill_tier, COUNT(*) c FROM messages "
                "WHERE date_ts>=? AND date_ts<? AND deleted_ts=0 GROUP BY category, bill_tier",
                (d0, d1)).fetchall():
            name = display(r["category"], r["bill_tier"])
            row[name] = row.get(name, 0) + r["c"]
        daily.append(row)
    important = conn.execute(
        """SELECT m.id, m.subject, m.sender_addr, m.date_ts, m.category, m.bill_tier, m.importance,
                  m.summary, m.unread, a.name AS account_name, a.color AS account_color
           FROM messages m JOIN accounts a ON a.id=m.account_id
           WHERE m.deleted_ts=0 AND (m.category IN ('重要','安全','可疑') OR m.importance >= 4)
           ORDER BY m.unread DESC, m.date_ts DESC LIMIT 8""").fetchall()
    return {"stats": stats, "days": days, "daily": daily,
            "important": [dict(r) for r in important]}


def test_overview_matches_legacy_queries(clean_db):
    acc = _mk_account(clean_db)
    day_start = datetime.fromtimestamp(NOW, sync.TZ).replace(
        hour=0, minute=0, second=0, microsecond=0).timestamp()
    rnd = random.Random(20261020)
    categories = list(rules.CATEGORIES) + ["未分类"]
    for uid in range(1, 301):
        category = categories[rnd.randrange(len(categories))]
        tier = ""
        if category == "账单":
            tier = ("重要", "一般", "")[rnd.randrange(3)]
        day = rnd.randrange(20)                       # 跨 20 天，含 14 天窗口之外
        date_ts = day_start - day * 86400 + rnd.uniform(0.002, 0.998) * 86400
        _insert(clean_db, acc, uid, category=category, bill_tier=tier, date_ts=date_ts,
                unread=rnd.randrange(2), importance=rnd.randrange(6),
                subject=f"主题 {uid}", sender_addr=f"s{uid}@example.com",
                deleted_ts=date_ts if rnd.random() < 0.15 else 0)

    new = mailhub_app._overview_sync(now=NOW)
    old = _legacy_overview(clean_db, day_start)
    assert new["stats"] == old["stats"]
    assert new["days"] == old["days"]
    assert new["daily"] == old["daily"]
    assert new["important"] == old["important"]
    # 未定级的账单按「重要账单」展示，并与已定级的同名分类合并
    assert new["stats"]["by_category"].get("重要账单", 0) > 0


def test_overview_keeps_display_categories_and_bill_split(clean_db):
    acc = _mk_account(clean_db)
    day_start = datetime.fromtimestamp(NOW, sync.TZ).replace(
        hour=0, minute=0, second=0, microsecond=0).timestamp()
    _insert(clean_db, acc, 1, category="账单", bill_tier="重要", date_ts=day_start + 3600)
    _insert(clean_db, acc, 2, category="账单", bill_tier="", date_ts=day_start + 7200)
    _insert(clean_db, acc, 3, category="账单", bill_tier="一般", date_ts=day_start + 10800)

    data = mailhub_app._overview_sync(now=NOW)
    assert data["stats"]["by_category"] == {"重要账单": 2, "一般账单": 1}
    assert data["daily"][-1]["重要账单"] == 2 and data["daily"][-1]["一般账单"] == 1
    assert data["daily"][-1]["day"] == data["days"][-1]
    assert data["categories"] == mailhub_app.DISPLAY_CATEGORIES


# ---------------- 8. 账户列表 ----------------

def test_account_totals_ignore_trash(clean_db, client):
    acc = _mk_account(clean_db)
    _insert(clean_db, acc, 1, unread=1)
    _insert(clean_db, acc, 2, unread=0)
    _insert(clean_db, acc, 3, unread=1, deleted_ts=time.time())

    rows = client.get("/api/accounts").json()
    row = next(a for a in rows if a["id"] == acc)
    assert row["total"] == 2
    assert row["unread"] == 1


# ---------------- 9. 删除账户 ----------------

def test_delete_account_404_and_cleanup(clean_db, client):
    assert client.delete("/api/accounts/999").status_code == 404

    acc = _mk_account(clean_db)
    sync._set_status(acc, "idle")
    sync.engine.trigger(acc)
    assert str(acc) in sync.get_status()

    assert client.delete(f"/api/accounts/{acc}").status_code == 200
    assert str(acc) not in sync.get_status()
    assert acc not in sync._force_queue
    assert acc not in sync._sync_locks

    row = clean_db.execute(
        "SELECT * FROM audit_log WHERE action='delete_account'").fetchone()
    assert row is not None
    assert row["actor"] == "user" and row["tier"] == "C"
    assert str(acc) in row["target_ids"]
    assert "a@example.com" in row["reason"]
    assert clean_db.execute("SELECT COUNT(*) c FROM accounts").fetchone()["c"] == 0


# ---------------- 10. 时区 ----------------

def test_resolve_tz_falls_back_and_keeps_valid_zones():
    assert sync._resolve_tz("")[0] == "Asia/Shanghai"
    assert str(sync._resolve_tz("")[1]) == "Asia/Shanghai"
    name, tz = sync._resolve_tz("Invalid/Zone")
    assert name == "Asia/Shanghai" and str(tz) == "Asia/Shanghai"
    name, tz = sync._resolve_tz("Asia/Taipei")
    assert name == "Asia/Taipei" and str(tz) == "Asia/Taipei"
    assert sync.TZ_NAME == "Asia/Shanghai"


# ---------------- 其他小修 ----------------

def test_digest_limit_is_clamped(clean_db, client):
    for i in range(40):
        clean_db.execute("INSERT INTO digests(day, content, created_ts) VALUES(?,?,?)",
                         (f"2026-09-{i + 1:02d}", "内容", 0))
    clean_db.commit()
    assert len(client.get("/api/digests", params={"limit": 999}).json()) == 30
    assert len(client.get("/api/digests", params={"limit": 0}).json()) == 1
    assert len(client.get("/api/digests", params={"limit": 5}).json()) == 5


# ---------------- 11. 不透明游标 token（T03-fix-1） ----------------

# 这个时间戳在 15 位有效数字下会变成 1792951496.51235（比原值大），
# 正是 T05 冒烟里第二页重复第一页最后一封的原因
CURSOR_BASE = 1792951496.5123494


def _b64(text: str) -> str:
    import base64

    return base64.urlsafe_b64encode(text.encode()).decode().rstrip("=")


def test_opaque_cursor_token_pages_without_duplicates(clean_db, client):
    acc = _mk_account(clean_db)
    inserted = {}
    for i in range(50):
        # 每两封共享一个时间戳；小数位很多，任何十进制转一手都会丢精度
        ts = CURSOR_BASE + (i // 2) * 0.0000001
        inserted[_insert(clean_db, acc, i + 1, date_ts=ts)] = ts
    expected = [mid for mid, ts in
                sorted(inserted.items(), key=lambda kv: (kv[1], kv[0]), reverse=True)]

    page = client.get("/api/messages", params={"page_size": 10}).json()
    seen = [m["id"] for m in page["items"]]
    cursor = page["next_cursor"]
    assert cursor and cursor["token"]

    while cursor:
        page = client.get("/api/messages",
                          params={"page_size": 10, "cursor": cursor["token"]}).json()
        seen.extend(m["id"] for m in page["items"])
        cursor = page["next_cursor"]

    assert len(seen) == 50
    assert len(set(seen)) == 50
    assert seen == expected


def test_legacy_float_cursor_loses_precision_and_repeats(clean_db, client):
    acc = _mk_account(clean_db)
    # 9 封比 CURSOR_BASE 新，第一页最后一封正好落在 CURSOR_BASE 上
    stamps = [CURSOR_BASE + (i + 1) * 0.001 for i in range(9)]
    stamps.append(CURSOR_BASE)
    stamps += [CURSOR_BASE - (i + 1) * 0.001 for i in range(20)]
    for i, ts in enumerate(stamps):
        _insert(clean_db, acc, i + 1, date_ts=ts)

    first = client.get("/api/messages", params={"page_size": 10}).json()
    last = first["items"][-1]
    assert last["date_ts"] == CURSOR_BASE

    lossy = str(round(CURSOR_BASE, 5))          # 模拟 PowerShell / 日志的十进制格式化
    assert float(lossy) > CURSOR_BASE
    body = client.get("/api/messages", params={"page_size": 10, "before_ts": lossy,
                                               "before_id": last["id"]}).json()
    assert body["items"][0]["id"] == last["id"]      # 旧参数确实重复了同一封


def test_cursor_token_wins_over_legacy_params(clean_db, client):
    acc = _mk_account(clean_db)
    for i in range(30):
        _insert(clean_db, acc, i + 1, date_ts=1_700_000_000.0 + i)

    token = client.get("/api/messages", params={"page_size": 10}).json()["next_cursor"]["token"]
    expected = client.get("/api/messages", params={"page_size": 10, "cursor": token}).json()
    body = client.get("/api/messages", params={
        "page_size": 10, "cursor": token,
        "before_id": 10 ** 6, "before_ts": 1_700_000_000.0 + 999}).json()
    assert body["total"] is None
    assert [m["id"] for m in body["items"]] == [m["id"] for m in expected["items"]]


def test_next_cursor_keeps_legacy_fields_and_adds_token(clean_db, client):
    acc = _mk_account(clean_db)
    for i in range(25):
        _insert(clean_db, acc, i + 1, date_ts=1_700_000_000.0 + i)

    cursor = client.get("/api/messages", params={"page_size": 10}).json()["next_cursor"]
    assert set(cursor) == {"before_ts", "before_id", "token"}
    assert isinstance(cursor["token"], str) and cursor["token"]
    assert "=" not in cursor["token"]
    # token 解出来的位置与旧字段一致
    assert mailhub_app._decode_cursor(cursor["token"]) == (cursor["before_ts"], cursor["before_id"])


@pytest.mark.parametrize("token", [
    "abc",                       # 不是合法 base64
    "====",                      # 只有填充
    _b64(" "),                   # 解出来是空白，没有字段
    _b64("|"),                   # 两个字段都是空
    _b64("1792951496.5|0"),      # id 必须是正数
    _b64("abc|5"),               # 时间戳不是数字
])
def test_invalid_cursor_tokens_return_400(clean_db, client, token):
    response = client.get("/api/messages", params={"cursor": token})
    assert response.status_code == 400
    assert "游标" in response.json()["detail"]

