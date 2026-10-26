"""演示数据种子脚本：安全闸、可复现性与命令行入口。"""
import os
import sqlite3
import subprocess
import sys

import pytest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.join(ROOT, "scripts"))

import db  # noqa: E402
import seed_demo  # noqa: E402

SECRET = "demo-secret-for-seed-tests-0123456789"


@pytest.fixture
def isolated_db(tmp_path, monkeypatch):
    """照 tests/test_oauth_auth.py 的写法：换库路径并关掉旧连接。"""
    old = getattr(db._local, "conn", None)
    if old is not None:
        old.close()
        del db._local.conn
    monkeypatch.setattr(db, "DB_PATH", str(tmp_path / "mailhub.db"))
    db.init_db()
    yield db.get_conn()
    current = getattr(db._local, "conn", None)
    if current is not None:
        current.close()
        del db._local.conn


def _run(env, *args):
    return subprocess.run([sys.executable, "scripts/seed_demo.py", *args],
                          cwd=ROOT, env=env, capture_output=True, text=True)


def _script_env():
    env = dict(os.environ, MAILHUB_SECRET=SECRET)
    env.pop("MAILHUB_DB", None)
    return env


# ---------------- 1. 生成内容 ----------------

def test_seed_writes_demo_data(isolated_db):
    stats = seed_demo.seed(isolated_db, count=200)
    assert stats["accounts"] == 3
    assert stats["messages"] == 200

    accounts = isolated_db.execute("SELECT * FROM accounts").fetchall()
    assert len(accounts) == 3
    assert {a["imap_host"] for a in accounts} == {seed_demo.DEMO_HOST}
    assert all(a["enabled"] == 0 for a in accounts)
    assert all(a["secret_enc"] == "" for a in accounts)
    assert {a["email"] for a in accounts} == {
        "work@demo.example", "me@demo.example", "news@demo.example"}

    messages = isolated_db.execute("SELECT * FROM messages").fetchall()
    assert len(messages) == 200
    assert {m["category"] for m in messages} == set(seed_demo.CATEGORIES)
    assert any(m["deleted_ts"] > 0 for m in messages)
    assert any(m["body_html"] and "tracker.example" in m["body_html"] for m in messages)
    assert sum(1 for m in messages if not m["governance_version"]) == 3
    # 每条都有摘要与置信度
    assert all(m["snippet"] and m["summary"] and m["confidence"] for m in messages)
    # 发件域只用保留域名
    for m in messages:
        domain = m["sender_addr"].rsplit("@", 1)[-1]
        assert domain.endswith((".example", ".example.com", ".invalid")), domain


def test_seed_adds_digest_run_and_audit(isolated_db):
    seed_demo.seed(isolated_db, count=60)

    assert isolated_db.execute("SELECT COUNT(*) c FROM digests").fetchone()["c"] == 1
    digest = isolated_db.execute("SELECT content FROM digests").fetchone()["content"]
    assert "需要关注" in digest and "一句话速览" in digest

    run = isolated_db.execute("SELECT * FROM governance_runs").fetchone()
    assert run["trigger"] == seed_demo.DEMO_TRIGGER and run["status"] == "completed"
    assert run["category_counts"] and run["ministry_counts"]

    audits = isolated_db.execute("SELECT * FROM audit_log ORDER BY id").fetchall()
    assert len(audits) == 6
    assert {a["actor"] for a in audits} == {seed_demo.DEMO_ACTOR}
    soft = [a for a in audits if a["action"] == "soft_delete" and a["undo_payload"]]
    assert len(soft) == 2
    assert any(a["action"] == "purge" and a["reversible"] == 0 for a in audits)
    assert any(a["allowed"] == 0 for a in audits)


# ---------------- 2. 可复现 ----------------

def test_seed_is_reproducible(isolated_db):
    def rows():
        return [tuple(r) for r in isolated_db.execute(
            "SELECT subject, category, otp_code FROM messages ORDER BY id").fetchall()]

    seed_demo.seed(isolated_db, count=120)
    first = rows()
    seed_demo.reset_demo(isolated_db)
    seed_demo.seed(isolated_db, count=120)
    assert len(first) == 120
    assert first == rows()


# ---------------- 3. 安全闸 ----------------

def test_seed_refuses_databases_with_real_accounts(isolated_db):
    isolated_db.execute(
        """INSERT INTO accounts(name, provider, email, imap_host, imap_port,
                                auth_type, enabled, created_ts)
           VALUES('real','custom','real@corp.test','imap.real.test',993,'password',1,0)""")
    isolated_db.commit()

    with pytest.raises(seed_demo.RealAccountError):
        seed_demo.seed(isolated_db, count=20)

    # 一条数据都没写
    assert isolated_db.execute("SELECT COUNT(*) c FROM messages").fetchone()["c"] == 0
    assert isolated_db.execute("SELECT COUNT(*) c FROM accounts").fetchone()["c"] == 1
    assert isolated_db.execute("SELECT COUNT(*) c FROM audit_log").fetchone()["c"] == 0


def test_reset_cannot_bypass_the_safety_gate(tmp_path):
    db_path = tmp_path / "demo.db"
    env = _script_env()
    assert _run(env, "--db", str(db_path), "--count", "30").returncode == 0

    conn = sqlite3.connect(db_path)
    conn.execute(
        """INSERT INTO accounts(name, provider, email, imap_host, imap_port,
                                auth_type, enabled, created_ts)
           VALUES('real','custom','real@corp.test','imap.real.test',993,'password',1,0)""")
    conn.commit()
    before = conn.execute("SELECT COUNT(*) FROM messages").fetchone()[0]
    conn.close()
    assert before == 30

    blocked = _run(env, "--db", str(db_path), "--count", "30", "--reset")
    assert blocked.returncode == 3
    assert "真实账户" in (blocked.stdout + blocked.stderr)

    # --reset 没有删掉任何演示数据
    conn = sqlite3.connect(db_path)
    assert conn.execute("SELECT COUNT(*) FROM messages").fetchone()[0] == before
    conn.close()


# ---------------- 4. 脚本入口 ----------------

def test_script_entry_point_and_missing_secret(tmp_path):
    db_path = tmp_path / "demo.db"
    env = _script_env()

    ok = _run(env, "--db", str(db_path), "--count", "50")
    assert ok.returncode == 0, ok.stderr
    assert db_path.exists()

    no_secret = dict(env)
    no_secret.pop("MAILHUB_SECRET", None)
    other = tmp_path / "other.db"
    missing = _run(no_secret, "--db", str(other), "--count", "50")
    assert missing.returncode == 2
    assert "MAILHUB_SECRET" in (missing.stdout + missing.stderr)
    assert not other.exists()


def test_admin_password_is_set_without_printing_it(tmp_path):
    db_path = tmp_path / "demo.db"
    initial = tmp_path / "initial-admin-password.txt"
    initial.write_text("old\n", encoding="utf-8")

    result = _run(_script_env(), "--db", str(db_path), "--count", "20",
                  "--admin-password", "demo-pass-2026")
    assert result.returncode == 0, result.stderr
    assert "demo-pass-2026" not in (result.stdout + result.stderr)

    conn = sqlite3.connect(db_path)
    stored = conn.execute("SELECT v FROM settings WHERE k='admin_hash'").fetchone()[0]
    conn.close()
    assert stored.startswith("pbkdf2$")
    assert not initial.exists()
