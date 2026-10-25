"""管理员会话与登录限流。

会话保存在服务端（只存令牌的 SHA-256），因此可以逐个吊销：
登出只让当前设备失效，修改密码或"退出所有设备"会让全部会话失效。
"""
import hashlib
import secrets
import threading
import time

from db import get_conn

SESSION_TTL = 30 * 86400


def _hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def create_session(ttl: int = SESSION_TTL) -> str:
    token = secrets.token_urlsafe(32)
    now = time.time()
    conn = get_conn()
    conn.execute("DELETE FROM sessions WHERE expires_ts < ?", (now,))
    conn.execute(
        "INSERT INTO sessions(id_hash, created_ts, expires_ts) VALUES(?,?,?)",
        (_hash(token), now, now + ttl),
    )
    conn.commit()
    return token


def verify_session(token: str | None) -> bool:
    # 新令牌是 43 字符的 urlsafe 串；旧版 "exp.hmac" 格式的 cookie 一律视为无效
    if not token or len(token) > 128:
        return False
    row = get_conn().execute(
        "SELECT expires_ts FROM sessions WHERE id_hash=?", (_hash(token),)
    ).fetchone()
    return bool(row) and row["expires_ts"] > time.time()


def revoke_session(token: str | None):
    if not token:
        return
    conn = get_conn()
    conn.execute("DELETE FROM sessions WHERE id_hash=?", (_hash(token),))
    conn.commit()


def revoke_all_sessions() -> int:
    conn = get_conn()
    count = conn.execute("DELETE FROM sessions").rowcount
    conn.commit()
    return count


class LoginThrottle:
    """按来源 IP 的登录失败退避，外加一个较宽的全局上限。

    - 同一 IP 前 MAX_FREE 次失败不受限，之后每次失败把等待时间翻倍（60s 起，最长 1 小时）；
    - 所有来源合计 GLOBAL_LIMIT 次失败 / GLOBAL_WINDOW 秒后暂停登录，用来减缓分布式猜测。
    成功登录会清除该 IP 的记录。全部状态在内存里，重启即清空。
    """

    MAX_FREE = 5
    BASE_DELAY = 60
    MAX_DELAY = 3600
    WINDOW = 3600
    GLOBAL_LIMIT = 100
    GLOBAL_WINDOW = 600

    def __init__(self):
        self._lock = threading.Lock()
        self._by_ip: dict[str, list[float]] = {}
        self._global: list[float] = []

    def _prune(self, now: float):
        self._global = [t for t in self._global if now - t < self.GLOBAL_WINDOW]
        for ip in list(self._by_ip):
            kept = [t for t in self._by_ip[ip] if now - t < self.WINDOW]
            if kept:
                self._by_ip[ip] = kept
            else:
                del self._by_ip[ip]

    def retry_after(self, ip: str, now: float | None = None) -> int:
        """返回还需等待的秒数；0 表示可以尝试登录。"""
        now = time.time() if now is None else now
        with self._lock:
            self._prune(now)
            waits = [0.0]
            if len(self._global) >= self.GLOBAL_LIMIT:
                waits.append(self._global[0] + self.GLOBAL_WINDOW - now)
            fails = self._by_ip.get(ip, [])
            if len(fails) >= self.MAX_FREE:
                delay = min(self.MAX_DELAY, self.BASE_DELAY * 2 ** (len(fails) - self.MAX_FREE))
                waits.append(fails[-1] + delay - now)
            return max(0, int(max(waits) + 0.999))

    def record_failure(self, ip: str, now: float | None = None):
        now = time.time() if now is None else now
        with self._lock:
            self._by_ip.setdefault(ip, []).append(now)
            self._global.append(now)

    def record_success(self, ip: str):
        with self._lock:
            self._by_ip.pop(ip, None)
