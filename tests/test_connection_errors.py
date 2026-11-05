"""连接错误文案测试：给用户看的中文说明，绝不把 Python / 系统异常原样抛给用户。

夹具写法照 tests/test_api_hardening.py：每个用例前清空业务表，
用网页方式登录的 TestClient 调「测试连接」接口。全部合成数据，不访问网络。
"""
import imaplib
import os
import socket
import ssl
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

import app as mailhub_app  # noqa: E402
import db  # noqa: E402
import mail_client  # noqa: E402
import oauth_auth  # noqa: E402
import sessions  # noqa: E402
import sync  # noqa: E402
from security import hash_password  # noqa: E402

PASSWORD = "correct horse battery"


# ---------------- 夹具 ----------------

@pytest.fixture(autouse=True)
def clean_db():
    """每个用例前清空业务表（照 tests/test_api_hardening.py 的写法）。"""
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
    """用网页方式登录的 TestClient（secure cookie 只会在 https 上回传）。"""
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


# ---------------- 1. 逐类异常的文案 ----------------

@pytest.mark.parametrize("exc, expected", [
    (RuntimeError("连接测试失败: 授权码不对"), "连接测试失败: 授权码不对"),
    (ValueError("端口必须在 1..65535 之间"), "端口必须在 1..65535 之间"),
    (imaplib.IMAP4.error("LOGIN failed"), "邮箱服务器拒绝登录，请检查授权码或密码"),
    (socket.timeout("timed out"), "连接邮箱服务器超时"),
    (TimeoutError("timed out"), "连接邮箱服务器超时"),
    (ConnectionRefusedError(10061, "由于目标计算机积极拒绝，无法连接。"),
     "邮箱服务器拒绝连接，请检查服务器地址和端口"),
    (ConnectionResetError(10054, "远程主机强迫关闭了一个现有的连接。"),
     "连接被邮箱服务器中断，请稍后重试"),
    (ConnectionAbortedError(10053, "你的主机中的软件中止了一个已建立的连接。"),
     "连接被邮箱服务器中断，请稍后重试"),
    (socket.gaierror(11001, "getaddrinfo failed"), "找不到邮箱服务器，请检查服务器地址"),
    (ssl.SSLError(1, "[SSL: WRONG_VERSION_NUMBER] wrong version number"),
     "与邮箱服务器建立加密连接失败"),
    # 不带 errno 的 OSError 是一个普通 OSError：带 errno 时 Python 会按错误号换成具体子类。
    # 文案取自本机 IMAP 客户端的系统提示原文，用来确认这类文本不会漏给用户。
    (OSError("由于连接方在一段时间后没有正确答复或连接的主机没有反应，连接尝试失败。"),
     "无法连接邮箱服务器"),
    (KeyError("secret_enc"), "测试失败，请查看服务器日志"),
])
def test_connection_error_text(exc, expected):
    assert mail_client.connection_error_text(exc) == expected


def test_project_errors_are_truncated_to_200_characters():
    """项目自己抛的异常原文已经是中文，但也不能无限长。"""
    text = "邮箱服务器拒绝登录，" * 50

    assert mail_client.connection_error_text(RuntimeError(text)) == text[:200]


def test_system_exception_text_never_leaks():
    """原始异常文本（Windows 错误号加英文/系统文案）一个字都不能出现。"""
    exc = ConnectionResetError(10054, "远程主机强迫关闭了一个现有的连接。")

    message = mail_client.connection_error_text(exc)

    assert "10054" not in message
    assert "WinError" not in message
    assert "ConnectionResetError" not in message


# ---------------- 2. 测试连接接口 ----------------

def test_test_connection_api_returns_chinese_message(client, clean_db, monkeypatch):
    """probe_connection 抛系统异常时，接口返回中文说明而不是原始异常文本。"""
    acc = _mk_account(clean_db)
    monkeypatch.setattr(sync, "resolve_secret", lambda account: "secret")

    def refuse(account, credential):
        raise ConnectionResetError(10054, "远程主机强迫关闭了一个现有的连接。")

    monkeypatch.setattr(oauth_auth, "probe_connection", refuse)

    response = client.post(f"/api/accounts/{acc}/test")

    assert response.status_code == 200
    assert response.json() == {"ok": False, "msg": "连接被邮箱服务器中断，请稍后重试"}


def test_test_connection_api_keeps_project_messages(client, clean_db, monkeypatch):
    """项目自己抛的中文说明原样返回，仍然是给用户看的话。"""
    acc = _mk_account(clean_db)
    monkeypatch.setattr(sync, "resolve_secret", lambda account: "secret")

    def reject(account, credential):
        raise RuntimeError(mail_client.NETEASE_LOGIN_HELP)

    monkeypatch.setattr(oauth_auth, "probe_connection", reject)

    response = client.post(f"/api/accounts/{acc}/test")

    assert response.status_code == 200
    assert response.json() == {"ok": False, "msg": mail_client.NETEASE_LOGIN_HELP}
