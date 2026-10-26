"""通知推送模块：重要/安全邮件与验证码可推送到 Bark / Telegram / Onepush，失败不影响同步。

Onepush（https://github.com/y1ndan/onepush）的配置格式与 AzurLaneAutoScript 相同：
一段 YAML，`provider` 指定服务商，其余键是该服务商的参数，例如

    provider: bark
    key: xxxxxxxx

配置里含令牌或密码，因此加密保存在 settings 的 notify_onepush_enc 中。
"""
import logging
import threading
from urllib.parse import urlparse

import httpx

from db import get_conn, get_setting
from security import decrypt, encrypt

log = logging.getLogger("mailhub.notify")

ONEPUSH_SETTING = "notify_onepush_enc"
ONEPUSH_MAX_CHARS = 4000

# Bark 地址（含设备 key）和 Telegram Bot Token 都是凭据，加密保存在 settings 里。
# 键名以 _enc 结尾，security.rotate_encrypted_values 会在密钥轮换时一并重新加密。
BARK_SETTING = "notify_bark_url_enc"
TG_TOKEN_SETTING = "notify_tg_token_enc"
# 迁移前的明文键，只读兼容与清理，不再写入
BARK_LEGACY_SETTING = "notify_bark_url"
TG_TOKEN_LEGACY_SETTING = "notify_tg_token"


class OnepushConfigError(ValueError):
    """Onepush 配置无法解析或缺少必填参数。"""


def _secret(enc_key: str, legacy_key: str) -> str:
    """先读加密键；迁移完成前回退到旧的明文键，保证推送不中断。"""
    value = decrypt(get_setting(enc_key, ""))
    return value or get_setting(legacy_key, "")


def _cfg() -> dict:
    return {
        "bark_url": _secret(BARK_SETTING, BARK_LEGACY_SETTING).rstrip("/"),
        "tg_token": _secret(TG_TOKEN_SETTING, TG_TOKEN_LEGACY_SETTING),
        "tg_chat": get_setting("notify_tg_chat", ""),
        "onepush": decrypt(get_setting(ONEPUSH_SETTING, "")),
        "important": get_setting("notify_important", "1") == "1",
        "otp": get_setting("notify_otp", "0") == "1",
    }


def migrate_plaintext_settings() -> int:
    """把旧的明文 Bark 地址 / Telegram Token 迁到加密键，返回迁移条数。

    幂等：加密键已有值时以它为准，只清掉旧明文键。整体在一个事务里完成。
    """
    conn = get_conn()

    def raw(key: str) -> str | None:
        row = conn.execute("SELECT v FROM settings WHERE k=?", (key,)).fetchone()
        return None if row is None else row["v"]

    migrated = 0
    for enc_key, legacy_key in ((BARK_SETTING, BARK_LEGACY_SETTING),
                                (TG_TOKEN_SETTING, TG_TOKEN_LEGACY_SETTING)):
        legacy = raw(legacy_key)
        if not legacy:
            if legacy is not None:
                conn.execute("DELETE FROM settings WHERE k=?", (legacy_key,))
            continue
        if not raw(enc_key):
            conn.execute(
                "INSERT INTO settings(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v",
                (enc_key, encrypt(legacy)),
            )
        conn.execute("DELETE FROM settings WHERE k=?", (legacy_key,))
        migrated += 1
    conn.commit()
    return migrated


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
    if config["provider"] == "bark":
        _normalize_bark(config)
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


_MISSING_DEPS = "服务器未安装 onepush 或 PyYAML，请在服务器上执行 pip install -r requirements.txt 后重启"


def _normalize_bark(config: dict):
    """兼容 Alas 文档的写法：自建 Bark 服务器时 key 填完整地址，如 https://bark.example.com/KEY。

    新版 onepush 用 base_url 指定服务器、key 只填设备 key，这里自动拆开。
    """
    key = str(config.get("key") or config.get("device_key") or "").strip()
    if not key.lower().startswith(("http://", "https://")):
        return
    parsed = urlparse(key.rstrip("/"))
    parts = [part for part in parsed.path.split("/") if part]
    if parts and parts[-1] == "push":
        parts.pop()
    if not parts:
        raise OnepushConfigError("Bark 的 key 写成了地址，但地址里没有设备 key，例如 https://bark.example.com/你的Key")
    config.pop("device_key", None)
    config["key"] = parts[-1]
    prefix = "/".join(parts[:-1])
    config.setdefault("base_url", f"{parsed.scheme}://{parsed.netloc}" + (f"/{prefix}" if prefix else ""))


