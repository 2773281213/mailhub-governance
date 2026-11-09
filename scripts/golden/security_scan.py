"""导出 security_scan.py（邮件安全扫描）的金标语料。

Kotlin 端在 com.mailhub.core.security.SecurityScan 里重写，必须与这里的每条用例逐字一致。

    python scripts/golden/security_scan.py           # 重新生成 JSON
    python scripts/golden/security_scan.py --check   # 检查是否与当前 Python 实现一致

用例来源：
- tests/test_security.py 里所有调用 security_scan.* 的输入；
- 每个检查项至少 3 条正例、2 条反例（显示名伪装、品牌冒用、同形字符、Reply-To、
  危险附件、链接文字与目标不符、裸 IP、链接域名同形字符、紧迫话术、Authentication-Results）；
- extract_html_links 与 _link_host 的分词与 URL 边界（见 test_security.py 的相关回归测试）；
- 含 emoji、&nbsp;、全角字符的样例。

Python 自身的不确定性：scan 第 6 步的 hosts 是 Python 的 set，`list(hosts)[:40]` 的顺序由
每个进程随机的字符串哈希决定，所以链接里有多个裸 IP、或去重后超过 40 个域名时，Python
自己每次运行的结果都可能不同。脚本对每条 scan 用例断言「去重后的域名不超过 40 个、最多
1 个裸 IP」，不满足就报错、不导出；Kotlin 版用插入顺序，见 SecurityScan 的 KDoc。
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _common  # noqa: E402

_common.prepare_imports()

import security_scan as ss  # noqa: E402

# 函数名 → Python 实现，expected 一律由这里现算，脚本里不手写期望值
FN = {
    "scan": ss.scan,
    "extract_html_links": ss.extract_html_links,
    "link_host": ss._link_host,
    "parse_authentication_results": ss.parse_authentication_results,
    "registrable_domain": ss._registrable,
    "risk_at_least": ss.risk_at_least,
    "has_confusable": ss._has_confusable,
    "domain_of": ss._domain_of,
}

FIRE = "\U0001F525"    # 🔥，U+1F525 在 UTF-16 里占两个码元
GRIN = "\U0001F600"    # 😀
CYR_A = "\u0430"       # а，西里尔字母
GREEK_O = "\u03bf"     # ο，希腊字母
U3000 = "\u3000"       # 全角空格

CASES: list[dict] = []


def add(fn: str, cid: str, **args) -> None:
    """按 fn 的名字调用 Python 原实现，把输入和实际输出都记下来。"""
    CASES.append({"id": cid, "fn": fn, "args": args, "expected": FN[fn](**args)})


def hosts_of(args: dict) -> set[str]:
    """复算 scan 第 6 步收集到的域名集合（正文里的裸链接 + HTML 链接的主机名）。"""
    body = (args.get("body") or "")[:20000]
    hosts = {h.lower().split(":")[0] for h in ss._URL_RE.findall(body)}
    for href, _text in ss.extract_html_links(args.get("html") or ""):
        host = ss._link_host(href)
        if host:
            hosts.add(host)
    return hosts


def add_scan(cid: str, **args) -> None:
    """scan 用例：先断言结果不依赖 Python set 的遍历顺序，再记录输入输出。"""
    hosts = hosts_of(args)
    if len(hosts) > 40:
        raise SystemExit(
            f"{cid}：链接域名去重后有 {len(hosts)} 个，超过 40 个，"
            f"list(hosts)[:40] 的顺序由 set 哈希决定，不能导出这条用例")
    ips = sorted(h for h in hosts if ss._IP_HOST_RE.match(h))
    if len(ips) > 1:
        raise SystemExit(
            f"{cid}：含 {len(ips)} 个裸 IP {ips}，set 顺序会改变命中的那一个，不能导出这条用例")
    add("scan", cid, **args)


# ---------------- 1. tests/test_security.py 里调用 scan 的输入 ----------------

def test_cases() -> None:
    add_scan("py-display-name-spoof",
             subject="账户提醒", body="请点击", sender_name="PayPal service@paypal.com",
             sender_addr="attacker@evil-domain.tk")
    add_scan("py-brand-impersonation",
             subject="您的 Apple ID 异常", body="点此验证", sender_name="Apple 支持",
             sender_addr="no-reply@apple-id-verify.xyz")
    add_scan("py-legit-brand-domain",
             subject="您的收据", body="感谢购买", sender_name="Apple",
             sender_addr="no_reply@apple.com")
    add_scan("py-dangerous-attachment",
             subject="发票", body="见附件", sender_name="财务", sender_addr="a@b.com",
             attachments=["invoice.pdf.exe"])
    add_scan("py-archive-attachment",
             subject="资料", body="见附件", sender_name="同事", sender_addr="a@b.com",
             attachments=["docs.zip"])
    add_scan("py-bare-ip-link",
             subject="请登录", body="访问 http://203.0.113.9/login 完成验证",
             sender_name="IT", sender_addr="it@corp.com")
    add_scan("py-reply-to-mismatch",
             subject="合作", body="详见附件", sender_name="张三",
             sender_addr="zhang@corp.com", reply_to="x@gmail.com")
    add_scan("py-cyrillic-homograph",
             subject="通知", body="内容", sender_name="银行",
             sender_addr="svc@sberb" + CYR_A + "nk.com")
    add_scan("py-urgent-phishing-with-link",
             subject="您的账户异常，请立即验证", body="点击 https://short.tk/x",
             sender_name="客服", sender_addr="a@b.com")
    add_scan("py-clean-mail",
             subject="周会纪要", body="详见正文，无附件。", sender_name="李四",
             sender_addr="lisi@corp.com")

    # test_scan_never_raises_on_weird_input：畸形输入不能抛异常
    add_scan("py-weird-empty", subject="", body="", sender_name="", sender_addr="")
    add_scan("py-weird-none", subject=None, body=None, sender_name=None, sender_addr=None)
    add_scan("py-weird-long", subject="a" * 50000, body="b" * 50000,
             sender_name="c", sender_addr="d@e.f")

    # test_ip_link_hidden_in_html_href_is_high_risk
    add_scan("py-html-href-bare-ip",
             subject="Please review your account",
             body="Your account will be suspended. Click here",
             sender_name="Security Team", sender_addr="alerts@example-bank.com",
             html='<p>Your account will be suspended. '
                  '<a href="http://203.0.113.7/login">Click here</a></p>')
    # test_brand_link_text_pointing_elsewhere_is_high_risk
    add_scan("py-html-brand-text-elsewhere",
             subject="Action required", body="", sender_name="Service",
             sender_addr="notice@mailer.example",
             html='<a href="https://paypa1-secure.example/verify">'
                  'https://www.paypal.com/signin</a>')
    # test_click_tracking_redirect_is_only_low_risk
    add_scan("py-html-click-tracking",
             subject="本周上新", body="", sender_name="Shop",
             sender_addr="news@shop-example.com",
             html='<a href="https://click.tracker.example/r?u=1">www.shop-example.com</a>')
    # test_link_to_senders_own_domain_is_not_flagged
    add_scan("py-html-sender-own-domain",
             subject="Security settings", body="", sender_name="GitHub",
             sender_addr="noreply@github.com",
             html='<a href="https://email.github.com/x">github.com/settings</a>')

    # test_authentication_results 的 6 组输入
    for i, header in enumerate([
        "mx.google.com; dkim=pass header.i=@a.com; spf=pass; dmarc=fail "
        "(p=REJECT) header.from=a.com",
        "mx.example; spf=fail smtp.mailfrom=b.com; dkim=none",
        "mx.example; spf=softfail smtp.mailfrom=b.com",
        "mx.example; spf=fail smtp.mailfrom=b.com; dkim=pass header.d=b.com",
        "mx.example; spf=pass; dkim=pass; dmarc=pass",
        "",
    ]):
        add_scan(f"py-auth-{i}",
                 subject="周会纪要", body="详见正文", sender_name="李四",
                 sender_addr="lisi@corp.com", auth_results=header)

    # test_brand_names_inside_other_words_are_not_impersonation
    for i, name in enumerate(["Pineapple Kitchen", "Googleplex Fan Club", "Amazonia Tours"]):
        add_scan(f"py-brand-subword-{i}",
                 subject="Weekly menu", body="", sender_name=name,
                 sender_addr="hello@kitchen.example")

    # test_malformed_html_does_not_break_scan
    for i, html in enumerate([
        "<a href=",
        "<a href='javascript:alert(1)'>x</a>",
        "<a>no href</a>",
        "<<<>>>",
    ]):
        add_scan(f"py-malformed-html-{i}",
                 subject="s", body="b", sender_name="n", sender_addr="a@b.com", html=html)


# ---------------- 2. 显示名里嵌了另一个域的邮箱地址 ----------------

def display_name_cases() -> None:
    # 正例：显示名里的域名与发件域的可注册域不同
    add_scan("name-spoof-solid",
             subject="账户提醒", body="", sender_name="客服 <service@bank.example>",
             sender_addr="attacker@evil.example")
    add_scan("name-spoof-uppercase",
             subject="账单", body="", sender_name="Service <HELP@PAYPAL.COM>",
             sender_addr="billing@mailer.example")
    add_scan("name-spoof-plus-tag",
             subject="发票", body="", sender_name="a.b+c@shop.example",
             sender_addr="newsletter@mailer.example")
    # 反例：显示名里的域名就是发件域（含子域），或者显示名根本没有地址
    add_scan("name-same-domain",
             subject="通知", body="", sender_name="客服 <service@bank.example>",
             sender_addr="notice@bank.example")
    add_scan("name-plain-text",
             subject="通知", body="", sender_name="只是中文名字 没有地址",
             sender_addr="notice@bank.example")
    # 发件地址里没有 @ 时不做这项判断
    add_scan("name-sender-without-at",
             subject="通知", body="", sender_name="客服 <service@bank.example>",
             sender_addr="no-at-sign")


# ---------------- 3. 品牌冒用（整词边界，中英文） ----------------

def brand_cases() -> None:
    add_scan("brand-english-impostor",
             subject="Your Apple ID", body="", sender_name="Apple Support",
             sender_addr="no-reply@apple-id-verify.example")
    add_scan("brand-english-bold",
             subject="Invoice", body="", sender_name="PAYPAL ALERTS",
             sender_addr="alerts@paypa1.example")
    add_scan("brand-chinese-impostor",
             subject="安全提醒", body="", sender_name="支付宝",
             sender_addr="service@alipay-secure.example")
    add_scan("brand-chinese-wechat",
             subject="登录提醒", body="", sender_name="微信团队",
             sender_addr="notice@weixin-security.example")
    # 反例：官方域名与子域都不算冒用
    add_scan("brand-english-official",
             subject="Receipt", body="", sender_name="Apple", sender_addr="no_reply@apple.com")
    add_scan("brand-english-subdomain",
             subject="Receipt", body="", sender_name="GitHub",
             sender_addr="noreply@mail.github.com")
    add_scan("brand-chinese-official",
             subject="账单", body="", sender_name="支付宝",
             sender_addr="service@alipay.com")
    # 反例：品牌名只是更长的单词的一部分（整词边界不命中）
    add_scan("brand-inside-pineapple",
             subject="Weekly menu", body="", sender_name="Pineapple Kitchen",
             sender_addr="hello@kitchen.example")
    add_scan("brand-inside-amazonia",
             subject="Travel", body="", sender_name="Amazonia Tours",
             sender_addr="hello@tours.example")


# ---------------- 4. 域名里的同形异义字符 ----------------

def confusable_cases() -> None:
    add_scan("confusable-cyrillic-a",
             subject="通知", body="", sender_name="银行",
             sender_addr="svc@sberb" + CYR_A + "nk.com")
    add_scan("confusable-greek-o",
             subject="Invoice", body="", sender_name="Microsoft",
             sender_addr="bill@" + GREEK_O + "ffice.com")
    add_scan("confusable-cyrillic-apple",
             subject="Apple", body="", sender_name="Apple 支持",
             sender_addr="no-reply@" + CYR_A + "pple.com")
    # 反例：纯 ASCII 域名，以及全角拉丁字母（不是西里尔/希腊）
    add_scan("confusable-clean-domain",
             subject="通知", body="", sender_name="客服", sender_addr="svc@sberbank.com")
    add_scan("confusable-fullwidth-latin",
             subject="通知", body="", sender_name="客服",
             sender_addr="svc@\uff42ank.com")


# ---------------- 5. Reply-To 与发件域不一致 ----------------

def reply_to_cases() -> None:
    add_scan("reply-to-free-mail",
             subject="合作", body="", sender_name="张三", sender_addr="zhang@corp.example",
             reply_to="x@gmail.com")
    add_scan("reply-to-other-domain",
             subject="合作", body="", sender_name="张三", sender_addr="zhang@corp.example",
             reply_to="zhang@another-corp.example")
    add_scan("reply-to-subdomain-mismatch",
             subject="合作", body="", sender_name="张三", sender_addr="zhang@a.corp.example",
             reply_to="zhang@b.other.example")
    # 反例：同一可注册域的子域、以及空 Reply-To
    add_scan("reply-to-same-registrable",
             subject="合作", body="", sender_name="张三", sender_addr="zhang@corp.example",
             reply_to="zhang@mail.corp.example")
    add_scan("reply-to-empty",
             subject="合作", body="", sender_name="张三", sender_addr="zhang@corp.example",
             reply_to="")


# ---------------- 6. 危险附件与压缩包附件 ----------------

def attachment_cases() -> None:
    add_scan("attach-exe", subject="发票", body="", sender_name="财务", sender_addr="a@b.example",
             attachments=["invoice.pdf.exe"])
    add_scan("attach-uppercase-ext", subject="结算单", body="", sender_name="财务",
             sender_addr="a@b.example", attachments=["Settlement.XLSM.SCR"])
    add_scan("attach-bat", subject="工资表", body="", sender_name="HR", sender_addr="a@b.example",
             attachments=["salary.bat"])
    add_scan("attach-apk", subject="安装包", body="", sender_name="IT", sender_addr="a@b.example",
             attachments=["update.apk"])
    add_scan("attach-archive-zip", subject="资料", body="", sender_name="同事",
             sender_addr="a@b.example", attachments=["docs.zip"])
    add_scan("attach-archive-multi-dot", subject="资料", body="", sender_name="同事",
             sender_addr="a@b.example", attachments=["backup.2026.tar.7z"])
    add_scan("attach-archive-iso", subject="镜像", body="", sender_name="同事",
             sender_addr="a@b.example", attachments=["disk.iso"])
    # 反例：没有扩展名、扩展名不在名单里、以及正常文档
    add_scan("attach-no-extension", subject="资料", body="", sender_name="同事",
             sender_addr="a@b.example", attachments=["README"])
    add_scan("attach-harmless", subject="资料", body="", sender_name="同事",
             sender_addr="a@b.example", attachments=["report.pdf", "photo.jpeg"])
    add_scan("attach-empty-list", subject="资料", body="", sender_name="同事",
             sender_addr="a@b.example", attachments=[])


# ---------------- 7. 链接文字显示的域名与实际目标不符 ----------------

def link_text_cases() -> None:
    add_scan("text-brand-domain-elsewhere",
             subject="Action required", body="", sender_name="Service",
             sender_addr="notice@mailer.example",
             html='<a href="https://paypa1-secure.example/verify">'
                  'https://www.paypal.com/signin</a>')
    add_scan("text-brand-icloud-to-tracker",
             subject="Action required", body="", sender_name="Service",
             sender_addr="notice@mailer.example",
             html='<a href="https://login.tracker.example/s">apple.com/support</a>')
    add_scan("text-plain-domain-elsewhere",
             subject="本周上新", body="", sender_name="Shop",
             sender_addr="news@shop-example.com",
             html='<a href="https://click.tracker.example/r?u=1">www.shop-example.com</a>')
    # 反例：指向文字显示的域名，或指向发件人自己的域名（点击统计跳转）
    add_scan("text-matches-target",
             subject="Security settings", body="", sender_name="GitHub",
             sender_addr="noreply@github.com",
             html='<a href="https://github.com/settings">github.com/settings</a>')
    add_scan("text-points-to-sender-domain",
             subject="Security settings", body="", sender_name="GitHub",
             sender_addr="noreply@github.com",
             html='<a href="https://email.github.com/x">github.com/settings</a>')
    # 反例：链接文字里没有域名
    add_scan("text-no-domain-in-anchor",
             subject="Click", body="", sender_name="Shop",
             sender_addr="news@shop-example.com",
             html='<a href="https://click.tracker.example/r">点我领取优惠券</a>')


# ---------------- 8. 裸 IP 与链接域名同形字符 ----------------

def host_cases() -> None:
    add_scan("ip-in-body",
             subject="请登录", body="访问 http://203.0.113.9/login 完成验证",
             sender_name="IT", sender_addr="it@corp.example")
    add_scan("ip-in-html-href",
             subject="Please review your account",
             body="Your account will be suspended. Click here",
             sender_name="Security Team", sender_addr="alerts@example-bank.com",
             html='<p>Your account will be suspended. '
                  '<a href="http://203.0.113.7/login">Click here</a></p>')
    add_scan("ip-with-port",
             subject="补丁", body="请更新 https://198.51.100.4:8443/patch",
             sender_name="IT", sender_addr="it@corp.example")
    # 反例：普通域名，以及数字看起来像 IP 但只有三段的主机名
    add_scan("ip-regular-host",
             subject="请登录", body="访问 https://portal.example.com/login 完成验证",
             sender_name="IT", sender_addr="it@corp.example")
    add_scan("ip-three-octets",
             subject="补丁", body="请更新 https://203.0.113.example.com/patch",
             sender_name="IT", sender_addr="it@corp.example")

    # 链接域名含同形字符：正文裸链接与 HTML 链接都算
    add_scan("host-confusable-in-body",
             subject="通知", body="请访问 https://payp" + CYR_A + "l.example/login",
             sender_name="客服", sender_addr="notice@mailer.example")
    add_scan("host-confusable-in-href",
             subject="通知", body="", sender_name="客服",
             sender_addr="notice@mailer.example",
             html='<a href="https://' + GREEK_O + 'ffice.example/x">office.example</a>')
    add_scan("host-confusable-cyrillic-html",
             subject="通知", body="", sender_name="客服",
             sender_addr="notice@mailer.example",
             html='<a href="https://sberb' + CYR_A + 'nk.example/x">详情</a>')
    add_scan("host-plain-html",
             subject="通知", body="", sender_name="客服",
             sender_addr="notice@mailer.example",
             html='<a href="https://portal.example.com/x">详情</a>')


# ---------------- 9. 紧迫话术（带链接 / 不带链接） ----------------

def urgent_cases() -> None:
    add_scan("urgent-cn-with-link",
             subject="您的账户异常，请立即验证", body="点击 https://short.tk/x",
             sender_name="客服", sender_addr="a@b.example")
    add_scan("urgent-cn-body-only",
             subject="日历提醒", body="限时 3 天处理，逾期失效。",
             sender_name="客服", sender_addr="a@b.example")
    add_scan("urgent-en-with-link",
             subject="Urgent action required",
             body="Please confirm your password at https://short.tk/y",
             sender_name="Support", sender_addr="a@b.example")
    add_scan("urgent-en-account-locked",
             subject="Your account suspended", body="Verify your account now",
             sender_name="Support", sender_addr="a@b.example")
    # 反例：没有紧迫话术（即使带外链也只看话术）
    add_scan("urgent-absent-with-link",
             subject="本周上新", body="详见 https://shop.example.com/new",
             sender_name="Shop", sender_addr="news@shop.example.com")
    add_scan("urgent-absent-cn",
             subject="周会纪要", body="详见正文，无附件。", sender_name="李四",
             sender_addr="lisi@corp.example")


# ---------------- 10. Authentication-Results ----------------

def auth_cases() -> None:
    headers = [
        "mx.google.com; dkim=pass header.i=@a.com; spf=pass; dmarc=fail (p=REJECT)",
        "MX.EXAMPLE; SPF=FAIL SMTP.mailfrom=b.com; DKIM=none",
        "mx.example; spf=softfail smtp.mailfrom=b.com",
        "mx.example; spf=fail smtp.mailfrom=b.com; dkim=pass header.d=b.com",
        "mx.example; spf=pass; dkim=pass; dmarc=pass",
        "mx.example; dmarc=none",
        "mx.example; spf=none dkim=none dmarc=none",
        "mx.example; spf=neutral dkim=temperror",
        "",
    ]
    for i, header in enumerate(headers):
        add_scan(f"auth-{i}",
                 subject="周会纪要", body="详见正文", sender_name="李四",
                 sender_addr="lisi@corp.example", auth_results=header)
        add("parse_authentication_results", f"parse-auth-{i}", value=header)
    # 小写化后再匹配：结论只认小写形式
    add("parse_authentication_results", "parse-auth-upper-value",
        value="x; spf=FAIL dkim=Pass dmarc=None")
    # 同一方法出现多次时只取第一条
    add("parse_authentication_results", "parse-auth-first-wins",
        value="x; dmarc=fail dmarc=pass")
    # 全角等号与空格不算匹配
    add("parse_authentication_results", "parse-auth-fullwidth",
        value="x; spf\uff1dpass")
    add("parse_authentication_results", "parse-auth-no-value", value="mx.example; spf=;")
    add("parse_authentication_results", "parse-auth-none", value=None)


# ---------------- 11. 可注册域与发件域 ----------------

def domain_cases() -> None:
    for i, domain in enumerate([
        "mail.example.com", "a.b.example.co.uk", "shop.example.com.cn",
        "www.example.ne.jp", "service.example.com.tw", "example.io", "localhost",
        "", "...a.b...", "EXAMPLE.COM", ".", "a.b.c.d.e", "x.co.uk",
    ]):
        add("registrable_domain", f"registrable-{i}", domain=domain)
    # 非 ASCII 域名原样保留（同形字符由 _has_confusable 另行判断）
    add("registrable_domain", "registrable-confusable", domain="E" + CYR_A + "mple.COM")
    add("registrable_domain", "registrable-none", domain=None)

    for i, addr in enumerate([
        "a@b.com", "A@B.COM", "no-at-sign", "a@b@C.example", "@example.com",
        "zhang@corp.example", "", "a@ s p .com",
    ]):
        add("domain_of", f"domain-of-{i}", addr=addr)
    add("domain_of", "domain-of-none", addr=None)


# ---------------- 12. 同形字符判断与风险等级比较 ----------------

def misc_cases() -> None:
    for i, s in enumerate([
        "svc@sberb" + CYR_A + "nk.com", GREEK_O + "ffice.com", "plain.example.com",
        "\uff42ank.com", "银行.example.com", "", FIRE + "apple.com",
    ]):
        add("has_confusable", f"confusable-{i}", s=s)

    for i, (risk, threshold) in enumerate([
        ("high", "medium"), ("none", "none"), ("low", "low"),
        ("bogus", "low"), ("high", "bogus"), ("medium", "high"),
    ]):
        add("risk_at_least", f"risk-at-least-{i}", risk=risk, threshold=threshold)


# ---------------- 12b. 按码点截断（emoji 让码点下标与 UTF-16 下标错开） ----------------

def truncation_cases() -> None:
    # 正文前 20000 码点：按码点算 URL 还在，按 UTF-16 算（10000 码点）就切掉了
    add_scan("trunc-20000-emoji-keeps-url",
             subject="补丁", body=GRIN * 10000 + " http://203.0.113.9/patch",
             sender_name="IT", sender_addr="it@corp.example")
    # 中文正文超过 20000 码点，URL 在截断点之后
    add_scan("trunc-20000-cjk-cuts-url",
             subject="补丁", body="字" * 21000 + " http://203.0.113.9/patch",
             sender_name="IT", sender_addr="it@corp.example")
    # URL 完整地落在 20000 码点之内
    add_scan("trunc-20000-inside",
             subject="补丁", body="x" * 19960 + " http://203.0.113.9/patch",
             sender_name="IT", sender_addr="it@corp.example")
    # 紧迫话术只看 subject + 正文前 3000 码点
    add_scan("trunc-3000-emoji-keeps-urgent",
             subject="日历提醒", body=GRIN * 1600 + "立即验证",
             sender_name="客服", sender_addr="a@b.example")
    add_scan("trunc-3000-emoji-cuts-urgent",
             subject="日历提醒", body=GRIN * 3100 + "立即验证",
             sender_name="客服", sender_addr="a@b.example")
    add_scan("trunc-3000-ascii-keeps-urgent",
             subject="日历提醒", body="x" * 2990 + "立即验证",
             sender_name="客服", sender_addr="a@b.example")
    # &nbsp; 与全角字符出现在正文和链接文字里
    add_scan("text-nbsp-fullwidth",
             subject="通知", body="请访问 http://203.0.113.9/login" + U3000 + "核实",
             sender_name="客服", sender_addr="notice@mailer.example",
             html='<a href="http://203.0.113.9/login">' + U3000 + 'paypal.com'
                  + U3000 + '\u00a0</a>')
    add_scan("text-emoji-subject",
             subject=FIRE + "您的账户异常，请立即验证" + FIRE,
             body="点击 https://short.tk/x", sender_name="客服" + FIRE,
             sender_addr="a@b.example")


# ---------------- 13. extract_html_links 的边界 ----------------

def html_cases() -> None:
    samples = [
        ("basic", '<p>Your account will be suspended. '
                  '<a href="http://203.0.113.7/login">Click here</a></p>'),
        # 标签名、属性名大小写不敏感
        ("tag-upper", '<A HREF="http://a.example/x">T</A>'),
        ("attr-mixed-case", '<a HrEf="http://a.example/y" CLASS="c">t</a>'),
        # 属性值的三种写法
        ("value-double-quote", '<a href="http://a.example/d">t</a>'),
        ("value-single-quote", "<a href='http://a.example/s'>t</a>"),
        ("value-unquoted", '<a href=http://a.example/u>t</a>'),
        ("value-spaces-around-eq", '<a href = "http://a.example/v" >t</a>'),
        ("value-empty", '<a href="">t</a>'),
        ("value-missing", '<a href>t</a>'),
        # 重复的 href 以最后一个为准
        ("dup-href", '<a href="http://a.example/1" href="http://a.example/2">t</a>'),
        # 属性值与文本里的字符引用都要转换
        ("charref-attr", '<a href="http://a.example/?a=1&amp;b=2">t</a>'),
        ("charref-text", '<a href="http://a.example/">x&nbsp;y&amp;z</a>'),
        ("charref-prefix", '<a href="&notit;">x</a>'),
        ("charref-double", '<a href="&amp;amp;">double</a>'),
        # <a> 嵌套 <a>，以及 <a> 没闭合就结束
        ("nested-a", '<a href="http://a.example/outer">outer'
                     '<a href="http://a.example/inner">inner</a></a>'),
        ("nested-a-deep", '<a href="http://a.example/a"><a href="http://a.example/b">x</a></a>'),
        ("unclosed-a", '<a href="http://a.example/u">unclosed'),
        # <script> / <style> 里的内容
        ("script-inline", '<script>var a = "<a href=\'http://a.example/s\'>";</script>'
                          '<a href="http://a.example/t">t</a>'),
        ("script-unclosed", '<a href="http://a.example/1">1</a><script>unclosed'),
        ("style-inline", '<style>a{color:red}</style><a href="http://a.example/st">st</a>'),
        ("script-upper", '<SCRIPT type="x"><a href="http://a.example/s">s</a ></SCRIPT>'
                         '<a href="http://a.example/u">u</a>'),
        # 注释、<!DOCTYPE>、CDATA
        ("comment", '<!-- <a href="http://a.example/c">no</a> -->'
                    '<a href="http://a.example/d">yes</a>'),
        ("comment-unclosed", '<a href="http://a.example/1">1</a><!-- unclosed'),
        ("doctype", '<!DOCTYPE html><a href="http://a.example/e">e</a>'),
        ("cdata", '<![CDATA[ <a href="http://a.example/f">no</a> ]]>'
                  '<a href="http://a.example/g">g</a>'),
        ("cdata-unclosed", '<a href="http://a.example/1">1</a><![CDATA[ x'),
        ("marked-unknown", '<a href="http://a.example/x">t</a><![weird]>'),
        ("pi", '<?pi?><a href="http://a.example/pi">pi</a>'),
        # 自闭合 <a/>
        ("self-closing", '<a href="http://a.example/h"/>'),
        ("self-closing-attr-tail", '<a href="http://a.example/y" />t</a>'),
        # 不完整的结尾标签与截断的 HTML
        ("incomplete-tag", '<a href="http://a.example/i"'),
        ("truncated-body", '<a href="http://a.example/j">text'),
        ("endtag-junk", '<a href="http://a.example/m">m</a></a foo>'),
        ("endtag-only", '</a>'),
        ("endtag-opens", '</a'),
        ("garbage", '<<<>>>'),
        # 文本侧：空白压缩（含 &nbsp;、U+3000、U+2028）、emoji、内联标签
        ("text-whitespace", '<div><a href="http://a.example/w">multi\nline\n  text</a></div>'),
        ("text-fullwidth-space", '<a href="http://a.example/z">' + U3000 + 'full'
                                 + U3000 + '\u00a0nbsp</a>'),
        ("text-linesep", '<a href="http://a.example/p">\u2028line\u2029t</a>'),
        ("text-emoji", '<a href="http://a.example/q">' + FIRE + ' fire</a>'),
        ("text-inline-tags", '<a href="http://a.example/r"><b>bold</b> text</a>'),
        # 多个链接与 limit
        ("multi-links", '<a href=" http://a.example/1 ">1</a>'
                        '<a href="http://a.example/2">2</a>'
                        '<a href="http://a.example/3">3</a>'),
        ("no-href", '<a>no href</a>'),
        ("amp-at-end", '<a href="http://a.example/a">a y&</a>'),
    ]
    for cid, html in samples:
        add("extract_html_links", f"html-{cid}", html=html)
    add("extract_html_links", "html-limit-2",
        html='<a href="http://a.example/1">1</a><a href="http://a.example/2">2</a>'
             '<a href="http://a.example/3">3</a>', limit=2)
    add("extract_html_links", "html-empty", html="")
    add("extract_html_links", "html-none", html=None)
    add("extract_html_links", "html-protocol-relative",
        html='<a href="//a.example/x">x</a><a href="mailto:a@b.example">m</a>')


# ---------------- 14. _link_host 的边界 ----------------

def link_host_cases() -> None:
    hrefs = [
        # 常见形态
        "https://example.com/x",
        "http://Example.COM:8080/p",
        "https://user:pass@example.com/y",
        "HTTP://EXAMPLE.COM/Z",
        "https://example.com",
        "https://example.com:",
        "https://@example.com/x",
        "https://:@example.com/x",
        "https://example.com./x",
        "https://a.b:1/c",
        # IPv6 方括号
        "http://[::1]:8080/x",
        "http://[2001:db8::1]/x",
        "http://[::ffff:1.2.3.4]/x",
        "http://[2001:db8:0:0:0:0:0:1]/x",
        "http://[0:0:0:0:0:0:0:0]/x",
        "http://[::]/x",
        "http://[::1%25eth0]/x",
        "http://[fe80::1%25ETH0]/x",
        "http://[::1%25]/x",
        "http://user@[::1]:8/",
        "http://[2001:db8::1]:/x",
        "http://[::1]:99999/x",
        # 不合法的方括号：Python 抛 ValueError，_link_host 返回空串
        "http://[foo]/x",
        "http://[1.2.3.4]/x",
        "http://[::1",
        "http://::1]/",
        "http://[]/x",
        "http://[2001:db8::1::2]/x",
        "http://[12345::]/x",
        "http://[g::1]/x",
        "http://[::1]x/",
        "http://[::1],[::2]",
        "http://[v1.abc]/x",
        "http://[vz.abc]/x",
        # 非 http/https 协议
        "ftp://example.com/x",
        "mailto:a@b.example",
        "javascript:alert(1)",
        "data:text/html,x",
        # 相对链接与空主机名
        "/relative/path",
        "relative",
        "//example.com/x",
        "https:///path",
        "https://",
        "https://?x=1",
        "  https://example.com/sp  ",
        # 同形字符与全角
        "https://exa" + CYR_A + "mple.com/",
        "https://" + CYR_A + "pple.com/",
        "https://" + GREEK_O + "ffice.com/",
        "https://exa\uff0fmple.com/x",
        "https://\uff45xample.com/x",
        "https://xn--80ak6aa92e.com/",
        # 片段与查询参数里的域名不参与主机名
        "https://example.com/a?b#c",
        "https://h/\u00e9",
    ]
    for i, href in enumerate(hrefs):
        add("link_host", f"link-host-{i}", href=href)


def build_cases() -> list[dict]:
    test_cases()
    display_name_cases()
    brand_cases()
    confusable_cases()
    reply_to_cases()
    attachment_cases()
    link_text_cases()
    host_cases()
    urgent_cases()
    auth_cases()
    domain_cases()
    misc_cases()
    truncation_cases()
    html_cases()
    link_host_cases()
    return CASES


def main() -> None:
    _common.run("security_scan", build_cases)


if __name__ == "__main__":
    main()
