"""账单重要性分级：先描述交易本身（交易画像），再按通用策略定级。

画像只用与平台无关的通用措辞识别，策略里不出现任何品牌名或发件域名：
  - direction  资金方向：out 支出 / in 收入 / refund 退款 / none 无资金 / unknown
  - initiator  发起方：self 本人主动 / auto 自动续费扣款 / other 他人触发 / unknown
  - acquired   获得物：entitlement 长期权益（游戏、软件授权）/ physical 实物 /
               service 服务期（会员、订阅）/ virtual_item 虚拟物品流转 / balance 余额 / unknown
  - action     是否需要收件人处理（付款失败、待支付、订阅需要处理……）
  - amount / currency / cny  金额、币种与粗略折算的人民币
  - channel    支付渠道：real 真实支付 / balance 平台余额 / unknown

定级结果只有「重要」「一般」两档；定级理由写入治理审计和服务日志，不在界面展示。
"""
from __future__ import annotations

import re

TIER_IMPORTANT = "重要"
TIER_MINOR = "一般"
TIERS = (TIER_IMPORTANT, TIER_MINOR)

DIRECTIONS = ("out", "in", "refund", "none", "unknown")
INITIATORS = ("self", "auto", "other", "unknown")
ACQUIRED = ("entitlement", "physical", "service", "virtual_item", "balance", "unknown")
CHANNELS = ("real", "balance", "unknown")

# 策略默认值；设置页可覆盖
DEFAULTS = {
    "amount_threshold": 200.0,   # 折合人民币，超过即重要
    "routine_count": 10,         # 同类模板近 30 天出现次数达到即视为例行流水
    "auto_renew": TIER_IMPORTANT,
    "refund": TIER_MINOR,
}
ROUTINE_WINDOW_SECONDS = 30 * 86400

# 粗略汇率（1 单位外币 ≈ 多少人民币），只用于和阈值比较大小，不追求精确
_CNY_RATE = {
    "CNY": 1.0, "USD": 7.2, "EUR": 7.8, "GBP": 9.2, "HKD": 0.92, "TWD": 0.22, "JPY": 0.048,
    "KRW": 0.0052, "SGD": 5.3, "AUD": 4.7, "CAD": 5.2, "UAH": 0.17, "RUB": 0.08, "INR": 0.085,
    "TRY": 0.21, "BRL": 1.3, "KZT": 0.015, "PHP": 0.125, "MXN": 0.38, "ARS": 0.0075,
    "PLN": 1.8, "CHF": 8.2, "NZD": 4.3, "THB": 0.2, "VND": 0.00028, "IDR": 0.00045, "MYR": 1.55,
}

# ---------- 文本特征 ----------

