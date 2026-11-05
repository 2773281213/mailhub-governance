"""事件循环收尾：重活移出事件循环、账单批量定级口径、撤销语义如实标注。

全部为合成数据；数据库来自 tests/conftest.py 指定的临时目录。
"""
import asyncio
import json
import os
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest  # noqa: E402

import app as mailhub_app  # noqa: E402
import billing  # noqa: E402
import db  # noqa: E402
import governance  # noqa: E402

# 只含枚举的画像：direction=other 时定级会一路落到「例行流水 / 信息不足」两条规则，
# 这样同类封数才真正决定结果，便于验证批量统计与逐封统计的口径一致。
OTHER_FACETS = {
    "direction": "other", "initiator": "other", "acquired": "unknown", "action": False,
    "channel": "unknown", "amount": None, "currency": None, "cny": None,
}


# ---------------- 夹具 ----------------

@pytest.fixture
def conn():
    """干净的业务表 + 固定的账单阈值设置（照 tests/test_billing.py 的写法）。"""
    db.init_db()
    c = db.get_conn()
    for t in ("governance_decisions", "governance_runs", "messages", "accounts",
              "audit_log", "bill_prefs"):
        c.execute(f"DELETE FROM {t}")
    for k in ("bill_amount_threshold", "bill_routine_count", "bill_auto_renew", "bill_refund"):
        c.execute("DELETE FROM settings WHERE k=?", (k,))
    c.commit()
    # 同类 3 封即视为例行流水
    db.set_setting("bill_routine_count", "3")
    yield c


def _account(c):
    cur = c.execute(
        """INSERT INTO accounts(name, provider, email, imap_host, imap_port, auth_type, enabled, created_ts)
           VALUES('acc','custom','a@example.com','imap.example.com',993,'password',1,0)""")
    c.commit()
    return cur.lastrowid


