"""账单分级：交易画像、定级策略、正文占位修正、治理集成与接口。全部为合成数据。"""
import asyncio
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest  # noqa: E402

import ai  # noqa: E402
import app as mailhub_app  # noqa: E402
import billing  # noqa: E402
import db  # noqa: E402
import governance  # noqa: E402
import mail_client  # noqa: E402
import policy  # noqa: E402
import sync  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ---------------- 合成账单语料 ----------------
# 每条：标题、正文、期望等级、期望画像（只列出关心的维度）

BILL_CORPUS = [
    ("market-sale-cn", "您在社区市场上售出了一件物品",
     "您在社区市场中上架的一件物品已售出。您的钱包已获得 4.07 UAH。这封电子邮件将作为您的收据。"
     "您也可以随时在线查看您的消费历史记录。总计： 4.07 UAH",
     "一般", {"direction": "in", "initiator": "other"}),
    ("market-sale-en", "You have sold an item on the Community Market",
     "An item you listed has been sold. Your wallet has been credited. Total: $0.12",
     "一般", {"direction": "in"}),
    ("game-purchase-cn", "感谢您在商店的购买！",
     "以下物品已添加到您的游戏库。您已获得商店点数 110。小计： 41₴ 总计： 49₴ 支付方式： 商店钱包 "
     "该笔交易您共计花费： 49₴",
     "重要", {"direction": "out", "initiator": "self", "acquired": "entitlement"}),
    ("game-purchase-en", "Thank you for your purchase",
     "The following games were added to your library. Order total: €19,99. Payment method: Visa",
     "重要", {"direction": "out", "initiator": "self", "acquired": "entitlement", "channel": "real"}),
    ("refund", "您的购买已退款", "我们已将款项退还到您的钱包。退款总计： 184₴", "一般",
     {"direction": "refund"}),
    ("free-claim", "Free items added to your library.", "Enjoy your free games.", "一般",
     {"direction": "none"}),
    ("top-up", "[Hub] 余额充值成功", "您已成功充值 50 元，当前余额 80 元", "重要",
     {"direction": "out", "initiator": "self", "acquired": "balance"}),
    ("payment-failed", "Payment failed for your subscription",
     "We were unable to charge your card ending in 4242. Please update your payment method.",
     "重要", {"action": True}),
    ("subscription-attention", "您的订阅需要处理", "您的付款方式已失效，请更新付款方式。", "重要",
     {"action": True}),
    ("auto-renew", "Your subscription has been renewed",
     "We charged $9.99 to your card. Your Premium plan renews monthly.", "重要",
     {"initiator": "auto", "acquired": "service"}),
    ("order-cn", "已收到您的订单", "订单金额 ¥89.00，我们会尽快发货", "重要",
     {"direction": "out", "initiator": "self"}),
    ("cloud-bill", "Your invoice is available", "Amount due charged to your card: US$1,280.00", "重要",
     {"cny": 9216.0}),
    ("payout", "Your payout has been sent", "You received a payout of $15.00 to your bank account",
     "一般", {"direction": "in"}),
    ("cashback", "返现已到账", "您的账户已收到返现 2.30 元", "一般", {"direction": "in"}),
    ("purchase-en-brand-word", "Thank you for your Store purchase!",
     "The items below have been added to your Store library. Total: $5", "重要",
     {"initiator": "self", "acquired": "entitlement"}),
    ("receipt-generic-items", "Your receipt",
     "Order items: Game Deluxe Edition. Order total: $20.00 Payment method: PayPal", "重要",
     {"direction": "out", "channel": "real"}),
    ("refund-policy-footer", "您已更新订阅", "若欲了解退款流程详情，请前往帮助中心。您的会员将于下月续订",
     "重要", {"direction": "unknown"}),
]


@pytest.mark.parametrize("case_id, subject, body, tier, facets", BILL_CORPUS,
                         ids=[c[0] for c in BILL_CORPUS])
