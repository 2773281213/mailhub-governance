#!/usr/bin/env python3
"""生成演示用的合成数据库：不连 IMAP、不含任何真实邮件。

用法：
    python scripts/seed_demo.py --db data/demo.db [--count 300] [--admin-password 密码] [--reset]

退出码：0 成功；2 缺少 MAILHUB_SECRET；3 数据库里有真实账户（安全闸拒绝写入）。
"""
import argparse
import json
import os
import random
import sys
import time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# 演示账户统一用这个不可解析的主机名，同时充当安全闸的标记
DEMO_HOST = "demo.invalid"
DEMO_ACTOR = "demo"
DEMO_TRIGGER = "demo"

CATEGORIES = ("验证码", "重要", "账单", "安全", "可疑", "订阅", "通知", "其他")
CATEGORY_WEIGHTS = (15, 8, 8, 5, 4, 25, 25, 10)
MINISTRY = {
    "验证码": "工部", "重要": "吏部", "账单": "户部", "安全": "兵部",
    "可疑": "刑部", "订阅": "礼部", "通知": "礼部", "其他": "吏部",
}
ACCOUNTS = (
    ("A1 工作", "work@demo.example", "#38bdf8"),
    ("A2 个人", "me@demo.example", "#a78bfa"),
    ("A3 订阅", "news@demo.example", "#34d399"),
)
# 所有发件人都只用保留域名，保证不会命中真实邮箱
SUBJECTS = {
    "验证码": ("您的登录验证码", "验证码：账户安全确认", "新设备登录验证码"),
    "重要": ("合同评审结果已出", "季度预算需要你确认", "项目排期变更通知"),
    "账单": ("您的订阅已续费 ¥{amount}", "订单支付成功 ¥{amount}", "本期账单 ¥{amount}"),
    "安全": ("新设备登录提醒", "密码已在新设备上修改", "异地登录已被拦截"),
    "可疑": ("账户存在异常登录", "您的账单地址需要确认", "请尽快验证您的账户"),
    "订阅": ("本周精选 · 5 篇值得一读的文章", "你关注的话题有了更新", "每周技术简报"),
    "通知": ("系统维护通知", "服务条款更新", "版本更新说明"),
    "其他": ("一条普通消息", "周末安排", "闲聊：最近怎么样"),
}
SENDERS = {
    "验证码": ("no-reply@verify.example", "MailHub 验证"),
    "重要": ("boss@corp.example", "王经理"),
    "账单": ("billing@shop.example", "示例商城"),
    "安全": ("security@account.example", "账户安全中心"),
    "可疑": ("notice@paypa1-secure.example", "service@paypal.com"),
    "订阅": ("digest@news.example", "技术周刊"),
    "通知": ("notice@service.example", "系统通知"),
    "其他": ("hello@misc.example", "朋友"),
}


class RealAccountError(RuntimeError):
    """数据库里有非演示账户，拒绝写入演示数据。"""


def assert_demo_only(conn):
    """安全闸：只要有一个不是 demo.invalid 的账户，就不许动这个库。"""
    row = conn.execute(
        "SELECT COUNT(*) AS c FROM accounts WHERE COALESCE(imap_host, '') != ?", (DEMO_HOST,)
    ).fetchone()
    if row["c"]:
        raise RealAccountError(
            f"这个数据库里有真实账户（{row['c']} 个 imap_host 不是 {DEMO_HOST}），拒绝写入演示数据。"
            "请换一个 --db 路径，例如 data/demo.db；--reset 也不会绕过这条检查。")


def reset_demo(conn) -> dict:
    """删掉演示账户（邮件级联删除）、demo 审计和 demo 治理运行。"""
    ids = [r["id"] for r in conn.execute(
        "SELECT id FROM accounts WHERE imap_host=?", (DEMO_HOST,)).fetchall()]
    removed = {"accounts": len(ids), "messages": 0, "audit": 0, "governance_runs": 0}
    if ids:
        ph = ",".join("?" * len(ids))
        removed["messages"] = conn.execute(
            f"SELECT COUNT(*) AS c FROM messages WHERE account_id IN ({ph})", ids).fetchone()["c"]
        conn.execute(f"DELETE FROM messages WHERE account_id IN ({ph})", ids)
        conn.execute(f"DELETE FROM accounts WHERE id IN ({ph})", ids)
    removed["audit"] = conn.execute(
        "DELETE FROM audit_log WHERE actor=?", (DEMO_ACTOR,)).rowcount
    conn.execute(
        "DELETE FROM governance_decisions WHERE run_id IN "
        "(SELECT id FROM governance_runs WHERE trigger=?)", (DEMO_TRIGGER,))
    removed["governance_runs"] = conn.execute(
        "DELETE FROM governance_runs WHERE trigger=?", (DEMO_TRIGGER,)).rowcount
    conn.commit()
    return removed


