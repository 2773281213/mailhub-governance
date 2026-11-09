"""导出邮件头解析（`mail_client.parse_message` 的头部部分）的金标语料。

    python scripts/golden/mail_headers.py           # 生成 .eml 夹具并写出 JSON
    python scripts/golden/mail_headers.py --check   # 只检查 .eml 与 JSON 是否与当前 Python 一致

做三件事：

1. 在 `android/core/src/test/resources/mail/` 下写合成 `.eml`（域名一律 `.example`，
   例外见 [FIXTURES] 里标了注释的 ESP 域名）。文件名在 [FIXTURES] 里明确列出，**不遍历目录**：
   L5b 会往同一个目录加文件，本脚本的 `--check` 不能因此失败。
2. 用 `mail_client.parse_message` 解析每封夹具，导出只与头部有关的字段（`fn` 为 `parse_headers`）。
   生成时把 `time.time()` 固定成 [FIXED_NOW]，这样日期缺失 / 非法（含 8 位字节）的夹具
   也有确定的 `date_ts`，Kotlin 侧用同样的注入时钟回放。
3. 导出 `_decode_header`、`parseaddr`、`getaddresses`、`parsedate_to_datetime` 的单函数用例，
   以及两类「Python 抛异常」的用例：`Message-ID` 含 8 位字节（`AttributeError`）、
   地址解析嵌套过深（`RecursionError`）。

金标里不放依赖「当前时间」或「进程本地时区」的用例：这类行为在 Kotlin 测试里用注入的时钟和
固定时区单独验证（见 `MailHeadersGoldenTest` 与 `MailHeadersTest`）。
"""
from __future__ import annotations

import email.utils
import sys
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _common  # noqa: E402

MAIL_DIR = _common.REPO_ROOT / "android" / "core" / "src" / "test" / "resources" / "mail"

# 生成金标时固定的 `time.time()`，用于日期缺失 / 非法的夹具；Kotlin 测试用同样的时钟值回放。
FIXED_NOW = 1700000000.0

# 这些夹具会让 Python 的 `parse_message` 直接抛异常（整封走 `unparsable_message()`），
# 不能用 `parse_headers` 导出字段，改用 `parse_headers_unparsable` 记录异常类型。
UNPARSABLE_FIXTURES = {"msgid_8bit.eml"}

# Python 确实抛 `RecursionError` 的嵌套深度：(kind, depth)。kind 见 recursion_cases。
RECURSION_CASES = [("comments", 5000), ("groups", 2000)]

HEADER_FIELDS = [
    "msg_id",
    "subject",
    "sender_name",
    "sender_addr",
    "to_addr",
    "reply_to",
    "date_ts",
    "unsubscribe",
    "auth_results",
    "header_signals",
]


def b64(text: str, charset: str = "utf-8") -> str:
    """把一个字符串编成 `=?charset?b?...?=` 形式的编码字。"""
    import base64

    return "=?%s?B?%s?=" % (charset, base64.b64encode(text.encode(charset)).decode("ascii"))


def q(charset: str, data: bytes) -> str:
    """把一段字节编成 `=?charset?q?...?=` 的 Q 编码（保留下划线转空格的行为）。"""
    out = []
    for byte in data:
        if byte == 0x20:
            out.append("_")
        elif 0x21 <= byte <= 0x7E and byte not in (0x3D, 0x3F, 0x5F):
            out.append(chr(byte))
        else:
            out.append("=%02X" % byte)
    return "=?%s?q?%s?=" % (charset, "".join(out))


def message(headers: list, body: str = "这是正文。\r\n", linesep: str = "\r\n") -> bytes:
    """拼一封合成邮件。headers 里可以混用 str 与 bytes（用来塞 8 位字节）。"""
    parts = []
    for header in headers:
        parts.append(header if isinstance(header, bytes) else header.encode("ascii"))
    parts.append(b"")
    parts.append(body.encode("utf-8"))
    return linesep.encode("ascii").join(parts)


# 每个夹具的 Date 都带明确时区，导出结果才与本机时区无关。
DATE_0800 = "Date: Tue, 05 Nov 2024 10:30:00 +0800"
DATE_GMT = "Date: Wed, 06 Nov 2024 02:30:00 GMT"
DATE_TWO_DIGIT = "Date: Thu, 07 Nov 24 08:00:00 +0800"
DATE_EST = "Date: Fri, 08 Nov 2024 09:15:30 EST"
DATE_RFC850 = "Date: Saturday, 09-Nov-24 12:00:00 GMT"