_ACTION = re.compile(
    r"付款失败|支付失败|扣款失败|交易失败|续费失败|未能(扣款|支付|续费)|待支付|待付款|未支付|请尽快(支付|付款)"
    r"|即将(扣费|扣款|续费|到期)|将(自动)?(扣费|扣款)|欠费|逾期|需要(您)?处理|请更新(您的)?(付款|支付)方式"
    r"|payment\s+(failed|declined|unsuccessful)|(card|charge)\s+(was\s+)?declined|past\s+due|overdue"
    r"|action\s+required|update\s+(your\s+)?payment|unable\s+to\s+(process|charge)"
    r"|requires?\s+(your\s+)?attention|will\s+be\s+charged|upcoming\s+(charge|payment|renewal)",
    re.I,
)
_REFUND = re.compile(r"退款|已退还|退回|refund(ed)?|reimburse", re.I)
# 正文里只认「已经发生的退款」，不认「了解退款政策」一类页脚
_REFUND_BODY = re.compile(
    r"已(将款项)?退(款|还|回)|退款(已|成功|总计|金额)|退还(到|至)|款项已退"
    r"|refund\s+(has\s+been|was|is)\s+(issued|processed|approved|completed)|(we('ve| have)|has\s+been)\s+refunded"
    r"|refunded\s+to",
    re.I,
)
_IN_SUBJECT = re.compile(
    r"售出|卖出|已获得|收到.{0,4}(款|付款|转账)|到账|入账|收款|返现|奖励金"
    r"|\bsold\b|payout|you('ve| have)?\s+(received|earned)|credited|cash\s*back",
    re.I,
)
_OUT_SUBJECT = re.compile(
    r"购买|购物|订单|收据|付款|支付|扣款|扣费|充值|续费|续订|账单|发票|消费"
    r"|receipt|purchase|\border|payment|charged|invoice|\bbill\b|renew|top[- ]?up",
    re.I,
)
# 正文里只认「强」证据，避免页脚模板词（消费记录、获得积分）误导方向
_OUT_BODY = re.compile(
    r"共计花费|总计花费|实付|实际支付|已支付|已付款|支付金额|付款金额|扣款金额|订单(总额|金额)|支付方式|付款方式"
    r"|amount\s+paid|total\s+charged|you\s+paid|payment\s+method|order\s+total|charged\s+to",
    re.I,
)
_IN_BODY = re.compile(
    r"(钱包|余额|账户)已?(获得|收到|增加)|已到账|已入账|收款金额|已售出|已经售出"
    r"|you\s+(received|earned)|has\s+been\s+credited|credited\s+to|payout|\bsold\b",
    re.I,
)
_FREE = re.compile(r"免费|零元|free\s+(items?|games?|gift|trial)|at\s+no\s+cost", re.I)

_OTHER = re.compile(r"售出|卖出|买家|购买了您的|\bsold\b|buyer|purchased\s+your", re.I)
_AUTO = re.compile(
    r"自动续费|自动扣费|自动续订|续订成功|续费成功|已续订|已续费|定期扣款|代扣"
    r"|auto[- ]?renew|renewed|recurring|subscription\s+(renewal|renewed|charge)",
    re.I,
)
_SELF = re.compile(
    r"购买|下单|您的订单|订购|充值|感谢.{0,8}(购买|订购|惠顾|交易)"
    r"|thank\s+you\s+for\s+your\s+(\S+\s+){0,3}(purchase|order)|your\s+(order|purchase)"
    r"|you\s+(bought|purchased|ordered)"
    r"|order\s+(confirm|placed)|receipt\s+(for|from)|top[- ]?up",
    re.I,
)

_ENTITLEMENT = re.compile(
    r"(添加|加入)到.{0,12}(库|账户|帐户)|激活码|序列号|产品密钥|授权|许可证|兑换码|已准备就绪"
    r"|added\s+to\s+your\s+(\S+\s+){0,2}(library|account|collection)|activation\s+key|product\s+key|license"
    r"|game\s+key|ready\s+to\s+(play|download)",
    re.I,
)
_PHYSICAL = re.compile(r"发货|配送|收货(地址|人)|快递|物流|shipping|deliver(y|ed)|shipped", re.I)
_SERVICE = re.compile(r"会员|订阅|套餐|服务期|有效期至|subscription|membership|premium|\bplan\b", re.I)
# 「物品/item」本身太宽泛（游戏收据也写「以下物品」），只认市场买卖和游戏内道具的说法
_VIRTUAL = re.compile(
    r"饰品|道具|皮肤|卡牌|社区市场|交易市场|物品已售出|上架的.{0,4}物品"
    r"|in-game\s+(item|currency)|marketplace|community\s+market|items?\s+(you\s+)?(listed|sold)"
    r"|trading\s+card|\bskins?\b",
    re.I,
)
_BALANCE = re.compile(r"充值|余额|钱包|wallet|balance|top[- ]?up|add(ed)?\s+funds", re.I)

