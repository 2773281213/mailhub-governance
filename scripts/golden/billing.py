"""导出 billing.py（交易画像、模板键、定级策略）的金标语料。

Kotlin 端在 com.mailhub.core.billing.Billing 里重写，必须与这里的每条用例逐字一致，
尤其是定级理由的中文措辞和金额的 Python 数值格式（`f"{cny:g}"`）。

    python scripts/golden/billing.py           # 重新生成 JSON
    python scripts/golden/billing.py --check   # 检查是否与当前 Python 实现一致

用例来源：
- tests/test_billing.py 里所有 billing.* 调用的输入与输出（BILL_CORPUS、金额写法、
  定级策略、AI 画像合并、模板键）；
- 任务要求的补充语料：金额的各种写法与全角/细空格千分位、超 1000 万、未知币种、
  多个金额与「总计」提示词的距离；每个direction / initiator / acquired / channel
  的中英文样例；退款只出现在正文；免费物品；他人购买；自动续费；需要处理；
  template_key 的括号编号、月份星期、数字、中文标点、超长主题截断、大写域名、
  没有 @ 的发件人；decide_tier 的每条策略分支与阈值边界、cfg 的字符串/整数/浮点/非法值、
  超出 Long 范围的大整数与非有限值（nan / inf 字符串）、定级理由里 100000 这类整数的 %g 写法；
  merge_ai_facets 在已有 ai_filled 列表上追加。
"""
from __future__ import annotations

import copy
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _common  # noqa: E402

_common.prepare_imports()

import billing  # noqa: E402

FN = {
    "extract_amount": billing.extract_amount,
    "to_cny": billing.to_cny,
    "extract_facets": billing.extract_facets,
    "merge_ai_facets": billing.merge_ai_facets,
    "template_key": billing.template_key,
    "decide_tier": billing.decide_tier,
    "tier_importance": billing.tier_importance,
    "stored_facets": billing.stored_facets,
}

CASES: list[dict] = []


def _plain(value):
    """元组在 JSON 里写成数组；dict 原样留下（json.dumps 处理）。"""
    return list(value) if isinstance(value, tuple) else value


def add(fn: str, cid: str, **args) -> None:
    """按 fn 的名字调用 Python 原实现，把输入和实际输出都记下来。

    参数先深拷贝再记下来：`merge_ai_facets` 会原地修改传入的画像（往 ai_filled 里追加），
    不拷贝的话金标里的 args 会记成被改过之后的样子。
    """
    snapshot = copy.deepcopy(args)
    CASES.append({"id": cid, "fn": fn, "args": snapshot, "expected": _plain(FN[fn](**args))})


# ---------------- tests/test_billing.py：BILL_CORPUS ----------------

BILL_CORPUS = [
    ("market-sale-cn", "您在社区市场上售出了一件物品",
     ("您在社区市场中上架的一件物品已售出。您的钱包已获得 4.07 UAH。这封电子邮件将作为您的收据。"
      "您也可以随时在线查看您的消费历史记录。总计： 4.07 UAH")),
    ("market-sale-en", "You have sold an item on the Community Market",
     "An item you listed has been sold. Your wallet has been credited. Total: $0.12"),
    ("game-purchase-cn", "感谢您在商店的购买！",
     ("以下物品已添加到您的游戏库。您已获得商店点数 110。小计： 41₴ 总计： 49₴ 支付方式： 商店钱包 "
      "该笔交易您共计花费： 49₴")),
    ("game-purchase-en", "Thank you for your purchase",
     "The following games were added to your library. Order total: €19,99. Payment method: Visa"),
    ("refund", "您的购买已退款", "我们已将款项退还到您的钱包。退款总计： 184₴"),
    ("free-claim", "Free items added to your library.", "Enjoy your free games."),
    ("top-up", "[Hub] 余额充值成功", "您已成功充值 50 元，当前余额 80 元"),
    ("payment-failed", "Payment failed for your subscription",
     "We were unable to charge your card ending in 4242. Please update your payment method."),
    ("subscription-attention", "您的订阅需要处理", "您的付款方式已失效，请更新付款方式。"),
    ("auto-renew", "Your subscription has been renewed",
     "We charged $9.99 to your card. Your Premium plan renews monthly."),
    ("order-cn", "已收到您的订单", "订单金额 ¥89.00，我们会尽快发货"),
    ("cloud-bill", "Your invoice is available", "Amount due charged to your card: US$1,280.00"),
    ("payout", "Your payout has been sent", "You received a payout of $15.00 to your bank account"),
    ("cashback", "返现已到账", "您的账户已收到返现 2.30 元"),
    ("purchase-en-brand-word", "Thank you for your Store purchase!",
     "The items below have been added to your Store library. Total: $5"),
    ("receipt-generic-items", "Your receipt",
     "Order items: Game Deluxe Edition. Order total: $20.00 Payment method: PayPal"),
    ("free-claim-zero-total", "您的商店收据",
     "谢谢您的购买。以下是您订购的物品：价格: $88.00 HKD 折扣: 特卖折扣 - $88.00 HKD 总计: $0.00 HKD"),
    ("refund-policy-footer", "您已更新订阅", "若欲了解退款流程详情，请前往帮助中心。您的会员将于下月续订"),
]


