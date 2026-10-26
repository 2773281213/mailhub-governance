"""邮箱服务商注册表：每个服务商的域名、IMAP 主机和可用的认证方式只在这里定义。

认证方式只有四种，前后端统一用这几个名字：

- oauth_browser  浏览器 OAuth 登录（Gmail / Outlook 用授权码 + PKCE，网易用合作方 implicit flow）
- oauth_device   设备码登录（Outlook）
- app_password   服务商生成的授权码 / 应用专用密码 / 客户端专用密码
- password       自定义 IMAP 的普通密码

账户表里的 auth_type 仍只区分 "oauth" 和 "password"（后两种都按密码登录 IMAP）。
OAuth 是否可用取决于部署者是否配置了自有客户端，由 oauth_auth 判断。
"""
from __future__ import annotations

from dataclasses import dataclass

OAUTH_BROWSER = "oauth_browser"
OAUTH_DEVICE = "oauth_device"
APP_PASSWORD = "app_password"
PASSWORD = "password"
SECRET_METHODS = (APP_PASSWORD, PASSWORD)


@dataclass(frozen=True)
class SecretAuth:
    """以密码登录 IMAP 的方式：授权码、应用专用密码或普通密码。"""
    kind: str                 # APP_PASSWORD | PASSWORD
    input_label: str          # 输入框标题
    setup_url: str = ""       # 去哪里开启 IMAP / 生成授权码
    setup_label: str = ""
    steps: tuple[str, ...] = ()
    # 在 setup_url 之前要先打开的页面，例如 Gmail 需要先开启两步验证：((标题, 地址), ...)
    prereq_links: tuple[tuple[str, str], ...] = ()
    troubleshoot: str = ""    # 常见报错的解释


@dataclass(frozen=True)
class Provider:
    key: str
    label: str
    imap_host: str
    domains: tuple[str, ...] = ()
    imap_port: int = 993
    secret: SecretAuth | None = None
    # "" 表示不支持 OAuth；"standard" 为 Google/Microsoft 授权码流程；"netease" 为网易合作方流程
    oauth: str = ""
    oauth_label: str = ""
    device_flow: bool = False
    # 网易 OAuth 账户连接 imapmail.* 主机，授权码账户连接 imap.* 主机
    oauth_imap_host: str = ""
    # 部署者没有配置 OAuth 客户端时给用户的提示（说明配置后能换成更简单的登录方式）
    oauth_missing_hint: str = ""
    netease_web: str = ""     # 网易授权入口所在的网页域名
    send_imap_id: bool = False  # 认证后发送 RFC 2971 ID（网易不发会返回 Unsafe Login）
    note: str = ""

    def host_for(self, auth_type: str) -> str:
        if auth_type == "oauth" and self.oauth_imap_host:
            return self.oauth_imap_host
        return self.imap_host

    @property
    def netease_authorize_url(self) -> str:
        if self.oauth != "netease":
            return ""
        return f"{self.netease_web}/fgw/mailsrv-oauth2-fapi/oauth2/authorize/entry"


_NETEASE_STEPS = (
    "登录网页版邮箱 → 设置 → POP3/SMTP/IMAP，开启 IMAP/SMTP 服务",
    "按提示生成「客户端授权密码」（不是网页登录密码）",
    "回到这里，用完整邮箱地址和授权密码登录",
)


def _netease(key: str, label: str, domain: str, web: str, imap_host: str,
             oauth_imap_host: str) -> Provider:
    # 域名、授权入口和 imapmail 主机来自 Outlook Android 5.2606.0 的 NetEase.kt，
    # 授权密码用的 imap 主机来自网易邮箱大师；不要凭记忆改动。
    return Provider(
        key=key, label=label, domains=(domain,), imap_host=imap_host,
        secret=SecretAuth(APP_PASSWORD, "网易客户端授权密码", f"{web}/", "打开网易邮箱设置",
                          _NETEASE_STEPS),
        oauth="netease", oauth_label="使用网易邮箱登录",
        oauth_imap_host=oauth_imap_host, netease_web=web, send_imap_id=True,
    )


