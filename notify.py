"""通知推送模块：重要/安全邮件与验证码可推送到 Bark / Telegram / Onepush，失败不影响同步。

Onepush（https://github.com/y1ndan/onepush）的配置格式与 AzurLaneAutoScript 相同：
一段 YAML，`provider` 指定服务商，其余键是该服务商的参数，例如

    provider: bark
    key: xxxxxxxx

配置里含令牌或密码，因此加密保存在 settings 的 notify_onepush_enc 中。
"""
import logging
import threading

import httpx

from db import get_setting
from security import decrypt

log = logging.getLogger("mailhub.notify")

ONEPUSH_SETTING = "notify_onepush_enc"
ONEPUSH_MAX_CHARS = 4000


class OnepushConfigError(ValueError):
    """Onepush 配置无法解析或缺少必填参数。"""


def _cfg() -> dict:
    return {
        "bark_url": get_setting("notify_bark_url", "").rstrip("/"),
        "tg_token": get_setting("notify_tg_token", ""),
        "tg_chat": get_setting("notify_tg_chat", ""),
        "onepush": decrypt(get_setting(ONEPUSH_SETTING, "")),
        "important": get_setting("notify_important", "1") == "1",
        "otp": get_setting("notify_otp", "0") == "1",
    }


def _any_channel(cfg: dict) -> bool:
    return bool(cfg["bark_url"] or (cfg["tg_token"] and cfg["tg_chat"]) or cfg["onepush"])


# ---------- Onepush ----------

def parse_onepush_config(text: str) -> dict:
    """解析 YAML 配置，返回包含 provider 的字典。

    与 Alas 一致：允许多个 YAML 文档（`---` 分隔），后面的键覆盖前面的。
    """
    import yaml

    if len(text or "") > ONEPUSH_MAX_CHARS:
        raise OnepushConfigError(f"Onepush 配置过长（上限 {ONEPUSH_MAX_CHARS} 字符）")
    config: dict = {}
    try:
        for doc in yaml.safe_load_all(text or ""):
            if doc is None:
                continue
            if not isinstance(doc, dict):
                raise OnepushConfigError("Onepush 配置必须是 键: 值 形式的 YAML")
            config.update(doc)
    except yaml.YAMLError as exc:
        raise OnepushConfigError(f"Onepush 配置不是合法的 YAML：{str(exc)[:150]}") from exc
    provider = str(config.get("provider") or "").strip().lower()
    if not provider:
        raise OnepushConfigError("Onepush 配置缺少 provider，例如 provider: bark")
    config["provider"] = provider
    return config


def _get_notifier(provider: str):
    from onepush import get_notifier

    try:
        return get_notifier(provider)
    except Exception as exc:
        raise OnepushConfigError(f"Onepush 不支持服务商「{provider}」") from exc


def validate_onepush_config(text: str) -> str:
    """校验配置并返回 provider 名称；缺少必填参数时报错，不会真正发送。"""
    config = parse_onepush_config(text)
    notifier = _get_notifier(config["provider"])
    required = (getattr(notifier, "params", None) or {}).get("required", [])
    # 标题和正文由 MailHub 提供；bark 的 device_key 也可以写成 key
    provided = set(config) | {"title", "content"}
    if provided & {"key", "device_key"}:
        provided |= {"key", "device_key"}
    missing = [key for key in required if key not in provided]
    if missing:
        raise OnepushConfigError(
            f"Onepush 服务商「{config['provider']}」缺少必填参数：{', '.join(missing)}")
    return config["provider"]


def onepush_provider(text: str) -> str:
    """设置页展示用：返回已保存配置的服务商名，解析失败返回空串。"""
    try:
        return parse_onepush_config(text)["provider"] if text else ""
    except OnepushConfigError:
        return ""