def corpus_cases() -> None:
    for cid, subject, body in BILL_CORPUS:
        add("extract_facets", f"corpus-facets-{cid}", subject=subject, body=body)
        facets = billing.extract_facets(subject, body)
        # 测试里是 decide_tier(extract_facets(...))，这里把画像铺平成参数
        add("decide_tier", f"corpus-tier-{cid}", facets=facets, routine_count=0,
            preference="", cfg=None)
        add("stored_facets", f"corpus-stored-{cid}", facets=facets)


# ---------------- 金额 ----------------

AMOUNT_TEXTS = [
    # tests/test_billing.py::test_extract_amount
    ("test-eur-de", "Order total: €1.299,00"),
    ("test-cny-comma", "共计 ¥1,299.50"),
    ("test-uah-space", "Total 1 299,00 UAH"),
    ("test-uah-sign", "49₴"),
    ("test-usd-prefix", "US$12"),
    ("test-rmb-prefix", "RMB 300"),
    ("test-year-only", "价格 2026 年"),
    ("test-hint-gift", "小计 10 元，总计 12 元，赠送 500 元代金券"),
    ("test-zero-total", "价格: $88.00 HKD 折扣: - $88.00 HKD 总计: $0.00 HKD"),
    ("test-usd-suffix", "Total: $25.00 USD"),
    # 各种写法
    ("fmt-plain-comma", "Total: $1,299.00"),
    ("fmt-decimals-short", "Total: 12,5 €"),
    ("fmt-yen-space", "价格 ¥ 88"),
    ("fmt-hkd-dollar", "应付 HK$ 3"),
    ("fmt-yuan-suffix", "已付款 88 元"),
    ("fmt-rmb-cn", "人民币 100"),
    ("fmt-rmb-space", "RMB 50"),
    ("fmt-rmb-after", "50 RMB"),
    # 全角数字
    ("wide-digits-yen", "￥９９"),
    ("wide-digits-code", "USD １２"),
    # 千分位里夹不可见空白：nbsp 会被 _to_float 去掉，\u2009 / \u3000 不会（float() 报错）
    ("nbsp-thousands", "Total 1\u00a0299,00 EUR"),
    ("thin-space-thousands", "Total 1\u2009299,00 EUR"),
    ("ideographic-space-thousands", "Total 1\u3000299,00 EUR"),
    # 超过 1000 万
    ("over-limit", "Total: ¥12,000,000.00"),
    ("just-under-limit", "Total: ¥9,999,999"),
    # 不认识的币种
    ("unknown-code", "Total: 100 XYZ"),
    ("unknown-symbol", "₦500"),
    # 一封邮件里有多个金额：总计提示词在前 / 在后 / 太远
    ("hint-first", "总计 1200 元，另附赠品 500 元"),
    ("hint-far", "总计 1200 元。以下说明与本次交易无关，请忽略后续的营销信息，赠送 500 元"),
    ("hint-later", "赠送 500 元代金券，实付 20 元"),
    ("no-currency", "Total 1,299.00"),
]


def amount_cases() -> None:
    for cid, text in AMOUNT_TEXTS:
        add("extract_amount", f"amount-{cid}", text=text)


