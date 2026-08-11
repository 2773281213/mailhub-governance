"""通知推送模块：重要/安全邮件与验证码可推送到 Bark / Telegram，失败静默不影响同步"""
import threading

import httpx

from db import get_setting


def _cfg() -> dict:
    return {
        "bark_url": get_setting("notify_bark_url", "").rstrip("/"),
        "tg_token": get_setting("notify_tg_token", ""),
        "tg_chat": get_setting("notify_tg_chat", ""),
        "important": get_setting("notify_important", "1") == "1",
        "otp": get_setting("notify_otp", "0") == "1",
    }


def _bark(url: str, title: str, body: str):
    try:
        httpx.post(url, json={"title": title, "body": body, "group": "MailHub"}, timeout=10)
    except Exception:
        pass


def _telegram(token: str, chat: str, text: str):
    try:
        httpx.post(f"https://api.telegram.org/bot{token}/sendMessage",
                   json={"chat_id": chat, "text": text}, timeout=10)
    except Exception:
        pass


def _dispatch(title: str, body: str):
    cfg = _cfg()
    if cfg["bark_url"]:
        _bark(cfg["bark_url"], title, body)
    if cfg["tg_token"] and cfg["tg_chat"]:
        _telegram(cfg["tg_token"], cfg["tg_chat"], f"{title}\n{body}")


def notify_new_messages(msgs: list[dict], account_name: str):
    """msgs: [{subject, sender_addr, category, otp_code, importance}]，异步推送不阻塞同步线程"""
    cfg = _cfg()
    if not (cfg["bark_url"] or (cfg["tg_token"] and cfg["tg_chat"])):
        return
    items = []
    for m in msgs:
        important = m.get("category") in ("重要", "安全") or (m.get("importance") or 0) >= 4
        if important and cfg["important"]:
            items.append((f"[{m['category']}] {account_name}",
                          f"{m.get('sender_addr', '')}: {m.get('subject', '')[:80]}"))
        elif m.get("otp_code") and cfg["otp"]:
            items.append((f"验证码 {m['otp_code']} · {account_name}",
                          f"{m.get('sender_addr', '')}: {m.get('subject', '')[:60]}"))
    if not items:
        return

    def run():
        for title, body in items[:5]:  # 单次同步最多推 5 条，防轰炸
            _dispatch(title, body)

    threading.Thread(target=run, daemon=True).start()


def test_push() -> str:
    cfg = _cfg()
    if not (cfg["bark_url"] or (cfg["tg_token"] and cfg["tg_chat"])):
        raise RuntimeError("未配置任何推送通道")
    _dispatch("MailHub 测试推送", "如果你看到这条消息，说明推送配置正确")
    return "已发送测试推送"