def _bill(c, acc, uid, subject, body, sender="noreply@shop.example", **kw):
    """插入一封账单（或通知）邮件；facets 传了就当已存画像写入，供 reuse_facets 使用。"""
    key = kw.get("template_key")
    if key is None:
        key = billing.template_key(sender, subject)
    facets = kw.get("facets")
    cur = c.execute(
        """INSERT INTO messages(account_id, uid, subject, sender_addr, date_ts, category,
                                body_text, snippet, importance, has_attach, template_key,
                                bill_tier, bill_facets, bill_tier_locked, deleted_ts, unread)
           VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        (acc, uid, subject, sender, kw.get("date_ts", time.time() - 3600),
         kw.get("category", "账单"), body, body[:200], kw.get("importance", 0),
         kw.get("has_attach", 0), key, kw.get("bill_tier", ""),
         json.dumps(facets, ensure_ascii=False) if facets else "{}",
         kw.get("bill_tier_locked", 0), kw.get("deleted_ts", 0), kw.get("unread", 1)))
    c.commit()
    return cur.lastrowid


# ---------------- 1. 批量定级与逐封定级结果一致 ----------------

def _pending_rows(c):
    """retier_bills 会处理的账单，取数口径与它保持一致。"""
    return [dict(r) for r in c.execute(
        f"""SELECT id, subject, sender_addr, substr(body_text, 1, {governance.BODY_SCAN_CHARS}) AS body_text,
                   importance, template_key, bill_tier, bill_facets, bill_tier_locked
            FROM messages WHERE category='账单' AND deleted_ts=0 AND bill_tier_locked=0""").fetchall()]


def _reference_result(c):
    """参考实现：逐封调用 decide_bill、不传批量缓存（即改动前 retier_bills 的写法）。"""
    cfg = governance.bill_config()
    now = time.time()
    out = {}
    for row in _pending_rows(c):
        bill = governance.decide_bill(c, row, cfg=cfg, now=now, reuse_facets=True)
        out[row["id"]] = {
            "tier": bill["tier"],
            "facets": bill["facets"],
            "importance": billing.tier_importance(
                bill["tier"], bill["facets"], int(row["importance"] or 0)),
            "reason": bill["reason"],
        }
    return out


def _seed_bills(c, acc):
    """约 30 封待定级账单：多模板、部分有偏好、部分在 30 天窗口外、含回收站行与手动锁定行。"""
    now = time.time()
    out = {"pending": 0}

    # A. 窗口内同模板 3 封（其中 1 封在回收站）+ 窗口外 1 封：
    #    统计只看窗口内、且不过滤回收站，所以是 3 封 ≥ 阈值 3 → 例行流水。
    #    只要改成「过滤回收站」或「不看时间窗口」，这组的等级就会变，用例能发现。
    for i in range(3):
        _bill(c, acc, 100 + i, "月度账单提醒", "本期应还金额已生成", sender="billing@shop.example",
              date_ts=now - 3600 * (i + 1), facets=OTHER_FACETS,
              deleted_ts=now - 60 if i == 0 else 0)
        if i:
            out["pending"] += 1
    _bill(c, acc, 110, "月度账单提醒", "上期账单归档", sender="billing@shop.example",
          date_ts=now - 40 * 86400, facets=OTHER_FACETS, deleted_ts=now - 60)

    # B. 窗口内 5 封真实购买账单：定级自己就能定（与同类封数无关）
    for i in range(5):
        _bill(c, acc, 200 + i, "感谢您的购买！", "以下物品已添加到您的游戏库。共计花费 49₴",
              sender="store@example.com", date_ts=now - 7200 * (i + 1))
        out["pending"] += 1

    # C. 4 封在 30 天窗口外的旧账单：同类统计为 0，回落到「信息不足」
    for i in range(4):
        _bill(c, acc, 300 + i, "旧账单通知", "历史账单归档", sender="old@example.com",
              date_ts=now - 40 * 86400 - 60 * i, facets=OTHER_FACETS)
        out["pending"] += 1

    # D. 有用户偏好的两个模板，各 3 封：偏好优先于任何策略
    minor_key = billing.template_key("minor@example.com", "常规订阅扣款")
    major_key = billing.template_key("major@example.com", "重要订阅扣款")
    c.execute("INSERT INTO bill_prefs(template_key, tier, created_ts) VALUES(?,?,?)",
              (minor_key, "一般", now))
    c.execute("INSERT INTO bill_prefs(template_key, tier, created_ts) VALUES(?,?,?)",
              (major_key, "重要", now))
    for i in range(3):
        _bill(c, acc, 400 + i, "常规订阅扣款", "本期扣款明细", sender="minor@example.com",
              date_ts=now - 3600 * (i + 1), facets=OTHER_FACETS)
        out["pending"] += 1
    for i in range(3):
        _bill(c, acc, 410 + i, "重要订阅扣款", "本期扣款明细", sender="major@example.com",
              date_ts=now - 3600 * (i + 1), facets=OTHER_FACETS)
        out["pending"] += 1

    # E. 偏好里存了非法等级：按没偏好处理（口径必须与 _preference 一致）
    bad_key = billing.template_key("bad@example.com", "待确认账单")
    c.execute("INSERT INTO bill_prefs(template_key, tier, created_ts) VALUES(?,?,?)",
              (bad_key, "普通", now))
    for i in range(2):
        _bill(c, acc, 500 + i, "待确认账单", "请核对本期账目", sender="bad@example.com",
              date_ts=now - 3600 * (i + 1), facets=OTHER_FACETS)
        out["pending"] += 1

    # F. 用户手动锁定的账单：不在待定级范围里，必须原样保留
    out["locked"] = _bill(c, acc, 600, "手动锁定账单", "用户已手动设为一般", sender="locked@example.com",
                          bill_tier="一般", bill_tier_locked=1, date_ts=now - 3600)

    # G. 各种真实语料的账单，凑够约 30 封待定级
    extra = [
        ("您在社区市场上售出了一件物品", "物品已售出。您的钱包已获得 4.07 UAH。"),
        ("Your subscription has been renewed", "We charged $9.99 to your card."),
        ("您的购买已退款", "我们已将款项退还到您的钱包。退款总计： 184₴"),
        ("Your invoice is available", "Amount due charged to your card: US$1,280.00"),
        ("Payment failed for your subscription", "We were unable to charge your card."),
        ("[Hub] 余额充值成功", "您已成功充值 50 元，当前余额 80 元"),
        ("Free items added to your library.", "Enjoy your free games."),
        ("您的商店收据", "谢谢您的购买。总计: $0.00 HKD"),
        ("Your payout has been sent", "You received a payout of $15.00 to your bank account"),
        ("Transaction 950178953", "Reference 950178953"),
        ("您的订阅需要处理", "您的付款方式已失效，请更新付款方式。"),
    ]
    for i, (subject, body) in enumerate(extra):
        _bill(c, acc, 700 + i, subject, body, sender=f"spam{i}@example.com",
              date_ts=now - 1800 * (i + 1))
        out["pending"] += 1
    return out


def test_retier_bills_matches_per_message_decide(conn):
    """批量预取统计/偏好之后，结果必须与逐封查库完全一致。"""
    acc = _account(conn)
    seeded = _seed_bills(conn, acc)
    expected = _reference_result(conn)
    assert len(expected) == seeded["pending"] == 30

    governance.retier_bills(conn)

    rows = {r["id"]: dict(r) for r in conn.execute(
        "SELECT id, bill_tier, bill_facets, importance FROM messages")}
    for mid, want in expected.items():
        got = rows[mid]
        assert got["bill_tier"] == want["tier"], (mid, want["reason"])
        assert json.loads(got["bill_facets"]) == want["facets"], mid
        assert got["importance"] == want["importance"], mid

    # 手动锁定的那封不受影响
    locked = rows[seeded["locked"]]
    assert (locked["bill_tier"], locked["bill_facets"]) == ("一般", "{}")

    # 回收站里的同类邮件也要计入统计（原 _routine_count 不过滤 deleted_ts），
    # 窗口外的同类邮件不计入（3 封而不是 4 封）
    reasons = [w["reason"] for w in expected.values()]
    assert any("例行流水" in reason for reason in reasons)
    assert any("近 30 天 3 封" in reason for reason in reasons)


# ---------------- 2. 定级查询数不随账单数增长 ----------------

def _count_selects(c, fn):
    """统计 fn 执行期间该连接上发出的 SELECT 条数。"""
    seen = []
    c.set_trace_callback(lambda sql: seen.append(sql))
    try:
        fn()
    finally:
        c.set_trace_callback(None)
    return sum(1 for sql in seen if sql.lstrip().upper().startswith("SELECT"))


def test_retier_bills_query_count_does_not_grow_with_bills(conn):
    acc = _account(conn)
    for i in range(3):
        _bill(conn, acc, 200 + i, "感谢您的购买！", "以下物品已添加到您的游戏库。共计花费 49₴")
    few = _count_selects(conn, lambda: governance.retier_bills(conn))

    for i in range(3, 30):
        _bill(conn, acc, 200 + i, "感谢您的购买！", "以下物品已添加到您的游戏库。共计花费 49₴")
    many = _count_selects(conn, lambda: governance.retier_bills(conn))

    assert few == many


# ---------------- 3. 重活不在事件循环线程上 ----------------

def _spy_retier(monkeypatch, idents):
    real = governance.retier_bills

    def spy(c, **kw):
        idents.append(threading.get_ident())
        return real(c, **kw)

    monkeypatch.setattr(governance, "retier_bills", spy)


def test_set_bill_tier_retiers_off_event_loop(conn, monkeypatch):
    acc = _account(conn)
    first = _bill(conn, acc, 1, "[Hub] 余额充值成功", "您已成功充值 50 元")
    _bill(conn, acc, 2, "[Hub] 余额充值成功", "您已成功充值 30 元")

    idents = []
    _spy_retier(monkeypatch, idents)

    result = asyncio.run(mailhub_app.set_bill_tier(
        first, mailhub_app.BillTierBody(tier="一般")))

    assert result["ok"] is True and result["similar_updated"] >= 1
    assert idents and idents[0] != threading.get_ident()


def test_put_settings_retiers_off_event_loop(conn, monkeypatch):
    acc = _account(conn)
    mid = _bill(conn, acc, 1, "Your subscription has been renewed",
                "We charged $9.99 to your card.")

    idents = []
    _spy_retier(monkeypatch, idents)

    asyncio.run(mailhub_app.put_settings(mailhub_app.SettingsBody(bill_auto_renew="一般")))

    assert idents and idents[0] != threading.get_ident()
    assert conn.execute("SELECT bill_tier FROM messages WHERE id=?",
                        (mid,)).fetchone()["bill_tier"] == "一般"


def test_delete_account_off_event_loop(conn, monkeypatch):
    acc = _account(conn)
    _bill(conn, acc, 1, "周报", "本周进展", category="通知")

    idents = []
    real_get_conn = mailhub_app.get_conn

    def spy_get_conn():
        idents.append(threading.get_ident())
        return real_get_conn()

    monkeypatch.setattr(mailhub_app, "get_conn", spy_get_conn)

    asyncio.run(mailhub_app.delete_account(acc))

    assert idents and idents[0] != threading.get_ident()


def test_governance_status_off_event_loop(monkeypatch):
    idents = []
    real_status = governance.governance_status

    def spy_status():
        idents.append(threading.get_ident())
        return real_status()

    monkeypatch.setattr(governance, "governance_status", spy_status)

    data = asyncio.run(mailhub_app.governance_status())

    assert data["version"] == governance.VERSION
    assert idents and idents[0] != threading.get_ident()


# ---------------- 4. 删除账户：邮件一起删掉，审计照常写 ----------------

def test_delete_account_removes_messages_and_writes_audit(conn):
    acc = _account(conn)
    _bill(conn, acc, 1, "周报", "本周进展", category="通知")
    _bill(conn, acc, 2, "验证码", "您的验证码是 123456", category="验证码")

    asyncio.run(mailhub_app.delete_account(acc))

    assert conn.execute("SELECT COUNT(*) AS c FROM messages WHERE account_id=?",
                        (acc,)).fetchone()["c"] == 0
    assert conn.execute("SELECT COUNT(*) AS c FROM accounts WHERE id=?",
                        (acc,)).fetchone()["c"] == 0
    row = conn.execute(
        "SELECT * FROM audit_log WHERE action='delete_account' ORDER BY id DESC LIMIT 1").fetchone()
    assert row is not None
    assert json.loads(row["target_ids"]) == [acc]
    assert "本地邮件一并删除" in row["reason"]

    with pytest.raises(mailhub_app.HTTPException) as exc_info:
        asyncio.run(mailhub_app.delete_account(acc))
    assert exc_info.value.status_code == 404


# ---------------- 5. 服务器端已读/未读的撤销语义 ----------------

def test_server_side_read_is_marked_irreversible(conn, monkeypatch):
    acc = _account(conn)
    first = _bill(conn, acc, 1, "通知一", "内容", category="通知")
    second = _bill(conn, acc, 2, "通知二", "内容", category="通知")
    monkeypatch.setattr(mailhub_app, "_server_op", lambda ids, _action: (list(ids), []))

    result = asyncio.run(mailhub_app.batch_messages(mailhub_app.BatchBody(
        ids=[first, second], action="read", server=True)))

    assert result["ok"] is True and result["count"] == 2
    assert result["reversible"] is False
    row = conn.execute("SELECT * FROM audit_log WHERE id=?", (result["audit_id"],)).fetchone()
    assert row["reversible"] == 0
    assert row["undo_payload"] == ""
    assert "不提供撤销" in row["reason"]
    # 本地状态照常写入
    assert {r["unread"] for r in conn.execute(
        "SELECT unread FROM messages WHERE id IN (?,?)", (first, second))} == {0}

    # 撤销接口如实拒绝
    with pytest.raises(mailhub_app.HTTPException) as exc_info:
        asyncio.run(mailhub_app.undo_action(result["audit_id"]))
    assert exc_info.value.status_code == 400


def test_server_side_unread_is_marked_irreversible(conn, monkeypatch):
    acc = _account(conn)
    mid = _bill(conn, acc, 1, "通知", "内容", category="通知", unread=0)
    monkeypatch.setattr(mailhub_app, "_server_op", lambda ids, _action: (list(ids), []))

    result = asyncio.run(mailhub_app.batch_messages(mailhub_app.BatchBody(
        ids=[mid], action="unread", server=True)))

    assert result["reversible"] is False
    row = conn.execute("SELECT * FROM audit_log WHERE id=?", (result["audit_id"],)).fetchone()
    assert row["reversible"] == 0 and row["undo_payload"] == ""
    assert "不提供撤销" in row["reason"]


def test_server_side_read_failure_does_not_claim_sync(conn, monkeypatch):
    """服务器全部失败等于什么都没同步：既不可撤销，也不能写成「已同步到邮件服务器」。"""
    acc = _account(conn)
    mid = _bill(conn, acc, 1, "通知", "内容", category="通知")
    monkeypatch.setattr(mailhub_app, "_server_op",
                        lambda _ids, _action: ([], ["账户1: unavailable"]))

    result = asyncio.run(mailhub_app.batch_messages(mailhub_app.BatchBody(
        ids=[mid], action="read", server=True)))

    assert result["ok"] is False and result["count"] == 0
    assert result["reversible"] is False
    row = conn.execute("SELECT * FROM audit_log WHERE id=?", (result["audit_id"],)).fetchone()
    assert row["reversible"] == 0 and row["undo_payload"] == ""
    assert "已同步到邮件服务器" not in row["reason"]


def test_local_only_read_stays_reversible_and_undo_restores(conn):
    acc = _account(conn)
    mid = _bill(conn, acc, 1, "通知", "内容", category="通知")

    result = asyncio.run(mailhub_app.batch_messages(mailhub_app.BatchBody(
        ids=[mid], action="read", server=False)))

    assert result["reversible"] is True
    row = conn.execute("SELECT * FROM audit_log WHERE id=?", (result["audit_id"],)).fetchone()
    assert row["reversible"] == 1
    assert "不提供撤销" not in row["reason"]
    assert json.loads(row["undo_payload"]) == {"unread": {str(mid): 1}}
    assert conn.execute("SELECT unread FROM messages WHERE id=?", (mid,)).fetchone()["unread"] == 0

    undone = asyncio.run(mailhub_app.undo_action(result["audit_id"]))
    assert undone == {"ok": True, "restored": 1}
    assert conn.execute("SELECT unread FROM messages WHERE id=?", (mid,)).fetchone()["unread"] == 1