def to_cny_cases() -> None:
    for cid, amount, currency in [
        ("usd-100", 100, "USD"),
        ("eur-float", 1299.0, "EUR"),
        ("uah-small", 49.0, "UAH"),
        ("none-amount", None, "USD"),
        ("none-currency", 100, None),
        ("empty-currency", 100, ""),
        ("unknown-currency", 100, "XYZ"),
        ("zero-cny", 0.0, "CNY"),
        ("vnd-rounds-to-zero", 1.0, "VND"),
        ("jpy-two-decimals", 1.0, "JPY"),
        ("krw-2-decimals", 3.0, "KRW"),
        ("large-usd", 1234567.89, "USD"),
        ("int-amount-uah", 4, "UAH"),
    ]:
        add("to_cny", f"cny-{cid}", amount=amount, currency=currency)


# ---------------- 交易画像：每个维度都有中文和英文 ----------------

FACET_CORPUS = [
    # direction=out（中文 / 英文）
    ("out-cn-order", "【订单】您的账单已出", "已支付 ¥120.00，订单金额 ¥120.00，感谢使用。"),
    ("out-cn-receipt", "您的消费收据", "实付 39 元，支付方式：银行卡"),
    ("out-en-receipt", "Your receipt is ready", "Amount paid: $12.00. Payment method: card ending 4242."),
    ("out-en-invoice", "Invoice for your order", "Order total: $40.00 charged to your card."),
    # direction=in（中文 / 英文）
    ("in-cn-sold", "您在社区市场上售出了一件物品",
     "您在社区市场中上架的一件物品已售出。您的钱包已获得 4.07 UAH。总计： 4.07 UAH"),
    ("in-cn-cashback", "返现已到账", "您的账户已收到返现 2.30 元"),
    ("in-en-payout", "Your payout has been sent", "You received a payout of $15.00 to your bank account"),
    ("in-en-credited", "Wallet activity", "Your wallet has been credited with $3.00"),
    # direction=refund（中文 / 英文 / 只在正文）
    ("refund-cn", "您的购买已退款", "我们已将款项退还到您的钱包。退款总计： 184₴"),
    ("refund-en", "Your refund has been issued", "We've refunded $25.00 to your card."),
    ("refund-body-only-cn", "关于您的近期交易", "我们已将款项退还到您的钱包。退款总计 30 元"),
    ("refund-body-only-en", "Transaction update",
     "Your refund has been processed and the amount was returned."),
    # direction=none（免费物品，中文 / 英文）
    ("free-cn", "免费领取会员体验", "本月免费赠送，无需支付。"),
    ("free-cn-zero", "零元购活动", "本次为零元领取，无需付费"),
    ("free-en", "Free items added to your library.", "Enjoy your free games."),
    ("free-en-no-cost", "A gift for you", "This add-on is available at no cost for a week."),
    # direction=unknown：既没有方向证据，也没有金额
    ("unknown-cn", "周报", "本周进展：完成了三件事"),
    ("unknown-en", "Weekly newsletter", "Here are this week's updates."),
    # initiator=self（中文 / 英文）
    ("self-cn", "感谢您的购买", "订单已确认，扣款成功"),
    ("self-cn-thanks", "感谢您在本店的惠顾", "您的订单已生成，请留意物流信息"),
    ("self-en", "Thank you for your order", "Your order has been placed successfully."),
    ("self-en-receipt", "Receipt for your purchase", "You purchased the annual plan."),
    # initiator=auto（中文 / 英文）
    ("auto-cn-renew", "自动续费成功通知", "您的会员已自动续费，扣款 15 元"),
    ("auto-cn-recurring", "定期扣款提醒", "本次为代扣，续费成功"),
    ("auto-en-renewed", "Your subscription has been renewed",
     "We charged $9.99 to your card. Your Premium plan renews monthly."),
    ("auto-en-recurring", "Your recurring payment receipt", "This is a recurring charge for your plan."),
    # initiator=other（他人购买 / 中文 / 英文）
    ("other-cn-buyer", "买家已付款", "买家已完成付款，请尽快发货。订单金额 ¥88.00"),
    ("other-en-market", "You have sold an item on the Community Market",
     "An item you listed has been sold. Total: $0.12"),
    # acquired=entitlement（中文 / 英文）
    ("ent-cn-library", "游戏购买确认", "以下物品已添加到您的游戏库。共计花费 49₴"),
    ("ent-cn-key", "您的激活码", "产品密钥：XXXX-YYYY，请在软件中兑换"),
    ("ent-en-library", "Thank you for your purchase", "The following games were added to your library."),
    ("ent-en-key", "Your game key is ready", "Activation key: ABCDE-12345. Ready to play."),
    # acquired=physical（中文 / 英文）
    ("phy-cn-shipped", "您的订单已发货", "订单已发货，快递单号 123，收货地址已确认"),
    ("phy-cn-delivery", "配送通知", "您的包裹正在配送中，请保持电话畅通"),
    ("phy-en-shipped", "Your order has shipped", "Shipping confirmation: your package is on the way."),
    ("phy-en-delivery", "Delivery update", "Your parcel will be delivered tomorrow."),
    # acquired=service（中文 / 英文）
    ("svc-cn-member", "会员订阅确认", "您的订阅已生效，服务期至 2027 年"),
    ("svc-cn-plan", "套餐变更成功", "当前套餐：专业版，有效期至下月"),
    ("svc-en-member", "Your membership is active", "Your subscription plan is now active."),
    ("svc-en-premium", "Premium plan confirmation", "Your premium service starts today."),
    # acquired=virtual_item（中文 / 英文）
    ("vir-cn-skin", "社区市场交易提醒", "您上架的饰品已售出，物品已售出"),
    ("vir-cn-market", "交易市场通知", "您在社区市场卖出了一张卡牌"),
    ("vir-en-item", "Community Market trade", "An in-game item you sold on the marketplace."),
    ("vir-en-trading", "Trading card completed", "Your trading card set is complete."),
    # acquired=balance（中文 / 英文）
    ("bal-cn-topup", "[Hub] 余额充值成功", "您已成功充值 50 元，当前余额 80 元"),
    ("bal-cn-wallet", "钱包余额变动", "您的钱包余额增加 20 元"),
    ("bal-en-topup", "Wallet top-up complete", "You added funds to your wallet. Balance: $20.00"),
    ("bal-en-credits", "Balance updated", "Your balance is now $12.00"),
    # channel=balance（中文 / 英文）
    ("chan-bal-cn", "购买成功", "支付方式： 商店钱包，共计花费 20₴"),
    ("chan-bal-en", "Purchase complete", "Paid with wallet balance: $5.00"),
    ("chan-bal-en-credits", "Order complete", "Paid using credits: $3.00"),
    # channel=real（中文 / 英文）
    ("chan-real-cn", "支付成功", "支付方式：支付宝，实付 30 元"),
    ("chan-real-en", "Payment received", "Payment method: Visa, amount paid $30.00"),
    # 需要处理
    ("action-cn-attention", "您的订阅需要处理", "您的付款方式已失效，请更新付款方式。"),
    ("action-cn-upcoming", "扣费提醒", "您的会员将于 3 天后自动续费，请确保余额充足。"),
    ("action-en-failed", "Payment failed for your subscription",
     "We were unable to charge your card ending in 4242. Please update your payment method."),
    ("action-en-past-due", "Your invoice is past due", "Action required: please pay the outstanding amount."),
]


