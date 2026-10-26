"""Onepush 推送配置：解析、校验、发送与设置页保存。全部用假的 notifier，不发真实请求。"""
import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest  # noqa: E402

import app as mailhub_app  # noqa: E402
import db  # noqa: E402
import notify  # noqa: E402


class FakeResponse:
    def __init__(self, status_code=200, payload=None):
        self.status_code = status_code
        self._payload = payload or {}

    def json(self):
        return self._payload


class FakeNotifier:
    def __init__(self, required=("key",), response=None, error=None):
        self.params = {"required": list(required), "optional": []}
        self.calls = []
        self.response = response if response is not None else FakeResponse()
        self.error = error

    def notify(self, **kwargs):
        self.calls.append(kwargs)
        if self.error:
            raise self.error
        return self.response


@pytest.fixture
def fake_notifier(monkeypatch):
    notifier = FakeNotifier()
    monkeypatch.setattr(notify, "_get_notifier", lambda provider: notifier)
    return notifier


@pytest.fixture
def clean_settings():
    db.init_db()
    keys = ("notify_bark_url", "notify_tg_token", "notify_tg_chat", notify.ONEPUSH_SETTING,
            "notify_bark_url_enc", "notify_tg_token_enc")
    conn = db.get_conn()
    conn.execute(f"DELETE FROM settings WHERE k IN ({','.join('?' * len(keys))})", keys)
    conn.commit()
    yield
    conn.execute(f"DELETE FROM settings WHERE k IN ({','.join('?' * len(keys))})", keys)
    conn.commit()


# ---------------- 解析与校验 ----------------

def test_parse_alas_style_config():
    config = notify.parse_onepush_config("provider: Bark\nkey: abc\nsound: alert\n")
    assert config == {"provider": "bark", "key": "abc", "sound": "alert"}


def test_parse_merges_multiple_documents():
    config = notify.parse_onepush_config("provider: bark\nkey: old\n---\nkey: new\n")
    assert config["key"] == "new"


@pytest.mark.parametrize("text, message", [
    ("key: abc", "provider"),
    ("- a\n- b", "键: 值"),
    ("provider: [unclosed", "YAML"),
    ("provider: bark\nkey: " + "x" * 5000, "过长"),
])
def test_parse_rejects_bad_config(text, message):
    with pytest.raises(notify.OnepushConfigError, match=message):
        notify.parse_onepush_config(text)


def test_validate_reports_missing_required_params(monkeypatch):
    monkeypatch.setattr(notify, "_get_notifier",
                        lambda provider: FakeNotifier(required=("token", "userid", "title")))
    with pytest.raises(notify.OnepushConfigError, match="userid"):
        notify.validate_onepush_config("provider: telegram\ntoken: 1:abc")
    assert notify.validate_onepush_config(
        "provider: telegram\ntoken: 1:abc\nuserid: 10000") == "telegram"


def test_bark_key_alias_satisfies_device_key(monkeypatch):
    monkeypatch.setattr(notify, "_get_notifier",
                        lambda provider: FakeNotifier(required=("device_key",)))
    assert notify.validate_onepush_config("provider: bark\nkey: abc") == "bark"


def test_real_onepush_library_is_wired():
    """确认依赖里的 onepush 提供 get_notifier 和 params 结构"""
    assert notify.validate_onepush_config("provider: bark\nkey: abc") == "bark"
    with pytest.raises(notify.OnepushConfigError, match="不支持"):
        notify.validate_onepush_config("provider: no-such-provider\nkey: abc")


# ---------------- 发送 ----------------

def test_send_passes_title_content_and_params(fake_notifier):
    assert notify.send_onepush("provider: bark\nkey: abc\nsound: alert", "标题", "正文") is None
    assert fake_notifier.calls == [
        {"key": "abc", "sound": "alert", "title": "标题", "content": "正文"}]


def test_custom_webhook_sends_json_with_title_and_content(fake_notifier):
    text = "provider: custom\nurl: https://hook.example/x\nmethod: post\ndata:\n  extra: 1\n"
    assert notify.send_onepush(text, "T", "C") is None
    call = fake_notifier.calls[0]
    assert call["datatype"] == "json"
    assert call["data"] == {"extra": 1, "title": "T", "content": "C"}


def test_custom_webhook_without_data_still_works(fake_notifier):
    assert notify.send_onepush("provider: custom\nurl: https://hook.example/x", "T", "C") is None
    assert fake_notifier.calls[0]["data"] == {"title": "T", "content": "C"}


def test_gocqhttp_access_token_alias(fake_notifier):
    notify.send_onepush("provider: gocqhttp\nendpoint: http://cq\naccess_token: tk\n"
                        "message_type: private\nuser_id: 1", "T", "C")
    call = fake_notifier.calls[0]
    assert call["token"] == "tk" and "access_token" not in call