_ALL = (
    Provider(
        key="qq", label="QQ 邮箱", domains=("qq.com", "foxmail.com"), imap_host="imap.qq.com",
        secret=SecretAuth(
            APP_PASSWORD, "QQ 邮箱授权码", "https://mail.qq.com/", "打开 QQ 邮箱",
            ("登录网页版 QQ 邮箱 → 设置 → 账号，开启 IMAP/SMTP 服务",
             "按提示生成 16 位「授权码」"),
        ),
        note="QQ 邮箱的 IMAP 只接受授权码；QQ 密码和 QQ 互联登录都不能用于 IMAP。",
    ),
    _netease("163", "网易 163", "163.com", "https://mail.163.com",
             "imap.163.com", "imapmail.163.com"),
    _netease("126", "网易 126", "126.com", "https://mail.126.com",
             "imap.126.com", "imapmail.126.com"),
    _netease("yeah", "网易 yeah.net", "yeah.net", "https://mail.yeah.net",
             "imap.yeah.net", "imapmail.yeah.net"),
    _netease("188", "网易 188", "188.com", "https://mail.188.com",
             "imap.188.com", "imapmail.188.com"),
    _netease("vip163", "网易 VIP 163", "vip.163.com", "https://vip.163.com",
             "imap.vip.163.com", "imapmail.vip.163.com"),
    _netease("vip126", "网易 VIP 126", "vip.126.com", "https://vip.126.com",
             "imap.vip.126.com", "imapmail.vip.126.com"),
    # APK 只给出 vip.188.com 的授权入口，OAuth 的 IMAP 仍复用 188 的 imapmail 主机
    _netease("vip188", "网易 VIP 188", "vip.188.com", "https://vip.188.com",
             "imap.vip.188.com", "imapmail.188.com"),
    Provider(
        key="exmail", label="腾讯企业邮", imap_host="imap.exmail.qq.com",
        secret=SecretAuth(
            APP_PASSWORD, "企业邮客户端专用密码", "https://exmail.qq.com/", "打开腾讯企业邮",
            ("登录企业邮网页版 → 设置 → 客户端专用密码，生成一个专用密码",
             "回到这里，用完整邮箱地址和专用密码登录"),
        ),
        note="企业自有域名无法自动识别，请在服务商里手动选择「腾讯企业邮」。",
    ),
    Provider(
        key="qiye163", label="网易企业邮", imap_host="imap.qiye.163.com", send_imap_id=True,
        secret=SecretAuth(
            APP_PASSWORD, "企业邮客户端授权密码", "https://qiye.163.com/", "打开网易企业邮",
            ("请管理员在网易企业邮后台开启 IMAP/SMTP",
             "在邮箱设置里生成客户端授权密码",
             "回到这里，用完整邮箱地址和授权密码登录"),
        ),
        note="企业自有域名无法自动识别，请在服务商里手动选择「网易企业邮」。",
    ),
    Provider(
        key="gmail", label="Gmail", domains=("gmail.com", "googlemail.com"),
        imap_host="imap.gmail.com",
        secret=SecretAuth(
            APP_PASSWORD, "Google 应用专用密码（16 位）", "https://myaccount.google.com/apppasswords",
            "创建应用专用密码",
            ("先为 Google 账号开启两步验证（没开时创建页面会提示「您的账号不支持您正在尝试的设置」）",
             "在应用专用密码页面随便填一个名称（如 MailHub）并创建",
             "把显示的 16 位密码粘贴到下方，空格可以不删"),
            prereq_links=(("开启两步验证", "https://myaccount.google.com/signinoptions/twosv"),),
            troubleshoot=("开启两步验证后仍提示不支持：工作或学校账号可能被管理员禁用了应用专用密码；"
                          "两步验证只用安全密钥、或开启了高级保护的账号也无法创建。这些账号请改用 Google 登录。"),
        ),
        oauth="standard", oauth_label="使用 Google 登录",
        oauth_missing_hint=("不想开两步验证？管理员在「设置 → Google 登录导入」填写本站的 Google OAuth 客户端后，"
                            "这里会出现「使用 Google 登录」，直接用 Google 账号授权即可添加。"),
    ),
    Provider(
        key="outlook", label="Outlook / Hotmail",
        domains=("outlook.com", "hotmail.com", "live.com", "msn.com"),
        imap_host="outlook.office365.com",
        oauth="standard", oauth_label="使用 Microsoft 登录", device_flow=True,
        note="Microsoft 已停用个人邮箱的 IMAP 密码登录，只能通过 OAuth 添加。",
    ),
    Provider(
        key="custom", label="自定义 IMAP", imap_host="",
        secret=SecretAuth(PASSWORD, "邮箱密码或服务商要求的应用专用密码"),
        note="填写支持 IMAP SSL 的服务器地址和端口。",
    ),
)

PROVIDERS: dict[str, Provider] = {p.key: p for p in _ALL}


def get(key: str) -> Provider | None:
    return PROVIDERS.get((key or "").strip().lower())


def detect(email_addr: str) -> str:
    """按邮箱域名识别服务商；识别不出来时返回 custom。"""
    domain = (email_addr or "").strip().lower().rpartition("@")[2]
    for provider in _ALL:
        if domain and domain in provider.domains:
            return provider.key
    return "custom"


def needs_imap_id(key: str) -> bool:
    provider = get(key)
    return bool(provider and provider.send_imap_id)


def netease_keys() -> tuple[str, ...]:
    return tuple(p.key for p in _ALL if p.oauth == "netease")