def facet_cases() -> None:
    for cid, subject, body in FACET_CORPUS:
        add("extract_facets", f"facets-{cid}", subject=subject, body=body)
        facets = billing.extract_facets(subject, body)
        add("decide_tier", f"facets-tier-{cid}", facets=facets, routine_count=0,
            preference="", cfg=None)


# ---------------- merge_ai_facets ----------------

def merge_cases() -> None:
    # tests/test_billing.py::test_ai_facets_only_fill_unknown_dimensions
    add("merge_ai_facets", "merge-test-case",
        local={"direction": "out", "initiator": "unknown", "acquired": "unknown", "action": False},
        ai_facets={"direction": "in", "initiator": "self", "acquired": "bogus", "action": True})

    local_unknown = {"direction": "unknown", "initiator": "unknown", "acquired": "unknown",
                     "action": False}
    # 本地 unknown 的三个维度都被 AI 填补，顺序与 Python 的循环一致
    add("merge_ai_facets", "merge-fill-all", local=local_unknown,
        ai_facets={"direction": "in", "initiator": "auto", "acquired": "service"})
    # AI 给 unknown 不填补
    add("merge_ai_facets", "merge-ai-unknown", local=local_unknown,
        ai_facets={"direction": "unknown", "initiator": "unknown", "acquired": "unknown"})
    # 非法枚举（不在允许集合里）丢弃
    add("merge_ai_facets", "merge-invalid-enums", local=local_unknown,
        ai_facets={"direction": "sideways", "initiator": "hacker", "acquired": "bogus"})
    # 值类型不对（数字）也丢弃
    add("merge_ai_facets", "merge-wrong-types", local=local_unknown,
        ai_facets={"direction": 1, "initiator": True, "acquired": ["service"]})
    # ai_facets 为 None / 不是字典：原样返回
    add("merge_ai_facets", "merge-none", local=local_unknown, ai_facets=None)
    add("merge_ai_facets", "merge-not-dict", local=local_unknown, ai_facets="not a dict")
    add("merge_ai_facets", "merge-list", local=local_unknown, ai_facets=[["direction", "in"]])
    # 空 dict：不改变 local，也不加 ai_filled
    add("merge_ai_facets", "merge-empty-ai", local=local_unknown, ai_facets={})
    # action 只由 AI 补成 True；本地已有 action=True 时不重复记
    add("merge_ai_facets", "merge-action-true", local={"action": False}, ai_facets={"action": True})
    add("merge_ai_facets", "merge-action-already", local={"action": True}, ai_facets={"action": True})
    # Python 是 `is True`：非布尔的真值不采纳
    add("merge_ai_facets", "merge-action-string", local={"action": False},
        ai_facets={"action": "yes"})
    add("merge_ai_facets", "merge-action-one", local={"action": False}, ai_facets={"action": 1})
    # 本地 direction 已识别时不被覆盖，但 action 仍可补
    add("merge_ai_facets", "merge-keep-local", local={"direction": "refund", "action": False},
        ai_facets={"direction": "in", "action": True})
    # 已存在的 ai_filled 是列表：Python 追加到原列表末尾（Kotlin 不能原地改只读 List，要复制再写回）
    add("merge_ai_facets", "merge-existing-ai-filled",
        local={"direction": "unknown", "initiator": "unknown", "acquired": "unknown",
               "ai_filled": ["acquired"]},
        ai_facets={"direction": "in", "initiator": "auto", "acquired": "service"})