def send_onepush(text: str, title: str, content: str) -> str | None:
    """按配置发送一条 Onepush 消息；成功返回 None，失败返回错误说明（不含凭据）。"""
    try:
        config = parse_onepush_config(text)
        provider = config.pop("provider")
        notifier = _get_notifier(provider)
    except OnepushConfigError as exc:
        return str(exc)

    if provider == "custom":
        # 与 Alas 相同：POST 默认以 JSON 发送，标题和正文放进 data
        if not config.get("method") or str(config["method"]).lower() == "post":
            config["datatype"] = "json"
        data = config.get("data")
        if not isinstance(data, dict):
            data = {}
        data.update({"title": title, "content": content})
        config["data"] = data
    if provider == "gocqhttp" and config.get("access_token"):
        config["token"] = config.pop("access_token")
    config.update({"title": title, "content": content})

    try:
        response = notifier.notify(**config)
    except Exception as exc:
        log.warning("Onepush(%s) 发送失败：%s", provider, type(exc).__name__)
        return f"Onepush({provider}) 发送失败：{type(exc).__name__}: {str(exc)[:120]}"
    if response is None and provider != "smtp":
        # onepush 在网络错误时会记录日志并返回 None，而不是抛异常
        return f"Onepush({provider}) 没有收到服务端响应，可能是网络或代理问题"
    status = getattr(response, "status_code", None)
    if status is not None and status >= 400:
        log.warning("Onepush(%s) 返回 HTTP %s", provider, status)
        return f"Onepush({provider}) 返回 HTTP {status}"
    if provider == "gocqhttp" and status is not None:
        try:
            if response.json().get("status") == "failed":
                return f"Onepush({provider}) 返回失败：{str(response.json())[:120]}"
        except ValueError:
            pass
    return None


# ---------- Bark / Telegram ----------

def _bark(url: str, title: str, body: str) -> str | None:
    try:
        r = httpx.post(url, json={"title": title, "body": body, "group": "MailHub"}, timeout=10)
        return None if r.status_code < 400 else f"Bark 返回 HTTP {r.status_code}"
    except Exception as exc:
        return f"Bark 发送失败：{type(exc).__name__}"


def _telegram(token: str, chat: str, text: str) -> str | None:
    try:
        r = httpx.post(f"https://api.telegram.org/bot{token}/sendMessage",
                       json={"chat_id": chat, "text": text}, timeout=10)
        return None if r.status_code < 400 else f"Telegram 返回 HTTP {r.status_code}"
    except Exception as exc:
        # 异常信息可能包含带令牌的 URL，只记录类型
        return f"Telegram 发送失败：{type(exc).__name__}"


def _dispatch(title: str, body: str) -> list[tuple[str, str | None]]:
    """发往所有已配置的通道，返回 [(通道名, 错误或 None)]。"""
    cfg = _cfg()
    results: list[tuple[str, str | None]] = []
    if cfg["bark_url"]:
        results.append(("Bark", _bark(cfg["bark_url"], title, body)))
    if cfg["tg_token"] and cfg["tg_chat"]:
        results.append(("Telegram", _telegram(cfg["tg_token"], cfg["tg_chat"], f"{title}\n{body}")))
    if cfg["onepush"]:
        name = f"Onepush({onepush_provider(cfg['onepush']) or '?'})"
        results.append((name, send_onepush(cfg["onepush"], title, body)))
    return results


def notify_new_messages(msgs: list[dict], account_name: str):
    """msgs: [{subject, sender_addr, category, otp_code, importance}]，异步推送不阻塞同步线程"""
    cfg = _cfg()
    if not _any_channel(cfg):
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
            for channel, error in _dispatch(title, body):
                if error:
                    log.warning("%s 推送失败：%s", channel, error)

    threading.Thread(target=run, daemon=True).start()


def test_push() -> str:
    cfg = _cfg()
    if not _any_channel(cfg):
        raise RuntimeError("未配置任何推送通道")
    results = _dispatch("MailHub 测试推送", "如果你看到这条消息，说明推送配置正确")
    failed = [f"{channel}：{error}" for channel, error in results if error]
    if failed and len(failed) == len(results):
        raise RuntimeError("；".join(failed))
    sent = [channel for channel, error in results if not error]
    return "已发送到 " + "、".join(sent) + ("（失败：" + "；".join(failed) + "）" if failed else "")