def test_bill_corpus(case_id, subject, body, tier, facets):
    got = billing.extract_facets(subject, body)
    for key, value in facets.items():
        assert got[key] == value, f"{case_id}: {key}={got[key]!r}"
    decided, reason = billing.decide_tier(got)
    assert decided == tier, f"{case_id}: {reason}"
    assert reason


def test_policy_code_is_not_tied_to_any_brand_or_sender():
    """定级只依赖通用的交易画像：策略代码里不允许出现品牌名或发件域名。"""
    source = open(os.path.join(ROOT, "billing.py"), encoding="utf-8").read().lower()
    for brand in ("steam", "valve", "google", "apple store", "epic", "gog", "jd.com", "taobao",
                  "alipay.com", "amazon", "playstation", "xbox", "nintendo"):
        assert brand not in source, brand
    assert not re.search(r"[a-z0-9-]+\.(com|net|org|cn|top|io|xyz)\b", source)


@pytest.mark.parametrize("text, expected", [
    ("Order total: €1.299,00", (1299.0, "EUR")),
    ("共计 ¥1,299.50", (1299.5, "CNY")),
    ("Total 1 299,00 UAH", (1299.0, "UAH")),
    ("49₴", (49.0, "UAH")),
    ("US$12", (12.0, "USD")),
    ("RMB 300", (300.0, "CNY")),
    ("价格 2026 年", (None, None)),
    ("小计 10 元，总计 12 元，赠送 500 元代金券", (12.0, "CNY")),
])
def test_extract_amount(text, expected):
    assert billing.extract_amount(text) == expected


def test_decide_tier_rules_and_settings():
    out_self = {"direction": "out", "initiator": "self", "acquired": "unknown", "cny": 10}
    assert billing.decide_tier(out_self)[0] == "重要"
    # 用户偏好优先于一切
    assert billing.decide_tier(out_self, preference="一般")[0] == "一般"
    # 金额阈值
    big_in = {"direction": "in", "initiator": "other", "cny": 500}
    assert billing.decide_tier(big_in)[0] == "重要"
    assert billing.decide_tier(big_in, cfg={"amount_threshold": 0})[0] == "一般"
    # 自动续费、退款按设置
    renew = {"direction": "out", "initiator": "auto", "cny": 10}
    assert billing.decide_tier(renew)[0] == "重要"
    assert billing.decide_tier(renew, cfg={"auto_renew": "一般"})[0] == "一般"
    refund = {"direction": "refund", "cny": 10}
    assert billing.decide_tier(refund, cfg={"refund": "重要"})[0] == "重要"
    # 余额内买卖虚拟物品
    item = {"direction": "out", "initiator": "self", "acquired": "virtual_item", "channel": "balance"}
    assert billing.decide_tier(item)[0] == "一般"
    # 信息不足：例行流水降为一般，否则保守视为重要
    unknown = {"direction": "unknown", "initiator": "unknown", "acquired": "unknown"}
    assert billing.decide_tier(unknown)[0] == "重要"
    assert billing.decide_tier(unknown, routine_count=12)[0] == "一般"
    assert billing.decide_tier(unknown, routine_count=12, cfg={"routine_count": 0})[0] == "重要"


def test_ai_facets_only_fill_unknown_dimensions():
    local = {"direction": "out", "initiator": "unknown", "acquired": "unknown", "action": False}
    merged = billing.merge_ai_facets(
        local, {"direction": "in", "initiator": "self", "acquired": "bogus", "action": True})
    assert merged["direction"] == "out"          # 本地已识别，不被 AI 覆盖
    assert merged["initiator"] == "self"         # 本地未知，由 AI 填补
    assert merged["acquired"] == "unknown"       # 非法枚举被丢弃
    assert merged["action"] is True


def test_template_key_groups_same_kind_of_mail():
    a = billing.template_key("noreply@shop.example", "Your Order Receipt from Oct 23, 2026")
    b = billing.template_key("noreply@shop.example", "Your Order Receipt from Sep 3, 2026")
    c = billing.template_key("other@mail.example", "Your Order Receipt from Oct 23, 2026")
    d = billing.template_key("x@shop.example", "您的订单收据(2026年10月18日)")
    e = billing.template_key("x@shop.example", "您的订单收据(2026年9月1日)")
    assert a == b and a != c and d == e


