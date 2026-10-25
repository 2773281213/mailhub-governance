"""邮件安全扫描：显示名欺骗、异形域名、危险附件、可疑链接

纯静态分析，不访问邮件中的任何链接、不打开附件。
返回风险等级与可读的判定理由，供界面展示与策略引擎使用。
"""
import re
import unicodedata
from html.parser import HTMLParser
from urllib.parse import urlparse

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


# 国家顶级域下常见的二级注册后缀（co.uk / com.cn / ne.jp / com.tw …）。
# 不是完整的 Public Suffix List，但覆盖了邮件里最常见的情况，且不引入外部依赖。
_SECOND_LEVEL_LABELS = frozenset({
    "co", "com", "net", "org", "gov", "edu", "ac", "ne", "or", "go", "ltd", "plc",
    "idv", "mil", "nom", "gen", "biz", "info",
})


def _registrable(domain: str) -> str:
    """取可注册域：一般取末两段；ccTLD 下的 co/com/ne/ac… 二级后缀取三段。"""
    parts = [p for p in (domain or "").lower().strip(".").split(".") if p]
    if len(parts) < 2:
        return ".".join(parts)
    if len(parts) >= 3 and len(parts[-1]) == 2 and parts[-2] in _SECOND_LEVEL_LABELS:
        return ".".join(parts[-3:])
    return ".".join(parts[-2:])


_DOMAIN_TEXT_RE = re.compile(r"(?:https?://)?((?:[a-z0-9-]+\.)+[a-z]{2,})(?:[/:?#]|$)", re.I)


class _LinkCollector(HTMLParser):
    """从 HTML 正文里收集 (href, 链接文字)；纯解析，不发起任何请求。"""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.links: list[tuple[str, str]] = []
        self._href: str | None = None
        self._text: list[str] = []

    def handle_starttag(self, tag, attrs):
        if tag == "a":
            self._href = dict(attrs).get("href") or ""
            self._text = []

    def handle_data(self, data):
        if self._href is not None:
            self._text.append(data)

    def handle_endtag(self, tag):
        if tag == "a" and self._href is not None:
            self.links.append((self._href.strip(), " ".join("".join(self._text).split())))
            self._href = None


def extract_html_links(html: str, limit: int = 200) -> list[tuple[str, str]]:
    collector = _LinkCollector()
    try:
        collector.feed((html or "")[:300000])
        collector.close()
    except Exception:
        pass   # 畸形 HTML：保留已经解析出的链接
    return collector.links[:limit]


def _link_host(href: str) -> str:
    try:
        parsed = urlparse(href)
    except ValueError:
        return ""
    if parsed.scheme not in ("http", "https"):
        return ""
    return (parsed.hostname or "").lower()


def parse_authentication_results(value: str) -> dict:
    """解析 Authentication-Results 头里的 spf/dkim/dmarc 结论，例如 {"dmarc": "fail"}。"""
    out: dict[str, str] = {}
    for method, result in re.findall(r"\b(spf|dkim|dmarc)\s*=\s*([a-z]+)", (value or "").lower()):
        out.setdefault(method, result)
    return out


def _has_confusable(s: str) -> bool:
    """检测同形异义字符：非 ASCII 拉丁字母混入域名（西里尔 а / 希腊 ο 等）"""
    for ch in s or "":
        if ch.isalpha() and ord(ch) > 127:
            name = unicodedata.name(ch, "")
            if "CYRILLIC" in name or "GREEK" in name:
                return True
    return False


def scan(subject: str, body: str, sender_name: str, sender_addr: str,
         reply_to: str = "", attachments: list[str] | None = None,
         html: str = "", auth_results: str = "") -> dict:
    """返回 {"risk": 等级, "reasons": [...]}；纯离线判定，不发起任何网络请求。

    html: 邮件的 HTML 正文，用来检查 href 的真实目标（纯文本转换会丢掉链接）。
    auth_results: 收件服务器写入的第一条 Authentication-Results 头（SPF/DKIM/DMARC 结论）。
    """
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

    # 2) 显示名冒用知名品牌但域名对不上。英文品牌按整词匹配，避免 "Pineapple" 命中 apple
    low_name = (sender_name or "").lower()
    for brand, domains in BRAND_DOMAINS.items():
        if brand.isascii():
            mentioned = re.search(rf"(?<![a-z0-9]){re.escape(brand)}(?![a-z0-9])", low_name)
        else:
            mentioned = brand in low_name
        if mentioned and sender_reg:
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

    # 6) 链接指向裸 IP、同形异义域名，或链接文字显示的域名与实际目标不符
    hosts = {h.lower().split(":")[0] for h in _URL_RE.findall(body)}
    brand_spoof = plain_mismatch = None
    for href, text in extract_html_links(html):
        host = _link_host(href)
        if not host:
            continue
        hosts.add(host)
        shown = _DOMAIN_TEXT_RE.search(text or "")
        if not shown:
            continue
        shown_reg, target_reg = _registrable(shown.group(1)), _registrable(host)
        if not shown_reg or target_reg in (shown_reg, sender_reg):
            continue   # 指向显示的域名，或指向发件人自己的域名（常见的点击统计跳转）
        pair = (shown.group(1).lower(), host)
        if brand_spoof is None and any(shown_reg == d for ds in BRAND_DOMAINS.values() for d in ds):
            brand_spoof = pair
        elif plain_mismatch is None:
            plain_mismatch = pair
    if brand_spoof:
        bump(RISK_HIGH, f"链接文字显示为知名品牌域名 {brand_spoof[0]}，实际指向 {brand_spoof[1]}")
    elif plain_mismatch:
        # 营销邮件普遍经第三方点击统计域名跳转，单独出现只作提示，不强制归为可疑
        bump(RISK_LOW, f"链接文字显示为 {plain_mismatch[0]}，实际指向 {plain_mismatch[1]}")
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

    # 8) 收件服务器的 SPF/DKIM/DMARC 结论
    auth = parse_authentication_results(auth_results)
    if auth.get("dmarc") == "fail":
        bump(RISK_MEDIUM, "发件域 DMARC 验证失败，发件人可能被冒充")
    elif auth.get("spf") in ("fail", "softfail") and auth.get("dkim") in (None, "none", "fail"):
        bump(RISK_MEDIUM, "SPF 验证失败且没有有效 DKIM 签名")

    return {"risk": risk, "reasons": reasons}


def risk_at_least(risk: str, threshold: str) -> bool:
    return _ORDER.get(risk, 0) >= _ORDER.get(threshold, 0)
