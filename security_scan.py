"""邮件安全扫描：显示名欺骗、异形域名、危险附件、可疑链接

纯静态分析，不访问邮件中的任何链接、不打开附件。
返回风险等级与可读的判定理由，供界面展示与策略引擎使用。
"""
import re
import unicodedata

RISK_NONE = "none"
RISK_LOW = "low"
RISK_MEDIUM = "medium"
RISK_HIGH = "high"

_ORDER = {RISK_NONE: 0, RISK_LOW: 1, RISK_MEDIUM: 2, RISK_HIGH: 3}

# 可执行/脚本类附件——直接判高危
DANGEROUS_EXT = {
    "exe", "scr", "com", "pif", "bat", "cmd", "msi", "jar", "vbs", "vbe",
    "js", "jse", "wsf", "wsh", "ps1", "hta", "cpl", "lnk", "reg", "apk", "dll",
}
# 常被用来夹带的压缩包——中危（内容不可见）
ARCHIVE_EXT = {"zip", "rar", "7z", "iso", "img", "cab"}

# 常被仿冒的品牌与其官方域名后缀
BRAND_DOMAINS = {
    "paypal": ["paypal.com"],
    "apple": ["apple.com", "icloud.com"],
    "google": ["google.com", "gmail.com", "youtube.com"],
    "microsoft": ["microsoft.com", "outlook.com", "live.com", "office.com"],
    "amazon": ["amazon.com", "amazon.cn"],
    "github": ["github.com"],
    "支付宝": ["alipay.com", "antgroup.com"],
    "微信": ["tencent.com", "qq.com", "weixin.qq.com"],
    "淘宝": ["taobao.com", "alibaba.com"],
    "银行": [],  # 任何自称银行但域名非常规的都可疑
}

_URL_RE = re.compile(r"https?://([^\s/\"'>)]+)", re.I)
_IP_HOST_RE = re.compile(r"^\d{1,3}(\.\d{1,3}){3}(:\d+)?$")
# 显示名里混入了一个邮箱地址（经典的 "客服 <service@bank.com>" 欺骗）
_ADDR_IN_NAME_RE = re.compile(r"[\w.+-]+@([\w-]+\.[\w.-]+)")

# 高风险诱导词
_URGENT_RE = re.compile(
    r"账户.{0,4}(冻结|异常|封停|停用)|立即(验证|确认|更新|处理)|限时.{0,6}(处理|失效)"
    r"|verify\s+your\s+account|account\s+(suspended|locked|will\s+be\s+closed)"
    r"|urgent\s+action|click\s+here\s+immediately|confirm\s+your\s+password",
    re.I,
)


def _domain_of(addr: str) -> str:
    return addr.rsplit("@", 1)[-1].lower().strip() if "@" in (addr or "") else ""


def _registrable(domain: str) -> str:
    """取可注册域（粗略：末两段；对 .co.uk 之类取三段）"""
    parts = [p for p in (domain or "").split(".") if p]
    if len(parts) < 2:
        return domain or ""
    if len(parts) >= 3 and parts[-2] in ("co", "com", "net", "org", "gov", "edu") and len(parts[-1]) == 2:
        return ".".join(parts[-3:])
    return ".".join(parts[-2:])


def _has_confusable(s: str) -> bool:
    """检测同形异义字符：非 ASCII 拉丁字母混入域名（西里尔 а / 希腊 ο 等）"""
    for ch in s or "":
        if ch.isalpha() and ord(ch) > 127:
            name = unicodedata.name(ch, "")
            if "CYRILLIC" in name or "GREEK" in name:
                return True
    return False


def scan(subject: str, body: str, sender_name: str, sender_addr: str,
         reply_to: str = "", attachments: list[str] | None = None) -> dict:
    """返回 {"risk": 等级, "reasons": [...]}；纯离线判定，不发起任何网络请求。"""
    reasons: list[str] = []
    risk = RISK_NONE

    def bump(level: str, why: str):
        nonlocal risk
        reasons.append(why)
        if _ORDER[level] > _ORDER[risk]:
            risk = level

    subject = subject or ""
    body = (body or "")[:20000]
    sender_domain = _domain_of(sender_addr)
    sender_reg = _registrable(sender_domain)

    # 1) 显示名里嵌了另一个域的邮箱地址
    m = _ADDR_IN_NAME_RE.search(sender_name or "")
    if m and sender_reg and _registrable(m.group(1)) != sender_reg:
        bump(RISK_HIGH, f"显示名伪装成 {m.group(1)}，实际发件域为 {sender_domain}")

    # 2) 显示名冒用知名品牌但域名对不上
    low_name = (sender_name or "").lower()
    for brand, domains in BRAND_DOMAINS.items():
        if brand in low_name and sender_reg:
            if domains and not any(sender_reg == d or sender_reg.endswith("." + d) for d in domains):
                bump(RISK_HIGH, f"自称「{brand}」但发件域 {sender_domain} 不属于其官方域名")
            break

    # 3) 域名同形异义字符
    if _has_confusable(sender_domain):
        bump(RISK_HIGH, f"发件域 {sender_domain} 含非拉丁同形字符，疑似仿冒域名")

    # 4) Reply-To 与发件域不一致
    if reply_to:
        rt = _registrable(_domain_of(reply_to))
        if rt and sender_reg and rt != sender_reg:
            bump(RISK_MEDIUM, f"回复地址域 {rt} 与发件域 {sender_reg} 不一致")

    # 5) 危险附件
    for fn in attachments or []:
        ext = fn.rsplit(".", 1)[-1].lower() if "." in fn else ""
        if ext in DANGEROUS_EXT:
            bump(RISK_HIGH, f"含可执行附件 {fn}（不会自动打开）")
        elif ext in ARCHIVE_EXT:
            bump(RISK_MEDIUM, f"含压缩包附件 {fn}，内容无法预先检查")

    # 6) 链接指向裸 IP 或与发件域完全无关的短域名
    hosts = {h.lower().split(":")[0] for h in _URL_RE.findall(body)}
    for h in list(hosts)[:40]:
        if _IP_HOST_RE.match(h):
            bump(RISK_HIGH, f"正文含指向裸 IP 的链接 {h}")
            break
    if _has_confusable(" ".join(list(hosts)[:40])):
        bump(RISK_MEDIUM, "正文链接域名含同形异义字符")

    # 7) 紧迫性话术 + 外链，典型钓鱼组合
    if _URGENT_RE.search(subject + "\n" + body[:3000]):
        if hosts:
            bump(RISK_MEDIUM, "含账户风险类紧迫话术且带外部链接，符合钓鱼特征")
        else:
            bump(RISK_LOW, "含账户风险类紧迫话术")

    return {"risk": risk, "reasons": reasons}


def risk_at_least(risk: str, threshold: str) -> bool:
    return _ORDER.get(risk, 0) >= _ORDER.get(threshold, 0)