def test_ai_bill_facets_are_validated(conn, monkeypatch):
    raw = json.dumps([{"id": 1, "category": "账单", "confidence": 0.9, "importance": 2,
                       "summary": "s", "reason": "r",
                       "bill": {"direction": "in", "initiator": "hacker", "action": "yes"}}])
    monkeypatch.setattr(ai, "chat", lambda *a, **k: raw)
    out = ai.classify_batch([{"id": 1, "subject": "s", "sender": "x@y.example"}])
    assert out[0]["bill"] == {"direction": "in"}


# ---------------- 正文占位修正 ----------------

def test_stub_text_part_falls_back_to_html():
    html = ("<html><body><p>以下物品已添加到您的库。</p><p>总计：49₴</p><p>支付方式：钱包</p>"
            + "<p>" + "详细说明文字。" * 40 + "</p></body></html>")
    stub = "感谢您近期的交易。要查看详情，请访问 https://shop.example/receipt?x=1"
    assert "总计：49₴" in mail_client.readable_body(stub, html)
    real = "您在市场中上架的一件物品已售出。您的钱包已获得 4.07 UAH。总计： 4.07 UAH。" * 5
    assert mail_client.readable_body(real, html) == real
    assert mail_client.readable_body("", html).strip()


# ---------------- 治理集成 ----------------

@pytest.fixture
def conn():
    db.init_db()
    c = db.get_conn()
    for t in ("governance_decisions", "governance_runs", "messages", "accounts", "audit_log",
              "bill_prefs"):
        c.execute(f"DELETE FROM {t}")
    for k in ("bill_amount_threshold", "bill_routine_count", "bill_auto_renew", "bill_refund",
              "clean_bill_minor_days", "clean_otp_days"):
        c.execute("DELETE FROM settings WHERE k=?", (k,))
    c.commit()
    yield c


def _account(c):
    cur = c.execute(
        """INSERT INTO accounts(name, provider, email, imap_host, imap_port, auth_type, enabled, created_ts)
           VALUES('acc','custom','a@example.com','imap.example.com',993,'password',1,0)""")
    c.commit()
    return cur.lastrowid


def _bill(c, acc, uid, subject, body, sender="noreply@shop.example", **kw):
    cur = c.execute(
        """INSERT INTO messages(account_id, uid, subject, sender_addr, date_ts, category, body_text,
                                snippet, importance, has_attach, template_key, deleted_ts)
           VALUES(?,?,?,?,?,?,?,?,?,?,?,0)""",
        (acc, uid, subject, sender, kw.get("date_ts", time.time() - 3600), kw.get("category", "账单"),
         body, body[:200], kw.get("importance", 0), kw.get("has_attach", 0),
         billing.template_key(sender, subject)))
    c.commit()
    return cur.lastrowid


def test_governance_assigns_tier_and_keeps_reason_out_of_ui_fields(conn, monkeypatch):
    acc = _account(conn)
    sale = _bill(conn, acc, 1, "您在社区市场上售出了一件物品", "物品已售出。您的钱包已获得 4.07 UAH。")
    buy = _bill(conn, acc, 2, "感谢您的购买！", "以下物品已添加到您的游戏库。共计花费 49₴")
    monkeypatch.setattr(governance.ai, "ai_config", lambda: {"enabled": False})

    governance.run_governance(trigger="test")
    rows = {r["id"]: dict(r) for r in conn.execute(
        "SELECT id, bill_tier, bill_facets, importance, governance_reason, ai_reason FROM messages")}
    assert rows[sale]["bill_tier"] == "一般" and rows[sale]["importance"] <= 2
    assert rows[buy]["bill_tier"] == "重要" and rows[buy]["importance"] >= 3
    assert json.loads(rows[sale]["bill_facets"])["direction"] == "in"
    # 定级理由只进审计证据，不进界面会展示的字段
    for row in rows.values():
        assert "定级" not in row["governance_reason"] and "收入" not in row["governance_reason"]
        assert "收入" not in row["ai_reason"]
    evidence = json.loads(conn.execute(
        "SELECT evidence FROM governance_decisions WHERE message_id=?", (sale,)).fetchone()["evidence"])
    assert evidence["bill_tier"] == "一般" and evidence["bill_reason"]
    assert "物品" not in json.dumps(evidence, ensure_ascii=False)   # 证据里没有原文


