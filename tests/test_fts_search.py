"""邮件搜索：FTS5 三元组索引与 LIKE 路径等价、索引同步、不可用时回落。

全部合成数据，用临时 SQLite 库（见 tests/conftest.py）。
"""
import os
import random
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

import app as mailhub_app  # noqa: E402
import db  # noqa: E402
import oauth_auth  # noqa: E402
import sessions  # noqa: E402
from security import hash_password  # noqa: E402

PASSWORD = "correct horse battery"


# ---------------- 夹具 ----------------

@pytest.fixture(autouse=True)
def clean_db():
    """每个用例前清空业务表（与 tests/test_mobile_api.py 一致）。"""
    db.init_db()
    oauth_auth.init_oauth_db()
    conn = db.get_conn()
    for t in ("governance_decisions", "governance_runs", "messages", "accounts",
              "audit_log", "aliases", "rules", "digests"):
        conn.execute(f"DELETE FROM {t}")
    conn.commit()
    yield conn


@pytest.fixture
def client(clean_db):
    db.set_setting("admin_hash", hash_password(PASSWORD))
    sessions.revoke_all_sessions()
    mailhub_app.login_throttle = sessions.LoginThrottle()
    c = TestClient(mailhub_app.app, base_url="https://testserver")
    assert c.post("/api/login", json={"password": PASSWORD}).status_code == 200
    return c


def _mk_account(conn, name="acc", email="a@example.com"):
    cur = conn.execute(
        """INSERT INTO accounts(name, provider, email, imap_host, imap_port,
                                auth_type, enabled, created_ts)
           VALUES(?,?,?,?,?,?,1,0)""",
        (name, "custom", email, "imap.example.com", 993, "password"))
    conn.commit()
    return cur.lastrowid


def _insert(conn, acc_id, uid, **kw):
    row = {
        "subject": f"邮件 {uid}",
        "sender_name": "发件人",
        "sender_addr": "sender@example.com",
        "date_ts": 1_700_000_000.0 + uid,
        "category": "通知",
        "unread": 1,
        "deleted_ts": 0,
        "snippet": "",
    }
    row.update(kw)
    row["account_id"] = acc_id
    row["uid"] = uid
    cols = list(row)
    conn.execute(
        f"INSERT INTO messages({','.join(cols)}) VALUES({','.join('?' * len(cols))})",
        [row[c] for c in cols])
    conn.commit()
    return conn.execute("SELECT id FROM messages WHERE account_id=? AND uid=?",
                        (acc_id, uid)).fetchone()["id"]


# ---------------- 搜索路径辅助 ----------------

def _api_ids(client, q, **params):
    """走当前生效的搜索路径（FTS 或 LIKE），返回 (id 集合, total)。"""
    body = client.get("/api/messages/ids", params={"q": q, **params})
    assert body.status_code == 200, body.text
    data = body.json()
    return set(data["ids"]), data["total"]


def _like_ids(client, q, monkeypatch, **params):
    """强制走 LIKE 路径的结果：把 FTS 可用性缓存临时改成不可用。"""
    with monkeypatch.context() as mp:
        mp.setattr(db, "_fts_available", False)
        return _api_ids(client, q, **params)


def _sql_like_ids(conn, q):
    """独立对照实现：直接按原来的 4 字段 LIKE 查出 id 集合。"""
    like = "%" + mailhub_app._escape_like(q) + "%"
    sql = ("SELECT id FROM messages WHERE (subject LIKE ? ESCAPE '\\'"
           " OR sender_addr LIKE ? ESCAPE '\\' OR sender_name LIKE ? ESCAPE '\\'"
           " OR snippet LIKE ? ESCAPE '\\') AND deleted_ts=0")
    return {r["id"] for r in conn.execute(sql, (like, like, like, like))}


def _assert_fts_path(q):
    """确认这个关键词真的交给 FTS5 索引，而不是悄悄回落到 LIKE。"""
    cond, args = mailhub_app._message_filter("", 0, -1, q, "")
    assert "messages_fts MATCH" in cond, cond
    assert "LIKE" not in cond, cond
    # 关键词作为单个短语传入：内部的双引号写成两个，用户输入不会被当成 FTS 语法
    assert args == ['"' + q.replace('"', '""') + '"'], args