# ---------------- template_key ----------------

TEMPLATE_CASES = [
    # tests/test_billing.py::test_template_key_groups_same_kind_of_mail
    ("test-a", "noreply@shop.example", "Your Order Receipt from Oct 23, 2026"),
    ("test-b", "noreply@shop.example", "Your Order Receipt from Sep 3, 2026"),
    ("test-c", "other@mail.example", "Your Order Receipt from Oct 23, 2026"),
    ("test-d", "x@shop.example", "您的订单收据(2026年10月18日)"),
    ("test-e", "x@shop.example", "您的订单收据(2026年9月1日)"),
    # 括号里的编号：中文括号、方括号、英文括号
    ("bracket-cn", "x@shop.example", "【订单号 88231】您的订单已发货"),
    ("bracket-round", "x@shop.example", "您的账单（2026-10）已生成"),
    ("bracket-en", "x@shop.example", "Invoice [INV-2026-0007] is available"),
    # 英文月份与星期的各种写法
    ("month-abbr", "x@shop.example", "Receipt for October"),
    ("month-sept", "x@shop.example", "Statement Sept 2026"),
    ("month-full", "x@shop.example", "Your September statement"),
    ("month-dot", "x@shop.example", "Your Jan. statement"),
    ("day-full", "x@shop.example", "Tuesday digest"),
    ("day-abbr", "x@shop.example", "Mon weekly summary"),
    ("day-thu", "x@shop.example", "Thursday roundup"),
    # 数字
    ("numbers", "x@shop.example", "Order 123456 shipped in 3 days"),
    ("numbers-wide", "x@shop.example", "订单 １２３ 已完成"),
    # 中文和标点
    ("cjk-punct", "x@shop.example", "【提醒】您的账单已出，请查看！"),
    ("cjk-mixed", "x@shop.example", "您好：本月消费 ￥128.00（含税），谢谢。"),
    # 大写域名
    ("upper-domain", "NoReply@SHOP.EXAMPLE", "Your receipt"),
    ("mixed-domain", "noreply@Shop.Example", "  您的收据  "),
    # 没有 @ 的发件人 / 空串
    ("no-at", "bounce", "Delivery status notification"),
    ("empty-sender", "", "空发件人"),
    ("empty-subject", "x@shop.example", ""),
    ("none-both", "", ""),
]