_CHANNEL_BALANCE = re.compile(
    r"(支付方式|付款方式|payment\s+method|paid\s+(with|using))[^。\n]{0,20}(钱包|余额|礼品卡|点数|wallet|balance|gift\s*card|credits?)",
    re.I,
)
_CHANNEL_REAL = re.compile(
    r"信用卡|借记卡|银行卡|储蓄卡|支付宝|微信支付|云闪付|银联|paypal|visa|mastercard|amex|apple\s*pay"
    r"|google\s*pay|card\s+ending|ending\s+in\s+\d{4}|尾号\s*\d{4}",
    re.I,
)

# ---------- 金额 ----------

_SYMBOLS = {
    "US$": "USD", "HK$": "HKD", "NT$": "TWD", "A$": "AUD", "C$": "CAD", "S$": "SGD", "R$": "BRL",
    "¥": "CNY", "￥": "CNY", "$": "USD", "€": "EUR", "£": "GBP", "₴": "UAH", "₽": "RUB",
    "₹": "INR", "₩": "KRW", "₺": "TRY", "₸": "KZT", "₱": "PHP", "元": "CNY", "人民币": "CNY",
    "美元": "USD", "欧元": "EUR", "英镑": "GBP", "港币": "HKD", "日元": "JPY",
}
_CODES = "|".join(sorted(_CNY_RATE, key=len, reverse=True)) + "|RMB|UAH"
_SYM = "|".join(re.escape(s) for s in sorted(_SYMBOLS, key=len, reverse=True))
_NUM = r"\d{1,3}(?:[.,\s]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?"
_AMOUNT = re.compile(
    rf"(?P<pre>{_SYM}|\b(?:{_CODES})\b)\s?(?P<n1>{_NUM})"
    rf"|(?P<n2>{_NUM})\s?(?P<post>{_SYM}|\b(?:{_CODES})\b)",
)
_CODE_AFTER = re.compile(rf"\s?\b({_CODES})\b")
_TOTAL_HINT = re.compile(
    r"总计|合计|共计|实付|花费|总额|金额|应付|退款总计|total|amount|charged|paid|grand", re.I)


def _to_float(raw: str) -> float | None:
    """兼容 1,299.00 / 1.299,00 / 1 299,00 / 12,5 等写法：最后一个分隔符后若是 1-2 位数字就是小数点。"""
    s = raw.replace(" ", "").replace("\u00a0", "")
    m = re.search(r"[.,](\d{1,2})$", s)
    if m:
        whole, frac = s[:m.start()], m.group(1)
    else:
        whole, frac = s, ""
    whole = re.sub(r"[.,]", "", whole)
    try:
        return float(f"{whole}.{frac}" if frac else whole)
    except ValueError:
        return None


def _currency(token: str) -> str:
    token = token.strip()
    if token.upper() == "RMB":
        return "CNY"
    return _SYMBOLS.get(token, token.upper())


def extract_amount(text: str) -> tuple[float | None, str | None]:
    """返回 (金额, 币种)。优先取「总计/实付/total」附近的金额，否则取最大的一个。"""
    best_hint, best_any = None, None
    prev_end = 0
    for m in _AMOUNT.finditer(text or ""):
        # 关键词必须紧挨在这个金额前面（中间没有别的金额），否则「总计 12 元，赠送 500 元」会误取 500
        window = text[max(prev_end, m.start() - 24):m.start()]
        prev_end = m.end()
        raw = m.group("n1") or m.group("n2")
        cur = _currency(m.group("pre") or m.group("post") or "")
        # 「$88.00 HKD」：通用的 $ 符号后面跟着明确的币种代码时，以代码为准
        suffix = _CODE_AFTER.match(text, m.end())
        if m.group("pre") in ("$", "¥", "￥") and suffix:
            cur = _currency(suffix.group(1))
        value = _to_float(raw or "")
        if value is None or value > 10_000_000 or cur not in _CNY_RATE:
            continue
        item = (value * _CNY_RATE[cur], value, cur)
        if best_any is None or item > best_any:
            best_any = item
        if _TOTAL_HINT.search(window):
            if best_hint is None or item > best_hint:
                best_hint = item
    pick = best_hint or best_any
    return (pick[1], pick[2]) if pick else (None, None)


