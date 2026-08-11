"""IMAP 客户端封装：多服务商预设、163 ID 命令、Outlook XOAUTH2、增量拉取与邮件解析"""
import email
import email.policy
import email.utils
import html as html_mod
import imaplib
import re
import socket
import time

import httpx

# imaplib 默认不认识 ID 命令（RFC 2971），163 不发 ID 会拒绝收信（Unsafe Login）
imaplib.Commands["ID"] = ("NONAUTH", "AUTH", "SELECTED")

# 网易邮箱客户端需要在认证后发送 RFC 2971 ID；三个品牌使用各自的服务器。
NETEASE_PROVIDERS = frozenset({"163", "126", "yeah"})
IMAP_ID_PAYLOAD = (
    '("name" "MailHub" "version" "1.1" "vendor" "MailHub" '
    '"support-email" "support@11451405.xyz")'
)
NETEASE_LOGIN_HELP = (
    "网易邮箱认证失败：请使用完整邮箱地址登录，确认网页邮箱已开启 IMAP/SMTP，"
    "并填写客户端授权密码（不是网页登录密码）"
)


# 服务商预设；help 文案会显示在前端添加账户界面
PROVIDERS = {
    "qq": {
        "label": "QQ 邮箱",
        "host": "imap.qq.com", "port": 993, "auth": "password", "need_id": False,
        "help": "网页版 QQ 邮箱 → 设置 → 账号 → 开启 IMAP/SMTP 服务，生成「授权码」，此处填授权码而非 QQ 密码",
    },
    "163": {
        "label": "网易 163",
        "host": "imap.163.com", "port": 993, "auth": "password", "need_id": True,
        "help": "网页版 163 邮箱 → 设置 → POP3/SMTP/IMAP → 开启 IMAP/SMTP；登录时使用完整邮箱地址和客户端授权密码",
    },
    "126": {
        "label": "网易 126",
        "host": "imap.126.com", "port": 993, "auth": "password", "need_id": True,
        "help": "网页版 126 邮箱 → 设置 → POP3/SMTP/IMAP → 开启 IMAP/SMTP；登录时使用完整邮箱地址和客户端授权密码",
    },
    "yeah": {
        "label": "网易 yeah.net",
        "host": "imap.yeah.net", "port": 993, "auth": "password", "need_id": True,
        "help": "网页版 yeah.net 邮箱 → 设置 → POP3/SMTP/IMAP → 开启 IMAP/SMTP；登录时使用完整邮箱地址和客户端授权密码",
    },
    "gmail": {
        "label": "Gmail",
        "host": "imap.gmail.com", "port": 993, "auth": "password", "need_id": False,
        "help": "Google 账号需开启两步验证，然后在 myaccount.google.com/apppasswords 创建「应用专用密码」填入此处（服务器在美国，直连无碍）",
    },
    "outlook": {
        "label": "Outlook / Hotmail",
        "host": "outlook.office365.com", "port": 993, "auth": "oauth", "need_id": False,
        "help": "微软已停用 IMAP 密码登录，点击下方按钮走 OAuth 设备码授权（浏览器打开链接输入代码即可）",
    },
    "custom": {
        "label": "自定义 IMAP",
        "host": "", "port": 993, "auth": "password", "need_id": False,
        "help": "填写任意支持 IMAP SSL 的邮箱服务器",
    },
}

# ---------- Microsoft OAuth2 设备码流程（Thunderbird 公共客户端，个人账户可用） ----------
MS_CLIENT_ID = "9e5f94bc-e8a4-4e73-b8be-63364c29d753"
MS_AUTH_BASE = "https://login.microsoftonline.com/common/oauth2/v2.0"
MS_SCOPE = "https://outlook.office.com/IMAP.AccessAsUser.All offline_access"


def ms_device_code() -> dict:
    """发起设备码授权，返回 user_code / verification_uri / device_code / interval"""
    r = httpx.post(f"{MS_AUTH_BASE}/devicecode",
                   data={"client_id": MS_CLIENT_ID, "scope": MS_SCOPE}, timeout=20)
    r.raise_for_status()
    return r.json()


def ms_poll_token(device_code: str) -> dict:
    """轮询一次令牌端点；未完成授权时返回 {'pending': True}"""
    r = httpx.post(f"{MS_AUTH_BASE}/token", data={
        "client_id": MS_CLIENT_ID,
        "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
        "device_code": device_code,
    }, timeout=20)
    data = r.json()
    if "access_token" in data:
        return data
    if data.get("error") in ("authorization_pending", "slow_down"):
        return {"pending": True}
    raise RuntimeError(data.get("error_description", data.get("error", "授权失败")))


def ms_refresh(refresh_token: str) -> dict:
    r = httpx.post(f"{MS_AUTH_BASE}/token", data={
        "client_id": MS_CLIENT_ID,
        "grant_type": "refresh_token",
        "refresh_token": refresh_token,
        "scope": MS_SCOPE,
    }, timeout=20)
    r.raise_for_status()
    return r.json()


