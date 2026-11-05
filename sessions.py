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


def session_id(token: str) -> str:
    return _hash(token)[:16]


def create_session(ttl: int = SESSION_TTL, *, label: str = "", client: str = "web") -> str:
    token = secrets.token_urlsafe(32)
    now = time.time()
    conn = get_conn()
    conn.execute("DELETE FROM sessions WHERE expires_ts < ?", (now,))
    conn.execute(
        "INSERT INTO sessions(id_hash, created_ts, expires_ts, label, client, last_seen_ts) VALUES(?,?,?,?,?,?)",
        (_hash(token), now, now + ttl, label, client, now),
    )
    conn.commit()
    return token


def verify_session(token: str | None) -> bool:
    # 新令牌是 43 字符的 urlsafe 串；旧版 "exp.hmac" 格式的 cookie 一律视为无效
    if not token or len(token) > 128:
        return False
    now = time.time()
    conn = get_conn()
    row = conn.execute(
        "SELECT expires_ts, last_seen_ts FROM sessions WHERE id_hash=?", (_hash(token),)
    ).fetchone()
    if not row or row["expires_ts"] <= now:
        return False
    last_seen = row["last_seen_ts"] or 0
    if now - last_seen > 300:
        conn.execute(
            "UPDATE sessions SET last_seen_ts=? WHERE id_hash=?",
            (now, _hash(token)),
        )
        conn.commit()
    return True


def list_sessions(current_token: str | None) -> list[dict]:
    now = time.time()
    conn = get_conn()
    rows = conn.execute(
        """SELECT id_hash, label, client, created_ts, last_seen_ts, expires_ts
           FROM sessions
           WHERE expires_ts > ?
           ORDER BY created_ts DESC""",
        (now,),
    ).fetchall()
    cur_hash = _hash(current_token) if current_token else None
    items = []
    for r in rows:
        items.append({
            "id": r["id_hash"][:16],
            "label": r["label"] or "",
            "client": r["client"] or "web",
            "created_ts": r["created_ts"],
            "last_seen_ts": r["last_seen_ts"] or 0.0,
            "expires_ts": r["expires_ts"],
            "current": bool(cur_hash and r["id_hash"] == cur_hash),
        })
    return items


def revoke_session_id(sid: str) -> int:
    if not isinstance(sid, str) or len(sid) != 16:
        return 0
    try:
        int(sid, 16)
    except ValueError:
        return 0
    conn = get_conn()
    cur = conn.execute("DELETE FROM sessions WHERE substr(id_hash, 1, 16) = ?", (sid.lower(),))
    conn.commit()
    return cur.rowcount


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
        # 正在进行中的尝试：同一 IP 并发登录时，未记录失败的请求也算一次尝试，
        # 否则「先查后记」会让并发请求全部通过检查（免费猜测）
        self._pending: dict[str, int] = {}
        self._pending_total = 0

    def _prune(self, now: float):
        self._global = [t for t in self._global if now - t < self.GLOBAL_WINDOW]
        for ip in list(self._by_ip):
            kept = [t for t in self._by_ip[ip] if now - t < self.WINDOW]
            if kept:
                self._by_ip[ip] = kept
            else:
                del self._by_ip[ip]

    def _wait_seconds(self, ip: str, now: float, pending_ip: int = 0,
                      pending_global: int = 0) -> int:
        """在锁内计算还需等待的秒数，retry_after 与 begin 共用。

        pending_ip / pending_global 是「正在进行中、结果未知」的尝试数，
        计算时一律当作已经发生的失败，这样并发请求无法抢在记录之前通过检查。
        进行中的尝试按 now 时刻计入，所以在原始逻辑上不改变等待时间。
        """
        self._prune(now)
        waits = [0.0]
        if len(self._global) + pending_global >= self.GLOBAL_LIMIT:
            # 窗口起点取最早的那条失败记录；只有进行中的尝试时按 now 起算
            oldest = self._global[0] if self._global else now
            waits.append(oldest + self.GLOBAL_WINDOW - now)
        fails = self._by_ip.get(ip, [])
        attempts = len(fails) + pending_ip
        if attempts >= self.MAX_FREE:
            # 指数上限在 MAX_DELAY 处饱和（60 * 2**6 已超过 3600），这里只防大整数
            exponent = min(attempts - self.MAX_FREE, 32)
            delay = min(self.MAX_DELAY, self.BASE_DELAY * 2 ** exponent)
            last_ts = fails[-1] if fails else now
            if pending_ip:
                last_ts = max(last_ts, now)
            waits.append(last_ts + delay - now)
        return max(0, int(max(waits) + 0.999))

    def retry_after(self, ip: str, now: float | None = None) -> int:
        """返回还需等待的秒数；0 表示可以尝试登录。"""
        now = time.time() if now is None else now
        with self._lock:
            return self._wait_seconds(ip, now)

    def begin(self, ip: str, now: float | None = None) -> int:
        """登录尝试前调用：判断能否开始，能则占用一个进行中名额。

        与检查在同一把锁内完成，返回 0 表示放行（此时进行中计数已加 1，
        调用方必须配一次 finish）；返回大于 0 表示应直接拒绝，不需 finish。
        """
        now = time.time() if now is None else now
        with self._lock:
            wait = self._wait_seconds(ip, now, self._pending.get(ip, 0), self._pending_total)
            if wait == 0:
                self._pending[ip] = self._pending.get(ip, 0) + 1
                self._pending_total += 1
            return wait

    def finish(self, ip: str, ok: bool, now: float | None = None):
        """登录尝试结束调用：释放进行中名额，并按结果成功清记录 / 失败记一次。"""
        now = time.time() if now is None else now
        with self._lock:
            left = self._pending.get(ip, 0) - 1
            if left > 0:
                self._pending[ip] = left
            else:
                self._pending.pop(ip, None)
            if self._pending_total > 0:
                self._pending_total -= 1
            if ok:
                self._by_ip.pop(ip, None)
            else:
                self._by_ip.setdefault(ip, []).append(now)
                self._global.append(now)

    def record_failure(self, ip: str, now: float | None = None):
        now = time.time() if now is None else now
        with self._lock:
            self._by_ip.setdefault(ip, []).append(now)
            self._global.append(now)

    def record_success(self, ip: str):
        with self._lock:
            self._by_ip.pop(ip, None)