FIXTURES: list[tuple[str, bytes]] = [
    (
        "plain_ascii.eml",
        message([
            "From: Alice <alice@shop.example>",
            "To: bob@corp.example",
            "Subject: Quarterly report",
            DATE_0800,
            "Message-ID: <plain-1@mail.shop.example>",
        ]),
    ),
    (
        "utf8_b64_subject.eml",
        message([
            "From: =?utf-8?B?6YCa55+l?= <notify@bank.example>",
            "To: bob@corp.example",
            "Subject: =?utf-8?B?5L2g5aW977yM6L+Z5piv5LiA5bCB5rWL6K+V6YKu5Lu2?=",
            DATE_GMT,
        ]),
    ),
    (
        "utf8_q_subject.eml",
        message([
            "From: =?utf-8?q?=E8=B4=A6=E5=8D=95=E4=B8=AD=E5=BF=83?= <billing@shop.example>",
            "To: bob@corp.example",
            "Subject: =?utf-8?Q?=E6=9C=AC=E6=9C=88=E8=B4=A6=E5=8D=95?=",
            DATE_GMT,
        ]),
    ),
    (
        "gb2312_q_subject.eml",
        message([
            "From: =?gb2312?B?0ru2oc2o1qo=?= <notify@bank.example>",
            "To: bob@corp.example",
            "Subject: " + q("gb2312", "账户安全提醒".encode("gb2312")),
            DATE_0800,
        ]),
    ),
    (
        "gb2312_sender_encoded.eml",
        # 发件人显示名也是 gb2312 编码字
        message([
            "From: " + b64("银行通知", "gb2312") + " <notice@bank.example>",
            "To: bob@corp.example",
            "Subject: GB2312 sender",
            DATE_0800,
        ]),
    ),
    (
        "gb2312_gbk_only.eml",
        # 0x8140 是 GBK 独有码位（U+4E02）。gb2312 编解码器严格按 GB2312-80，
        # 解不出来，`errors="replace"` 得到 U+FFFD 加字面 `@`（不是两个 U+FFFD）。
        message([
            "From: notifier@bank.example",
            "To: bob@corp.example",
            "Subject: =?gb2312?q?=81=40?=",
            DATE_0800,
        ]),
    ),
    (
        "two_encoded_words.eml",
        # 两个编码字之间只隔一个空格：这段空白要被丢掉
        message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject: =?utf-8?B?5L2g?= =?utf-8?B?5aW9?=",
            DATE_0800,
        ]),
    ),
    (
        "mixed_text_words.eml",
        message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject: Re: =?utf-8?B?5L2g5aW9?= (fwd) 2024",
            DATE_0800,
        ]),
    ),
    (
        "folded_subject.eml",
        # 头部折行：`get` 拿到的值里保留 CRLF 与续行空白
        message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject: =?utf-8?B?5L2g5aW9?=",
            "  =?utf-8?B?5LiW55WM?= and more text",
            DATE_0800,
        ]),
    ),
    (
        "folded_from.eml",
        message([
            "From: =?utf-8?B?6LSi5Yqh6YOo?=",
            "  <finance@corp.example>",
            "To: bob@corp.example",
            "Subject: Folded from",
            DATE_0800,
        ]),
    ),
    (
        "quoted_name_comma.eml",
        # 显示名里带逗号，必须加引号，否则会被当成两个地址
        message([
            'From: "Doe, John" <john@shop.example>',
            'To: "Smith, Jane" <jane@corp.example>',
            "Subject: Quoted comma",
            DATE_0800,
        ]),
    ),
    (
        "comment_address.eml",
        # 老式写法：地址后面跟注释
        message([
            "From: jsmith@shop.example (John Smith)",
            "To: bob@corp.example",
            "Subject: Comment address",
            DATE_0800,
        ]),
    ),
    (
        "group_address.eml",
        message([
            "From: notice@shop.example",
            "To: undisclosed-recipients:;",
            "Cc: team@corp.example",
            "Subject: Group address",
            DATE_0800,
        ]),
    ),
    (
        "multi_to.eml",
        message([
            "From: notice@shop.example",
            "To: a@corp.example, B <b@corp.example>",
            "Cc: c@corp.example",
            "Delivered-To: alias+tag@corp.example",
            "Subject: Multi recipients",
            DATE_0800,
        ]),
    ),
    (
        "to_dedupe_sort.eml",
        # 同一地址大小写不同、Delivered-To 与 To 重复：结果要小写去重后按码点排序
        message([
            "From: notice@shop.example",
            "To: Zed@Corp.example, amy@corp.example",
            "Cc: ZED@corp.example, bob@corp.example, amy@CORD.example",
            "Delivered-To: amy@corp.example",
            "Subject: Dedupe and sort",
            DATE_0800,
        ]),
    ),
    (
        "to_truncate.eml",
        message([
            "From: notice@shop.example",
            "To: " + ", ".join("user%02d@corp.example" % index for index in range(30)),
            "Subject: Truncated recipients",
            DATE_0800,
        ]),
    ),
    (
        "reply_to.eml",
        message([
            "From: newsletter@shop.example",
            "Reply-To: Support Team <support@shop.example>",
            "To: bob@corp.example",
            "Subject: Reply-To header",
            DATE_0800,
        ]),
    ),
    (
        "reply_to_empty.eml",
        # Reply-To 存在但解析不出地址：reply_to 是空串
        message([
            "From: newsletter@shop.example",
            "Reply-To: not-an-address",
            "To: bob@corp.example",
            "Subject: Broken reply-to",
            DATE_0800,
        ]),
    ),
    (
        "list_unsubscribe.eml",
        message([
            "From: newsletter@shop.example",
            "To: bob@corp.example",
            "List-Unsubscribe: <mailto:unsub@shop.example>",
            "List-Id: Shop Newsletter <news.shop.example>",
            "Subject: Unsubscribe present",
            DATE_0800,
        ]),
    ),
    (
        "precedence_bulk.eml",
        message([
            "From: bulk@shop.example",
            "To: bob@corp.example",
            "Precedence: Bulk",
            "Subject: Bulk precedence",
            DATE_0800,
        ]),
    ),
    (
        "precedence_list.eml",
        message([
            "From: list@shop.example",
            "To: bob@corp.example",
            "Precedence:  list  ",
            "Subject: List precedence",
            DATE_0800,
        ]),
    ),
    (
        "precedence_other.eml",
        # 不在 bulk/list/junk 里的取值：信号为假
        message([
            "From: urgent@shop.example",
            "To: bob@corp.example",
            "Precedence: urgent",
            "Subject: Urgent precedence",
            DATE_0800,
        ]),
    ),
    (
        "auto_submitted.eml",
        message([
            "From: autoresponder@shop.example",
            "To: bob@corp.example",
            "Auto-Submitted: auto-replied",
            "Subject: Auto submitted",
            DATE_0800,
        ]),
    ),
    (
        "auto_submitted_no.eml",
        # `Auto-Submitted: no` 不算自动投递
        message([
            "From: human@shop.example",
            "To: bob@corp.example",
            "Auto-Submitted: NO",
            "Subject: Auto submitted no",
            DATE_0800,
        ]),
    ),
    (
        "feedback_id.eml",
        message([
            "From: reports@shop.example",
            "To: bob@corp.example",
            "Feedback-ID: 12345:shop:shop.example",
            "Subject: Feedback id",
            DATE_0800,
        ]),
    ),
    (
        "return_path_esp.eml",
        # Return-Path 命中群发平台（sendgrid）。sendgrid.net 不是 .example 域名：
        # 这里必须用真实 ESP 域名才能命中 `_ESP_RE`，是夹具里唯一几处有意为之的例外之一。
        message([
            "Return-Path: <bounce@sendgrid.net>",
            "From: promo@shop.example",
            "To: bob@corp.example",
            "Subject: ESP return path",
            DATE_0800,
        ]),
    ),
    (
        "dkim_multi.eml",
        # 两个 DKIM-Signature：d= 域名要全部取出来拼在一起再匹配
        message([
            "DKIM-Signature: v=1; a=rsa-sha256; d=mailchimp.example; s=sel; b=abc",
            "DKIM-Signature: v=1; a=rsa-sha256; d=shop.example; s=sel2; b=def",
            "From: promo@shop.example",
            "To: bob@corp.example",
            "Subject: Two DKIM signatures",
            DATE_0800,
        ]),
    ),
    (
        "dkim_domain_only.eml",
        # d= 指向群发平台，但 Return-Path 干净。mandrillapp.com 同 sendgrid.net：
        # 为了命中 `_ESP_RE` 必须用真实域名，不改成 .example。
        message([
            "DKIM-Signature: v=1; a=rsa-sha256; d=mandrillapp.com; s=sel; b=abc",
            "From: promo@shop.example",
            "To: bob@corp.example",
            "Subject: DKIM domain only",
            DATE_0800,
        ]),
    ),
    (
        "auth_results_multi.eml",
        # 两条 Authentication-Results：只取最上面一条
        message([
            "Authentication-Results: mx.corp.example; spf=pass smtp.mailfrom=shop.example",
            "Authentication-Results: forged.example; spf=fail smtp.mailfrom=evil.example",
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject: Two auth results",
            DATE_0800,
        ]),
    ),
    (
        "auth_results_long.eml",
        message([
            "Authentication-Results: mx.corp.example; " + "dkim=pass header.d=shop.example; " * 80,
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject: Long auth results",
            DATE_0800,
        ]),
    ),
    (
        "msgid_whitespace.eml",
        # Message-ID 两侧有空白：入库前要 strip
        message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Message-ID:   <spaced-id@mail.shop.example>   ",
            "Subject: Spaced message id",
            DATE_0800,
        ]),
    ),
    (
        "date_two_digit_year.eml",
        message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject: Two digit year",
            DATE_TWO_DIGIT,
        ]),
    ),
    (
        "date_est.eml",
        message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject: EST date",
            DATE_EST,
        ]),
    ),
    (
        "date_rfc850.eml",
        message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject: RFC 850 date",
            DATE_RFC850,
        ]),
    ),
    (
        "date_no_seconds.eml",
        # 没有秒的时间，且时区紧贴在时间后面
        message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject: Date without seconds",
            "Date: Mon, 11 Nov 2024 07:30 +0900",
        ]),
    ),
    (
        "malformed_from_paren.eml",
        # 括号不配对：Python 的严格解析整段判失败，sender 两个字段都是空串
        message([
            "From: John (unclosed <john@shop.example>",
            "To: bob@corp.example",
            "Subject: Unbalanced comment",
            DATE_0800,
        ]),
    ),
    (
        "malformed_to_bracket.eml",
        # 域名里的 `[` 说明域名限制没解析成功，严格解析判失败
        message([
            "From: notice@shop.example",
            "To: user@[corp.example",
            "Subject: Broken domain literal",
            DATE_0800,
        ]),
    ),
    (
        "no_from_header.eml",
        message([
            "To: bob@corp.example",
            "Subject: No From header",
            DATE_0800,
        ]),
    ),
    (
        "raw_8bit_subject.eml",
        # 头部里有非 ASCII 字节（不是合法 UTF-8）：compat32 把它们变成 U+FFFD
        message([
            b"From: notice@shop.example",
            b"To: bob@corp.example",
            b"Subject: caf\xe9 au lait \xa1hola!",
            DATE_0800.encode("ascii"),
        ]),
    ),
    (
        "bad_base64.eml",
        # base64 长度不合法：Python 抛 HeaderParseError，_decode_header 退回原始字符串
        message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject: =?utf-8?B?a?=",
            DATE_0800,
        ]),
    ),
    (
        "unknown_charset.eml",
        # 未知 charset：LookupError，同样退回原始字符串
        message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject: =?x-unknown-charset?B?YWJj?=",
            DATE_0800,
        ]),
    ),
    (
        "latin1_b64.eml",
        message([
            "From: =?iso-8859-1?B?Sm9zw6k=?= <jose@shop.example>",
            "To: bob@corp.example",
            "Subject: =?iso-8859-1?q?caf=E9?=",
            DATE_0800,
        ]),
    ),
    (
        "latin1_sender_b64.eml",
        # 显示名用 iso-8859-1 编码字：José
        message([
            "From: " + b64("José", "iso-8859-1") + " <jose@shop.example>",
            "To: bob@corp.example",
            "Subject: Latin-1 sender",
            DATE_0800,
        ]),
    ),
    (
        "duplicate_subject.eml",
        # 两个 Subject：`get` 取第一条
        message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject: =?utf-8?B?56ys5LiA5bCB?=",
            "Subject: =?utf-8?B?56ys5LqM5bCB?=",
            DATE_0800,
        ]),
    ),
    (
        "lowercase_headers.eml",
        # 头名大小写不敏感：全部小写也要取到
        message([
            "from: notice@shop.example",
            "to: bob@corp.example",
            "subject: =?utf-8?B?5bCP5YaZ5aS05ZCN?=",
            "date: Tue, 05 Nov 2024 10:30:00 +0800",
            "message-id: <lowercase-id@mail.shop.example>",
        ]),
    ),
    (
        "cp1252_subject.eml",
        # cp1252 里的欧元符号（0x80）
        message([
            "From: shop@shop.example",
            "To: bob@corp.example",
            "Subject: " + q("windows-1252", b"\x80 100 \x93quoted\x94"),
            DATE_0800,
        ]),
    ),
    (
        "big5_subject.eml",
        message([
            "From: shop@shop.example",
            "To: bob@corp.example",
            "Subject: " + b64("繁體中文郵件", "big5"),
            DATE_0800,
        ]),
    ),
    (
        "shift_jis_subject.eml",
        message([
            "From: shop@shop.example",
            "To: bob@corp.example",
            "Subject: " + b64("日本語のメール", "shift_jis"),
            DATE_0800,
        ]),
    ),
    (
        "euckr_subject.eml",
        message([
            "From: shop@shop.example",
            "To: bob@corp.example",
            "Subject: " + b64("한국어 메일", "euc-kr"),
            DATE_0800,
        ]),
    ),
    (
        "iso2022jp_subject.eml",
        message([
            "From: shop@shop.example",
            "To: bob@corp.example",
            "Subject: " + b64("こんにちは", "iso-2022-jp"),
            DATE_0800,
        ]),
    ),
    (
        "gbk_subject.eml",
        message([
            "From: shop@shop.example",
            "To: bob@corp.example",
            "Subject: " + q("gbk", "简体中文（GBK）".encode("gbk")),
            DATE_0800,
        ]),
    ),
    (
        "gb18030_subject.eml",
        message([
            "From: shop@shop.example",
            "To: bob@corp.example",
            "Subject: " + b64("GB18030 编码的主题", "gb18030"),
            DATE_0800,
        ]),
    ),
    (
        "lf_line_endings.eml",
        # 只有 LF 的邮件：折行与取值同样要正确
        message(
            [
                "From: notice@shop.example",
                "To: bob@corp.example",
                "Subject: =?utf-8?B?5L2g5aW9?=",
                "  =?utf-8?B?5LiW55WM?=",
                DATE_0800,
            ],
            linesep="\n",
        ),
    ),
    (
        "empty_subject.eml",
        # Subject 存在但是空的：subject 是空串，unsubscribe 为 0
        message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject:",
            "List-Unsubscribe:",
            DATE_0800,
        ]),
    ),
    (
        "unfolded_tab.eml",
        # 续行用制表符开头
        message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject: first",
            "\tsecond",
            DATE_0800,
        ]),
    ),
    (
        "semicolon_subject.eml",
        # 中文标点与 emoji（BMP 之外的字符）
        message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject: =?utf-8?B?5oql6KGo77ya8J+TiA==?=",
            DATE_0800,
        ]),
    ),
    (
        "unix_from_line.eml",
        # mbox 的信封行：解析头部时要丢掉，不算头部
        b"From sender@shop.example Tue Nov  5 10:30:00 2024\r\n"
        + message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject: Unix from line",
            DATE_0800,
        ]),
    ),
    (
        "colon_at_start.eml",
        # 冒号在第 0 列的行是缺陷：跳过它，后面的头部照常解析
        message([
            ":defect line",
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject: Colon at start",
            DATE_0800,
        ]),
    ),
    (
        "value_starts_with_newline.eml",
        # 冒号后面直接换行再接续行：值前面的 CRLF 与空白要一起去掉
        message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject:",
            "   =?utf-8?B?5L2g5aW9?=",
            DATE_0800,
        ]),
    ),
    (
        "delivered_to_only.eml",
        # 只有 Delivered-To：收件地址也要合并进来（加号别名匹配靠它）
        message([
            "From: notice@shop.example",
            "Delivered-To: alias+tag@corp.example",
            "Subject: Delivered-To only",
            DATE_0800,
        ]),
    ),
    # ---- H2：头部含 8 位字节。compat32 下 msg.get 返回 Header 对象而不是 str ----
    (
        "from_8bit.eml",
        # parseaddr(Header) 直接返回 ("", "")：发件人两个字段都是空串
        message([
            b"From: \xe5\xbc\xa0 <forged@bank.example>",
            "To: bob@corp.example",
            "Subject: Eight bit from",
            DATE_0800,
        ]),
    ),
    (
        "reply_to_8bit.eml",
        message([
            "From: newsletter@shop.example",
            b"Reply-To: \xe5\xbc\xa0 <reply@bank.example>",
            "To: bob@corp.example",
            "Subject: Eight bit reply-to",
            DATE_0800,
        ]),
    ),
    (
        "date_8bit.eml",
        # parsedate_to_datetime(Header) 抛 AttributeError：parse_message 回退 time.time()
        message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            b"Date: Tue, 05 Nov 2024 10:30:00 +0800 (\xb1\xb1)",
            "Subject: Eight bit date",
        ]),
    ),
    (
        "msgid_8bit.eml",
        # (msg.get("Message-ID") or "").strip() 抛 AttributeError：整封走 unparsable_message()
        message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            b"Message-ID: <\xe5\xbc\xa0@mail.shop.example>",
            "Subject: Eight bit message id",
            DATE_0800,
        ]),
    ),
    (
        "recipients_8bit.eml",
        # getaddresses 先对每个值做 str(v)（净化成 U+FFFD），收件地址照常解析
        message([
            "From: notice@shop.example",
            b"To: \xe5\xbc\xa0 <a@corp.example>",
            b"Cc: \xb1 <c@corp.example>",
            b"Delivered-To: \xe5 <d@corp.example>",
            "Subject: Eight bit recipients",
            DATE_0800,
        ]),
    ),
    (
        "subject_8bit_encoded.eml",
        # 8 位字节与编码字混排：_decode_header 先 str(Header)，替换符留在普通文本里，
        # 再按 raw-unicode-escape 变成字面量 \ufffd 后与编码字拼接
        message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            b"Subject: caf\xe9 =?utf-8?B?5L2g5aW9?=",
            DATE_0800,
        ]),
    ),
    (
        "signals_8bit.eml",
        # header_signals 全部先 str(...)：Precedence 变成 "\ufffdbulk"（不算 bulk），
        # List-Id / Feedback-ID 非空仍为真，Authentication-Results 带替换符，ESP 仍命中 sendgrid
        message([
            b"Return-Path: <bounce@sendgrid.net\xe5>",
            b"DKIM-Signature: v=1; a=rsa-sha256; d=\xe5mailchimp; s=sel; b=abc",
            b"Precedence: \xe5bulk",
            b"List-Id: \xe5news.shop.example",
            b"Authentication-Results: \xe5mx.corp.example; spf=pass",
            b"Feedback-ID: \xe5123",
            b"Auto-Submitted: \xe5auto",
            b"List-Unsubscribe: \xe5<mailto:unsub@shop.example>",
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject: Eight bit signals",
            DATE_0800,
        ]),
    ),
    # ---- H3：单独的 \r 也断行（Python 的 BufferedSubFile 按 \r\n|\r|\n 切）----
    (
        "bare_cr_subject_from.eml",
        # 伪造的 From 是独立的头，而且排在真实 From 前面，msg.get 取到的就是它
        b"Subject: x\rFrom: forged@evil.example\r\nFrom: real@bank.example\r\n"
        + DATE_0800.encode("ascii")
        + b"\r\n\r\nbody\r\n",
    ),
    (
        "bare_cr_blank_line.eml",
        # 裸 \r 构成的空行结束头部块：B、From、Subject 都落到正文里
        b"A: 1\r\rB: 2\r\nFrom: notice@shop.example\r\nSubject: s\r\n\r\nbody\r\n",
    ),
    (
        "bare_cr_folded_subject.eml",
        # 裸 \r 后面接空格是续行，值里保留这个 \r
        b"Subject: first\r second\r\nFrom: notice@shop.example\r\n"
        + DATE_0800.encode("ascii")
        + b"\r\n\r\nbody\r\n",
    ),
    # ---- L3：Compat32.header_source_parse 的 lstrip(' \t\r\n') ----
    (
        "auth_results_folded_leading.eml",
        # 冒号后直接换行再接一行一个空格：lstrip 后值是 "x"
        b"Authentication-Results:\r\n x\r\nFrom: notice@shop.example\r\nTo: bob@corp.example\r\n"
        b"Subject: Folded auth results\r\n" + DATE_0800.encode("ascii") + b"\r\n\r\nbody\r\n",
    ),
    (
        "list_id_blank.eml",
        # 续行只有一个空格：lstrip + rstrip('\r\n') 后是空串，bool 为假
        b"List-Id:\r\n \r\nFrom: notice@shop.example\r\nTo: bob@corp.example\r\n"
        b"Subject: Blank list id\r\n" + DATE_0800.encode("ascii") + b"\r\n\r\nbody\r\n",
    ),
    # ---- M6：日期边界 ----
    (
        "date_offset_1900.eml",
        # +19:00 合法（datetime.timezone 允许 ±24 小时以内；Java 的 ZoneOffset 只到 ±18）
        message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject: Plus 19 offset",
            "Date: Tue, 05 Nov 2024 10:30:00 +1900",
        ]),
    ),
    (
        "date_year_10000.eml",
        # 10000 年超出 datetime 的 1–9999，回退当前时间
        message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject: Year 10000",
            "Date: 1 Jan 10000 00:00:00 +0000",
        ]),
    ),
    (
        "date_huge_offset.eml",
        # 偏移绝对值远超 24 小时，datetime.timezone 拒绝，回退当前时间
        message([
            "From: notice@shop.example",
            "To: bob@corp.example",
            "Subject: Huge offset",
            "Date: Tue, 05 Nov 2024 10:30:00 +99999999999",
        ]),
    ),
]