@pytest.mark.parametrize("extra, expected", [("", 1), ("markdown: 0\n", 1), ("markdown: 2\n", 2)])
def test_wechatworkbot_defaults_to_markdown(fake_notifier, extra, expected):
    """onepush 的企业微信机器人在 markdown=0 时发送空正文（errcode 44004）"""
    assert notify.send_onepush("provider: wechatworkbot\nkey: abc\n" + extra, "T", "C") is None
    assert fake_notifier.calls[0]["markdown"] == expected


def test_real_wechatworkbot_sends_title_and_content(monkeypatch):
    from onepush import get_notifier

    sent = []

    def fake_request(method, url, proxies=None, **kwargs):
        sent.append((url, kwargs.get("json")))
        return FakeResponse(payload={"errcode": 0, "errmsg": "ok"})

    notifier = get_notifier("wechatworkbot")
    monkeypatch.setattr(type(notifier), "request", staticmethod(fake_request))
    hook_url = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=KEY123"
    assert notify.send_onepush(f"provider: wechatworkbot\nkey: {hook_url}", "标题", "正文") is None
    url, payload = sent[0]
    assert url == hook_url
    text = json.dumps(payload, ensure_ascii=False)
    assert "标题" in text and "正文" in text


def test_wechatworkbot_empty_content_error_is_reported(monkeypatch):
    response = FakeResponse(payload={"errcode": 44004, "errmsg": "empty content"})
    monkeypatch.setattr(notify, "_get_notifier", lambda provider: FakeNotifier(response=response))
    error = notify.send_onepush("provider: wechatworkbot\nkey: abc", "T", "C")
    assert error and "44004" in error


def test_send_reports_http_errors(monkeypatch):
    monkeypatch.setattr(notify, "_get_notifier",
                        lambda provider: FakeNotifier(response=FakeResponse(500)))
    assert "HTTP 500" in notify.send_onepush("provider: bark\nkey: abc", "T", "C")


def test_send_treats_missing_response_as_failure(monkeypatch):
    notifier = FakeNotifier()
    notifier.response = None
    monkeypatch.setattr(notify, "_get_notifier", lambda provider: notifier)
    assert "没有收到服务端响应" in notify.send_onepush("provider: bark\nkey: abc", "T", "C")


def test_send_error_does_not_echo_config(monkeypatch):
    monkeypatch.setattr(notify, "_get_notifier",
                        lambda provider: FakeNotifier(error=RuntimeError("connection refused")))
    error = notify.send_onepush("provider: bark\nkey: very-secret-key", "T", "C")
    assert "connection refused" in error
    assert "very-secret-key" not in error


# ---------------- 设置页与测试推送 ----------------

def test_settings_store_onepush_encrypted_and_never_return_it(clean_settings, fake_notifier):
    fake_notifier.params["required"] = ["sctkey"]
    text = "provider: serverchanturbo\nsctkey: SCT-very-secret"
    asyncio.run(mailhub_app.put_settings(mailhub_app.SettingsBody(notify_onepush_config=text)))

    stored = db.get_setting(notify.ONEPUSH_SETTING)
    assert stored and "SCT-very-secret" not in stored
    public = asyncio.run(mailhub_app.get_settings())
    assert public["notify_onepush_set"] is True
    assert public["notify_onepush_provider"] == "serverchanturbo"
    assert "SCT-very-secret" not in repr(public)

    # 留空不修改；勾选清除才删除
    asyncio.run(mailhub_app.put_settings(mailhub_app.SettingsBody(notify_onepush_config="")))
    assert db.get_setting(notify.ONEPUSH_SETTING) == stored
    asyncio.run(mailhub_app.put_settings(mailhub_app.SettingsBody(clear_notify_onepush=True)))
    assert db.get_setting(notify.ONEPUSH_SETTING) == ""


def test_settings_reject_invalid_onepush_without_saving(clean_settings):
    with pytest.raises(mailhub_app.HTTPException) as exc_info:
        asyncio.run(mailhub_app.put_settings(mailhub_app.SettingsBody(
            notify_onepush_config="key: no-provider", notify_bark_url="https://bark.example/k")))
    assert exc_info.value.status_code == 400
    assert db.get_setting(notify.ONEPUSH_SETTING, "") == ""
    assert db.get_setting("notify_bark_url", "") == ""


def test_test_push_uses_onepush(clean_settings, fake_notifier):
    asyncio.run(mailhub_app.put_settings(mailhub_app.SettingsBody(
        notify_onepush_config="provider: bark\nkey: abc")))
    message = notify.test_push()
    assert "Onepush(bark)" in message
    assert fake_notifier.calls[0]["title"] == "MailHub 测试推送"