def long_subject(astral_prefix: int, astral_count: int, tail: int) -> str:
    """拼一个带增补平面字符（CJK 扩展 B，U+20000）的超长主题，用来验证按码点截断。"""
    return "测" * astral_prefix + "\U00020000" * astral_count + "试" * tail


def template_cases() -> None:
    for cid, sender, subject in TEMPLATE_CASES:
        add("template_key", f"tpl-{cid}", sender_addr=sender, subject=subject)
    # [:80] 截断：清理后的前 80 个码点里有增补平面字符，按 UTF-16 截断会切坏
    add("template_key", "tpl-truncate-80",
        sender_addr="noreply@shop.example",
        subject=long_subject(40, 10, 60) + "😀")
    # [:160] 截断：域名足够长，使「域名|标题」超过 160 个码点并落在增补平面字符中间
    add("template_key", "tpl-truncate-160",
        sender_addr="x@" + "d" * 100 + ".example",
        subject=long_subject(40, 10, 60) + "🎉🎉")
    # 清理后不足 80：只有空白和标点
    add("template_key", "tpl-only-punct", sender_addr="x@shop.example", subject="！！！???  ——")


# ---------------- decide_tier ----------------

OUT_SELF = {"direction": "out", "initiator": "self", "acquired": "unknown", "cny": 10}
OUT_SELF_ITEM = {"direction": "out", "initiator": "self", "acquired": "virtual_item",
                 "channel": "balance"}
BIG_IN = {"direction": "in", "initiator": "other", "cny": 500}
RENEW = {"direction": "out", "initiator": "auto", "cny": 10}
REFUND = {"direction": "refund", "cny": 10}
UNKNOWN_TIER = {"direction": "unknown", "initiator": "unknown", "acquired": "unknown"}


def tier(cid: str, facets: dict, *, routine_count: int = 0, preference: str = "",
         cfg: dict | None = None) -> None:
    add("decide_tier", cid, facets=facets, routine_count=routine_count, preference=preference,
        cfg=cfg)