def fixture(name: str) -> bytes:
    for fixture_name, data in FIXTURES:
        if fixture_name == name:
            return data
    raise KeyError(name)


def write_fixtures(check: bool) -> None:
    """写出（或检查）合成 .eml。文件按字节原样写入，不做行尾转换。"""
    stale = []
    if not check:
        MAIL_DIR.mkdir(parents=True, exist_ok=True)
    for name, data in FIXTURES:
        path = MAIL_DIR / name
        if check:
            if not path.exists() or path.read_bytes() != data:
                stale.append(name)
        else:
            path.write_bytes(data)
    if stale:
        print(
            f"{len(stale)} 个 .eml 与 scripts/golden/mail_headers.py 不一致："
            + ", ".join(stale[:10]),
            file=sys.stderr,
        )
        sys.exit(1)


def parse_headers_cases(parse_message) -> list[dict]:
    cases = []
    for name, data in FIXTURES:
        if name in UNPARSABLE_FIXTURES:
            continue
        # 固定 time.time()：日期缺失 / 非法的夹具也能导出确定的 date_ts
        with mock.patch("mail_client.time.time", return_value=FIXED_NOW):
            parsed = parse_message(data)
        expected = {field: parsed[field] for field in HEADER_FIELDS}
        cases.append({
            "id": name.removesuffix(".eml"),
            "fn": "parse_headers",
            "args": {"file": name, "clock": FIXED_NOW},
            "expected": expected,
        })
    return cases


