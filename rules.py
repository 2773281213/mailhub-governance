"""本地分类规则引擎：验证码提取 + 关键词分类，AI 之前的第一道（零成本、零隐私外泄）防线"""
import re

# 分类固定集合，前后端与 AI 提示词保持一致
# 「可疑」由 security_scan 的静态检测置入，不参与 AI 自由判定的降级路径
CATEGORIES = ["验证码", "重要", "账单", "安全", "可疑", "订阅", "通知", "其他"]

# 每个分类的策略属性（对应需求文档第四条）
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


def allows_cloud_ai(category: str) -> bool:
    """验证码/账单/安全/可疑类默认不外送云端 AI——它们含敏感凭证或已由规则确诊"""
    return CATEGORY_POLICY.get(category, {}).get("cloud_ai", True)

# ---------- 验证码识别 ----------

OTP_KEYWORDS = re.compile(
    r"验证码|校验码|动态密码|动态码|驗證碼|确认码|激活码|取件码|提取码|安全码"  # 中文
    r"|verification\s*code|verify|security\s*code|one[-\s]?time|otp\b|passcode|auth(entication)?\s*code"
    r"|login\s*code|access\s*code|confirmation\s*code|码为|code\s*(is|:)|is\s*your",
    re.I,
)

# 纯数字 4-8 位（避免匹配年份、金额、电话片段：前后不能是数字/小数点/连字符）
_NUM_CODE = re.compile(r"(?<![\d.\-])(\d{4,8})(?![\d.\-])")
# 字母数字混合 5-8 位（必须含数字，全大写或数字，排除常见单词）
_ALNUM_CODE = re.compile(r"\b(?=[A-Z0-9]*\d)([A-Z0-9]{5,8})\b")

_BAD_NUMS = re.compile(r"^(19|20)\d{2}$")  # 年份


def _pick_code(text: str) -> str:
    """在关键词附近窗口内挑选最像验证码的 token，优先 6 位数字"""
    if not text:
        return ""
    candidates = []
    for m in OTP_KEYWORDS.finditer(text):
        window = text[max(0, m.start() - 60): m.end() + 120]
        for nm in _NUM_CODE.finditer(window):
            tok = nm.group(1)
            if _BAD_NUMS.match(tok):
                continue
            candidates.append(tok)
        for am in _ALNUM_CODE.finditer(window):
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
    r"异常登录|异地登录|安全提醒|安全警告|风险提示|账号异常|修改密码|密码已(被)?修改|冻结"
    r"|security\s*alert|suspicious|unusual\s*(sign|activity|login)|new\s*(device|sign[-\s]?in)"
    r"|password\s*(was\s*)?(changed|reset)|two[-\s]?factor|2fa",
    re.I,
)
_BILL = re.compile(
    r"账单|对账|发票|扣费|扣款|支付成功|付款|充值|余额|欠费|续费|租金|价格调整"
    r"|invoice|receipt|billing|payment\s*(received|due|confirm)|charged|subscription\s*renew"
    r"|order\s*(confirm|placed|shipped)|订单",
    re.I,
)
_SUBSCRIBE = re.compile(
    r"退订|取消订阅|newsletter|unsubscribe|weekly\s*digest|promo(tion)?|优惠|折扣|特惠|活动预告"
    r"|限时|大促|双1[12]|黑五|sale\b|deal\b|coupon",
    re.I,
)
_NOTIFY_SENDER = re.compile(
    r"noreply|no-reply|no_reply|notification|notify|donotreply|updates?@|news@|info@"
    r"|github\.com|gitlab|atlassian|jira|slack\.com|discord|telegram|linkedin|docker\.com",
    re.I,
)
_IMPORTANT = re.compile(
    r"面试|offer|录用|合同|签署|法院|税务|海关|签证|visa\s*(appointment|approved)|deadline"
    r"|urgent|重要通知|逾期|到期提醒|续签",
    re.I,
)


def classify(subject: str, body: str, sender_addr: str, has_unsubscribe: bool,
             custom_rules: list) -> tuple[str, str]:
    """返回 (分类, 验证码)。custom_rules 为 DB 中用户自定义规则（已按 priority 排序）"""
    subject = subject or ""
    body_head = (body or "")[:3000]
    text = subject + "\n" + body_head

    # 1. 用户自定义规则优先
    for r in custom_rules:
        target = {"subject": subject, "sender": sender_addr or "", "body": body_head}.get(r["field"], "")
        try:
            if re.search(r["pattern"], target, re.I):
                return r["category"], extract_otp(subject, body_head) if r["category"] == "验证码" else ""
        except re.error:
            continue

    # 2. 验证码：能提取到码才算，防止"验证您的邮箱"类营销误判
    otp = extract_otp(subject, body_head)
    if otp and OTP_KEYWORDS.search(text):
        return "验证码", otp

    # 3. 显式重要信号
    if _IMPORTANT.search(subject):
        return "重要", ""
    # 4. 安全告警
    if _SECURITY.search(text):
        return "安全", ""
    # 5. 账单/交易
    if _BILL.search(subject):
        return "账单", ""
    # 6. 订阅营销（带退订头或关键词）
    if has_unsubscribe or _SUBSCRIBE.search(text):
        return "订阅", ""
    # 7. 机器通知类发件人
    if _NOTIFY_SENDER.search(sender_addr or ""):
        return "通知", ""
    # 8. 交给 AI（或保持"其他"）
    return "其他", ""


def load_custom_rules(conn) -> list:
    rows = conn.execute(
        "SELECT field, pattern, category FROM rules WHERE enabled=1 ORDER BY priority ASC, id ASC"
    ).fetchall()
    return [dict(r) for r in rows]