def decide_tier_cases() -> None:
    # tests/test_billing.py::test_decide_tier_rules_and_settings
    tier("decide-out-self", OUT_SELF)
    tier("decide-pref-minor", OUT_SELF, preference="一般")
    tier("decide-big-in", BIG_IN)
    tier("decide-big-in-threshold-zero", BIG_IN, cfg={"amount_threshold": 0})
    tier("decide-renew", RENEW)
    tier("decide-renew-minor", RENEW, cfg={"auto_renew": "一般"})
    tier("decide-refund-important", REFUND, cfg={"refund": "重要"})
    tier("decide-item-balance", OUT_SELF_ITEM)
    tier("decide-unknown-default", UNKNOWN_TIER)
    tier("decide-unknown-routine", UNKNOWN_TIER, routine_count=12)
    tier("decide-unknown-routine-off", UNKNOWN_TIER, routine_count=12,
         cfg={"routine_count": 0})

    # 用户偏好：两种取值都生效，其它字符串不生效（继续走策略）
    tier("pref-important", OUT_SELF, preference="重要")
    tier("pref-minor-over-action", {"direction": "out", "action": True, "cny": 10},
         preference="一般")
    tier("pref-empty", OUT_SELF, preference="")
    tier("pref-invalid", OUT_SELF, preference="普通")
    tier("pref-on-refund", REFUND, preference="重要")

    # 需要处理
    tier("action-only", {"direction": "none", "action": True})
    tier("action-false", {"direction": "in", "action": False, "cny": 1})
    tier("action-truthy-non-bool", {"direction": "out", "initiator": "self", "action": "yes"})

    # 金额阈值：等于、小于、阈值 0、负阈值、cny 缺失、direction=none 时不算
    tier("amount-eq-threshold", {"direction": "out", "cny": 200.0})
    tier("amount-below-threshold", {"direction": "out", "cny": 199.99})
    tier("amount-zero-threshold", {"direction": "out", "cny": 1000},
         cfg={"amount_threshold": 0})
    tier("amount-negative-threshold", {"direction": "out", "cny": 1000},
         cfg={"amount_threshold": -5})
    tier("amount-null", {"direction": "out", "cny": None})
    tier("amount-none-direction", {"direction": "none", "cny": 5000})
    tier("amount-fraction", {"direction": "out", "cny": 1299.5})
    tier("amount-exponent", {"direction": "out", "cny": 12345678.0})
    tier("amount-int", {"direction": "out", "cny": 9216})

    # 定级理由里的金额用 Python 的 %g：整数末尾的 0 不能删（100000 是 "100000"，不是 "1"）
    for cid, cny in [
        ("100000", 100000),
        ("180000", 180000),
        ("99999-99", 99999.99),
        ("123450-5", 123450.5),
        ("1234565", 1234565),
        ("0-0000125", 0.0000125),
    ]:
        tier(f"reason-g-cny-{cid}", {"direction": "out", "initiator": "self", "cny": cny},
             cfg={"amount_threshold": 100000})

    # cfg：字符串 / 整数 / 浮点 / 非法值
    tier("cfg-threshold-str", {"direction": "out", "cny": 260}, cfg={"amount_threshold": "250"})
    tier("cfg-threshold-int", {"direction": "out", "cny": 260}, cfg={"amount_threshold": 250})
    tier("cfg-threshold-float", {"direction": "out", "cny": 260},
         cfg={"amount_threshold": 250.5})
    tier("cfg-threshold-invalid", {"direction": "out", "cny": 260},
         cfg={"amount_threshold": "abc"})
    tier("cfg-threshold-none", {"direction": "out", "cny": 260},
         cfg={"amount_threshold": None})
    tier("cfg-threshold-empty", {"direction": "out", "cny": 260},
         cfg={"amount_threshold": ""})
    tier("cfg-threshold-list", {"direction": "out", "cny": 260},
         cfg={"amount_threshold": [1, 2]})
    # float("nan") / float("inf") 是合法值，与任何金额都比不出来，于是继续走后面的策略
    tier("cfg-threshold-inf", {"direction": "out", "cny": 260}, cfg={"amount_threshold": "inf"})
    tier("cfg-threshold-nan", {"direction": "out", "cny": 260}, cfg={"amount_threshold": "nan"})
    tier("cfg-unknown-key", {"direction": "out", "cny": 10},
         cfg={"no_such_key": 1, "auto_renew": "一般"})
    tier("cfg-empty", {"direction": "out", "cny": 10}, cfg={})
    tier("cfg-auto-invalid", RENEW, cfg={"auto_renew": "随便"})
    tier("cfg-auto-none", RENEW, cfg={"auto_renew": None})
    tier("cfg-refund-invalid", REFUND, cfg={"refund": "普通"})
    tier("cfg-refund-important-int", REFUND, cfg={"refund": 1})

    # routine_count 的边界
    tier("routine-below", UNKNOWN_TIER, routine_count=9)
    tier("routine-eq", UNKNOWN_TIER, routine_count=10)
    tier("routine-above", UNKNOWN_TIER, routine_count=11)
    tier("routine-zero-count", UNKNOWN_TIER, routine_count=0)
    tier("routine-cfg-str", UNKNOWN_TIER, routine_count=12, cfg={"routine_count": "12"})
    tier("routine-cfg-str-fraction", UNKNOWN_TIER, routine_count=12,
         cfg={"routine_count": "12.5"})
    tier("routine-cfg-float", UNKNOWN_TIER, routine_count=12, cfg={"routine_count": 12.9})
    tier("routine-cfg-int", UNKNOWN_TIER, routine_count=12, cfg={"routine_count": 12})
    tier("routine-cfg-invalid", UNKNOWN_TIER, routine_count=10,
         cfg={"routine_count": "abc"})
    tier("routine-cfg-empty", UNKNOWN_TIER, routine_count=10, cfg={"routine_count": ""})
    tier("routine-cfg-list", UNKNOWN_TIER, routine_count=12,
         cfg={"routine_count": [1, 2, 3]})
    # routine_count 超出 Long 范围：Python 是大整数，结论仍是「不是例行」，Kotlin 不能截断回绕
    tier("routine-cfg-huge-str", UNKNOWN_TIER, routine_count=12,
         cfg={"routine_count": "18446744073709551621"})
    tier("routine-cfg-huge-float", UNKNOWN_TIER, routine_count=12,
         cfg={"routine_count": 1e308})
    # int("nan") / int("inf") 抛 ValueError，回退默认值 10（routine_count=12 时仍算例行）
    tier("routine-cfg-nan-str", UNKNOWN_TIER, routine_count=12,
         cfg={"routine_count": "nan"})
    tier("routine-cfg-inf-str", UNKNOWN_TIER, routine_count=12,
         cfg={"routine_count": "inf"})

    # 本人主动支出：获得物标签
    for acquired in ("entitlement", "physical", "service", "balance", "virtual_item",
                     "unknown", None, "bogus"):
        tier(f"self-acquired-{acquired}",
             {"direction": "out", "initiator": "self", "acquired": acquired, "cny": 10})
    # 余额内流转只在 channel=balance 时降级
    tier("item-balance-unknown-dir",
         {"direction": "unknown", "acquired": "virtual_item", "channel": "balance"})
    tier("item-real-channel",
         {"direction": "out", "initiator": "self", "acquired": "virtual_item",
          "channel": "real"})
    tier("item-balance-later-branch",
         {"direction": "unknown", "initiator": "unknown", "acquired": "virtual_item",
          "channel": "balance", "cny": None, "action": False})
    # 收入 / 零金额 / 其它方向
    tier("in-only", {"direction": "in"})
    tier("none-only", {"direction": "none"})
    tier("refund-default", {"direction": "refund"})
    tier("auto-default", {"direction": "out", "initiator": "auto"})
    # 空画像
    tier("empty-facets", {})


