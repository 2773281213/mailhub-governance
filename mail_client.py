"""IMAP 客户端封装：多服务商预设、163 ID 命令、Outlook XOAUTH2、增量拉取与邮件解析"""
import email
import email.policy
import email.utils
import html as html_mod
import imaplib
import re
import time


# imaplib 默认不认识 ID 命令（RFC 2971），163 不发 ID 会拒绝收信（Unsafe Login）
imaplib.Commands["ID"] = ("NONAUTH", "AUTH", "SELECTED")

# 网易邮箱客户端需要在认证后发送 RFC 2971 ID；各品牌使用各自的服务器。
# Outlook Android 5.2606.0 的 authspecificconfigs/NetEase.kt 里共有七个域名，
# 这里保持同一份品牌清单，避免网页登录拿到的账户被识别成 custom。
NETEASE_PROVIDERS = frozenset(
    {"163", "126", "yeah", "188", "vip163", "vip126", "vip188", "qiye163"}
)
IMAP_ID_PAYLOAD = (
    '("name" "MailHub" "version" "1.1" "vendor" "MailHub" '
    '"support-email" "mailhub@localhost")'
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
        "help": "网页版 QQ 邮箱 → 设置 → 账号 → 开启 IMAP/SMTP 服务，生成 16 位「授权码」，"
                "此处填授权码而非 QQ 密码；QQ 互联的 OAuth token 没有 IMAP 权限",
    },
    "exmail": {
        "label": "腾讯企业邮",
        "host": "imap.exmail.qq.com", "port": 993, "auth": "password", "need_id": False,
        "help": "企业邮网页版 → 设置 → 客户端专用密码；登录名用完整邮箱地址，密码用专用密码。"
                "主机来自企业微信邮件模块的 IMAP 配置，企业自有域名无法自动识别，需要手动选择本项",
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
    "188": {
        "label": "网易 188",
        "host": "imap.188.com", "port": 993, "auth": "password", "need_id": True,
        "help": "网页版 188 邮箱 → 设置 → POP3/SMTP/IMAP → 开启 IMAP/SMTP；登录时使用完整邮箱地址和客户端授权密码",
    },
    "qiye163": {
        "label": "网易企业邮",
        "host": "imap.qiye.163.com", "port": 993, "auth": "password", "need_id": True,
        "help": "网易企业邮（qiye.163.com）→ 管理后台开启 IMAP/SMTP 并生成客户端授权密码；"
                "企业自有域名无法自动识别，需要手动选择本项",
    },
    "vip163": {
        "label": "网易 VIP 163",
        "host": "imap.vip.163.com", "port": 993, "auth": "password", "need_id": True,
        "help": "VIP 163 邮箱 → 设置 → POP3/SMTP/IMAP → 开启 IMAP/SMTP；登录时使用完整邮箱地址和客户端授权密码",
    },
    "vip126": {
        "label": "网易 VIP 126",
        "host": "imap.vip.126.com", "port": 993, "auth": "password", "need_id": True,
        "help": "VIP 126 邮箱 → 设置 → POP3/SMTP/IMAP → 开启 IMAP/SMTP；登录时使用完整邮箱地址和客户端授权密码",
    },
    "vip188": {
        "label": "网易 VIP 188",
        "host": "imap.vip.188.com", "port": 993, "auth": "password", "need_id": True,
        "help": "VIP 188 邮箱 → 设置 → POP3/SMTP/IMAP → 开启 IMAP/SMTP；登录时使用完整邮箱地址和客户端授权密码",
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
        # 只取最上面一条：它由收件服务器写入；更靠下的同名头可能是发件人伪造的
        "auth_results": str((msg.get_all("Authentication-Results") or [""])[0])[:2000],
    }


def unparsable_message() -> dict:
    """解析失败时的占位记录：邮件仍然入库，用户能看到并到网页邮箱查看原文。"""
    return {
        "msg_id": "",
        "subject": "（无法解析的邮件）",
        "sender_name": "",
        "sender_addr": "",
        "to_addr": "",
        "reply_to": "",
        "attach_names": [],
        "date_ts": time.time(),
        "snippet": "MailHub 无法解析这封邮件，请在邮箱网页版查看原文。",
        "body_text": "MailHub 无法解析这封邮件，请在邮箱网页版查看原文。",
        "body_html": "",
        "has_attach": 0,
        "unsubscribe": 0,
        "auth_results": "",
    }


# ---------- IMAP 会话 ----------

def _uid_chunks(uids: list[int], size: int = 100):
    for i in range(0, len(uids), size):
        yield ",".join(str(u) for u in uids[i:i + size])


class ImapSession:
    def __init__(self, host: str, port: int = 993, timeout: int = 30):
        # 超时只作用于本连接；不再用 socket.setdefaulttimeout 改动整个进程的默认值
        self.conn = imaplib.IMAP4_SSL(host, port, timeout=timeout)
        self.caps = frozenset(str(c).upper() for c in getattr(self.conn, "capabilities", ()))

    def _refresh_capabilities(self):
        """登录后重新读取能力列表：Gmail 等服务器在认证前不会通告 UIDPLUS。"""
        typ, data = self.conn.capability()
        if typ == "OK" and data:
            self.caps = frozenset(_imap_response_text(data).upper().split())

    def login_password(self, user: str, password: str):
        self.conn.login(user, password)
        self._refresh_capabilities()

    def login_oauth2(self, user: str, access_token: str):
        auth = f"user={user}\x01auth=Bearer {access_token}\x01\x01"
        self.conn.authenticate("XOAUTH2", lambda _: auth.encode())
        self._refresh_capabilities()

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

    def fetch_messages(self, uids: list[int]) -> tuple[list[dict], set[int]]:
        """按 UID 批量抓取完整邮件（BODY.PEEK 不改变已读状态）。

        返回 (解析后的邮件, 抓取失败的 UID)。调用方只能把同步水位推进到第一个
        失败 UID 之前，下一轮会重试。批次成功但缺少某个 UID 时，视为该邮件
        已被服务器删除，不算失败。无法解析的邮件生成占位记录，而不是静默丢弃。
        """
        out: list[dict] = []
        failed: set[int] = set()
        for i in range(0, len(uids), 20):
            batch = uids[i:i + 20]
            uidset = ",".join(str(u) for u in batch)
            try:
                typ, data = self.conn.uid("fetch", uidset, "(UID FLAGS BODY.PEEK[])")
            except Exception:
                failed.update(batch)
                continue
            if typ != "OK":
                failed.update(batch)
                continue
            for item in data or []:
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
                    parsed = unparsable_message()
                parsed["uid"] = int(m_uid.group(1))
                parsed["unread"] = 0 if "\\Seen" in flags else 1
                out.append(parsed)
        return out, failed

    def store_flags(self, uids: list[int], flag: str, add: bool = True):
        if not uids:
            return
        op = "+FLAGS" if add else "-FLAGS"
        for uidset in _uid_chunks(list(uids)):
            typ, data = self.conn.uid("store", uidset, op, f"({flag})")
            if typ != "OK":
                detail = _imap_response_text(data)
                raise RuntimeError(f"设置邮件标记失败{f'：{detail[:120]}' if detail else ''}")

    def mark_read(self, uids: list[int], read: bool = True):
        self.store_flags(uids, "\\Seen", add=read)

    def delete(self, uids: list[int]) -> str:
        """永久删除指定 UID，只清除 MailHub 自己标记的邮件。

        返回 "expunged"（已从服务器清除）或 "flagged"（已打上 \\Deleted，
        但收件箱里还有其他客户端标记待删的邮件，为避免连带清除而暂不 EXPUNGE；
        大多数客户端会隐藏这些邮件，并在下次清除时一并移除）。
        """
        targets = sorted({int(u) for u in uids if int(u) > 0})
        if not targets:
            return "expunged"
        self.store_flags(targets, "\\Deleted", add=True)

        if "UIDPLUS" in getattr(self, "caps", frozenset()):
            # RFC 4315：UID EXPUNGE 只清除列出的 UID
            for uidset in _uid_chunks(targets):
                typ, data = self.conn.uid("EXPUNGE", uidset)
                if typ != "OK":
                    detail = _imap_response_text(data)
                    raise RuntimeError(f"服务器拒绝清除邮件{f'：{detail[:120]}' if detail else ''}")
            return "expunged"

        # 没有 UIDPLUS 时，普通 EXPUNGE 会清掉收件箱里所有带 \Deleted 的邮件。
        # 只有当待删集合恰好都是本次标记的邮件时才执行。
        typ, data = self.conn.uid("search", None, "DELETED")
        if typ != "OK":
            return "flagged"
        flagged = {int(u) for u in (data[0] or b"").split()} if data else set()
        if not flagged <= set(targets):
            return "flagged"
        typ, data = self.conn.expunge()
        if typ != "OK":
            detail = _imap_response_text(data)
            raise RuntimeError(f"服务器拒绝清除邮件{f'：{detail[:120]}' if detail else ''}")
        return "expunged"

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
