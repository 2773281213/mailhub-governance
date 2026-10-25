"""本地分类规则引擎：验证码提取 + 关键词/邮件头信号分类。

同步时立即给出标签和置信度（零成本、零外泄）。启用 AI 后它是交给 AI 的「规则初判」
和 AI 失败、拿不准时的兜底；未启用 AI 时它就是最终分类。
"""
import re

# 分类固定集合，前后端与 AI 提示词保持一致
# 「可疑」由 security_scan 的静态检测置入，不参与 AI 自由判定的降级路径
CATEGORIES = ["验证码", "重要", "账单", "安全", "可疑", "订阅", "通知", "其他"]

# 每个分类的策略属性（对应需求文档第四条）
# cloud_ai 只在 AI 判定范围设为「仅规则未识别的邮件」（ai_scope=other）时生效；
# 范围为 all / uncertain 时由部署者在设置页显式选择外送范围。
CATEGORY_POLICY = {
    #                 风险      默认动作      保留天数  可自动删  可送云端AI  需立即提醒
    "验证码": {"risk": "low",    "action": "label",    "keep": 7,   "auto_del": True,  "cloud_ai": False, "alert": False},
    "重要":   {"risk": "low",    "action": "notify",   "keep": 0,   "auto_del": False, "cloud_ai": True,  "alert": True},
    "账单":   {"risk": "low",    "action": "remind",   "keep": 0,   "auto_del": False, "cloud_ai": False, "alert": False},
    "安全":   {"risk": "high",   "action": "notify",   "keep": 0,   "auto_del": False, "cloud_ai": False, "alert": True},
    "可疑":   {"risk": "high",   "action": "quarantine", "keep": 0, "auto_del": False, "cloud_ai": False, "alert": True},
    "订阅":   {"risk": "low",    "action": "archive",  "keep": 30,  "auto_del": True,  "cloud_ai": True,  "alert": False},
    "通知":   {"risk": "low",    "action": "digest",   "keep": 30,  "auto_del": True,  "cloud_ai": True,  "alert": False},
    "其他":   {"risk": "low",    "action": "none",     "keep": 0,   "auto_del": False, "cloud_ai": True,  "alert": False},
}

# 规则置信度：治理流水线据此决定是否交 AI 复核（ai_scope=uncertain）以及回退时的置信度
CONF_USER_RULE = 0.95
CONF_OTP = 0.95
CONF_STRONG = 0.9
CONF_MEDIUM = 0.8
CONF_BULK_STRONG = 0.75
CONF_WEAK = 0.6
CONF_DEFAULT = 0.5


def allows_cloud_ai(category: str) -> bool:
    """验证码/账单/安全/可疑类默认不外送云端 AI——它们含敏感凭证或已由规则确诊"""
    return CATEGORY_POLICY.get(category, {}).get("cloud_ai", True)


# ---------- 验证码识别 ----------

OTP_KEYWORDS = re.compile(
    r"验证码|校验码|动态密码|动态码|驗證碼|确认码|激活码|取件码|提取码|安全码|(登录|安全|验证)代码"  # 中文
    r"|verification\s*code|verify\s+(with\s+)?(this|the\s+following|your)\s+code|security\s*code"
    r"|one[-\s]?time\s+(pass)?(code|password|pin)|otp\b|passcode|auth(entication)?\s*code"
    r"|login\s*code|sign[-\s]?in\s*code|access\s*code|confirmation\s*code|guard\s*code"
    r"|码为|code\s*(is|:)",
    re.I,
)

# 关键词附近出现这些词，说明是优惠码/礼品码，不是一次性验证码
_PROMO_NEAR = re.compile(
    r"promo|coupon|discount|voucher|gift\s*card|referral|\d+\s*%\s*off"
    r"|优惠|折扣|兑换|礼品|满减|领券",
    re.I,
)

# 纯数字 4-8 位（避免匹配年份、金额、电话片段：前后不能是数字/连字符，也不能是小数）
_NUM_CODE = re.compile(r"(?<![\d.\-])(\d{4,8})(?![\d\-]|\.\d)")
# 字母数字混合 5-8 位（必须含数字和字母，全大写或数字，排除常见单词）
_ALNUM_CODE = re.compile(r"\b(?=[A-Z0-9]*\d)(?=[A-Z0-9]*[A-Z])([A-Z0-9]{5,8})\b")

_BAD_NUMS = re.compile(r"^(19|20)\d{2}$")  # 年份
# 候选码前面是这些词时，它是订单号/电话/卡号/金额，而不是验证码
_NOT_CODE_BEFORE = re.compile(
    r"([¥$€£￥]|订单号?|单号|运单|快递|电话|手机|尾号|卡号|账号|工号|编号"
    r"|order|ref|invoice|account|tel|phone|no\.?|#)\s*[:：#]?\s*$",
    re.I,
)
# 候选码后面是这些单位时，它是金额/数量/日期
_NOT_CODE_AFTER = re.compile(
    r"^\s*(元|块|美元|USD|RMB|CNY|%|折|件|人|天|小时|GB|MB|KB|万|次|年|月|日)", re.I)