def unparsable_cases(parse_message) -> list[dict]:
    """`parse_message` 在 Python 里直接抛异常的夹具：整封走 `unparsable_message()`。"""
    cases = []
    for name in sorted(UNPARSABLE_FIXTURES):
        data = fixture(name)
        try:
            with mock.patch("mail_client.time.time", return_value=FIXED_NOW):
                parse_message(data)
        except Exception as exc:  # noqa: BLE001 —— 金标记录 Python 实际抛出的类型
            error = type(exc).__name__
        else:
            raise ValueError(f"{name}：Python 没有抛异常，不能作为不可解析用例")
        cases.append({
            "id": name.removesuffix(".eml"),
            "fn": "parse_headers_unparsable",
            "args": {"file": name, "clock": FIXED_NOW},
            "expected": error,
        })
    return cases


def recursion_cases() -> list[dict]:
    """Python 确实抛 `RecursionError` 的输入：Kotlin 要抛普通 Exception，不能是 `Error`。"""
    cases = []
    for kind, depth in RECURSION_CASES:
        text = "(" * depth + ")" * depth if kind == "comments" else ":".join(["a"] * depth)
        try:
            email.utils.parseaddr(text)
        except RecursionError:
            error = "RecursionError"
        except Exception as exc:  # noqa: BLE001 —— 记录实际类型，方便排查
            error = type(exc).__name__
        else:
            raise ValueError(f"{kind} 深度 {depth} 没有触发 RecursionError，请加大深度")
        cases.append({
            "id": f"parseaddr-recursion-{kind}",
            "fn": "parseaddr_recursion",
            "args": {"kind": kind, "depth": depth},
            "expected": error,
        })
    return cases