# ---------------- tier_importance / stored_facets ----------------

def importance_cases() -> None:
    for cid, tier_name, facets, current in [
        ("minor-zero", "一般", {"direction": "in"}, 0),
        ("minor-current-high", "一般", {"direction": "in"}, 5),
        ("minor-current-two", "一般", {"action": True}, 2),
        ("important-action-zero", "重要", {"action": True}, 0),
        ("important-action-high", "重要", {"action": True}, 5),
        ("important-action-two", "重要", {"action": True}, 2),
        ("important-no-action-zero", "重要", {"action": False}, 0),
        ("important-no-action-high", "重要", {}, 5),
        ("unknown-tier", "普通", {"action": True}, 1),
    ]:
        add("tier_importance", f"importance-{cid}", tier=tier_name, facets=facets,
            current=current)


def stored_cases() -> None:
    add("stored_facets", "stored-full", facets={
        "direction": "out", "initiator": "self", "acquired": "service", "action": True,
        "channel": "balance", "amount": 9.99, "currency": "USD", "cny": 71.93})
    add("stored_facets", "stored-empty", facets={})
    add("stored_facets", "stored-partial", facets={"direction": "in", "cny": 4.07})
    add("stored_facets", "stored-truthy-action", facets={"action": "yes"})
    add("stored_facets", "stored-null-values", facets={
        "direction": None, "initiator": None, "acquired": None, "action": None,
        "channel": None, "amount": None, "currency": None, "cny": None})
    add("stored_facets", "stored-extra-keys", facets={
        "direction": "refund", "ai_filled": ["direction"]})


def build_cases() -> list[dict]:
    corpus_cases()
    amount_cases()
    to_cny_cases()
    facet_cases()
    merge_cases()
    template_cases()
    decide_tier_cases()
    importance_cases()
    stored_cases()
    return CASES


def main() -> None:
    _common.run("billing", build_cases)


if __name__ == "__main__":
    main()
