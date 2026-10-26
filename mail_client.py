"""IMAP 客户端封装：登录（密码 / XOAUTH2）、网易 ID 命令、增量拉取与邮件解析"""
import email
import email.policy
import email.utils
import html as html_mod
import imaplib
import re
import time

import providers


# imaplib 默认不认识 ID 命令（RFC 2971），163 不发 ID 会拒绝收信（Unsafe Login）
imaplib.Commands["ID"] = ("NONAUTH", "AUTH", "SELECTED")

# 服务商的主机、认证方式以及是否需要发送 ID 统一定义在 providers.py
IMAP_ID_PAYLOAD = (
    '("name" "MailHub" "version" "1.1" "vendor" "MailHub" '
    '"support-email" "mailhub@localhost")'
)
NETEASE_LOGIN_HELP = (
    "网易邮箱认证失败：请使用完整邮箱地址登录，确认网页邮箱已开启 IMAP/SMTP，"
    "并填写客户端授权密码（不是网页登录密码）"
)


# ---------- 邮件解析辅助 ----------

_TAG_RE = re.compile(r"<(script|style)[^>]*>.*?</\1>", re.S | re.I)
_HTML_RE = re.compile(r"<[^>]+>")


def html_to_text(h: str) -> str:
    h = _TAG_RE.sub(" ", h or "")
    h = re.sub(r"<br\s*/?>|</p>|</div>|</tr>", "\n", h, flags=re.I)
    return html_mod.unescape(_HTML_RE.sub(" ", h))


# 纯文本部分只是「请在浏览器中查看」之类的占位时，真正的内容在 HTML 里
_STUB_HINT = re.compile(
    r"在(网页|浏览器)(上|中)?查看|要查看详情|请访问|查看完整|无法(正常)?显示"
    r"|view\s+(this|it|the)?\s*(e-?mail|message)?\s*(in|on)\s+(your|a|the)?\s*(web\s*)?browser"
    r"|view\s+(it\s+)?online|not\s+support\s+html|open\s+(this|the)\s+(e-?mail|message)\s+in"
    r"|to\s+view\s+(this|the)\s+(e-?mail|message)",
    re.I,
)
_URL_RE = re.compile(r"https?://\S+|<https?://[^>]*>", re.I)


def is_stub_text(text: str, html_text: str) -> bool:
    """纯文本部分是否只是占位：去掉链接后很短且带「浏览器查看」提示，或几乎只有链接，
    同时 HTML 转出的文字明显更长。"""
    plain = re.sub(r"\s+", " ", _URL_RE.sub(" ", text or "")).strip()
    rich = re.sub(r"\s+", " ", html_text or "").strip()
    if len(rich) < max(200, 2 * len(plain)):
        return False
    return len(plain) < 80 or (len(plain) < 600 and bool(_STUB_HINT.search(plain)))


def tidy_body(text: str) -> str:
    text = re.sub(r"[ \t\r]+", " ", text or "")
    return re.sub(r"\n{3,}", "\n\n", text).strip()[:50000]


def make_snippet(text: str) -> str:
    return re.sub(r"\s+", " ", text or "")[:200]


def readable_body(body_text: str, body_html: str) -> str:
    """供分类使用的正文：纯文本是占位或为空时改用 HTML 转出的文字。"""
    if not body_html:
        return body_text or ""
    html_text = html_to_text(body_html)
    if not (body_text or "").strip() or is_stub_text(body_text, html_text):
        return html_text
    return body_text


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


# 常见群发/营销投递平台（Return-Path 或 DKIM d= 出现即视为群发）。
# 不含 Amazon SES、Postmark 这类大量承载验证码和回执的事务邮件通道。
_ESP_RE = re.compile(
    r"sendgrid\.net|mcsv\.net|mcdlv\.net|rsgsv\.net|mailchimp|mandrillapp\.com|mailgun\.org"
    r"|sparkpostmail\.com|sendinblue\.com|brevo\.com|mailjet\.com|exacttarget\.com"
    r"|createsend\.com|cmail\d+\.com|klaviyomail\.com|hubspotemail\.net|constantcontact\.com"
    r"|mktomail\.com|rsys\d*\.net|mailerlite\.com|sendcloud\.(net|org)",
    re.I,
)
_DKIM_DOMAIN_RE = re.compile(r"(?<![a-z])d=([\w.-]+)", re.I)


def header_signals(msg) -> dict:
    """从邮件头提取群发/自动化信号，只返回布尔值，不保存头的原文。"""
    precedence = str(msg.get("Precedence") or "").strip().lower()
    auto = str(msg.get("Auto-Submitted") or "").strip().lower()
    dkim = " ".join(str(h) for h in (msg.get_all("DKIM-Signature") or []))
    dkim_domains = " ".join(_DKIM_DOMAIN_RE.findall(dkim))
    origin = str(msg.get("Return-Path") or "") + " " + dkim_domains
    return {
        "list_id": bool(msg.get("List-Id")),
        "precedence_bulk": precedence in ("bulk", "list", "junk"),
        "auto_submitted": bool(auto) and auto != "no",
        "feedback_id": bool(msg.get("Feedback-ID")),
        "esp": bool(_ESP_RE.search(origin)),
    }


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

    body_text = tidy_body(readable_body(body_text, body_html))
    snippet = make_snippet(body_text)

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
        "header_signals": header_signals(msg),
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
        "header_signals": {},
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
    needs_id = providers.needs_imap_id(account.get("provider", "")) or bool(account.get("need_id"))
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
        if needs_id and _is_auth_failure(exc):
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


def is_auth_failure(exc: Exception) -> bool:
    """异常是否像认证失败（密码、授权码错误）。"""
    return _is_auth_failure(exc)


def _is_auth_failure(exc: Exception) -> bool:
    text = str(exc).lower()
    return any(token in text for token in (
        "auth", "login", "password", "credential", "invalid", "denied", "unsafe login",
    ))