DECODE_HEADER_SAMPLES = [
    None,
    "",
    "   ",
    "纯文本主题",
    "Re: =?utf-8?B?5L2g5aW9?=",
    "=?utf-8?B?5L2g5aW9?=",
    "=?utf-8?b?5L2g5aW9?=",
    "=?utf-8?q?caf=C3=A9?=",
    "=?UTF-8?Q?caf=C3=A9?=",
    "=?utf-8?B?5L2g?= =?utf-8?B?5aW9?=",
    "=?utf-8?B?5L2g?=   =?utf-8?B?5aW9?=",
    "=?utf-8?B?5L2g?= =?gb2312?B?xOO6ww==?=",
    "a =?utf-8?B?5L2g?= b =?utf-8?B?5aW9?= c",
    "=?utf-8?B?5L2g5aW9?= trail",
    "=?utf-8?q?a_b?=",
    "=?gb2312?B?xOO6ww==?=",
    "=?gb2312?q?=81=40?=",
    "=?gbk?q?=81=40?=",
    "=?big5?B?pMFA?=",
    "=?shift_jis?B?grGC8YLJgr+CzQ==?=",
    "=?euc-kr?B?x9Gxub6u?=",
    "=?iso-2022-jp?B?GyRCJCIbKEI=?=",
    "=?iso-8859-1?q?caf=E9?=",
    "=?windows-1252?q?=80?=",
    "=?x-unknown?B?YWJj?=",
    "=?utf-8?B?a?=",
    "=?utf-8?B?5L2g5aW9?=  =?utf-8?B?5LiW55WM?=",
    "  前导空白 =?utf-8?B?5L2g5aW9?=  尾巴  ",
    "=?utf-8?B?5L2g?=\r\n =?utf-8?B?5aW9?=",
    "=?utf-8?q??=",
    "=?utf-8?b??=",
    "=?utf-8?B?5L2g5aW9?= =?utf-8?q?x?=",
    "=?utf-8?B?5L2g5aW9?=x=?utf-8?B?5aW977yM?=",
    "=?utf-8?B?5L2g?=\r\n\t=?utf-8?B?5aW9?=",
    "=?utf-8?q?=E4=BD=A0?= =?gbk?B?xOO6ww==?= =?utf-8?B?5aW9?=",
    "trailing text =?utf-8?B?5L2g5aW9?=   ",
    "=?utf-8?B?5L2g5aW9?= =?utf-8?B?5LiW55WM?= tail =?utf-8?B?77yB?=",
    "=?utf-8?B?5L2g5aW9?= =?x-unknown?B?YWJj?=",
    "not =? utf-8 ?B?5L2g5aW9?= an encoded word",
    "=?utf-8?x?abc?=",
    "=?utf-8?B?5L2g5aW9?==?utf-8?B?5LiW55WM?=",
    " ，=?utf-8?B?5L2g5aW9?=。 ",
    "=?iso-2022-jp?B?GyRCJCIbKEI=?= =?utf-8?B?5L2g5aW9?=",
    # M2：binascii.a2b_base64 的宽松状态机。`=` 只有在 quad_pos >= 2 且 quad_pos + pads >= 4
    # 时才终止解码，所以第一个 `=` 之后的字符仍可能参与解码。
    "=?utf-8?B?QUJD=REVG?=",
    "=?utf-8?B?Q=UJD?=",
    "=?utf-8?B?QUJD\xffREVG?=",
    # M3：charset 为空串时 Python 走 `charset or "utf-8"`
    "=??q?a?=",
    "=??B?YWJj?=",
    # L1：raw-unicode-escape 把 U+10000 以上写成 \\UXXXXXXXX
    "😀 =?utf-8?q?a?=",
    "😀 =?utf-8?B?QUJD?=",
]