def _plausible_code(window: str, start: int, end: int) -> bool:
    if _NOT_CODE_BEFORE.search(window[max(0, start - 12):start]):
        return False
    if _NOT_CODE_AFTER.match(window[end:end + 6]):
        return False
    return True


def _pick_code(text: str) -> str:
    """在关键词附近窗口内挑选最像验证码的 token，优先 6 位数字"""
    if not text:
        return ""
    candidates = []
    for m in OTP_KEYWORDS.finditer(text):
        window = text[max(0, m.start() - 60): m.end() + 120]
        if _PROMO_NEAR.search(window):
            continue
        for nm in _NUM_CODE.finditer(window):
            tok = nm.group(1)
            if _BAD_NUMS.match(tok) or not _plausible_code(window, nm.start(1), nm.end(1)):
                continue
            candidates.append(tok)
        for am in _ALNUM_CODE.finditer(window):
            if _plausible_code(window, am.start(1), am.end(1)):
                candidates.append(am.group(1))
    if not candidates:
        return ""
    # 6 位数字 > 4-8 位数字 > 混合码
    for tok in candidates:
        if len(tok) == 6 and tok.isdigit():
            return tok
    for tok in candidates:
        if tok.isdigit():
            return tok
    return candidates[0]


def extract_otp(subject: str, body: str) -> str:
    """主题优先（很多服务把码直接放主题里），其次正文前 2000 字符"""
    combined_subject = subject or ""
    if OTP_KEYWORDS.search(combined_subject):
        code = _pick_code(combined_subject)
        if code:
            return code
        # 主题带关键词但码在正文
        code = _pick_code((body or "")[:2000])
        if code:
            return code
    return _pick_code((body or "")[:2000])


# ---------- 关键词分类 ----------

_SECURITY = re.compile(
    r"异常登录|异地登录|安全提醒|安全警告|风险提示|账号异常|账户异常|修改密码|密码已(被)?修改|冻结"
    r"|新设备|新的设备|登录提醒|登录通知|新登录"
    r"|security\s*alert|suspicious|unusual\s*(sign|activity|login)|new\s*(device|sign[-\s]?in|login)"
    r"|password\s*(was\s*)?(changed|reset)|two[-\s]?factor|2fa",
    re.I,
)
_BILL = re.compile(
    r"账单|对账|发票|扣费|扣款|支付成功|付款|充值|余额|欠费|续费|租金|价格调整|还款|应还"
    r"|订单.{0,4}(确认|已提交|支付|付款|已付)"
    r"|invoice|receipt|billing|payment\s*(received|due|confirm)|charged|subscription\s*renew"
    r"|order\s*(confirm|placed)",
    re.I,
)
# 有这些词才算真正的交易；只有物流词的订单邮件归「通知」
_PAYMENT = re.compile(r"支付|付款|扣款|扣费|金额|已付|invoice|receipt|payment|charged|refund|退款", re.I)
_SHIPPING = re.compile(
    r"发货|已发出|快递|物流|派送|派件|签收|运单|配送|出库|揽收"
    r"|shipped|shipping\s+(update|confirmation)|out\s+for\s+delivery|delivered|tracking|in\s+transit"
    r"|your\s+(package|parcel)",
    re.I,
)
_SUBSCRIBE = re.compile(
    r"退订|取消订阅|newsletter|unsubscribe|weekly\s*digest|promo(tion)?|优惠|折扣|特惠|活动预告"
    r"|限时|大促|双1[12]|黑五|sale\b|deal\b|coupon|周刊|月刊|简报"
    r"|\d+\s*%\s*off|special\s+offer|limited\s+time|free\s+shipping|满减|秒杀|包邮|领券|买一送一",
    re.I,
)
_NOTIFY_SENDER = re.compile(
    r"noreply|no-reply|no_reply|notification|notify|donotreply|updates?@|news@|info@"
    r"|github\.com|gitlab|atlassian|jira|slack\.com|discord|telegram|linkedin|docker\.com",
    re.I,
)
# 群发邮件里，这些平台发件人更像「通知」而不是营销订阅
_NOTIFY_PLATFORM = re.compile(
    r"notifications?@|notify@|github\.com|gitlab|atlassian|jira|slack\.com|discord|docker\.com",
    re.I,
)
# 强重要信号：单独出现即可判「重要」
_IMPORTANT_STRONG = re.compile(
    r"面试|录用|录取|offer\s*letter|合同|签署|法院|传票|税务|海关|签证|入职"
    r"|visa\s*(appointment|approved|interview)",
    re.I,
)
# 弱重要信号：营销邮件也常用，只在非群发、无营销词时才算
_IMPORTANT_WEAK = re.compile(
    r"\boffer\b|deadline|urgent|重要通知|逾期|到期提醒|续签|截止",
    re.I,
)