def test_ai_bill_facets_fill_gaps_in_governance(conn, monkeypatch):
    acc = _account(conn)
    mid = _bill(conn, acc, 1, "Transaction 950178953", "Reference 950178953")
    monkeypatch.setattr(governance.ai, "ai_config", lambda: {"enabled": True, "scope": "all"})
    monkeypatch.setattr(governance.ai, "classify_batch", lambda mails: [
        {"id": m["id"], "category": "账单", "confidence": 0.9, "importance": 2, "summary": "",
         "reason": "", "bill": {"direction": "in", "initiator": "other"}, "needs_review": False}
        for m in mails])
    governance.run_governance(trigger="test")
    row = conn.execute("SELECT bill_tier, bill_facets FROM messages WHERE id=?", (mid,)).fetchone()
    assert row["bill_tier"] == "一般"
    assert json.loads(row["bill_facets"])["direction"] == "in"


def test_non_bill_mail_has_no_tier(conn, monkeypatch):
    acc = _account(conn)
    mid = _bill(conn, acc, 1, "周报", "本周进展", category="通知")
    monkeypatch.setattr(governance.ai, "ai_config", lambda: {"enabled": False})
    governance.run_governance(trigger="test")
    assert conn.execute("SELECT bill_tier FROM messages WHERE id=?", (mid,)).fetchone()["bill_tier"] == ""


def test_user_override_applies_to_similar_bills(conn):
    acc = _account(conn)
    first = _bill(conn, acc, 1, "[Hub] 余额充值成功", "您已成功充值 50 元")
    second = _bill(conn, acc, 2, "[Hub] 余额充值成功", "您已成功充值 30 元")
    other = _bill(conn, acc, 3, "感谢您的购买！", "以下物品已添加到您的游戏库。共计花费 49₴")
    governance.retier_bills(conn, only_missing=True)
    assert {r["bill_tier"] for r in conn.execute("SELECT bill_tier FROM messages")} == {"重要"}

    result = asyncio.run(mailhub_app.set_bill_tier(first, mailhub_app.BillTierBody(tier="一般")))
    assert result["similar_updated"] == 1
    tiers = {r["id"]: (r["bill_tier"], r["bill_tier_locked"]) for r in conn.execute(
        "SELECT id, bill_tier, bill_tier_locked FROM messages")}
    assert tiers[first] == ("一般", 1) and tiers[second] == ("一般", 0) and tiers[other][0] == "重要"
    # 以后新到的同类邮件也按偏好定级
    third = _bill(conn, acc, 4, "[Hub] 余额充值成功", "您已成功充值 20 元")
    governance.retier_bills(conn, only_missing=True)
    assert conn.execute("SELECT bill_tier FROM messages WHERE id=?", (third,)).fetchone()["bill_tier"] == "一般"
    # 手动锁定的那封不受之后的策略变化影响
    asyncio.run(mailhub_app.put_settings(mailhub_app.SettingsBody(bill_amount_threshold=1)))
    assert conn.execute("SELECT bill_tier FROM messages WHERE id=?", (first,)).fetchone()["bill_tier"] == "一般"


def test_bill_tier_endpoint_rejects_non_bills_and_bad_tier(conn):
    acc = _account(conn)
    mid = _bill(conn, acc, 1, "周报", "本周进展", category="通知")
    with pytest.raises(mailhub_app.HTTPException):
        asyncio.run(mailhub_app.set_bill_tier(mid, mailhub_app.BillTierBody(tier="一般")))
    with pytest.raises(mailhub_app.HTTPException):
        asyncio.run(mailhub_app.set_bill_tier(mid, mailhub_app.BillTierBody(tier="普通")))