PARSEADDR_SAMPLES = [
    "Alice <alice@shop.example>",
    "alice@shop.example",
    "<alice@shop.example>",
    '"Doe, John" <john@shop.example>',
    "Doe, John <john@shop.example>",
    "jsmith@shop.example (John Smith)",
    "John Smith <jsmith@shop.example>",
    "  spaced@shop.example  ",
    "",
    "not-an-address",
    "undisclosed-recipients:;",
    "Group: a@shop.example, b@shop.example;",
    "a@shop.example, b@shop.example",
    "Alice <alice@shop.example",
    "Alice alice@shop.example>",
    "user@[127.0.0.1]",
    "user@[corp.example",
    "a@malicious.example@important.example",
    "=?utf-8?B?5L2g5aW9?= <you@shop.example>",
    "@shop.example",
    "a@",
    '"quoted local"@shop.example',
    "Name <sp ace@shop.example>",
    "Name (comment) <c@shop.example>",
    "one@shop.example two@shop.example",
    "Name (one) (two) <c@shop.example>",
    "'single quoted' <s@shop.example>",
    '"escaped \\" quote" <q@shop.example>',
    "<@route.example:user@shop.example>",
    '"a b" <a@shop.example>, "c d" <c@shop.example>',
    "Name <>",
    "<>",
    "a@shop.example (comment",
    "a@shop.example)",
    "Name <a@shop.example> extra",
    "  ",
    "\t",
    "Name\t<a@shop.example>",
    # L2：Python 的 `oldcl` 只是 commentlist 的别名，注释会被重复收集
    "a(c1)@b.example",
    "a(c1)@b.example, c@d.example",
    "(c1)a@b.example",
    "a@b.example (c1) (c2)",
    # 深度 100 的嵌套注释在 Python 里还不算 RecursionError（Kotlin 的上限见 AddressParse）
    "(" * 100 + ")" * 100,
]

