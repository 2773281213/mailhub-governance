"""导出 rules.py（本地分类、验证码提取）的金标语料。

Kotlin 端在 com.mailhub.core.rules.Rules 里重写，必须与这里的每条用例逐字一致。

    python scripts/golden/rules.py           # 重新生成 JSON
    python scripts/golden/rules.py --check   # 检查是否与当前 Python 实现一致

用例来源：
- tests/classification_corpus.py 的每条样本（参数取法与 tests/test_classification.py::_classify 相同）；
- tests/test_classification.py 里其他用到 rules.* 的断言的输入；
- 针对移植差异手写的合成用例：emoji 造成的码点/UTF-16 下标错开、全角数字、
  Unicode 大小写折叠、用户自定义规则（含各种正则写法与 3000 码点截断边界）、
  群发信号的组合、群发降级与群发 + 营销抑制、验证码候选的上下文窗口与优先级、换行符；
- 模块级常量 CATEGORIES 与 CATEGORY_POLICY 的整表（expected 是整张表，Kotlin 侧逐字段比较）。
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _common  # noqa: E402

_common.prepare_imports()

import rules  # noqa: E402
from classification_corpus import CORPUS  # noqa: E402

# 纯函数名 → Python 实现，expected 一律由这里现算，脚本里不手写期望值
FN = {
    "allows_cloud_ai": rules.allows_cloud_ai,
    "extract_otp": rules.extract_otp,
    "is_bulk": rules.is_bulk,
    "classify_detailed": rules.classify_detailed,
    "classify": rules.classify,
    # 模块级常量：整张表一次导出，Kotlin 侧逐字段比较
    "categories": lambda: rules.CATEGORIES,
    "category_policy": lambda: rules.CATEGORY_POLICY,
}

FIRE = "\U0001F525"  # 🔥，U+1F525 在 UTF-16 里占两个码元
GRIN = "\U0001F600"  # 😀
LONG_S = "\u017F"    # ſ，大小写折叠成 s
KELVIN = "\u212A"    # K（开尔文符号），大小写折叠成 k
LINE_SEP = "\u2028"  # 只被 Python 的 . 匹配，不当换行

CASES: list[dict] = []


def add(fn: str, cid: str, **args) -> None:
    """按 fn 的名字调用 Python 原实现，把输入和实际输出都记下来。"""
    CASES.append({"id": cid, "fn": fn, "args": args, "expected": FN[fn](**args)})


def rule(field: str, pattern: str, category: str) -> dict:
    """用户自定义规则（DB 里的 field / pattern / category 三列）。"""
    return {"field": field, "pattern": pattern, "category": category}


# ---------------- tests/classification_corpus.py ----------------

def corpus_cases() -> None:
    for case in CORPUS:
        body = case.get("body", "")
        add("classify_detailed", f"corpus-{case['id']}",
            subject=case["subject"], body=body, sender_addr=case["sender"],
            has_unsubscribe=case.get("unsubscribe", False), custom_rules=[],
            signals=case.get("signals"))
        add("extract_otp", f"corpus-otp-{case['id']}", subject=case["subject"], body=body)


# ---------------- tests/test_classification.py 的其他断言 ----------------

def confidence_cases() -> None:
    """test_rule_confidence_reflects_signal_strength 的四个输入。"""
    add("classify_detailed", "conf-otp", subject="验证码 384756", body="",
        sender_addr="a@b.com", has_unsubscribe=False, custom_rules=[], signals=None)
    add("classify_detailed", "conf-weak", subject="Urgent: call me", body="",
        sender_addr="boss@corp.example", has_unsubscribe=False, custom_rules=[], signals=None)
    add("classify_detailed", "conf-default", subject="你好", body="",
        sender_addr="friend@mail.example", has_unsubscribe=False, custom_rules=[], signals=None)
    add("classify_detailed", "conf-custom", subject="x", body="",
        sender_addr="a@b.com", has_unsubscribe=False,
        custom_rules=[rule("sender", "a@b", "通知")], signals=None)


def classify_cases() -> None:
    """classify 包装函数：返回 (分类, 验证码)，与 classify_detailed 同源。"""
    samples = [
        ("classify-otp", "【腾讯云】验证码 384756，5分钟内有效", ""),
        ("classify-bill", "AWS 账单已出", "您 7 月账单金额为 12.50 USD"),
        ("classify-other", "会议纪要", "附件为本周会议纪要，请查收"),
        ("classify-shipping", "您的订单已发货", "快递单号 SF1234567890，预计明天送达"),
    ]
    for cid, subject, body in samples:
        add("classify", cid, subject=subject, body=body, sender_addr="noreply@shop.example",
            has_unsubscribe=False, custom_rules=[], signals=None)


def cloud_ai_cases() -> None:
    """allows_cloud_ai：每个分类各一条，外加未知分类。"""
    for category in rules.CATEGORIES:
        add("allows_cloud_ai", f"cloud-{category}", category=category)
    add("allows_cloud_ai", "cloud-unknown", category="未知分类")


# ---------------- 1. emoji：码点下标与 UTF-16 下标错开 ----------------

def emoji_cases() -> None:
    # 关键词前放 70 个 emoji：窗口左侧按码点回退 60，与按 UTF-16 回退 60 落在不同位置
    add("extract_otp", "emoji-before-keyword",
        subject=FIRE * 70 + " 验证码 384756", body="")
    add("classify_detailed", "emoji-before-keyword-classify",
        subject=FIRE * 70 + " 验证码 384756", body="", sender_addr="noreply@tencent.example",
        has_unsubscribe=False, custom_rules=[], signals=None)

    # 营销词落在按码点算的窗口内、按 UTF-16 算的窗口外：Python 认定是优惠码，不出验证码
    promo_subject = FIRE * 10 + "优惠" + FIRE * 50 + "验证码 384756"
    add("extract_otp", "emoji-window-promo", subject=promo_subject, body="")
    add("classify_detailed", "emoji-window-promo-classify",
        subject=promo_subject, body="", sender_addr="promo@shop.example",
        has_unsubscribe=False, custom_rules=[], signals=None)

    # 验证码离关键词 70 个 emoji：按码点算仍在 m.end() + 120 的窗口内，按 UTF-16 算已经出了窗口
    far_subject = "验证码" + FIRE * 70 + " 384756"
    add("extract_otp", "emoji-far-code", subject=far_subject, body="")
    add("classify_detailed", "emoji-far-code-classify",
        subject=far_subject, body="", sender_addr="noreply@tencent.example",
        has_unsubscribe=False, custom_rules=[], signals=None)

    # 正文前 2000 码点截断：按码点算保住了验证码，按 UTF-16 算（1000 码点）会切掉
    add("extract_otp", "emoji-trunc2000-keeps-code",
        subject="", body=GRIN * 1000 + "验证码 384756")
    add("classify_detailed", "emoji-trunc2000-keeps-code-classify",
        subject="系统维护公告", body=GRIN * 1000 + "验证码 384756",
        sender_addr="noreply@service.example", has_unsubscribe=False,
        custom_rules=[], signals=None)

    # 正文前 2000 码点截断：验证码整体在截断点之后，两种算法都取不到，结果为 ""
    add("extract_otp", "emoji-trunc2000-cuts-code",
        subject="", body=GRIN * 2000 + "验证码 384756")
    add("classify_detailed", "emoji-trunc2000-cuts-code-classify",
        subject="系统维护公告", body=GRIN * 2000 + "验证码 384756",
        sender_addr="noreply@service.example", has_unsubscribe=False,
        custom_rules=[], signals=None)

    # 正文前 3000 码点截断（classify_detailed 的 body_head）：按码点算能看到验证码关键词
    add("classify_detailed", "emoji-trunc3000-otp",
        subject="系统维护公告", body=GRIN * 1500 + "x" * 200 + "验证码 384756",
        sender_addr="noreply@service.example", has_unsubscribe=False,
        custom_rules=[], signals=None)

    # 正文前 3000 码点截断：安全关键词刚好在按 UTF-16 算的截断点之后
    add("classify_detailed", "emoji-trunc3000-security-body",
        subject="Weekly note", body=GRIN * 1400 + "x" * 1000 + "异常登录",
        sender_addr="colleague@corp.example", has_unsubscribe=False,
        custom_rules=[], signals=None)


# ---------------- 2. 全角数字 ----------------

def fullwidth_cases() -> None:
    add("extract_otp", "fw-six-digits", subject="验证码 ３８４７５６", body="")
    add("classify_detailed", "fw-six-digits-classify",
        subject="验证码 ３８４７５６", body="", sender_addr="noreply@tencent.example",
        has_unsubscribe=False, custom_rules=[], signals=None)
    add("extract_otp", "fw-four-digits", subject="",
        body="您的取件码 ９９２１，请凭码到驿站取件")
    add("classify_detailed", "fw-mixed-digits",
        subject="", body="Your verification code: ５５２９１０",
        sender_addr="service@pay.example", has_unsubscribe=False,
        custom_rules=[], signals=None)


# ---------------- 3. 大小写折叠 ----------------

def case_cases() -> None:
    add("extract_otp", "case-upper-keyword", subject="VERIFICATION CODE 384756", body="")
    add("classify_detailed", "case-upper-keyword-classify",
        subject="VERIFICATION CODE 384756", body="", sender_addr="no-reply@github.example",
        has_unsubscribe=False, custom_rules=[], signals=None)
    # ſ（U+017F）折叠成 s：security code / security alert 都应命中
    add("extract_otp", "case-long-s-code", subject=LONG_S + "ecurity code 552910", body="")
    add("classify_detailed", "case-long-s-alert",
        subject=LONG_S + "ecurity alert", body="", sender_addr="security@mail.example",
        has_unsubscribe=False, custom_rules=[], signals=None)
    # K（U+212A 开尔文符号）折叠成 k：weekly digest 应命中营销词
    add("classify_detailed", "case-kelvin-weekly",
        subject="Wee" + KELVIN + "ly digest", body="Top stories for you",
        sender_addr="noreply@medium.example", has_unsubscribe=False,
        custom_rules=[], signals=None)


# ---------------- 4. 用户自定义规则 ----------------

def custom_rule_cases() -> None:
    def detailed(cid: str, subject: str = "", body: str = "",
                 sender_addr: str = "someone@corp.example",
                 rules_: list | None = None) -> None:
        add("classify_detailed", cid, subject=subject, body=body, sender_addr=sender_addr,
            has_unsubscribe=False, custom_rules=rules_ or [], signals=None)

    detailed("custom-field-subject", subject="您的发票已开具",
             rules_=[rule("subject", "发票", "账单")])
    detailed("custom-field-sender", subject="随便一个标题", sender_addr="hr@company.example",
             rules_=[rule("sender", "hr@company", "重要")])
    detailed("custom-field-body", subject="随便一个标题", body="请签署合同",
             rules_=[rule("body", "合同", "重要")])
    detailed("custom-ignore-case", subject="BigOffer daily digest",
             rules_=[rule("subject", "BIGOFFER", "订阅")])
    # 非法正则：Python 抛 re.error，规则被跳过，落到后面的普通规则
    detailed("custom-bad-regex", subject="异常登录提醒",
             sender_addr="security@mail.example",
             rules_=[rule("subject", "([a-", "订阅")])
    # 前一条非法、后一条命中：确认非法规则只是 continue，不影响后面的规则
    detailed("custom-bad-then-good", subject="hello",
             rules_=[rule("subject", "([a-", "安全"), rule("subject", "hello", "通知")])
    # 命名组：Java 需要先改写成 (?<code>…)
    detailed("custom-named-group", subject="订单12345",
             rules_=[rule("subject", "订单(?P<num>\\d+)", "账单")])
    # \Z：Python 的字符串末尾锚点，Java 要换成 \z
    detailed("custom-z-anchor-hit", subject="invoice total",
             rules_=[rule("subject", "invoice total\\Z", "账单")])
    detailed("custom-z-anchor-miss", subject="invoice total 12",
             rules_=[rule("subject", "invoice total\\Z", "账单")])
    # {,n} 量词也是 Python 写法
    detailed("custom-brace-quantifier", subject="优惠码 ABC123",
             rules_=[rule("subject", "优惠{,2}码", "订阅")])
    # 自定义规则判「验证码」时还要顺带提取验证码
    detailed("custom-otp-category", subject="[ALERT] 验证码 394857",
             rules_=[rule("subject", "\\[ALERT\\]", "验证码")])
    # 自定义规则判「验证码」但文本里提取不到码：otp 为空，分类仍然是 验证码
    detailed("custom-otp-no-code", subject="[ALERT] 394857",
             rules_=[rule("subject", "\\[ALERT\\]", "验证码")])

    # ---- 边界：field 不是 subject / sender / body ----
    # 原实现是 {"subject": …, "sender": …, "body": …}.get(field, "")，未知 field 的目标是空串：
    # 不能匹配空串的模式不命中，「^$」这类能匹配空串的模式命中
    detailed("custom-unknown-field-no-match", subject="您的发票已开具",
             rules_=[rule("attachment", "发票", "订阅")])
    detailed("custom-unknown-field-empty-match", subject="您的发票已开具",
             rules_=[rule("attachment", "^$", "订阅")])
    # pattern 是空串：re.search("", target) 恒命中，等于命中全部邮件
    detailed("custom-empty-pattern-subject", subject="随便一个标题",
             rules_=[rule("subject", "", "订阅")])
    detailed("custom-empty-pattern-body", subject="随便一个标题", body="随便一段正文",
             rules_=[rule("body", "", "通知")])
    # 两条合法规则同时命中：列表里靠前的那条胜出
    detailed("custom-first-wins", subject="发票已开具，限时优惠",
             rules_=[rule("subject", "发票", "账单"), rule("subject", "优惠", "订阅")])
    detailed("custom-first-wins-reversed", subject="发票已开具，限时优惠",
             rules_=[rule("subject", "优惠", "订阅"), rule("subject", "发票", "账单")])

    # ---- field = "body"：匹配内容正好落在 3000 码点截断边界上 ----
    # 「发票」起始于第 2995 个码点：整词在 body_head 内，命中
    detailed("custom-body-boundary-inside", subject="随手一写",
             body="x" * 2995 + "发票" + "y" * 10,
             rules_=[rule("body", "发票", "账单")])
    # 「发」是第 3000 个码点、「票」被截掉：整词不命中
    detailed("custom-body-boundary-straddle", subject="随手一写",
             body="x" * 2999 + "发票" + "y" * 10,
             rules_=[rule("body", "发票", "账单")])
    # 整词都在截断点之后
    detailed("custom-body-boundary-outside", subject="随手一写",
             body="x" * 3000 + "发票",
             rules_=[rule("body", "发票", "账单")])

    # ---- Python 能编译、旧版 Kotlin 会拒绝的写法 ----
    detailed("custom-regex-uppercase-u8", subject=FIRE + " 会议改期",
             rules_=[rule("subject", "\\U0001F525", "通知")])
    # \141 是八进制转义（'a'），后面跟字面量 bc
    detailed("custom-regex-octal-escape", subject="abc 会议改期",
             rules_=[rule("subject", "\\141bc", "通知")])
    # (?#…) 是注释，不占位置
    detailed("custom-regex-comment-group", subject="xabc 会议改期",
             rules_=[rule("subject", "(?#note)abc", "通知")])
    # \v 是垂直制表符 U+000B
    detailed("custom-regex-vertical-tab", subject="a\u000bb 会议改期",
             rules_=[rule("subject", "a\\vb", "通知")])
    # 两个不同的合法组名：a_b 不是合法 Java 组名（会被改名），pyg1 是合法组名，改名时不能撞上它
    detailed("custom-regex-two-group-names", subject="xy 会议改期",
             rules_=[rule("subject", "(?P<a_b>x)(?P<pyg1>y)", "通知")])

    # ---- Python 拒绝的写法：re.error → 规则被跳过，落到后面的普通规则 ----
    # 主题里含「面试」：规则只要没被跳过就会先命中（category=订阅），
    # 所以期望的「重要 / important_strong」证明这条规则确实被跳过了
    for cid, bad_pattern in (
        ("custom-reject-unknown-escape", "\\p{L}+"),
        ("custom-reject-bad-group-name", "(?P<1a>x)"),
        ("custom-reject-bad-group-ref", "(a)\\12"),
        ("custom-reject-bad-class-range", "[\\s-\\u4000]"),
    ):
        detailed(cid, subject="面试邀请：后端工程师",
                 rules_=[rule("subject", bad_pattern, "订阅")])


# ---------------- 5. 群发信号的组合 ----------------

SIGNAL_KEYS = ["list_id", "precedence_bulk", "feedback_id", "esp", "auto_submitted"]


def bulk_cases() -> None:
    for key in SIGNAL_KEYS:
        for unsub in (False, True):
            signals = {key: True}
            add("is_bulk", f"isbulk-{key}-unsub{int(unsub)}",
                signals=signals, has_unsubscribe=unsub)
            add("classify_detailed", f"bulk-{key}-unsub{int(unsub)}",
                subject="Team update", body="Monthly update from the club",
                sender_addr="list@club.example", has_unsubscribe=unsub,
                custom_rules=[], signals=signals)

    add("is_bulk", "isbulk-none", signals=None, has_unsubscribe=False)
    add("is_bulk", "isbulk-empty", signals={}, has_unsubscribe=False)
    add("is_bulk", "isbulk-all-false",
        signals={key: False for key in SIGNAL_KEYS}, has_unsubscribe=False)
    add("is_bulk", "isbulk-all-true",
        signals={key: True for key in SIGNAL_KEYS}, has_unsubscribe=False)
    add("is_bulk", "isbulk-esp-only-false-others",
        signals={"esp": True, "list_id": False, "precedence_bulk": False,
                 "feedback_id": False, "auto_submitted": False},
        has_unsubscribe=False)

    # 信号全是 False（字段存在但为假）：不能当成群发
    add("classify_detailed", "bulk-all-false-signals",
        subject="Team update", body="Monthly update from the club",
        sender_addr="list@club.example", has_unsubscribe=False, custom_rules=[],
        signals={key: False for key in SIGNAL_KEYS})

    # 群发 + 开发平台发件人：归「通知」；带营销词时归「订阅」
    add("classify_detailed", "bulk-platform-plain",
        subject="[GitHub] A new comment on PR #123", body="reply",
        sender_addr="notifications@github.com", has_unsubscribe=True,
        custom_rules=[], signals={"list_id": True})
    add("classify_detailed", "bulk-platform-with-promo",
        subject="[GitHub] Weekly digest with 20% off", body="reply",
        sender_addr="notifications@github.com", has_unsubscribe=True,
        custom_rules=[], signals={"list_id": True})
    # auto_submitted 单独出现：不算群发，但走「机器通知发件人」分支
    add("classify_detailed", "bulk-auto-submitted-only",
        subject="Build failed: main", body="Your build #456 failed",
        sender_addr="builds@ci.example", has_unsubscribe=False,
        custom_rules=[], signals={"auto_submitted": True})


# ---------------- 6. 换行：Python 的 . 不匹配 \n，但匹配 \r 和 U+2028 ----------------

def newline_cases() -> None:
    for line_sep, cid in (("\r", "cr"), ("\r\n", "crlf"), (LINE_SEP, "u2028")):
        add("classify_detailed", f"nl-bill-{cid}",
            subject="订单" + line_sep + "确认", body="",
            sender_addr="orders@shop.example", has_unsubscribe=False,
            custom_rules=[], signals=None)
    # \n 不是 . 能匹配的字符，所以「订单…确认」不成立，落到「其他」
    add("classify_detailed", "nl-bill-lf-no-match",
        subject="订单\n确认", body="", sender_addr="orders@shop.example",
        has_unsubscribe=False, custom_rules=[], signals=None)
    # 正文里的换行同样只影响 . 的匹配
    add("classify_detailed", "nl-security-body-crlf",
        subject="Weekly note", body="您的账号异常\r\n请立即修改密码",
        sender_addr="colleague@corp.example", has_unsubscribe=False,
        custom_rules=[], signals=None)
    add("classify_detailed", "nl-security-body-lf-no-match",
        subject="Weekly note", body="异常\n登录", sender_addr="colleague@corp.example",
        has_unsubscribe=False, custom_rules=[], signals=None)


# ---------------- 7. 分类策略整表 ----------------

def category_table_cases() -> None:
    """CATEGORIES 的顺序与整张 CATEGORY_POLICY（6 列），Kotlin 侧逐字段比较。

    这两个是模块级常量，不是函数：expected 是整张表，所以 fn 用 FN 里的零参数取值器。
    """
    add("categories", "categories-order")
    add("category_policy", "category-policy-table")


# ---------------- 8. 分支组合：群发降级、群发 + 营销抑制、物流与付款同现 ----------------

def branch_combination_cases() -> None:
    def detailed(cid: str, subject: str, *, sender_addr: str = "someone@corp.example",
                 unsubscribe: bool = False, body: str = "",
                 signals: dict | None = None) -> None:
        add("classify_detailed", cid, subject=subject, body=body, sender_addr=sender_addr,
            has_unsubscribe=unsubscribe, custom_rules=[], signals=signals)

    # 强重要信号 + 群发（没有营销词）：置信度降为 CONF_BULK_STRONG
    detailed("important-strong-bulk-header", "面试邀请：后端工程师", unsubscribe=True)
    detailed("important-strong-bulk-list", "面试邀请：后端工程师",
             signals={"list_id": True})
    # 强重要信号 + 群发 + 营销词：整个分支被抑制，落到群发分支（订阅）
    detailed("important-strong-bulk-promo", "签证办理限时优惠", unsubscribe=True,
             sender_addr="promo@shop.example")
    # 同一主题、非群发：强重要信号照常命中 CONF_STRONG
    detailed("important-strong-plain", "签证办理限时优惠")

    # 账单 + 群发：置信度降为 CONF_MEDIUM
    detailed("bill-bulk-header", "AWS 账单已出", unsubscribe=True)
    detailed("bill-bulk-list", "AWS 账单已出", signals={"list_id": True})
    detailed("bill-plain", "AWS 账单已出")

    # 物流词与付款词同时出现：跳过 shipping 分支（不是账单，也不是物流通知）
    detailed("shipping-with-payment-word", "包裹已签收，退款处理中")
    # 只有物流词：正常走 shipping 分支
    detailed("shipping-plain", "您的包裹已签收")


# ---------------- 9. 验证码候选：12 / 6 码点上下文窗口与优先级 ----------------

def code_context_cases() -> None:
    # 候选码前面 12 个码点的窗口里有 emoji（窗口按码点算，emoji 占 2 个 UTF-16 码元）
    add("extract_otp", "otp-context-before-emoji-ok",
        subject="验证码" + FIRE * 4 + "384756", body="")
    # 同一条前面隔着 emoji 的「尾号 」：候选码是尾号而不是验证码
    add("extract_otp", "otp-context-before-emoji-tail",
        subject="验证码" + FIRE * 4 + "尾号 384756", body="")
    # 候选码后面 6 个码点的窗口里有 emoji：「元」不再紧跟在候选码后 → 仍然取得到
    add("extract_otp", "otp-context-after-emoji",
        subject="验证码 384756" + FIRE * 3 + " 元", body="")
    # 对照：没有 emoji 时「元」落在窗口开头 → 是金额，不是验证码
    add("extract_otp", "otp-context-after-unit", subject="验证码 384756 元", body="")

    # 同一窗口里的候选优先级：6 位数字 > 4-8 位数字 > 字母数字混合码
    add("extract_otp", "otp-priority-six-digit",
        subject="验证码 1234 AB12CD 987654", body="")
    add("extract_otp", "otp-priority-four-digit",
        subject="验证码 1234 AB12CD", body="")
    add("extract_otp", "otp-priority-alnum", subject="验证码 AB12CD", body="")
    # 年份被 _BAD_NUMS 排除，「AB12CD」成为唯一候选
    add("extract_otp", "otp-priority-year-skipped",
        subject="验证码 2024 AB12CD", body="")


def build_cases() -> list[dict]:
    corpus_cases()
    confidence_cases()
    classify_cases()
    cloud_ai_cases()
    emoji_cases()
    fullwidth_cases()
    case_cases()
    custom_rule_cases()
    category_table_cases()
    bulk_cases()
    branch_combination_cases()
    code_context_cases()
    newline_cases()
    return CASES


def main() -> None:
    _common.run("rules", build_cases)


if __name__ == "__main__":
    main()