def test_test_push_reports_failure(clean_settings, monkeypatch):
    monkeypatch.setattr(notify, "_get_notifier",
                        lambda provider: FakeNotifier(response=FakeResponse(401)))
    asyncio.run(mailhub_app.put_settings(mailhub_app.SettingsBody(
        notify_onepush_config="provider: bark\nkey: abc")))
    with pytest.raises(RuntimeError, match="HTTP 401"):
        notify.test_push()


def test_new_mail_notification_reaches_onepush(clean_settings, fake_notifier, monkeypatch):
    asyncio.run(mailhub_app.put_settings(mailhub_app.SettingsBody(
        notify_onepush_config="provider: bark\nkey: abc", notify_important=True)))

    class Inline:
        def __init__(self, target, daemon=None):
            self.target = target

        def start(self):
            self.target()

    monkeypatch.setattr(notify.threading, "Thread", Inline)
    notify.notify_new_messages(
        [{"subject": "合同签署", "sender_addr": "boss@corp.example", "category": "重要"}], "工作邮箱")
    assert fake_notifier.calls[0]["title"] == "[重要] 工作邮箱"
    assert "合同签署" in fake_notifier.calls[0]["content"]


# ---------------- 真实 onepush 库 + 本地 HTTP 服务端到端 ----------------

import http.server  # noqa: E402
import json  # noqa: E402
import threading  # noqa: E402


class _Hook(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        self.server.requests.append((self.path, body))
        status, payload = self.server.reply
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *_args):
        pass


@pytest.fixture
def hook(monkeypatch):
    for name in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "ALL_PROXY", "all_proxy"):
        monkeypatch.delenv(name, raising=False)
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _Hook)
    server.requests = []
    server.reply = (200, {"code": 200, "message": "success"})
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield server
    server.shutdown()
    server.server_close()


def _json_body(raw: bytes) -> dict:
    return json.loads(raw.decode("utf-8"))


def test_real_custom_webhook_delivers_title_and_content(hook):
    url = f"http://127.0.0.1:{hook.server_port}/hook"
    error = notify.send_onepush(f"provider: custom\nurl: {url}\ndata:\n  source: mailhub\n",
                                "标题", "正文")
    assert error is None
    path, body = hook.requests[0]
    assert path == "/hook"
    assert _json_body(body) == {"source": "mailhub", "title": "标题", "content": "正文"}


def test_real_bark_accepts_alas_style_url_key(hook):
    """Alas 文档里自建 Bark 把完整地址写在 key 里；新版 onepush 需要拆成 base_url + key"""
    key_url = f"http://127.0.0.1:{hook.server_port}/DEVICEKEY123"
    assert notify.validate_onepush_config(f"provider: bark\nkey: {key_url}") == "bark"
    assert notify.send_onepush(f"provider: bark\nkey: {key_url}\nsound: alert", "T", "C") is None
    path, body = hook.requests[0]
    assert path == "/push"
    payload = _json_body(body)
    assert payload["device_key"] == "DEVICEKEY123"
    assert payload["title"] == "T" and payload["body"] == "C" and payload["sound"] == "alert"


def test_error_code_inside_http_200_is_reported(hook):
    hook.reply = (200, {"code": 400, "message": "failed to get device token"})
    key_url = f"http://127.0.0.1:{hook.server_port}/BADKEY"
    error = notify.send_onepush(f"provider: bark\nkey: {key_url}", "T", "C")
    assert error and "failed to get device token" in error


def test_unreachable_server_is_reported(hook):
    port = hook.server_port
    hook.shutdown()
    hook.server_close()
    error = notify.send_onepush(f"provider: custom\nurl: http://127.0.0.1:{port}/hook", "T", "C")
    assert error and "没有收到服务端响应" in error


def test_test_push_can_use_unsaved_config(clean_settings, hook):
    url = f"http://127.0.0.1:{hook.server_port}/hook"
    message = notify.test_push(f"provider: custom\nurl: {url}")
    assert "尚未保存" in message
    assert _json_body(hook.requests[0][1])["title"] == notify.TEST_TITLE
    assert db.get_setting(notify.ONEPUSH_SETTING, "") == ""


def test_test_push_with_invalid_unsaved_config_explains_why(clean_settings):
    with pytest.raises(RuntimeError, match="provider"):
        notify.test_push("key: abc")


@pytest.mark.parametrize("key, base_url, device", [
    ("https://bark.example.com/ABC", "https://bark.example.com", "ABC"),
    ("https://example.com/bark/ABC/", "https://example.com/bark", "ABC"),
    ("https://bark.example.com/ABC/push", "https://bark.example.com", "ABC"),
])
def test_normalize_bark_url_key(key, base_url, device):
    config = {"key": key}
    notify._normalize_bark(config)
    assert config == {"key": device, "base_url": base_url}


def test_normalize_bark_rejects_url_without_key():
    with pytest.raises(notify.OnepushConfigError, match="设备 key"):
        notify._normalize_bark({"key": "https://bark.example.com/"})