GETADDRESSES_SAMPLES = [
    ["Alice <alice@shop.example>"],
    ["Alice <alice@shop.example>", "Bob <bob@corp.example>"],
    ["a@shop.example, b@shop.example"],
    ["a@shop.example", "b@shop.example"],
    ['"Doe, John" <john@shop.example>'],
    ["Doe, John <john@shop.example>"],
    ["undisclosed-recipients:;"],
    [],
    [""],
    ["", ""],
    ["a@shop.example", ""],
    ["jsmith@shop.example (John Smith)"],
    ["Group: a@shop.example, b@shop.example;"],
    ["a@shop.example, b@shop.example, c@shop.example"],
    ["Alice <alice@shop.example>, Bob"],
    ["user@[127.0.0.1]"],
    ["user@[corp.example"],
    ["a@malicious.example@important.example"],
    ["Alice <alice@shop.example"],
    ["@"],
    ["a@shop.example\tb@shop.example"],
    ["=?utf-8?B?5L2g5aW9?= <you@shop.example>"],
    ["a@shop.example,", ",a@shop.example"],
    ["Name <a@shop.example>, Name <a@shop.example>"],
    ["a(c1)@b.example"],
    ["(c1)a@b.example"],
    ["a@b.example (c1) (c2)"],
]

PARSEDATE_SAMPLES = [
    "Tue, 05 Nov 2024 10:30:00 +0800",
    "Tue, 5 Nov 2024 10:30:00 +0800",
    "05 Nov 2024 10:30:00 +0800",
    "Tue, 05 Nov 2024 10:30:00 GMT",
    "Tue, 05 Nov 2024 10:30:00 UT",
    "Tue, 05 Nov 2024 10:30:00 UTC",
    "Tue, 05 Nov 2024 10:30:00 Z",
    "Tue, 05 Nov 2024 10:30:00 EST",
    "Tue, 05 Nov 2024 10:30:00 EDT",
    "Tue, 05 Nov 2024 10:30:00 CST",
    "Tue, 05 Nov 2024 10:30:00 PDT",
    "Tue, 05 Nov 2024 10:30:00 -0500",
    "Tue, 05 Nov 2024 10:30:00 +0000",
    "Tue, 05 Nov 2024 10:30 +0900",
    "Thu, 07 Nov 24 08:00:00 +0800",
    "Thursday, 07-Nov-24 08:00:00 GMT",
    "Sat, 09 Nov 2024 12:00:00 +0000",
    "Mon, 11 Nov 2024 07:30:59 +0530",
    "11 Nov 2024 07:30:59 -0700",
    "Tue, 05 Nov 2024 23:59:59 +1400",
    "Tue, 05 Nov 2024 00:00:01 -1200",
    "Wed, 06 Nov 2024 02:30:00 gmt",
    "Tue, 05 November 2024 10:30:00 +0800",
    "Tue, 05 Nov 2024 10:30:00 +0800 (CST)",
    "1 Jan 1970 00:00:00 +0000",
    "Wed, 31 Dec 1969 23:59:59 -0500",
    "Mon, 29 Feb 2024 12:00:00 +0100",
    "Tue, 05 Nov 68 10:30:00 +0800",
    "Tue, 05 Nov 69 10:30:00 +0800",
    "Fri, 01 Mar 2024 00:00:00 -0930",
    "Tue, 05 Nov 2024 10:30:00 +0000 extra",
    # M6：日期边界
    "Tue, 05 Nov 2024 10:30:00 +1900",        # ±18–24 小时之间：合法
    "Tue, 05 Nov 2024 10:30:00 -1900",
    "Tue, 05 Nov 2024 10:30:00 +1800",        # 恰好 18 小时：Java ZoneOffset 的上限
    "Tue, 05 Nov 2024 10:30:00 +2400",        # 恰好 24 小时：datetime.timezone 拒绝
    "1 Jan 9999 00:00:00 +0000",              # datetime 允许的最大年份
    "1 Jan 10000 00:00:00 +0000",             # 年份越界
    "1 Jan 0 00:00:00 +0000",                 # 两位年份规则：0 → 2000
    "Tue, 05 Nov 2024 10:30:00 +99999999999", # 偏移远超 ±24 小时
    "Tue, 05 Nov 2024 10:30:00 -99999999999",
]