def _response_error(provider: str, response) -> str | None:
    """检查服务端结果。很多服务在 HTTP 200 里用 code/errcode/ok 表示失败，只看状态码会误报成功。"""
    if response is None:
        if provider == "smtp":
            return None
        # onepush 遇到网络错误只记日志并返回 None，不抛异常
        return f"Onepush({provider}) 没有收到服务端响应，请检查服务器能否访问该推送服务（网络、DNS 或代理）"
    status = getattr(response, "status_code", None)
    if status is None:
        return None
    try:
        body = response.json()
    except Exception:
        body = None
    detail = ""
    if isinstance(body, dict):
        if body.get("ok") is False or body.get("success") is False:
            detail = body.get("description") or body.get("reason") or body.get("message") or ""
        elif "errcode" in body and str(body["errcode"]) != "0":
            detail = f"errcode={body['errcode']} {body.get('errmsg') or ''}"
        elif "code" in body and str(body["code"]) not in ("0", "200"):
            detail = f"code={body['code']} {body.get('message') or body.get('msg') or ''}"
        elif body.get("status") == "failed":
            detail = str(body.get("wording") or body.get("msg") or "status=failed")
        if not detail and status >= 400:
            detail = str(body.get("message") or body.get("msg") or body.get("error") or "")
    elif status >= 400:
        detail = (getattr(response, "text", "") or "")[:120]
    if status >= 400 or detail:
        return f"Onepush({provider}) 推送失败：HTTP {status}" + (f"，{str(detail).strip()[:150]}" if detail else "")
    return None


def send_onepush(text: str, title: str, content: str) -> str | None:
    """按配置发送一条 Onepush 消息；成功返回 None，失败返回错误说明（不含凭据）。"""
    try:
        config = parse_onepush_config(text)
        provider = config.pop("provider")
        notifier = _get_notifier(provider)
        if provider == "bark":
            _normalize_bark(config)
    except OnepushConfigError as exc:
        return str(exc)
    except ImportError:
        return _MISSING_DEPS

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
    if provider == "wechatworkbot" and str(config.get("markdown", "")).strip() in ("", "0"):
        # onepush 1.10 的企业微信机器人在 markdown=0 时把正文置空，服务端返回 errcode 44004；
        # 改用 markdown 消息，标题和正文都能显示
        config["markdown"] = 1
    config.update({"title": title, "content": content})

    try:
        response = notifier.notify(**config)
    except Exception as exc:
        log.warning("Onepush(%s) 发送失败：%s", provider, type(exc).__name__)
        return f"Onepush({provider}) 发送失败：{type(exc).__name__}: {str(exc)[:120]}"
    error = _response_error(provider, response)
    if error:
        log.warning("%s", error)
    return error


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


TEST_TITLE = "MailHub 测试推送"
TEST_CONTENT = "如果你看到这条消息，说明推送配置正确"


def test_push(onepush_text: str = "") -> str:
    """发送测试推送。传入 onepush_text 时只用这段（尚未保存的）Onepush 配置测试。"""
    if onepush_text.strip():
        try:
            provider = validate_onepush_config(onepush_text)
        except OnepushConfigError as exc:
            raise RuntimeError(str(exc)) from exc
        except ImportError as exc:
            raise RuntimeError(_MISSING_DEPS) from exc
        error = send_onepush(onepush_text, TEST_TITLE, TEST_CONTENT)
        if error:
            raise RuntimeError(error)
        return f"已发送到 Onepush({provider})（该配置尚未保存，确认收到后请点「保存全部设置」）"
    cfg = _cfg()
    if not _any_channel(cfg):
        raise RuntimeError("未配置任何推送通道")
    results = _dispatch(TEST_TITLE, TEST_CONTENT)
    failed = [f"{channel}：{error}" for channel, error in results if error]
    if failed and len(failed) == len(results):
        raise RuntimeError("；".join(failed))
    sent = [channel for channel, error in results if not error]
    return "已发送到 " + "、".join(sent) + ("（失败：" + "；".join(failed) + "）" if failed else "")