def test_settings_change_retiers_bills(conn):
    acc = _account(conn)
    mid = _bill(conn, acc, 1, "Your subscription has been renewed", "We charged $9.99 to your card.")
    governance.retier_bills(conn, only_missing=True)
    assert conn.execute("SELECT bill_tier FROM messages WHERE id=?", (mid,)).fetchone()["bill_tier"] == "重要"
    asyncio.run(mailhub_app.put_settings(mailhub_app.SettingsBody(bill_auto_renew="一般")))
    assert conn.execute("SELECT bill_tier FROM messages WHERE id=?", (mid,)).fetchone()["bill_tier"] == "一般"
    with pytest.raises(mailhub_app.HTTPException):
        asyncio.run(mailhub_app.put_settings(mailhub_app.SettingsBody(bill_refund="随便")))


def test_minor_bills_lose_category_protection_only():
    minor = {"category": "账单", "bill_tier": "一般"}
    assert policy.is_protected(minor) == (False, "")
    assert policy.is_protected({"category": "账单", "bill_tier": "重要"})[0]
    assert policy.is_protected({"category": "账单"})[0]
    assert policy.is_protected(dict(minor, has_attach=1))[0]


def test_auto_clean_removes_old_minor_bills_only(conn):
    acc = _account(conn)
    old = time.time() - 40 * 86400
    minor = _bill(conn, acc, 1, "您在社区市场上售出了一件物品", "钱包已获得 4.07 UAH", date_ts=old)
    keep = _bill(conn, acc, 2, "感谢您的购买！", "已添加到您的游戏库。共计花费 49₴", date_ts=old)
    attach = _bill(conn, acc, 3, "您在社区市场上售出了一件物品", "钱包已获得 1 UAH", date_ts=old, has_attach=1)
    governance.retier_bills(conn, only_missing=True)
    db.set_setting("clean_bill_minor_days", "30")
    sync.auto_clean()
    deleted = {r["id"] for r in conn.execute("SELECT id FROM messages WHERE deleted_ts > 0")}
    assert deleted == {minor}
    assert keep not in deleted and attach not in deleted


def test_overview_and_inbox_split_bills(conn):
    acc = _account(conn)
    _bill(conn, acc, 1, "您在社区市场上售出了一件物品", "钱包已获得 4.07 UAH")
    _bill(conn, acc, 2, "感谢您的购买！", "已添加到您的游戏库。共计花费 49₴")
    _bill(conn, acc, 3, "旧账单", "无法识别")          # 未定级的旧账单
    governance.retier_bills(conn, template=billing.template_key("noreply@shop.example", "您在社区市场上售出了一件物品"))
    governance.retier_bills(conn, template=billing.template_key("noreply@shop.example", "感谢您的购买！"))
    data = asyncio.run(mailhub_app.overview())
    assert data["stats"]["by_category"] == {"一般账单": 1, "重要账单": 2}
    assert "账单" not in data["categories"] and "重要账单" in data["categories"]
    page = asyncio.run(mailhub_app.list_messages(category="账单", bill_tier="一般"))
    assert page["total"] == 1 and page["items"][0]["bill_tier"] == "一般"


def test_readable_body_backfill_runs_once(conn):
    acc = _account(conn)
    html = "<p>以下物品已添加到您的库。</p><p>总计：49₴</p>" + "<p>说明</p>" * 120
    cur = conn.execute(
        """INSERT INTO messages(account_id, uid, subject, sender_addr, date_ts, category, body_text,
                                body_html, snippet, deleted_ts)
           VALUES(?,?,?,?,?,?,?,?,?,0)""",
        (acc, 1, "感谢您的购买！", "noreply@shop.example", time.time(), "账单",
         "要查看详情，请访问 https://shop.example/r", html, "要查看详情"))
    conn.commit()
    db.set_setting("body_readable_v1", "0")
    assert sync.backfill_readable_bodies() == 1
    row = conn.execute("SELECT body_text, snippet FROM messages WHERE id=?", (cur.lastrowid,)).fetchone()
    assert "总计：49₴" in row["body_text"] and "总计" in row["snippet"]
    assert sync.backfill_readable_bodies() == 0
