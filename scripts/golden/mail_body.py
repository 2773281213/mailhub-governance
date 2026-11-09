"""导出邮件正文解析（`mail_client.parse_message` 的正文部分与正文辅助函数）的金标语料。

    python scripts/golden/mail_body.py           # 生成 .eml 夹具并写出 JSON
    python scripts/golden/mail_body.py --check   # 只检查 .eml 与 JSON 是否与当前 Python 一致

做三件事：

1. 在 `android/core/src/test/resources/mail/` 下写 L5b 新增的合成 `.eml`（域名一律 `.example`）。
   文件名在 [NEW_FIXTURES] 里明确列出，**不遍历目录**：L5a 的 `mail_headers.py` 管自己那批文件，
   两个脚本互不影响。
2. 对 `mail/` 下**所有** `.eml`（含 L5a 的）导出 `mail_client.parse_message` 的完整结果。
   生成时把 `mail_client.time.time` 固定成 [FIXED_NOW]，`args` 里带 `clock`，所以「日期缺失 /
   非法时回退当前时间」的用例也有确定的 `date_ts`，Kotlin 侧用同样的注入时钟回放。
   Python 在解析阶段直接抛异常的邮件（例如嵌套过深触发 `RecursionError`），
   按 `mail_client._collect` 的做法改成 `unparsable_message()`，即 Kotlin 的 `parseMessage` 的返回值。
3. 导出 `html_to_text`、`is_stub_text`、`tidy_body`、`make_snippet`、`readable_body` 的单函数用例。

金标里不放依赖「当前时间」或「进程本地时区」的用例：夹具的 Date 都带明确时区（或缺失，
由注入时钟兜底），Kotlin 测试用同样的时钟值与固定时区回放。
"""
from __future__ import annotations

import sys
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _common  # noqa: E402

MAIL_DIR = _common.REPO_ROOT / "android" / "core" / "src" / "test" / "resources" / "mail"

# 生成金标时固定的 `time.time()`；Kotlin 测试用同样的时钟值回放。
FIXED_NOW = 1700000000.0

CRLF = b"\r\n"

MESSAGE_FIELDS = [
    "msg_id",
    "subject",
    "sender_name",
    "sender_addr",
    "to_addr",
    "reply_to",
    "attach_names",
    "date_ts",
    "snippet",
    "body_text",
    "body_html",
    "has_attach",
    "unsubscribe",
    "header_signals",
    "auth_results",
]

DATE = "Date: Tue, 05 Nov 2024 10:30:00 +0800"
FROM = "From: Notice <notice@shop.example>"
TO = "To: bob@corp.example"


def mail(headers: list, body: bytes = b"") -> bytes:
    """一封邮件 / 一个 MIME 部件：头部 + 空行 + 正文。正文本身不含结尾换行。"""
    lines = [h if isinstance(h, bytes) else h.encode("utf-8") for h in headers]
    return CRLF.join(lines) + CRLF + CRLF + body


def multipart(boundary: bytes, parts: list, preamble: bytes = b"", epilogue: bytes = b"",
              close: bool = True) -> bytes:
    """拼一个 multipart 正文。每个 part 的正文末尾不加换行，由这里补在边界之前。"""
    out = bytearray(preamble)
    for body in parts:
        out += b"--" + boundary + CRLF + body + CRLF
    if close:
        out += b"--" + boundary + b"--" + CRLF
    out += epilogue
    return bytes(out)


def text_head(subject: str) -> list:
    return [FROM, TO, "Subject: " + subject, DATE]