def to_cny(amount: float | None, currency: str | None) -> float | None:
    if amount is None or not currency or currency not in _CNY_RATE:
        return None
    return round(amount * _CNY_RATE[currency], 2)


# ---------- 交易画像 ----------

def _first(text: str, ordered: list[tuple[str, re.Pattern]], default: str = "unknown") -> str:
    for value, pattern in ordered:
        if pattern.search(text):
            return value
    return default


def _direction(subject: str, body: str, amount: float | None) -> str:
    # 标题是邮件的主旨，优先按标题判断；退款 > 收入 > 支出
    if _REFUND.search(subject):
        return "refund"
    if _IN_SUBJECT.search(subject):
        return "in"
    if _FREE.search(subject) or amount == 0:
        return "none"
    if _OUT_SUBJECT.search(subject):
        # 标题像支出，但正文只有收入证据（例如「订单收款通知」）时以正文为准
        if _IN_BODY.search(body) and not _OUT_BODY.search(body):
            return "in"
        return "out"
    if _REFUND_BODY.search(body):
        return "refund"
    outs, ins = len(_OUT_BODY.findall(body)), len(_IN_BODY.findall(body))
    if outs > ins:
        return "out"
    if ins > outs:
        return "in"
    if _FREE.search(body):
        return "none"
    return "unknown"


def extract_facets(subject: str, body: str) -> dict:
    """从标题和正文抽取交易画像。正文应已是可读文本（HTML 邮件先转成文字）。"""
    subject = subject or ""
    body = (body or "")[:20000]
    text = subject + "\n" + body
    amount, currency = extract_amount(text)
    direction = _direction(subject, body, amount)
    if direction == "in" or _OTHER.search(subject):
        initiator = "other" if _OTHER.search(text) else "unknown"
    else:
        initiator = _first(text, [("auto", _AUTO), ("self", _SELF)])
    acquired = _first(text, [
        ("entitlement", _ENTITLEMENT), ("physical", _PHYSICAL), ("service", _SERVICE),
        ("virtual_item", _VIRTUAL), ("balance", _BALANCE),
    ])
    if _CHANNEL_BALANCE.search(text):
        channel = "balance"
    elif _CHANNEL_REAL.search(text):
        channel = "real"
    else:
        channel = "unknown"
    return {
        "direction": direction,
        "initiator": initiator,
        "acquired": acquired,
        "action": bool(_ACTION.search(text)),
        "amount": amount,
        "currency": currency,
        "cny": to_cny(amount, currency),
        "channel": channel,
    }


def merge_ai_facets(local: dict, ai_facets: dict | None) -> dict:
    """本地抽取优先；AI 只填补本地识别为 unknown 的维度（金额只信本地）。"""
    merged = dict(local)
    if not isinstance(ai_facets, dict):
        return merged
    for key, allowed in (("direction", DIRECTIONS), ("initiator", INITIATORS), ("acquired", ACQUIRED)):
        value = ai_facets.get(key)
        if merged.get(key) == "unknown" and value in allowed and value != "unknown":
            merged[key] = value
            merged.setdefault("ai_filled", []).append(key)
    if ai_facets.get("action") is True and not merged.get("action"):
        merged["action"] = True
        merged.setdefault("ai_filled", []).append("action")
    return merged


# ---------- 模板与例行程度 ----------

_MONTHS = re.compile(
    r"\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?\b"
    r"|\b(mon|tue|wed|thu|fri|sat|sun)[a-z]*\b",
    re.I,
)


def template_key(sender_addr: str, subject: str) -> str:
    """发件域名 + 去掉数字、日期、编号后的标题，用来识别「同一类邮件」。"""
    domain = (sender_addr or "").rsplit("@", 1)[-1].strip().lower()
    s = (subject or "").lower()
    s = re.sub(r"[\[【(（][^\]】)）]{0,40}[\]】)）]", " ", s)   # 去掉括号里的编号、日期
    s = _MONTHS.sub("<m>", s)
    s = re.sub(r"\d+", "#", s)
    s = re.sub(r"[^\w#<>一-鿿]+", " ", s)
    s = re.sub(r"\s+", " ", s).strip()[:80]
    return f"{domain}|{s}"[:160]


