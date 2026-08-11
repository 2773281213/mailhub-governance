"""安全模块：秘密加密（Fernet）、口令哈希（PBKDF2）、会话签名（HMAC）"""
import base64
import hashlib
import hmac
import os
import secrets
import time

from cryptography.fernet import Fernet, InvalidToken

# 密钥来自环境变量（部署时生成，写入 /opt/mailhub/.env）
_SECRET = os.environ.get("MAILHUB_SECRET", "")
if not _SECRET:
    raise RuntimeError("缺少环境变量 MAILHUB_SECRET，请检查 /opt/mailhub/.env")

# 由主密钥派生 Fernet 密钥与会话签名密钥，避免直接复用
_fernet = Fernet(base64.urlsafe_b64encode(hashlib.sha256(("enc:" + _SECRET).encode()).digest()))
_session_key = hashlib.sha256(("sess:" + _SECRET).encode()).digest()


def encrypt(plain: str) -> str:
    if not plain:
        return ""
    return _fernet.encrypt(plain.encode()).decode()


def decrypt(token: str) -> str:
    if not token:
        return ""
    try:
        return _fernet.decrypt(token.encode()).decode()
    except InvalidToken:
        return ""


# ---------- 口令哈希 ----------

def hash_password(password: str) -> str:
    salt = secrets.token_hex(16)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode(), salt.encode(), 200_000)
    return f"pbkdf2${salt}${dk.hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        _, salt, expect = stored.split("$")
    except ValueError:
        return False
    dk = hashlib.pbkdf2_hmac("sha256", password.encode(), salt.encode(), 200_000)
    return hmac.compare_digest(dk.hex(), expect)


# ---------- 会话令牌（无状态签名 cookie，重启不失效） ----------

def make_session(days: int = 30) -> str:
    exp = str(int(time.time()) + days * 86400)
    sig = hmac.new(_session_key, exp.encode(), hashlib.sha256).hexdigest()
    return f"{exp}.{sig}"


def verify_session(token: str) -> bool:
    if not token or "." not in token:
        return False
    exp, sig = token.rsplit(".", 1)
    if not exp.isdigit() or int(exp) < time.time():
        return False
    expect = hmac.new(_session_key, exp.encode(), hashlib.sha256).hexdigest()
    return hmac.compare_digest(sig, expect)