# ---------- 邮件解析辅助 ----------

_TAG_RE = re.compile(r"<(script|style)[^>]*>.*?</\1>", re.S | re.I)
_HTML_RE = re.compile(r"<[^>]+>")


def html_to_text(h: str) -> str:
    h = _TAG_RE.sub(" ", h or "")
    h = re.sub(r"<br\s*/?>|</p>|</div>|</tr>", "\n", h, flags=re.I)
    return html_mod.unescape(_HTML_RE.sub(" ", h))


def _decode_header(raw) -> str:
    if raw is None:
        return ""
    try:
        parts = email.header.decode_header(str(raw))
        out = []
        for data, charset in parts:
            if isinstance(data, bytes):
                out.append(data.decode(charset or "utf-8", errors="replace"))
            else:
                out.append(data)
        return "".join(out).strip()
    except Exception:
        return str(raw)


def parse_message(raw_bytes: bytes) -> dict:
    """把原始 RFC822 字节解析成入库字段"""
    msg = email.message_from_bytes(raw_bytes)
    subject = _decode_header(msg.get("Subject", ""))
    name, addr = email.utils.parseaddr(msg.get("From", ""))
    name = _decode_header(name)

    # 收件地址（含 Delivered-To，用于加号别名匹配）
    to_pairs = email.utils.getaddresses(
        msg.get_all("To", []) + msg.get_all("Delivered-To", []) + msg.get_all("Cc", []))
    to_addr = ",".join(sorted({a.lower() for _, a in to_pairs if a}))[:500]

    date_ts = 0.0
    try:
        dt = email.utils.parsedate_to_datetime(msg.get("Date"))
        if dt is not None:
            date_ts = dt.timestamp()
    except Exception:
        pass
    if not date_ts:
        date_ts = time.time()

    body_text, body_html, has_attach = "", "", 0
    attach_names: list[str] = []
    for part in msg.walk():
        if part.is_multipart():
            continue
        dispo = str(part.get("Content-Disposition") or "")
        ctype = part.get_content_type()
        if "attachment" in dispo:
            has_attach = 1
            # 只取文件名做静态风险判定，绝不读取或落盘附件内容
            try:
                fn = _decode_header(part.get_filename() or "")
            except Exception:
                fn = ""
            if fn:
                attach_names.append(fn[:120])
            continue
        try:
            payload = part.get_payload(decode=True)
            if payload is None:
                continue
            charset = part.get_content_charset() or "utf-8"
            text = payload.decode(charset, errors="replace")
        except Exception:
            continue
        if ctype == "text/plain" and len(body_text) < 60000:
            body_text += text
        elif ctype == "text/html" and len(body_html) < 300000:
            body_html += text

    if not body_text and body_html:
        body_text = html_to_text(body_html)
    body_text = re.sub(r"[ \t\r]+", " ", body_text)
    body_text = re.sub(r"\n{3,}", "\n\n", body_text).strip()[:50000]
    snippet = re.sub(r"\s+", " ", body_text)[:200]

    return {
        "msg_id": (msg.get("Message-ID") or "").strip(),
        "subject": subject,
        "sender_name": name,
        "sender_addr": addr.lower(),
        "to_addr": to_addr,
        "reply_to": (email.utils.parseaddr(msg.get("Reply-To", ""))[1] or "").lower(),
        "attach_names": attach_names,
        "date_ts": date_ts,
        "snippet": snippet,
        "body_text": body_text,
        "body_html": body_html[:300000],
        "has_attach": has_attach,
        "unsubscribe": 1 if msg.get("List-Unsubscribe") else 0,
    }


# ---------- IMAP 会话 ----------

