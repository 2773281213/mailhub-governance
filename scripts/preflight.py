#!/usr/bin/env python3
"""MailHub 部署自检：环境变量、数据库、回调地址、IMAP 端点。

用法：
    python scripts/preflight.py                # 全部检查
    python scripts/preflight.py --skip-network # 只查本地配置

退出码 0 表示必须项全部通过，1 表示存在必须项失败。
"""
import argparse
import os
import socket
import ssl
import sys
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

FAIL = 0


def report(name, ok, detail="", required=True):
    global FAIL
    mark = "PASS" if ok else ("FAIL" if required else "WARN")
    if not ok and required:
        FAIL += 1
    print(f"[{mark}] {name}" + (f" — {detail}" if detail else ""))


def load_env():
    """读取 .env（不覆盖已有环境变量），避免依赖 shell 导出。"""
    env_file = ROOT / ".env"
    if not env_file.exists():
        return False
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        os.environ.setdefault(key.strip(), value.strip())
    return True


def check_env():
    secret = os.environ.get("MAILHUB_SECRET", "")
    report("MAILHUB_SECRET 长度 >= 32", len(secret) >= 32, f"{len(secret)} 字符")
    base = os.environ.get("MAILHUB_PUBLIC_URL", "").rstrip("/")
    report("MAILHUB_PUBLIC_URL 已设置", bool(base), base or "缺失")
    if base:
        parsed = urlparse(base)
        local = parsed.hostname in ("127.0.0.1", "localhost")
        report("公开地址协议", parsed.scheme == "https" or local,
               f"{parsed.scheme}://{parsed.hostname}", required=not local)
    db_path = os.environ.get("MAILHUB_DB", "data/mailhub.db")
    db_file = Path(db_path) if os.path.isabs(db_path) else ROOT / db_path
    parent = db_file.parent
    try:
        parent.mkdir(parents=True, exist_ok=True)
        probe = parent / ".preflight-write-test"
        probe.write_text("ok", encoding="utf-8")
        probe.unlink()
        report("数据库目录可写", True, str(parent))
    except Exception as exc:
        report("数据库目录可写", False, f"{parent}: {exc}")
    return base


def check_providers(base):
    channel = os.environ.get("NETEASE_CHANNEL", "own").strip().lower() or "own"
    netease_id = os.environ.get("NETEASE_CLIENT_ID", "").strip()
    style = os.environ.get("NETEASE_REDIRECT_STYLE", "web").strip().lower() or "web"
    if channel == "outlook":
        report("网易通道", True, "outlook：复用 Outlook APK 的合作方 appid")
        if netease_id and netease_id != "rjg1fubwqzie5unhx6":
            report("outlook 通道的 appid 覆盖", True, f"自定义 appid {netease_id[:6]}…", required=False)
    else:
        report("网易通道", bool(netease_id), f"own：appid={netease_id[:6]}…" if netease_id
               else "own：未配置 appid，网易走客户端授权密码", required=False)
        if netease_id == "rjg1fubwqzie5unhx6":
            report("own 通道未冒用 Outlook appid", False,
                   "检测到 rjg1fubwqzie5unhx6；请改用 NETEASE_CHANNEL=outlook 或填写自有 appid")
    if style == "olmoauth":
        report("网易回调风格", True, "olmoauth：浏览器无法完成授权，仅供协议对照", required=False)
    elif base:
        callback = f"{base}/api/oauth/163/callback"
        report("网易回调未指向 olmoauth", "olmoauth" not in callback, callback)
    report("Gmail OAuth 已配置（可选）",
           bool(os.environ.get("GOOGLE_CLIENT_ID") and os.environ.get("GOOGLE_CLIENT_SECRET")),
           "未配置时回退应用专用密码", required=False)
    report("Microsoft OAuth 已配置（可选）",
           bool(os.environ.get("MICROSOFT_CLIENT_ID")),
           "未配置时不能新增 Outlook 账户", required=False)


def check_dns(base):
    if not base:
        return
    host = urlparse(base).hostname
    if not host:
        return
    try:
        infos = socket.getaddrinfo(host, 443, proto=socket.IPPROTO_TCP)
        addrs = sorted({info[4][0] for info in infos})
        report("回调域名解析", True, f"{host} -> {', '.join(addrs[:3])}")
    except Exception as exc:
        report("回调域名解析", False, f"{host}: {exc}")


def check_http(base):
    if not base:
        return
    import httpx

    try:
        resp = httpx.get(f"{base}/api/health", timeout=15, follow_redirects=True)
        report("公网 /api/health 可达", resp.status_code == 200, f"HTTP {resp.status_code}")
    except Exception as exc:
        report("公网 /api/health 可达", False, str(exc)[:160])


def check_imap(host, port, expect_xoauth2, label, required=True):
    try:
        raw = socket.create_connection((host, port), timeout=15)
        ctx = ssl.create_default_context()
        sock = ctx.wrap_socket(raw, server_hostname=host)
        sock.recv(4096)
        sock.sendall(b"a1 CAPABILITY\r\n")
        cap = sock.recv(8192).decode("utf-8", "replace")
        sock.close()
        has = "AUTH=XOAUTH2" in cap.upper()
        detail = f"{host}:{port} " + ("AUTH=XOAUTH2" if has else "无 XOAUTH2")
        report(label, (has or not expect_xoauth2), detail, required=required)
    except Exception as exc:
        report(label, False, f"{host}:{port} {exc}"[:160], required=required)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-network", action="store_true")
    args = parser.parse_args()

    print(f"MailHub preflight — {ROOT}")
    print("=" * 60)
    env_loaded = load_env()
    print(f".env: {'已加载' if env_loaded else '未找到（使用当前环境变量）'}\n")

    base = check_env()
    check_providers(base)
    if not args.skip_network:
        check_dns(base)
        check_http(base)
        check_imap("imap.163.com", 993, True, "imap.163.com 支持 XOAUTH2", required=False)
        check_imap("imap.qq.com", 993, True, "imap.qq.com 支持 XOAUTH2", required=False)
    print("=" * 60)
    print("结果:", "通过" if FAIL == 0 else f"{FAIL} 项必须检查失败")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