def is_bulk(signals: dict | None, has_unsubscribe: bool = False) -> bool:
    """群发特征：退订头、邮件列表头、Precedence: bulk、群发平台投递、Feedback-ID。"""
    s = signals or {}
    return bool(has_unsubscribe or s.get("list_id") or s.get("precedence_bulk")
                or s.get("feedback_id") or s.get("esp"))


def _result(category: str, confidence: float, hit: str, otp: str = "") -> dict:
    return {"category": category, "otp": otp, "confidence": confidence, "hits": [hit]}


def classify_detailed(subject: str, body: str, sender_addr: str, has_unsubscribe: bool,
                      custom_rules: list, signals: dict | None = None) -> dict:
    """返回 {category, otp, confidence, hits}。custom_rules 为 DB 中用户自定义规则（已按 priority 排序）。

    signals 是 mail_client.parse_message 解析出的 header_signals（全为布尔值）。
    """
    subject = subject or ""
    sender_addr = sender_addr or ""
    body_head = (body or "")[:3000]
    text = subject + "\n" + body_head
    signals = signals or {}
    bulk = is_bulk(signals, has_unsubscribe)
    promo = bool(_SUBSCRIBE.search(text))

    # 1. 用户自定义规则优先
    for r in custom_rules:
        target = {"subject": subject, "sender": sender_addr, "body": body_head}.get(r["field"], "")
        try:
            if re.search(r["pattern"], target, re.I):
                otp = extract_otp(subject, body_head) if r["category"] == "验证码" else ""
                return _result(r["category"], CONF_USER_RULE, "custom", otp)
        except re.error:
            continue

    # 2. 验证码：能提取到码才算，防止"验证您的邮箱"类营销误判
    otp = extract_otp(subject, body_head)
    if otp and OTP_KEYWORDS.search(text):
        return _result("验证码", CONF_OTP, "otp", otp)

    # 3. 强重要信号（群发且带营销词时不算，例如「签证办理限时优惠」）
    if _IMPORTANT_STRONG.search(subject) and not (bulk and promo):
        return _result("重要", CONF_BULK_STRONG if bulk else CONF_STRONG, "important_strong")
    # 4. 安全告警：主题命中
    if _SECURITY.search(subject):
        return _result("安全", CONF_STRONG, "security_subject")
    # 5. 账单/交易
    if _BILL.search(subject):
        return _result("账单", CONF_MEDIUM if bulk else CONF_STRONG, "bill")
    # 6. 发货/物流：不是账单，也不是营销
    if _SHIPPING.search(subject) and not _PAYMENT.search(subject):
        return _result("通知", CONF_MEDIUM, "shipping")
    # 7. 安全告警只在正文出现：群发（周刊、营销）里提到 2FA/改密码不算
    if not bulk and _SECURITY.search(body_head):
        return _result("安全", CONF_WEAK, "security_body")
    # 8. 弱重要信号：非群发、无营销词
    if _IMPORTANT_WEAK.search(subject) and not bulk and not promo:
        return _result("重要", CONF_WEAK, "important_weak")
    # 9. 群发：开发平台的通知归「通知」，其余归「订阅」
    if has_unsubscribe or signals.get("list_id") or signals.get("precedence_bulk"):
        if _NOTIFY_PLATFORM.search(sender_addr) and not promo:
            return _result("通知", CONF_MEDIUM, "bulk_platform")
        return _result("订阅", CONF_MEDIUM, "bulk_header")
    if promo:
        return _result("订阅", CONF_WEAK, "promo_keyword")
    # 10. 机器通知类发件人
    if _NOTIFY_SENDER.search(sender_addr) or signals.get("auto_submitted"):
        return _result("通知", CONF_MEDIUM if signals.get("auto_submitted") else 0.7, "notify_sender")
    # 11. 交给 AI（或保持"其他"）
    return _result("其他", CONF_DEFAULT, "none")


def classify(subject: str, body: str, sender_addr: str, has_unsubscribe: bool,
             custom_rules: list, signals: dict | None = None) -> tuple[str, str]:
    """返回 (分类, 验证码)；需要置信度时用 classify_detailed。"""
    result = classify_detailed(subject, body, sender_addr, has_unsubscribe, custom_rules, signals)
    return result["category"], result["otp"]


def load_custom_rules(conn) -> list:
    rows = conn.execute(
        "SELECT field, pattern, category FROM rules WHERE enabled=1 ORDER BY priority ASC, id ASC"
    ).fetchall()
    return [dict(r) for r in rows]