class ImapSession:
    def __init__(self, host: str, port: int = 993, timeout: int = 30):
        socket.setdefaulttimeout(timeout)
        self.conn = imaplib.IMAP4_SSL(host, port)

    def login_password(self, user: str, password: str):
        self.conn.login(user, password)

    def login_oauth2(self, user: str, access_token: str):
        auth = f"user={user}\x01auth=Bearer {access_token}\x01\x01"
        self.conn.authenticate("XOAUTH2", lambda _: auth.encode())

    def send_id(self):
        """认证后提交客户端身份；网易未收到有效 ID 时会拒绝 SELECT。"""
        try:
            typ, data = self.conn.xatom("ID", IMAP_ID_PAYLOAD)
        except Exception as exc:
            raise RuntimeError(f"网易邮箱客户端身份声明失败；{NETEASE_LOGIN_HELP}") from exc
        if typ != "OK":
            detail = _imap_response_text(data)
            suffix = f"（服务器返回：{detail[:100]}）" if detail else ""
            raise RuntimeError(f"网易邮箱拒绝客户端身份声明{suffix}；{NETEASE_LOGIN_HELP}")

    def select_inbox(self) -> int:
        typ, data = self.conn.select("INBOX")
        if typ != "OK":
            detail = _imap_response_text(data)
            if "unsafe login" in detail.lower():
                raise RuntimeError(f"网易邮箱返回 Unsafe Login；{NETEASE_LOGIN_HELP}")
            raise RuntimeError(f"无法打开收件箱{f'：{detail[:120]}' if detail else ''}")
        uv = self.conn.response("UIDVALIDITY")[1]
        try:
            return int(uv[0])
        except (TypeError, ValueError, IndexError):
            return 0

    def search_uids(self, last_uid: int, since_days: int = 30, first_limit: int = 200) -> list[int]:
        """增量：取 UID 大于 last_uid 的邮件；首次：取最近 since_days 天（上限 first_limit 封）"""
        if last_uid > 0:
            typ, data = self.conn.uid("search", None, f"UID {last_uid + 1}:*")
        else:
            date = time.strftime("%d-%b-%Y", time.gmtime(time.time() - since_days * 86400))
            typ, data = self.conn.uid("search", None, f"SINCE {date}")
        if typ != "OK" or not data or not data[0]:
            return []
        uids = [int(u) for u in data[0].split()]
        uids = [u for u in uids if u > last_uid]
        if last_uid == 0 and len(uids) > first_limit:
            uids = uids[-first_limit:]
        return uids

    def fetch_messages(self, uids: list[int]) -> list[dict]:
        """按 UID 批量抓取完整邮件（BODY.PEEK 不改变已读状态），返回解析后的 dict 列表"""
        out = []
        for i in range(0, len(uids), 20):
            batch = uids[i:i + 20]
            uidset = ",".join(str(u) for u in batch)
            typ, data = self.conn.uid("fetch", uidset, "(UID FLAGS BODY.PEEK[])")
            if typ != "OK" or not data:
                continue
            for item in data:
                if not isinstance(item, tuple) or len(item) < 2:
                    continue
                head = item[0].decode(errors="replace") if isinstance(item[0], bytes) else str(item[0])
                m_uid = re.search(r"UID (\d+)", head)
                if not m_uid:
                    continue
                flags = ""
                m_flags = re.search(r"FLAGS \(([^)]*)\)", head)
                if m_flags:
                    flags = m_flags.group(1)
                try:
                    parsed = parse_message(item[1])
                except Exception:
                    continue
                parsed["uid"] = int(m_uid.group(1))
                parsed["unread"] = 0 if "\\Seen" in flags else 1
                out.append(parsed)
        return out

    def store_flags(self, uids: list[int], flag: str, add: bool = True):
        if not uids:
            return
        op = "+FLAGS" if add else "-FLAGS"
        for i in range(0, len(uids), 100):
            uidset = ",".join(str(u) for u in uids[i:i + 100])
            self.conn.uid("store", uidset, op, f"({flag})")

    def mark_read(self, uids: list[int], read: bool = True):
        self.store_flags(uids, "\\Seen", add=read)

    def delete(self, uids: list[int]):
        self.store_flags(uids, "\\Deleted", add=True)
        try:
            self.conn.expunge()
        except Exception:
            pass

    def close(self):
        try:
            self.conn.close()
        except Exception:
            pass
        try:
            self.conn.logout()
        except Exception:
            pass


def open_session(account: dict, secret: str) -> ImapSession:
    """按账户配置建立并登录 IMAP 会话；secret 为解密后的密码/授权码或 OAuth access_token"""
    sess = ImapSession(account["imap_host"], account["imap_port"])
    needs_id = account.get("provider") in NETEASE_PROVIDERS or bool(account.get("need_id"))
    try:
        if account["auth_type"] == "oauth":
            sess.login_oauth2(account["email"], secret)
        else:
            sess.login_password(account["email"], secret)
        # 网易官方要求认证成功后提交 ID；不要在 NONAUTH 状态提前发送。
        if needs_id:
            sess.send_id()
        return sess
    except Exception as exc:
        sess.close()
        if account.get("provider") in NETEASE_PROVIDERS and _is_auth_failure(exc):
            raise RuntimeError(NETEASE_LOGIN_HELP) from exc
        raise


def _imap_response_text(data) -> str:
    """把 IMAP 响应压平成可展示文本，不包含用户凭据。"""
    if not data:
        return ""
    if not isinstance(data, (list, tuple)):
        data = [data]
    parts = []
    for item in data:
        if item is None:
            continue
        if isinstance(item, bytes):
            parts.append(item.decode(errors="replace"))
        else:
            parts.append(str(item))
    return " ".join(parts).strip()


def _is_auth_failure(exc: Exception) -> bool:
    text = str(exc).lower()
    return any(token in text for token in (
        "auth", "login", "password", "credential", "invalid", "denied", "unsafe login",
    ))
