"""安全模块：秘密加密（Fernet）与口令哈希（PBKDF2）"""
import base64
import hashlib
import hmac
import logging
import os
import secrets

from cryptography.fernet import Fernet, InvalidToken, MultiFernet
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

log = logging.getLogger("mailhub.security")

# 密钥来自环境变量（部署时生成，写入 /opt/mailhub/.env）
_SECRET = os.environ.get("MAILHUB_SECRET", "")
if not _SECRET:
    raise RuntimeError("缺少环境变量 MAILHUB_SECRET，请检查 /opt/mailhub/.env")
if _SECRET == "replace-with-a-long-random-secret":
    raise RuntimeError("MAILHUB_SECRET 仍是 .env.example 里的占位值，请换成随机生成的长密钥")

# 轮换密钥时把旧值放在这里：旧密文仍能解开，启动时会自动用新密钥重新加密
_PREVIOUS_SECRET = os.environ.get("MAILHUB_SECRET_PREVIOUS", "").strip()

# ENCRYPTED_COLUMNS 列出数据库里所有用本模块加密的字段，供密钥轮换时重新加密
ENCRYPTED_COLUMNS = {
    "accounts": ("secret_enc", "oauth_refresh_enc", "oauth_access_enc"),
}


def _hkdf_key(secret: str) -> bytes:
    """当前的派生方式：HKDF-SHA256，带固定的应用 salt 与用途标签。"""
    derived = HKDF(
        algorithm=hashes.SHA256(), length=32,
        salt=b"mailhub-governance", info=b"mailhub:fernet:v2",
    ).derive(secret.encode("utf-8"))
    return base64.urlsafe_b64encode(derived)


def _legacy_key(secret: str) -> bytes:
    """早期版本的派生方式 sha256("enc:" + secret)，只用于解密旧数据。"""
    return base64.urlsafe_b64encode(hashlib.sha256(("enc:" + secret).encode()).digest())


def _build_fernet(current: str, previous: str = "") -> MultiFernet:
    """第一把密钥用于加密，其余只用于解密旧密文。"""
    keys = [_hkdf_key(current), _legacy_key(current)]
    if previous and previous != current:
        keys += [_hkdf_key(previous), _legacy_key(previous)]
    return MultiFernet([Fernet(k) for k in keys])


def _build_primary(current: str) -> Fernet:
    return Fernet(_hkdf_key(current))


_fernet = _build_fernet(_SECRET, _PREVIOUS_SECRET)
_primary = _build_primary(_SECRET)


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
        # 不记录密文本身；常见原因是 MAILHUB_SECRET 被更换而没有设置 MAILHUB_SECRET_PREVIOUS
        log.warning("无法用当前密钥解密已保存的凭据；如刚更换 MAILHUB_SECRET，"
                    "请把旧值设为 MAILHUB_SECRET_PREVIOUS 后重启")
        return ""


def _needs_rotation(token: str) -> bool:
    """能解开但不是用当前主密钥加密的密文需要重新加密。"""
    try:
        _primary.decrypt(token.encode())
        return False
    except InvalidToken:
        pass
    try:
        _fernet.decrypt(token.encode())
        return True
    except InvalidToken:
        return False


def rotate_encrypted_values(conn) -> dict:
    """把用旧密钥或旧派生方式加密的值改为当前主密钥加密。幂等，启动时调用。

    返回 {"rotated": n, "unreadable": m}。解不开的值保持原样，不会被清空。
    """
    rotated = unreadable = 0

    def handle(value: str) -> str | None:
        nonlocal rotated, unreadable
        if not value:
            return None
        if _needs_rotation(value):
            rotated += 1
            return _fernet.rotate(value.encode()).decode()
        try:
            _primary.decrypt(value.encode())
        except InvalidToken:
            unreadable += 1
        return None

    for table, columns in ENCRYPTED_COLUMNS.items():
        rows = conn.execute(f"SELECT rowid, {', '.join(columns)} FROM {table}").fetchall()
        for row in rows:
            for column in columns:
                new_value = handle(row[column])
                if new_value is not None:
                    conn.execute(f"UPDATE {table} SET {column}=? WHERE rowid=?",
                                 (new_value, row["rowid"]))
    for row in conn.execute("SELECT k, v FROM settings WHERE k LIKE '%\\_enc' ESCAPE '\\'").fetchall():
        new_value = handle(row["v"])
        if new_value is not None:
            conn.execute("UPDATE settings SET v=? WHERE k=?", (new_value, row["k"]))
    conn.commit()
    if rotated or unreadable:
        log.warning("密钥轮换：重新加密 %d 个值，%d 个值无法用当前或旧密钥解开", rotated, unreadable)
    return {"rotated": rotated, "unreadable": unreadable}


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