# ---------- 定级策略 ----------

def _config(cfg: dict | None) -> dict:
    merged = dict(DEFAULTS)
    for key, value in (cfg or {}).items():
        if key in merged and value not in (None, ""):
            merged[key] = value
    try:
        merged["amount_threshold"] = float(merged["amount_threshold"])
    except (TypeError, ValueError):
        merged["amount_threshold"] = DEFAULTS["amount_threshold"]
    try:
        merged["routine_count"] = int(merged["routine_count"])
    except (TypeError, ValueError):
        merged["routine_count"] = DEFAULTS["routine_count"]
    for key in ("auto_renew", "refund"):
        if merged[key] not in TIERS:
            merged[key] = DEFAULTS[key]
    return merged


def decide_tier(facets: dict, *, routine_count: int = 0, preference: str = "",
                cfg: dict | None = None) -> tuple[str, str]:
    """按顺序套用策略，命中即停。返回 (等级, 理由)；理由只用于审计和日志。"""
    c = _config(cfg)
    direction, initiator = facets.get("direction"), facets.get("initiator")
    acquired, cny = facets.get("acquired"), facets.get("cny")

    if preference in TIERS:
        return preference, "按用户对同类邮件的标记"
    if facets.get("action"):
        return TIER_IMPORTANT, "需要处理（付款失败、待支付或即将扣费等）"
    if cny is not None and c["amount_threshold"] > 0 and cny >= c["amount_threshold"] \
            and direction != "none":
        return TIER_IMPORTANT, f"金额约 ¥{cny:g}，不低于阈值 ¥{c['amount_threshold']:g}"
    if direction == "out" and initiator == "self":
        if acquired == "virtual_item" and facets.get("channel") == "balance":
            return TIER_MINOR, "用平台余额买卖虚拟物品，属于余额内流转"
        return TIER_IMPORTANT, f"本人主动支出，获得{_ACQUIRED_LABEL.get(acquired, '商品或服务')}"
    if direction == "out" and initiator == "auto":
        return c["auto_renew"], "自动续费扣款（按设置）"
    if direction == "refund":
        return c["refund"], "退款（按设置）"
    if direction == "in":
        return TIER_MINOR, "收入或到账，不需要处理"
    if direction == "none":
        return TIER_MINOR, "零金额（免费领取等）"
    if acquired == "virtual_item" and facets.get("channel") == "balance":
        return TIER_MINOR, "平台余额内的虚拟物品流转"
    if c["routine_count"] > 0 and routine_count >= c["routine_count"]:
        return TIER_MINOR, f"同类邮件近 30 天 {routine_count} 封，属于例行流水"
    return TIER_IMPORTANT, "信息不足，保守视为重要"


_ACQUIRED_LABEL = {
    "entitlement": "长期权益", "physical": "实物商品", "service": "服务或会员",
    "balance": "账户余额", "virtual_item": "虚拟物品",
}


def tier_importance(tier: str, facets: dict, current: int) -> int:
    """重要度随等级调整：需要处理的账单升到 4（会推送），一般账单不超过 2。"""
    if tier == TIER_MINOR:
        return min(current or 2, 2)
    if facets.get("action"):
        return max(current or 0, 4)
    return max(current or 0, 3)


def stored_facets(facets: dict) -> dict:
    """写入数据库的画像：只含枚举、布尔和金额，不含任何原文。"""
    return {
        "direction": facets.get("direction", "unknown"),
        "initiator": facets.get("initiator", "unknown"),
        "acquired": facets.get("acquired", "unknown"),
        "action": bool(facets.get("action")),
        "channel": facets.get("channel", "unknown"),
        "amount": facets.get("amount"),
        "currency": facets.get("currency"),
        "cny": facets.get("cny"),
    }
