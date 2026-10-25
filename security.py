"""安全模块：秘密加密（Fernet）与口令哈希（PBKDF2）"""
import base64
import hashlib
import hmac
import os
import secrets

from cryptography.fernet import Fernet, InvalidToken

# 密钥来自环境变量（部署时生成，写入 /opt/mailhub/.env）
_SECRET = os.environ.get("MAILHUB_SECRET", "")
if not _SECRET:
    raise RuntimeError("缺少环境变量 MAILHUB_SECRET，请检查 /opt/mailhub/.env")
if _SECRET == "replace-with-a-long-random-secret":
    raise RuntimeError("MAILHUB_SECRET 仍是 .env.example 里的占位值，请换成随机生成的长密钥")

# 由主密钥派生加密密钥（会话令牌改为服务端存储，见 sessions.py，不再需要签名密钥）
_fernet = Fernet(base64.urlsafe_b64encode(hashlib.sha256(("enc:" + _SECRET).encode()).digest()))


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