def _assert_like_path(q):
    """确认这个关键词走的是 LIKE 路径。"""
    cond, args = mailhub_app._message_filter("", 0, -1, q, "")
    assert "LIKE" in cond and "messages_fts" not in cond, cond
    assert len(args) == 4, args


# ---------------- 1. 与 LIKE 结果等价 ----------------

# 全部不少于 3 个字符（trigram 匹配不了更短的关键词）
MIXED_KEYWORDS = [
    # 中文 3 字以上
    "季度报告", "会议纪要", "发票已开具", "截止日期", "异地登录", "下一阶段排期",
    # 英文大小写混合
    "INVOICE", "invoice", "Weekly Digest", "WEEKLY", "Urgent",
    # 邮箱地址片段
    "finance@example", "support.desk", "@corp.cn", "BILLING@EXAMPLE.COM",
    # 摘要里的词
    "deadline", "receipt", "agenda in the calendar",
]

NON_EMPTY_KEYWORDS = {"季度报告", "会议纪要", "发票已开具", "INVOICE", "invoice",
                      "Weekly Digest", "finance@example", "support.desk",
                      "deadline", "下一阶段排期"}


def _seed_mixed_mailbox(conn):
    """约 50 封中英文混合邮件（主题、发件人、地址、摘要各有命中点）。"""
    acc = _mk_account(conn)
    topics = [
        ("季度报告 {q} 已生成", "财务部", "finance@example.com",
         "本季度营收与成本明细见附件，截止日期是本周五"),
        ("Invoice #{q} paid", "Finance Team", "finance@example.com",
         "Your payment receipt is attached, please review the deadline"),
        ("会议纪要：产品评审 {q}", "王小明", "wang.xm@corp.cn",
         "会上确认了下一阶段排期，纪要如下"),
        ("Weekly digest 周报 {q}", "Support Desk", "support.desk@corp.cn",
         "Summary of tickets this week, no action needed"),
        ("告警：登录异常 {q}", "安全中心", "security@corp.cn",
         "检测到异地登录，请尽快确认"),
        ("Reminder: meeting at {q}:00", "HR Team", "hr@example.com",
         "Please join the weekly sync, agenda in the calendar invite"),
        ("发票已开具 {q}", "财务部", "billing@example.com",
         "电子发票请到平台下载，摘要见正文"),
    ]
    rnd = random.Random(20261104)
    for i in range(50):
        subject, name, addr, snippet = topics[i % len(topics)]
        subject = subject.format(q=i + 1)
        if i % 3 == 0:
            subject += " " + rnd.choice(["Urgent", "urgent", "FYI"])
        _insert(conn, acc, i + 1,
                subject=subject,
                sender_name=name,
                sender_addr=addr,
                snippet=snippet,
                date_ts=1_700_000_000.0 + (i // 5),
                unread=i % 2,
                category=rnd.choice(["通知", "账单", "安全"]))
    return acc


def test_fts_matches_like_for_every_keyword(clean_db, client, monkeypatch):
    _seed_mixed_mailbox(clean_db)
    assert len(MIXED_KEYWORDS) == 18
    assert all(len(q) >= 3 for q in MIXED_KEYWORDS)
    for q in MIXED_KEYWORDS:
        _assert_fts_path(q)
        fts_ids, fts_total = _api_ids(client, q)
        like_ids, like_total = _like_ids(client, q, monkeypatch)
        assert fts_ids == like_ids, f"关键词 {q!r} 的 FTS 与 LIKE 结果不一致"
        assert fts_total == like_total == len(like_ids)
        # 再和一个完全独立的 SQL LIKE 对照实现比一次
        assert fts_ids == _sql_like_ids(clean_db, q), f"关键词 {q!r} 与 SQL LIKE 不一致"
        if q in NON_EMPTY_KEYWORDS:
            assert fts_ids, f"关键词 {q!r} 本该有命中，却是空集"


def test_fts_search_keeps_list_and_ids_endpoints_in_sync(clean_db, client):
    _seed_mixed_mailbox(clean_db)
    q = "季度报告"
    _assert_fts_path(q)
    listed = [m["id"] for m in
              client.get("/api/messages", params={"q": q, "page_size": 100}).json()["items"]]
    ids_body = client.get("/api/messages/ids", params={"q": q}).json()
    assert ids_body["ids"] == listed
    assert ids_body["total"] == len(listed) > 0
    # 分页在带搜索词时行为不变：两页不重叠，总数一致
    page1 = client.get("/api/messages", params={"q": q, "page": 1, "page_size": 10}).json()
    page2 = client.get("/api/messages", params={"q": q, "page": 2, "page_size": 10}).json()
    assert page1["total"] == page2["total"] == len(listed)
    assert {m["id"] for m in page1["items"]}.isdisjoint({m["id"] for m in page2["items"]})


def test_search_composes_with_other_filters(clean_db, client, monkeypatch):
    acc = _seed_mixed_mailbox(clean_db)
    q = "季度报告"
    for params in ({"unread": 1}, {"unread": 0}, {"category": "账单"}, {"account": acc}):
        fts_ids, _ = _api_ids(client, q, **params)
        like_ids, _ = _like_ids(client, q, monkeypatch, **params)
        assert fts_ids == like_ids, params
    # 账户筛选生效：别的账户里的同名邮件不该被搜到
    other = _mk_account(clean_db, name="other", email="o@example.com")
    _insert(clean_db, other, 1, subject="季度报告 别家账户")
    fts_ids, _ = _api_ids(client, q, account=acc)
    acc_ids = {r["id"] for r in clean_db.execute(
        "SELECT id FROM messages WHERE account_id=?", (acc,))}
    assert fts_ids == _sql_like_ids(clean_db, q) & acc_ids


# ---------------- 2. 少于 3 个字符仍走 LIKE ----------------

def test_short_keyword_still_uses_like(clean_db, client):
    acc = _mk_account(clean_db)
    one_char = _insert(clean_db, acc, 1, subject="报表 通知", snippet="报")
    two_chars = _insert(clean_db, acc, 2, subject="AB 开头", snippet="")
    other = _insert(clean_db, acc, 3, subject="完全无关", snippet="")

    # 1 个汉字：trigram 匹配不了，必须走 LIKE 且结果正确
    _assert_like_path("报")
    ids, total = _api_ids(client, "报")
    assert ids == {one_char} and total == 1
    assert ids == _sql_like_ids(clean_db, "报")

    # 2 个 ASCII 字符：同样走 LIKE，并保持原来的忽略大小写
    _assert_like_path("ab")
    for q in ("ab", "AB", "aB"):
        ids, _ = _api_ids(client, q)
        assert ids == {two_chars}, q
    assert other not in _api_ids(client, "ab")[0]

    # 边界：正好 3 个字符就走索引
    _assert_fts_path("报表 通")


# ---------------- 3. 特殊字符是字面量，不是 FTS 语法 ----------------

def test_special_characters_are_literals(clean_db, client, monkeypatch):
    acc = _mk_account(clean_db)
    ids = {
        "quote": _insert(clean_db, acc, 1, subject='Subject he"llo world'),
        "star": _insert(clean_db, acc, 2, subject="a*b*c 星号"),
        "percent": _insert(clean_db, acc, 3, subject="限时 50%off 折扣"),
        "underscore": _insert(clean_db, acc, 4, subject="a_b_c 报表"),
        "paren": _insert(clean_db, acc, 5, subject="(paren) 说明"),
        "or": _insert(clean_db, acc, 6, subject="OR 关键字测试"),
        "and": _insert(clean_db, acc, 7, subject="NOT AND OR 全是保留字"),
        "plain": _insert(clean_db, acc, 8, subject="普通通知"),
    }
    cases = {
        'he"llo': {ids["quote"]},
        'a"b"c': set(),                        # 双引号是字面量，不会拆成两个短语
        "a*b*c": {ids["star"]},
        "50%off": {ids["percent"]},
        "a_b_c": {ids["underscore"]},
        "(paren)": {ids["paren"]},
        "OR 关键字": {ids["or"]},
        "NOT AND OR": {ids["and"]},
        # 2 个字符走 LIKE：不会被当成 FTS 的布尔运算符；
        # LIKE 忽略大小写，所以 "world" 里的 or 也算命中
        "OR": {ids["quote"], ids["or"], ids["and"]},
        # LIKE 的通配符必须当字面量，不能匹配到所有邮件
        "%": {ids["percent"]},
        "_": {ids["underscore"]},
    }
    for q, expected in cases.items():
        body = client.get("/api/messages/ids", params={"q": q})
        assert body.status_code == 200, (q, body.text)
        data = body.json()
        got = set(data["ids"])
        assert got == expected, q
        assert data["total"] == len(expected), q
        assert got == _sql_like_ids(clean_db, q), q
        like_ids, _ = _like_ids(client, q, monkeypatch)
        assert got == like_ids, q


# ---------------- 4. 增删改后索引立即同步 ----------------

def test_index_follows_insert_update_and_delete(clean_db, client):
    acc = _mk_account(clean_db)
    mid = _insert(clean_db, acc, 1, subject="旧标题 alpha", snippet="")
    _assert_fts_path("旧标题")
    assert _api_ids(client, "旧标题")[0] == {mid}

    # 新增后立刻能搜到
    new_id = _insert(clean_db, acc, 2, subject="新到邮件 beta", snippet="")
    assert _api_ids(client, "新到邮件")[0] == {new_id}

    # 改主题：旧关键词搜不到，新关键词搜得到
    clean_db.execute("UPDATE messages SET subject='改过的主题 gamma' WHERE id=?", (mid,))
    clean_db.commit()
    assert _api_ids(client, "旧标题")[0] == set()
    assert _api_ids(client, "改过的主题")[0] == {mid}

    # 改发件人与摘要同样立即生效
    clean_db.execute("UPDATE messages SET sender_addr='newbie@example.com',"
                     " snippet='新的摘要内容' WHERE id=?", (new_id,))
    clean_db.commit()
    assert _api_ids(client, "newbie@example")[0] == {new_id}
    assert _api_ids(client, "新的摘要内容")[0] == {new_id}

    # 不相关的列被改时索引不受影响
    clean_db.execute("UPDATE messages SET unread=0, deleted_ts=0 WHERE id=?", (mid,))
    clean_db.commit()
    assert _api_ids(client, "改过的主题")[0] == {mid}

    # 物理删除后立刻搜不到
    clean_db.execute("DELETE FROM messages WHERE id=?", (mid,))
    clean_db.commit()
    assert _api_ids(client, "改过的主题")[0] == set()
    assert _api_ids(client, "新到邮件")[0] == {new_id}

    # 软删除（回收站）不出现在收件箱搜索结果里
    clean_db.execute("UPDATE messages SET deleted_ts=1 WHERE id=?", (new_id,))
    clean_db.commit()
    assert _api_ids(client, "新到邮件")[0] == set()

    # 索引与内容表保持一致（FTS5 官方完整性校验命令）
    clean_db.execute("INSERT INTO messages_fts(messages_fts) VALUES('integrity-check')")


# ---------------- 5. FTS 不可用时完整回落 LIKE ----------------

def test_search_falls_back_to_like_when_fts_unavailable(clean_db, client, monkeypatch):
    acc = _mk_account(clean_db)
    hit = _insert(clean_db, acc, 1, subject="季度报告 汇总", snippet="摘要 内容")
    _insert(clean_db, acc, 2, subject="普通通知", snippet="")
    assert db.fts_search_available() is True

    def boom(_conn):
        raise sqlite3.OperationalError("no such module: fts5")

    # 模拟当前 SQLite 建不了 FTS5 索引：探测结果缓存为不可用
    monkeypatch.setattr(db, "_fts_available", None)
    monkeypatch.setattr(db, "_create_fts", boom)
    assert db.fts_search_available() is False

    _assert_like_path("季度报告")
    ids, total = _api_ids(client, "季度报告")
    assert ids == {hit} and total == 1
    assert ids == _sql_like_ids(clean_db, "季度报告")
    # 短关键词、特殊字符同样正常
    assert _api_ids(client, "%")[0] == _sql_like_ids(clean_db, "%")
    assert _api_ids(client, "ab")[0] == _sql_like_ids(clean_db, "ab")
    # 列表接口照常工作
    listed = client.get("/api/messages", params={"q": "季度报告"})
    assert listed.status_code == 200
    assert [m["id"] for m in listed.json()["items"]] == [hit]