def build_cases() -> list[dict]:
    _common.prepare_imports()
    import mail_client

    cases = parse_headers_cases(mail_client.parse_message)
    cases += unparsable_cases(mail_client.parse_message)
    cases += recursion_cases()

    for index, sample in enumerate(DECODE_HEADER_SAMPLES):
        cases.append({
            "id": f"decode-header-{index:02d}",
            "fn": "decode_header",
            "args": {"raw": sample},
            "expected": mail_client._decode_header(sample),
        })

    for index, sample in enumerate(PARSEADDR_SAMPLES):
        name, addr = email.utils.parseaddr(sample)
        cases.append({
            "id": f"parseaddr-{index:02d}",
            "fn": "parseaddr",
            "args": {"addr": sample},
            "expected": [name, addr],
        })

    for index, values in enumerate(GETADDRESSES_SAMPLES):
        cases.append({
            "id": f"getaddresses-{index:02d}",
            "fn": "getaddresses",
            "args": {"values": values},
            "expected": [[name, addr] for name, addr in email.utils.getaddresses(values)],
        })

    for index, sample in enumerate(PARSEDATE_SAMPLES):
        # 年份越界、偏移越界时 Python 抛 ValueError；金标写成 null，对应 Kotlin 的 parse 失败
        try:
            parsed = email.utils.parsedate_to_datetime(sample)
            expected = parsed.timestamp()
        except (ValueError, OverflowError):
            expected = None
        cases.append({
            "id": f"parsedate-{index:02d}",
            "fn": "parsedate_to_datetime",
            "args": {"value": sample},
            "expected": expected,
        })

    return cases


def main() -> None:
    write_fixtures(check="--check" in sys.argv)
    _common.run("mail_headers", build_cases)


if __name__ == "__main__":
    main()
