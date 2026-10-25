"""邮件分类基准语料：全部为合成数据，不含任何真实邮件、地址或验证码。

每条样本：id、subject、body、sender、unsubscribe（是否带 List-Unsubscribe）、
signals（邮件头群发信号）、expected（期望的本地规则分类）、可选 otp（期望提取的验证码）
和 note（为什么这样判）。tests/test_classification.py 与 scripts/eval_classifier.py 共用。
"""
from collections import Counter

CORPUS = [
    # ---------- 验证码 ----------
    {"id": "otp-cn-subject", "subject": "【腾讯云】验证码 384756，5分钟内有效", "body": "",
     "sender": "noreply@tencent.example", "expected": "验证码", "otp": "384756"},
    {"id": "otp-en-body", "subject": "Your verification code",
     "body": "Your code is 921034. It expires in 10 minutes.",
     "sender": "no-reply@github.example", "expected": "验证码", "otp": "921034",
     "note": "句末句号不能挡住验证码"},
    {"id": "otp-cn-body", "subject": "登录验证", "body": "您的验证码为：552910，请勿泄露。",
     "sender": "service@pay.example", "expected": "验证码", "otp": "552910"},
    {"id": "otp-one-time", "subject": "Sign in to Example",
     "body": "Use this one-time code to sign in: 739204",
     "sender": "account@example.com", "expected": "验证码", "otp": "739204"},
    {"id": "otp-pickup", "subject": "快递已到驿站", "body": "取件码 8823，请凭码到驿站取件",
     "sender": "noreply@express.example", "expected": "验证码", "otp": "8823"},
    {"id": "otp-alnum", "subject": "Steam Guard code", "body": "Your Steam Guard code: F7K2Q",
     "sender": "noreply@game.example", "expected": "验证码", "otp": "F7K2Q"},

    # ---------- 不是验证码的数字 ----------
    {"id": "trap-year", "subject": "年度报告 2024", "body": "全年营收 2024 万元",
     "sender": "report@corp.example", "expected": "其他", "note": "年份不是验证码"},
    {"id": "trap-promo-code", "subject": "Weekend sale is here",
     "body": "Use code: SAVE20 for 20% off everything", "sender": "promo@store.example",
     "unsubscribe": True, "expected": "订阅", "note": "优惠码不是验证码"},
    {"id": "trap-order-verify", "subject": "Order confirmed",
     "body": "Order 4829173 confirmed. Please verify your email address.",
     "sender": "orders@shop.example", "expected": "账单",
     "note": "verify 附近的订单号不是验证码"},
    {"id": "trap-price", "subject": "限时秒杀，仅需 ¥4999",
     "body": "一次性付清立享优惠，验证码登录即可参与", "sender": "market@shop.example",
     "unsubscribe": True, "expected": "订阅", "note": "价格不是验证码"},
    {"id": "trap-card-tail", "subject": "信用卡还款提醒",
     "body": "尾号 6688 的信用卡本期应还 3200 元，验证码相关业务请勿泄露",
     "sender": "bank@bank.example", "expected": "账单", "note": "卡号尾号和金额不是验证码"},

    # ---------- 重要 ----------
    {"id": "important-interview", "subject": "面试邀请：后端工程师",
     "body": "请于周三下午两点参加面试", "sender": "hr@company.example", "expected": "重要"},
    {"id": "important-urgent-person", "subject": "Urgent: board meeting moved to 3pm",
     "body": "Please confirm you can attend.", "sender": "ceo@corp.example", "expected": "重要"},
    {"id": "important-contract", "subject": "劳动合同续签确认", "body": "请在附件中签署合同",
     "sender": "hr@corp.example", "expected": "重要"},
    {"id": "trap-offer-promo", "subject": "Special offer just for you - 50% off",
     "body": "Shop now and save", "sender": "deals@shop.example", "expected": "订阅",
     "note": "营销 offer 不是重要"},
    {"id": "trap-deadline-bulk", "subject": "Deadline extended: early bird tickets",
     "body": "Register now", "sender": "events@conf.example", "unsubscribe": True,
     "expected": "订阅", "note": "群发邮件里的 deadline 不是重要"},
    {"id": "trap-offer-cn", "subject": "限时特惠 offer 截止今晚", "body": "大促最后一天",
     "sender": "promo@mall.example", "expected": "订阅"},
    {"id": "trap-deadline-esp", "subject": "Exclusive deadline for members",
     "body": "Join before Friday", "sender": "club@brand.example", "signals": {"esp": True},
     "expected": "其他", "note": "群发平台投递的 deadline 不判重要，交给 AI"},

    # ---------- 安全 ----------
    {"id": "security-cn", "subject": "异常登录提醒", "body": "检测到您的账号在新设备登录",
     "sender": "security@mail.example", "expected": "安全"},
    {"id": "security-en", "subject": "New sign-in to your account",
     "body": "We noticed a new sign-in from Chrome on Windows.",
     "sender": "no-reply@accounts.example", "expected": "安全"},
    {"id": "security-new-device", "subject": "你的游戏账户：通过新设备访问",
     "body": "我们检测到新设备登录", "sender": "noreply@game.example", "expected": "安全"},
    {"id": "trap-newsletter-2fa", "subject": "This week in Rust",
     "body": "Tips: enable 2FA on crates.io and reset your password regularly.",
     "sender": "newsletter@rust.example", "unsubscribe": True, "expected": "订阅",
     "note": "周刊正文提到 2FA 不是安全告警"},
    {"id": "trap-list-password", "subject": "开发者周刊 第 42 期",
     "body": "本期话题：如何修改密码更安全", "sender": "weekly@dev.example",
     "signals": {"list_id": True}, "expected": "订阅"},

    # ---------- 账单 ----------
    {"id": "bill-cn", "subject": "AWS 账单已出", "body": "您 7 月账单金额为 12.50 USD",
     "sender": "billing@cloud.example", "expected": "账单"},
    {"id": "bill-receipt", "subject": "Your receipt from Example Cloud",
     "body": "Amount paid: $12.00", "sender": "receipts@cloud.example", "expected": "账单"},
    {"id": "bill-paid", "subject": "支付成功通知", "body": "您已成功支付 128.00 元",
     "sender": "service@pay.example", "expected": "账单"},

    # ---------- 通知 ----------
    {"id": "shipping-en", "subject": "Your order #12345678 has shipped",
     "body": "Track your package with tracking number 1Z999AA10123456784",
     "sender": "ship@shop.example", "unsubscribe": True, "expected": "通知",
     "note": "发货通知不是账单，也不是订阅"},
    {"id": "shipping-cn", "subject": "您的订单已发货", "body": "快递单号 SF1234567890，预计明天送达",
     "sender": "noreply@shop.example", "expected": "通知", "note": "发货通知不是账单"},
    {"id": "notify-github", "subject": "[GitHub] A new comment on PR #123", "body": "reply",
     "sender": "notifications@github.com", "unsubscribe": True,
     "signals": {"list_id": True}, "expected": "通知"},
    {"id": "notify-jira", "subject": "[Jira] Ticket ABC-123 assigned to you", "body": "assigned",
     "sender": "jira@corp.atlassian.net", "expected": "通知"},
    {"id": "notify-maintenance", "subject": "系统维护公告", "body": "我们将于周六凌晨进行系统维护",
     "sender": "noreply@service.example", "expected": "通知"},
    {"id": "notify-auto-submitted", "subject": "Build failed: main", "body": "Your build #456 failed",
     "sender": "builds@ci.example", "signals": {"auto_submitted": True}, "expected": "通知"},

    # ---------- 订阅 ----------
    {"id": "sub-weekly", "subject": "本周科技周刊", "body": "内容", "sender": "news@example.com",
     "unsubscribe": True, "expected": "订阅"},
    {"id": "sub-digest", "subject": "Weekly digest from Medium", "body": "Top stories for you",
     "sender": "noreply@medium.example", "unsubscribe": True, "expected": "订阅"},
    {"id": "sub-promo-cn", "subject": "双11 大促预告", "body": "领券满减",
     "sender": "promo@shop.example", "expected": "订阅"},
    {"id": "sub-precedence", "subject": "Team update", "body": "Monthly update from the club",
     "sender": "list@club.example", "signals": {"precedence_bulk": True}, "expected": "订阅"},

    # ---------- 其他 ----------
    {"id": "other-minutes", "subject": "会议纪要", "body": "附件为本周会议纪要，请查收",
     "sender": "colleague@corp.example", "expected": "其他"},
    {"id": "other-friend", "subject": "周末一起吃饭吗", "body": "好久不见，周六有空吗",
     "sender": "friend@mail.example", "expected": "其他"},
]


def format_matrix(matrix: Counter) -> str:
    """把 {(期望, 实际): 数量} 排成文本混淆矩阵。"""
    expected = sorted({e for e, _ in matrix})
    got = sorted({g for _, g in matrix})
    width = max([4] + [len(x) * 2 for x in expected + got])
    lines = ["期望\\实际".ljust(width) + "".join(g.ljust(width) for g in got)]
    for e in expected:
        lines.append(e.ljust(width) + "".join(str(matrix.get((e, g), "")).ljust(width) for g in got))
    return "\n".join(lines)