def long_text(size: int, unit: str = "内容") -> str:
    """重复 [unit] 到大约 [size] 个码点。"""
    return unit * (size // len(unit) + 1)


LONG_PLAIN = (
    "Your order has shipped. Track the parcel at https://shop.example/orders/1 "
    "or reply to this message if you need help from our support team."
)
LONG_HTML = "<html><body><p>Your order has shipped.</p></body></html>"


def deep_nested_message(depth: int) -> bytes:
    """depth 层 multipart/mixed 嵌套的完整邮件：Python 在 1000 层左右抛 RecursionError。"""
    head = CRLF.join(
        line if isinstance(line, bytes) else line.encode("utf-8")
        for line in text_head("Deep nesting") + ['Content-Type: multipart/mixed; boundary="deep0"']
    ) + CRLF + CRLF
    content = b"leaf"
    for index in range(depth, 0, -1):
        boundary = b"deep%d" % index
        content = (
            b'Content-Type: multipart/mixed; boundary="' + boundary + b'"' + CRLF + CRLF
            + b"--" + boundary + CRLF + content + CRLF
            + b"--" + boundary + b"--" + CRLF
        )
    return head + b"--deep0" + CRLF + content + CRLF + b"--deep0--" + CRLF


NEW_FIXTURES: list[tuple[str, bytes]] = [
    (
        "multipart_alternative.eml",
        mail(
            text_head("Multipart alternative") + ['Content-Type: multipart/alternative; boundary="alt1"'],
            multipart(b"alt1", [
                mail(["Content-Type: text/plain; charset=utf-8"], LONG_PLAIN.encode("utf-8")),
                mail(["Content-Type: text/html; charset=utf-8"], LONG_HTML.encode("utf-8")),
            ]),
        ),
    ),
    (
        "multipart_mixed_related.eml",
        # multipart/mixed 里嵌 multipart/related：正文与内嵌图片各归各的
        mail(
            text_head("Mixed with related") + ['Content-Type: multipart/mixed; boundary="mix1"'],
            multipart(b"mix1", [
                mail(["Content-Type: text/plain; charset=utf-8"], "纯文本正文，长度足够。".encode("utf-8")),
                mail(
                    ['Content-Type: multipart/related; boundary="rel1"'],
                    multipart(b"rel1", [
                        mail(["Content-Type: text/html; charset=utf-8"],
                             "<div>HTML 正文，带 <b>粗体</b>。</div>".encode("utf-8")),
                        mail(
                            ["Content-Type: image/png",
                             'Content-Disposition: attachment; filename="logo.png"',
                             "Content-Transfer-Encoding: base64"],
                            b"iVBORw0KGgo=",
                        ),
                    ]),
                ),
            ]),
        ),
    ),
    (
        "html_only.eml",
        # 只有 HTML：body_text 由 readable_body 从 HTML 转出来
        mail(
            text_head("HTML only") + ["Content-Type: text/html; charset=utf-8"],
            "<html><body><h1>欢迎</h1><p>这是只有 HTML 的邮件。</p></body></html>".encode("utf-8"),
        ),
    ),
    (
        "stub_short_plain.eml",
        # 纯文本只有一句占位（< 80 码点），HTML 才是正文
        mail(
            text_head("Stub short plain") + ['Content-Type: multipart/alternative; boundary="s1"'],
            multipart(b"s1", [
                mail(["Content-Type: text/plain"], b"Please view this email in your browser."),
                mail(["Content-Type: text/html; charset=utf-8"],
                     ("<html><body><p>" + long_text(400, "正文") + "</p></body></html>").encode("utf-8")),
            ]),
        ),
    ),
    (
        "stub_hint_long.eml",
        # 纯文本 80–600 码点且带「浏览器查看」提示：判为占位
        mail(
            text_head("Stub hint long") + ['Content-Type: multipart/alternative; boundary="s2"'],
            multipart(b"s2", [
                mail(["Content-Type: text/plain; charset=utf-8"], (
                    "亲爱的用户：为保证投递效果，本通知需要在浏览器中查看完整内容，"
                    "请访问 https://shop.example/view/abc 查看订单详情与物流信息。"
                    "本邮件由系统自动发出，请勿直接回复。"
                ).encode("utf-8")),
                mail(["Content-Type: text/html; charset=utf-8"],
                     ("<html><body><p>" + long_text(600, "详情") + "</p></body></html>").encode("utf-8")),
            ]),
        ),
    ),
    (
        "stub_no_hint.eml",
        # 纯文本 80–600 码点但没有提示、HTML 也不够长：不判占位，用纯文本
        mail(
            text_head("Stub no hint") + ['Content-Type: multipart/alternative; boundary="s3"'],
            multipart(b"s3", [
                mail(["Content-Type: text/plain; charset=utf-8"], (
                    "这是一段完整的纯文本通知，说明订单已经发出，物流单号在附件里，"
                    "如果有问题可以直接回复这封邮件联系客服。本段文字没有任何"
                    "「浏览器查看」之类的占位提示，也没有链接。"
                ).encode("utf-8")),
                mail(["Content-Type: text/html; charset=utf-8"],
                     "<html><body><p>短 HTML。</p></body></html>".encode("utf-8")),
            ]),
        ),
    ),
    (
        "stub_links_only.eml",
        # 纯文本几乎只有链接：去掉链接后很短，HTML 更长 → 占位
        mail(
            text_head("Stub links only") + ['Content-Type: multipart/alternative; boundary="s4"'],
            multipart(b"s4", [
                mail(["Content-Type: text/plain"],
                     b"https://shop.example/a https://shop.example/b <https://shop.example/c>"),
                mail(["Content-Type: text/html; charset=utf-8"],
                     ("<html><body><p>" + long_text(500, "链接") + "</p></body></html>").encode("utf-8")),
            ]),
        ),
    ),
    (
        "html_script_style.eml",
        # <script> / <style> 整段去掉；实体与换行标签各走一遍
        mail(
            text_head("Script and style") + ["Content-Type: text/html; charset=utf-8"],
            (
                "<html><head><style>p{color:red}</style>"
                "<script type=\"text/javascript\">alert('x>y');</script></head>"
                "<body><p>第一段&amp;实体</p><div>第二段&#36;价格</div>"
                "<table><tr><td>格&amp;子</td></tr></table>"
                "<p>a&nbsp;b &lt;tag&gt; &copy;</p></body></html>"
            ).encode("utf-8"),
        ),
    ),
    (
        "html_uppercase_tags.eml",
        # 大写的 <SCRIPT>/<STYLE> 与 <BR/>、</P>、</DIV>、</TR>
        mail(
            text_head("Uppercase tags") + ["Content-Type: text/html; charset=utf-8"],
            (
                "<HTML><HEAD><STYLE>body{margin:0}</STYLE>"
                "<SCRIPT type=\"text/javascript\">var a = 1 < 2;</SCRIPT></HEAD>"
                "<BODY>第一行<BR/>第二行<BR />第三行</P>第四行</DIV>第五行</TR>第六行"
                "</BODY></HTML>"
            ).encode("utf-8"),
        ),
    ),
    (
        "html_entities_emoji.eml",
        # emoji、&nbsp;、全角空格与各种实体
        mail(
            text_head("Entities and emoji") + ["Content-Type: text/html; charset=utf-8"],
            (
                "<div>表情：😀 &#x1F680;</div><div>空格：a&nbsp;b\u3000c&ensp;d</div>"
                "<div>符号：&amp;lt; &lt;b&gt; &quot;q&quot; &apos;a&apos; &#39;</div>"
            ).encode("utf-8"),
        ),
    ),
    (
        "b64_invalid_chars.eml",
        # base64 里混入非法字符：Python 忽略它们照常解码
        mail(
            text_head("Base64 invalid chars") + [
                "Content-Type: text/plain; charset=utf-8",
                "Content-Transfer-Encoding: base64",
            ],
            b"5L2g5aW9!@#77yM6L+Z5piv5q2j5paH44CC",
        ),
    ),
    (
        "b64_missing_padding.eml",
        # base64 缺少 padding（长度不是 4 的倍数）：Python 自动补齐
        mail(
            text_head("Base64 missing padding") + [
                "Content-Type: text/plain; charset=utf-8",
                "Content-Transfer-Encoding: base64",
            ],
            b"5L2g5aW977yM6L+Z5piv5q2j5paH44CC",
        ),
    ),
    (
        "b64_bad_length.eml",
        # base64 长度比 4 的倍数多 1：Python 无法解码，正文变成编码后的原文
        mail(
            text_head("Base64 bad length") + ["Content-Type: text/plain; charset=ascii",
                                              "Content-Transfer-Encoding: base64"],
            b"QUJDREVGQQ",
        ),
    ),
    (
        "qp_soft_break.eml",
        # quoted-printable：软换行、大小写十六进制、非法转义、结尾单个 =
        mail(
            text_head("Quoted printable") + [
                "Content-Type: text/plain; charset=utf-8",
                "Content-Transfer-Encoding: quoted-printable",
            ],
            (
                "=E4=BD=A0=E5=A5=BD=EF=BC=8C\r\n"
                "=E8=BF=99=E6=98=AF\r\n"
                "=e6=ad=A3=e6=96=87=E3=80=82\r\n"
                "bad=ZZ keep=4 lower=e4\r\n"
                "soft=\r\nbreak=\r\nend="
            ).encode("ascii"),
        ),
    ),
    (
        "charset_gb2312_gbk_only.eml",
        # 标称 gb2312，正文含 GBK 独有字节 0x81 0x40：errors="replace" 得到 U+FFFD 加字面 @
        mail(
            text_head("GBK only bytes") + ["Content-Type: text/plain; charset=gb2312"],
            b"\xd5\xcb\xb5\xa5\x81\x40" + "收款".encode("gb2312"),
        ),
    ),
    (
        "charset_unknown.eml",
        # 未知 charset：Python 抛 LookupError，这一部分被跳过
        mail(
            text_head("Unknown charset") + ["Content-Type: text/plain; charset=x-unknown-charset"],
            "这段正文解不出来。".encode("utf-8"),
        ),
    ),
    (
        "no_charset_8bit_cn.eml",
        # 没有 charset 的 8bit 中文：按 utf-8 解
        mail(
            text_head("No charset 8bit") + ["Content-Type: text/plain", "Content-Transfer-Encoding: 8bit"],
            "没有 charset 的中文正文。".encode("utf-8"),
        ),
    ),
    (
        "attach_rfc2231_continuation.eml",
        # RFC 2231 续行 + 百分号编码的附件名
        mail(
            text_head("RFC 2231 continuation") + ['Content-Type: multipart/mixed; boundary="a1"'],
            multipart(b"a1", [
                mail(["Content-Type: text/plain; charset=utf-8"], "正文在附件前面。".encode("utf-8")),
                mail(
                    ["Content-Type: application/octet-stream",
                     "Content-Disposition: attachment; filename*0*=UTF-8''%E8%B4%A6%E5%8D%95;"
                     " filename*1*=%2Epdf"],
                    b"PDFDATA",
                ),
            ]),
        ),
    ),
    (
        "attach_rfc2047_filename.eml",
        # 附件名是 RFC 2047 编码字：先取原始参数，再走 _decode_header
        mail(
            text_head("RFC 2047 filename") + ['Content-Type: multipart/mixed; boundary="a2"'],
            multipart(b"a2", [
                mail(["Content-Type: text/plain"], b"see attachment"),
                mail(
                    ["Content-Type: application/pdf",
                     'Content-Disposition: attachment; filename="=?UTF-8?B?5oql6KGoLnBkZg==?="'],
                    b"PDFDATA",
                ),
            ]),
        ),
    ),
    (
        "attach_no_filename.eml",
        # 没有文件名的附件：has_attach 为 1，attach_names 为空
        mail(
            text_head("Attachment without name") + ['Content-Type: multipart/mixed; boundary="a3"'],
            multipart(b"a3", [
                mail(["Content-Type: text/plain"], b"body"),
                mail(["Content-Type: application/octet-stream", "Content-Disposition: attachment"],
                     b"BLOB"),
            ]),
        ),
    ),
    (
        "attach_inline_filename.eml",
        # inline 带文件名不算附件（子串判断只认 attachment）
        mail(
            text_head("Inline filename") + ['Content-Type: multipart/mixed; boundary="a4"'],
            multipart(b"a4", [
                mail(['Content-Type: text/plain; charset=utf-8',
                      'Content-Disposition: inline; filename="note.txt"'],
                     "inline 的正文要算正文。".encode("utf-8")),
            ]),
        ),
    ),
    (
        "attach_uppercase.eml",
        # 大写的 ATTACHMENT 不算附件：子串判断区分大小写
        mail(
            text_head("Uppercase attachment") + ['Content-Type: multipart/mixed; boundary="a5"'],
            multipart(b"a5", [
                mail(["Content-Type: text/plain"], b"text"),
                mail(["Content-Type: application/pdf",
                      "Content-Disposition: ATTACHMENT; filename=UP.PDF"], b"PDFDATA"),
            ]),
        ),
    ),
    (
        "attach_long_name.eml",
        # 附件名超过 120 码点：截断
        mail(
            text_head("Long attachment name") + ['Content-Type: multipart/mixed; boundary="a6"'],
            multipart(b"a6", [
                mail([b"Content-Type: application/pdf",
                      b"Content-Disposition: attachment; filename=" + b"A" * 130], b"PDFDATA"),
            ]),
        ),
    ),
    (
        "attach_name_param.eml",
        # 没有 filename 参数时退回 Content-Type 的 name 参数（RFC 2231 带 charset）
        mail(
            text_head("Name parameter") + ['Content-Type: multipart/mixed; boundary="a7"'],
            multipart(b"a7", [
                mail(["Content-Type: application/pdf; name*=gb2312''%B1%A8%B8%E6.pdf",
                      "Content-Disposition: attachment"], b"PDFDATA"),
            ]),
        ),
    ),
    (
        "text_over_60000.eml",
        # 第一段超过 60000 码点：累加条件在追加之前判断，第二段不再追加
        mail(
            text_head("Over 60000 code points") + ['Content-Type: multipart/mixed; boundary="t1"'],
            multipart(b"t1", [
                mail(["Content-Type: text/plain"], b"A" * 60001),
                mail(["Content-Type: text/plain"], b"TAIL"),
            ]),
        ),
    ),
    (
        "body_emoji_nbsp.eml",
        # 正文含 emoji、字面 &nbsp;、全角空格、CRLF 与裸 CR
        mail(
            text_head("Emoji and spaces") + ["Content-Type: text/plain; charset=utf-8"],
            "第一行😀\r\n第二行&nbsp;与\u3000全角空格\r第三行".encode("utf-8"),
        ),
    ),
    (
        "empty_body.eml",
        # 只有头部，没有正文
        mail(text_head("Empty body")),
    ),
    (
        "multipart_no_boundary.eml",
        # 声称 multipart 却没有 boundary 参数：当成叶子，不产生正文
        mail(
            text_head("Multipart without boundary") + ["Content-Type: multipart/mixed"],
            b"this is not a part",
        ),
    ),
    (
        "multipart_boundary_missing.eml",
        # 有 boundary 参数但正文里没有分隔行：Python 记缺陷后当叶子处理
        mail(
            text_head("Boundary not found") + ['Content-Type: multipart/mixed; boundary="nope"'],
            b"no boundary line here",
        ),
    ),
    (
        "multipart_double_boundary.eml",
        # 前导文字、连续两条分隔行与结尾文字都要正确处理
        mail(
            text_head("Double boundary") + ['Content-Type: multipart/mixed; boundary="d1"'],
            b"preamble text\r\n"
            + b"--d1\r\n" + mail(["Content-Type: text/plain"], b"first") + b"\r\n"
            + b"--d1\r\n--d1\r\n" + mail(["Content-Type: text/plain"], b"second") + b"\r\n"
            + b"--d1--\r\n"
            + b"epilogue text\r\n",
        ),
    ),
    (
        "multipart_nested_three.eml",
        # 三层嵌套：mixed > related > alternative
        mail(
            text_head("Three levels") + ['Content-Type: multipart/mixed; boundary="l1"'],
            multipart(b"l1", [
                mail(["Content-Type: text/plain; charset=utf-8"], "外层纯文本。".encode("utf-8")),
                mail(
                    ['Content-Type: multipart/related; boundary="l2"'],
                    multipart(b"l2", [
                        mail(
                            ['Content-Type: multipart/alternative; boundary="l3"'],
                            multipart(b"l3", [
                                mail(["Content-Type: text/plain"], b"alt plain"),
                                mail(["Content-Type: text/html"], b"<p>alt html</p>"),
                            ]),
                        ),
                    ]),
                ),
            ]),
        ),
    ),
    (
        "multipart_related_inline_image.eml",
        # inline 的内嵌图片：不算附件，文件名不收集
        mail(
            text_head("Related inline image") + ['Content-Type: multipart/related; boundary="r1"'],
            multipart(b"r1", [
                mail(["Content-Type: text/html; charset=utf-8"], b"<p>inline image below</p>"),
                mail(["Content-Type: image/png",
                      "Content-Disposition: inline; filename=pic.png",
                      "Content-Transfer-Encoding: base64"], b"iVBORw0KGgo="),
            ]),
        ),
    ),
    (
        "message_rfc822_part.eml",
        # message/rfc822 是容器：Python 的 walk 会进到里面，正文照样累加
        mail(
            text_head("Nested message") + ['Content-Type: multipart/mixed; boundary="m1"'],
            multipart(b"m1", [
                mail(["Content-Type: text/plain"], b"outer text"),
                mail(["Content-Type: message/rfc822"],
                     mail(["Content-Type: text/plain"], b"inner text")),
            ]),
        ),
    ),
    (
        "content_type_broken.eml",
        # 没有子类型的 Content-Type：Python 退回 text/plain，正文照样算
        mail(text_head("Broken content type") + ["Content-Type: text"], b"broken type body"),
    ),
    (
        "content_type_uppercase.eml",
        # Content-Type 与 Transfer-Encoding 都大写
        mail(
            text_head("Uppercase content type") + [
                "Content-Type: TEXT/HTML; CHARSET=UTF-8",
                "Content-Transfer-Encoding: BASE64",
            ],
            b"PHA+5aSn5YaZ55qE5q2j5paHPC9wPg==",
        ),
    ),
    (
        "cte_binary.eml",
        mail(
            text_head("Binary transfer encoding") + [
                "Content-Type: text/plain; charset=utf-8",
                "Content-Transfer-Encoding: binary",
            ],
            "二进制传输的中文正文。".encode("utf-8"),
        ),
    ),
    (
        "charset_quoted_uppercase.eml",
        # charset 带引号且大写：get_content_charset 会小写化
        mail(
            text_head("Quoted charset") + ['Content-Type: text/plain; charset="GBK"'],
            "带引号的大写 charset。".encode("gbk"),
        ),
    ),
    (
        "html_charset_gbk.eml",
        mail(
            text_head("GBK html") + ["Content-Type: text/html; charset=gbk"],
            "<div>国标编码的 HTML 正文，含 <b>加粗</b>。</div>".encode("gbk"),
        ),
    ),
    (
        "two_text_parts_trim.eml",
        # 每个 part 正文末尾的换行属于分隔行：拼接时不保留
        mail(
            text_head("Two text parts") + ['Content-Type: multipart/mixed; boundary="p1"'],
            multipart(b"p1", [
                mail(["Content-Type: text/plain"], b"first line"),
                mail(["Content-Type: text/plain"], b"second line"),
            ]),
        ),
    ),
    (
        "deep_nested_recursion.eml",
        # 1100 层嵌套：Python 抛 RecursionError，整封走 unparsable_message()
        deep_nested_message(1100),
    ),
]

HTML_TO_TEXT_SAMPLES = [
    "",
    "没有标签的纯文本",
    "<p>一段</p>",
    "<div>块</div><div>二</div>",
    "a<br>b",
    "a<br/>b",
    "a<br />b",
    "a<br\t/ >b",
    "a<BR CLASS=x>b",
    "<script>alert(1)</script>after",
    "<SCRIPT TYPE=\"text/javascript\">var x = 1 < 2;</SCRIPT>after",
    "<style>p{color:red}</style>after",
    "<STYLE type=\"text/css\">.a{}</STYLE>after",
    "<script>unclosed",
    "<script type=\"a>b\">x</script>tail",
    "<script>a<script>b</script>c</script>",
    "<script>a</style>b</script>c",
    "&amp;lt; &lt;b&gt; &quot;q&quot; &apos;a&apos;",
    "a&nbsp;b\u3000c",
    "&#x1F600; &#36; &copy; &notanentity;",
    "<!-- comment -->text",
    "<img src=\"x.png\">text",
    "<table><tr><td>格</td></tr></table>",
    "<p>a</p><br>",
    ">>>",
]

IS_STUB_SAMPLES = [
    ("", ""),
    ("", "短"),
    ("short", "x" * 300),
    ("Please view this email in your browser.", "x" * 300),
    ("请在浏览器中查看", "x" * 300),
    ("https://shop.example/a https://shop.example/b", "x" * 300),
    ("https://shop.example/a", "x" * 100),
    ("这是一段完整的纯文本通知，说明订单已经发出，如果还有问题可以直接回复这封邮件联系客服人员。", "x" * 600),
    ("这是一段完整的纯文本通知，说明订单已经发出，如果还有问题可以直接回复这封邮件联系客服人员。", "x" * 50),
    ("要查看详情请访问 https://shop.example/x", "y" * 400),
    ("view this email in your browser", "y" * 400),
    ("VIEW IT ONLINE", "y" * 400),
    ("not support html", "y" * 400),
    ("to view this message", "y" * 400),
    ("open this email in browser", "y" * 400),
    ("x" * 79, "y" * 200),
    ("x" * 80, "y" * 200),
    ("x" * 599, "y" * 1200),
    ("x" * 600, "y" * 1200),
    ("短文本", "y" * 199),
    ("短文本", "y" * 200),
]

TIDY_SAMPLES = [
    "",
    "   ",
    "  前后空白  ",
    "a\tb\tc",
    "a \t\rb",
    "a\r\nb",
    "a\rb",
    "a\n\n\nb",
    "a\n\nb",
    "a\n\n\n\n\nb",
    "\n\n开头",
    "结尾\n\n",
    "a  b   c",
    "  第一行\n\n\n第二行  ",
    "a" * 50010,
    ("段落\r\n" * 10),
    "带\u3000全角空格的\t文本",
]

SNIPPET_SAMPLES = [
    "",
    "   ",
    "一行",
    "a  b",
    "a\nb",
    "a\t\tb",
    " 前导 和 结尾 ",
    "a" * 199,
    "a" * 200,
    "a" * 201,
    ("词 " * 120),
    "第一行\n第二行\n第三行",
    "emoji😀 与\u3000全角",
    "\r\n\r\n只留内容\r\n",
    "a" * 100 + " " + "b" * 200,
]

READABLE_SAMPLES = [
    ("", ""),
    ("", "<p>只有 HTML</p>"),
    ("纯文本正文", ""),
    ("纯文本正文", "<html><body><p>" + "长" * 300 + "</p></body></html>"),
    ("Please view this email in your browser.", "<html><body><p>" + "长" * 300 + "</p></body></html>"),
    ("https://shop.example/a", "<html><body><p>" + "长" * 300 + "</p></body></html>"),
    ("   ", "<html><body><p>空白纯文本</p></body></html>"),
    ("\n\t ", "<p>HTML 兜底</p>"),
    ("完整纯文本", "<p>短</p>"),
    ("a" * 600, "b" * 1200),
    ("<p>纯文本里的标签</p>", ""),
    ("", "<script>x</script>"),
    ("要查看详情请访问 https://shop.example/x", "<div>" + "详" * 300 + "</div>"),
    ("x" * 79, "y" * 200),
    ("x" * 600, "y" * 1300),
    ("带&amp;实体的纯文本", ""),
]


def fixture(name: str) -> bytes:
    for fixture_name, data in NEW_FIXTURES:
        if fixture_name == name:
            return data
    raise KeyError(name)


def write_fixtures(check: bool) -> None:
    """写出（或检查）L5b 的合成 .eml。文件按字节原样写入，不做行尾转换。"""
    stale = []
    if not check:
        MAIL_DIR.mkdir(parents=True, exist_ok=True)
    for name, data in NEW_FIXTURES:
        path = MAIL_DIR / name
        if check:
            if not path.exists() or path.read_bytes() != data:
                stale.append(name)
        else:
            path.write_bytes(data)
    if stale:
        print(
            f"{len(stale)} 个 .eml 与 scripts/golden/mail_body.py 不一致："
            + ", ".join(stale[:10]),
            file=sys.stderr,
        )
        sys.exit(1)


def message_cases(parse_message, unparsable_message) -> list[dict]:
    """`mail/` 下所有 .eml 的完整 `parse_message` 结果（含 L5a 的夹具）。"""
    cases = []
    for path in sorted(MAIL_DIR.glob("*.eml")):
        data = path.read_bytes()
        # 固定 time.time()：日期缺失 / 非法的夹具也能导出确定的 date_ts
        with mock.patch("mail_client.time.time", return_value=FIXED_NOW):
            try:
                parsed = parse_message(data)
            except Exception:  # noqa: BLE001 —— 与 _collect 一样，解析失败就存占位记录
                parsed = unparsable_message()
        expected = {field: parsed[field] for field in MESSAGE_FIELDS}
        cases.append({
            "id": "parse-" + path.name.removesuffix(".eml"),
            "fn": "parse_message",
            "args": {"file": path.name, "clock": FIXED_NOW},
            "expected": expected,
        })
    return cases


def build_cases() -> list[dict]:
    _common.prepare_imports()
    import mail_client

    if len(NEW_FIXTURES) < 30:
        raise ValueError("L5b 至少要有 30 封新增夹具")

    cases = message_cases(mail_client.parse_message, mail_client.unparsable_message)

    for index, sample in enumerate(HTML_TO_TEXT_SAMPLES):
        cases.append({
            "id": f"html-to-text-{index:02d}",
            "fn": "html_to_text",
            "args": {"html": sample},
            "expected": mail_client.html_to_text(sample),
        })

    for index, (text, html_text) in enumerate(IS_STUB_SAMPLES):
        cases.append({
            "id": f"is-stub-text-{index:02d}",
            "fn": "is_stub_text",
            "args": {"text": text, "html_text": html_text},
            "expected": mail_client.is_stub_text(text, html_text),
        })

    for index, sample in enumerate(TIDY_SAMPLES):
        cases.append({
            "id": f"tidy-body-{index:02d}",
            "fn": "tidy_body",
            "args": {"text": sample},
            "expected": mail_client.tidy_body(sample),
        })

    for index, sample in enumerate(SNIPPET_SAMPLES):
        cases.append({
            "id": f"make-snippet-{index:02d}",
            "fn": "make_snippet",
            "args": {"text": sample},
            "expected": mail_client.make_snippet(sample),
        })

    for index, (body_text, body_html) in enumerate(READABLE_SAMPLES):
        cases.append({
            "id": f"readable-body-{index:02d}",
            "fn": "readable_body",
            "args": {"body_text": body_text, "body_html": body_html},
            "expected": mail_client.readable_body(body_text, body_html),
        })

    return cases


def main() -> None:
    write_fixtures(check="--check" in sys.argv)
    _common.run("mail_body", build_cases)


if __name__ == "__main__":
    main()