def _insert(conn, table: str, row: dict) -> int:
    cols = list(row)
    cur = conn.execute(
        f"INSERT INTO {table}({','.join(cols)}) VALUES({','.join('?' * len(cols))})",
        [row[c] for c in cols])
    return cur.lastrowid


def _content(rnd, category: str, index: int) -> tuple[str, str, str]:
    """按分类生成 (subject, sender_name, sender_addr)。"""
    subject = rnd.choice(SUBJECTS[category])
    if "{amount}" in subject:
        subject = subject.format(amount=f"{rnd.uniform(9, 999):.2f}")
    sender_addr, sender_name = SENDERS[category]
    return subject, sender_name, sender_addr


def seed(conn, *, count: int, seed_value: int = 2026) -> dict:
    """写入演示数据并返回各类数量统计；已存在的演示数据会先被清掉。"""
    import billing
    import governance
    import sync

    count = max(1, int(count))
    assert_demo_only(conn)
    reset_demo(conn)

    rnd = random.Random(seed_value)
    now = time.time()
    tz = sync.TZ
    today_start = datetime.now(tz).replace(
        hour=0, minute=0, second=0, microsecond=0).timestamp()

    account_ids = []
    for name, email, color in ACCOUNTS:
        account_ids.append(_insert(conn, "accounts", {
            "name": name, "provider": "custom", "email": email,
            "imap_host": DEMO_HOST, "imap_port": 993, "auth_type": "password",
            "secret_enc": "", "enabled": 0, "poll_interval": 86400,
            "last_uid": 0, "uidvalidity": 1, "last_sync": 0, "last_error": "",
            "color": color, "created_ts": today_start - 30 * 86400,
        }))

    # 时间戳：最近 2 小时至少 5 封，其余分布在过去 21 天，每天数量不同
    stamps = [now - rnd.uniform(120, 7000) for _ in range(min(5, count))]
    remaining = count - len(stamps)
    per_day = [0] * 21
    for i in range(remaining):
        per_day[i if i < 21 else rnd.randrange(21)] += 1
    for day in range(21):
        day_start = today_start - day * 86400
        for _ in range(per_day[day]):
            if day == 0:
                stamps.append(rnd.uniform(day_start, max(day_start, now - 1)))
            else:
                stamps.append(rnd.uniform(day_start + 60, day_start + 86400 - 60))
    stamps.sort(reverse=True)

    # 先每种分类各一封，保证 8 个分类都有；其余按比例抽
    plan = list(CATEGORIES[:min(count, len(CATEGORIES))])
    plan += rnd.choices(CATEGORIES, weights=CATEGORY_WEIGHTS, k=count - len(plan))
    rnd.shuffle(plan)

    category_counts = {c: 0 for c in CATEGORIES}
    ministry_counts: dict[str, int] = {}
    message_ids: list[int] = []
    trashed_ids: list[int] = []
    unread = html_count = 0
    mixed_otp_left, blank_bill_left, bill_flip = 2, 2, 0

    for index, date_ts in enumerate(stamps):
        category = plan[index]
        acc_id = account_ids[index % len(account_ids)]
        subject, sender_name, sender_addr = _content(rnd, category, index)
        body_text = f"{subject}。这是一封用于演示的合成邮件，不含任何真实信息。"

        otp_code = ""
        if category == "验证码":
            if mixed_otp_left > 0:
                mixed_otp_left -= 1
                otp_code = "".join(
                    rnd.choice("ABCDEFGHJKLMNPQRSTUVWXYZ0123456789") for _ in range(6))
            else:
                otp_code = str(rnd.randrange(100000, 1000000))

        bill_tier = template_key = ""
        if category == "账单":
            if blank_bill_left > 0:
                blank_bill_left -= 1
            else:
                bill_tier = billing.TIERS[bill_flip % 2]
                bill_flip += 1
            template_key = billing.template_key(sender_addr, subject)

        risk_level, risk_reasons = "none", ""
        if category == "可疑":
            risk_level = rnd.choice(["medium", "high"])
            risk_reasons = ("显示名伪装成 service@paypal.com，实际发件域为 "
                            "paypa1-secure.example")

        importance = {
            "重要": rnd.choice([4, 5]),
            "可疑": rnd.choice([4, 5]),
            "安全": rnd.choice([3, 4]),
        }.get(category, rnd.choice([0, 1, 2]))

        body_html = ""
        if rnd.random() < 0.30:
            body_html = f"<p>{body_text}</p>"
            if rnd.random() < 0.5:
                body_html += ('<p><img src="https://tracker.example/p.gif" '
                              'alt="" width="1" height="1"></p>')
            body_html += f'<p><a href="https://portal.example/m/{index}">在网页中查看</a></p>'
            html_count += 1

        is_unread = 1 if rnd.random() < 0.4 else 0
        unread += is_unread
        deleted_ts = now - rnd.uniform(3600, 3 * 86400) if rnd.random() < 0.05 else 0

        # 最新的 3 封留空治理字段，用来演示待治理与 /api/changes 屏障
        pending = index < 3
        mid = _insert(conn, "messages", {
            "account_id": acc_id, "uid": index + 1,
            "msg_id": f"<demo-{index + 1}@demo.example>",
            "to_addr": ACCOUNTS[index % len(ACCOUNTS)][1],
            "subject": subject, "sender_name": sender_name, "sender_addr": sender_addr,
            "date_ts": date_ts, "snippet": body_text[:60],
            "body_text": body_text, "body_html": body_html,
            "category": category, "otp_code": otp_code, "importance": importance,
            "summary": subject[:40], "ai_done": 1, "unread": is_unread,
            "has_attach": 1 if category == "重要" and rnd.random() < 0.5 else 0,
            "unsubscribe": 0, "created_ts": now if pending else date_ts,
            "deleted_ts": deleted_ts, "risk_level": risk_level,
            "risk_reasons": risk_reasons, "confidence": round(rnd.uniform(0.55, 0.99), 2),
            "needs_review": 0,
            "ai_reason": f"规则与语义判定为「{category}」" if not pending else "",
            "governance_version": "" if pending else governance.VERSION,
            "governance_ministry": "" if pending else MINISTRY[category],
            "governance_action": "" if pending else "label_only",
            "governance_source": "" if pending else rnd.choice(["rule", "ai"]),
            "governance_reason": "" if pending else f"尚书省按「{category}」归档",
            "governance_ts": 0 if pending else date_ts,
            "template_key": template_key, "bill_tier": bill_tier, "alert_ts": 0,
        })
        message_ids.append(mid)
        if deleted_ts:
            trashed_ids.append(mid)
        category_counts[category] += 1
        if not pending:
            ministry_counts[MINISTRY[category]] = ministry_counts.get(MINISTRY[category], 0) + 1

    # 今天的晨报
    day = datetime.now(tz).strftime("%Y-%m-%d")
    digest = (
        f"# {day} 晨报（演示数据）\n\n"
        f"## 一句话速览\n今天收到 {count} 封邮件，{unread} 封未读，"
        f"{category_counts['验证码']} 封验证码，{category_counts['账单']} 封账单。\n\n"
        "## 需要关注\n"
        f"- 可疑邮件 {category_counts['可疑']} 封，请先确认发件域再操作\n"
        f"- 安全提醒 {category_counts['安全']} 封，含新设备登录\n\n"
        "## 统计\n"
        + "\n".join(f"- {c}：{n} 封" for c, n in category_counts.items())
    )
    conn.execute(
        "INSERT INTO digests(day, content, created_ts) VALUES(?,?,?) "
        "ON CONFLICT(day) DO UPDATE SET content=excluded.content, created_ts=excluded.created_ts",
        (day, digest, now))

    # 一次自动治理运行（统计为上面真实生成的数字）
    run_id = _insert(conn, "governance_runs", {
        "version": governance.VERSION, "trigger": DEMO_TRIGGER, "status": "completed",
        "started_ts": now - 900, "finished_ts": now - 600,
        "selected_count": count, "processed_count": count - 3, "ai_count": count - 3,
        "fallback_count": 0, "suspicious_count": category_counts["可疑"], "error_count": 0,
        "ministry_counts": json.dumps(ministry_counts, ensure_ascii=False),
        "category_counts": json.dumps(category_counts, ensure_ascii=False), "error": "",
    })

    # 6 条审计：2 条可撤销的软删除、1 条不可撤销的清理、1 条被拦截、2 条普通记录
    soft_a, soft_b = trashed_ids[:2], trashed_ids[2:4]
    audits = [
        {"action": "soft_delete", "tier": "C", "targets": soft_a, "allowed": 1,
         "reason": "演示：把两封促销邮件移入回收站", "reversible": 1,
         "undo": {"ids": soft_a}},
        {"action": "soft_delete", "tier": "C", "targets": soft_b, "allowed": 1,
         "reason": "演示：把两封过期通知移入回收站", "reversible": 1,
         "undo": {"ids": soft_b}},
        {"action": "purge", "tier": "C", "targets": trashed_ids[4:5], "allowed": 1,
         "reason": "演示：清理过期验证码邮件（不可撤销）", "reversible": 0, "undo": None},
        {"action": "soft_delete", "tier": "B", "targets": message_ids[3:4], "allowed": 0,
         "reason": "演示：受保护邮件被策略拦截", "reversible": 0, "undo": None},
        {"action": "read", "tier": "A", "targets": message_ids[4:5], "allowed": 1,
         "reason": "演示：批量标记已读", "reversible": 0, "undo": None},
        {"action": "governance_run", "tier": "B", "targets": [], "allowed": 1,
         "reason": f"演示：自动分拣完成，共 {count - 3} 封", "reversible": 0, "undo": None},
    ]
    for item in audits:
        targets = item["targets"]
        _insert(conn, "audit_log", {
            "ts": now - rnd.uniform(60, 6000), "actor": DEMO_ACTOR,
            "action": item["action"], "tier": item["tier"],
            "target_count": len(targets), "target_ids": json.dumps(targets),
            "reason": item["reason"], "allowed": item["allowed"],
            "reversible": item["reversible"], "undone": 0,
            "undo_payload": json.dumps(item["undo"]) if item["undo"] is not None else "",
        })

    conn.commit()
    return {
        "accounts": len(account_ids),
        "messages": count,
        "unread": unread,
        "trashed": len(trashed_ids),
        "body_html": html_count,
        "pending": 3,
        "categories": category_counts,
        "digests": 1,
        "governance_runs": 1,
        "governance_run_id": run_id,
        "audit": len(audits),
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="生成 MailHub 演示数据（完全合成，不连 IMAP）")
    parser.add_argument("--db", required=True, help="数据库路径，例如 data/demo.db")
    parser.add_argument("--count", type=int, default=300, help="邮件数量，默认 300")
    parser.add_argument("--admin-password", default="",
                        help="同时把管理密码设为这个值（脚本不会打印它）")
    parser.add_argument("--reset", action="store_true", help="先清掉已有的演示数据再生成")
    args = parser.parse_args(argv)

    if not os.environ.get("MAILHUB_SECRET", "").strip():
        print("缺少环境变量 MAILHUB_SECRET。先生成一个随机密钥再运行，例如：", file=sys.stderr)
        print('  python -c "import secrets; print(secrets.token_urlsafe(48))"', file=sys.stderr)
        return 2

    db_file = Path(args.db)
    if not db_file.is_absolute():
        db_file = ROOT / db_file
    db_file.parent.mkdir(parents=True, exist_ok=True)
    # db.DB_PATH 在导入时读取环境变量，所以必须先设好再 import db
    os.environ["MAILHUB_DB"] = str(db_file)
    sys.path.insert(0, str(ROOT))

    import db
    import security

    db.init_db()
    conn = db.get_conn()

    try:
        assert_demo_only(conn)
    except RealAccountError as exc:
        print(str(exc), file=sys.stderr)
        return 3

    if args.reset:
        print("已清除旧演示数据：" + json.dumps(reset_demo(conn), ensure_ascii=False))

    stats = seed(conn, count=max(1, args.count))
    stats["db"] = str(db_file)

    if args.admin_password:
        db.set_setting("admin_hash", security.hash_password(args.admin_password))
        initial = db_file.parent / "initial-admin-password.txt"
        if initial.exists():
            initial.unlink()
        print("已设置管理密码（脚本不会打印密码）")

    print(f"演示数据已写入 {db_file}")
    print(json.dumps(stats, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
