"""推送凭据加密：Bark 地址与 Telegram Token 不再明文落库，也不再从接口回传。"""
import asyncio
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest  # noqa: E402
from cryptography.fernet import Fernet  # noqa: E402

import app as mailhub_app  # noqa: E402
import db  # noqa: E402
import notify  # noqa: E402
import security  # noqa: E402

BARK_URL = "https://api.day.app/VerySecretDeviceKey"
TG_TOKEN = "123456:AAH-very-secret-token"

# 加密键 + 迁移前的明文键
KEYS = (notify.BARK_SETTING, notify.BARK_LEGACY_SETTING,
        notify.TG_TOKEN_SETTING, notify.TG_TOKEN_LEGACY_SETTING)


@pytest.fixture(autouse=True)
def clean_push_settings():
    """每个用例前后清空这 4 个键。"""
    db.init_db()
    conn = db.get_conn()

    def clear():
        conn.execute(f"DELETE FROM settings WHERE k IN ({','.join('?' * len(KEYS))})", KEYS)
        conn.commit()

    clear()
    yield conn
    clear()


def _save(**kwargs):
    asyncio.run(mailhub_app.put_settings(mailhub_app.SettingsBody(**kwargs)))


def _public() -> dict:
    return asyncio.run(mailhub_app.get_settings())


# ---------------- 1./2. 加密存储 + 不回传 ----------------

def test_bark_url_is_encrypted_and_never_returned(clean_push_settings):
    _save(notify_bark_url=BARK_URL)

    stored = db.get_setting(notify.BARK_SETTING)
    assert stored and BARK_URL not in stored and "VerySecretDeviceKey" not in stored
    assert notify._cfg()["bark_url"] == BARK_URL

    public = _public()
    dumped = json.dumps(public, ensure_ascii=False)
    assert BARK_URL not in dumped and "VerySecretDeviceKey" not in dumped
    assert "notify_bark_url" not in public
    assert public["notify_bark_set"] is True
    assert public["notify_bark_host"] == "api.day.app"


def test_telegram_token_is_encrypted_and_never_returned(clean_push_settings):
    _save(notify_tg_token=TG_TOKEN)

    stored = db.get_setting(notify.TG_TOKEN_SETTING)
    assert stored and TG_TOKEN not in stored
    assert notify._cfg()["tg_token"] == TG_TOKEN

    public = _public()
    assert public["notify_tg_token_set"] is True
    dumped = json.dumps(public, ensure_ascii=False)
    assert TG_TOKEN not in dumped and "AAH-very-secret-token" not in dumped


# ---------------- 3. 空值不清空，clear 才清空 ----------------

def test_empty_bark_url_keeps_value_and_clear_flag_removes_it(clean_push_settings):
    _save(notify_bark_url=BARK_URL)
    _save(notify_bark_url="")
    assert notify._cfg()["bark_url"] == BARK_URL
    assert db.get_setting(notify.BARK_SETTING)

    # clear 优先于同一请求里的新地址
    _save(notify_bark_url="https://api.day.app/another", clear_notify_bark=True)
    assert notify._cfg()["bark_url"] == ""
    assert db.get_setting(notify.BARK_SETTING) == ""
    assert db.get_setting(notify.BARK_LEGACY_SETTING, "") == ""
    assert _public()["notify_bark_set"] is False


def test_clear_telegram_token_removes_encrypted_value(clean_push_settings):
    _save(notify_tg_token=TG_TOKEN)
    assert db.get_setting(notify.TG_TOKEN_SETTING)

    _save(clear_notify_tg_token=True)
    assert db.get_setting(notify.TG_TOKEN_SETTING) == ""
    assert db.get_setting(notify.TG_TOKEN_LEGACY_SETTING, "") == ""
    assert notify._cfg()["tg_token"] == ""
    assert _public()["notify_tg_token_set"] is False


# ---------------- 4./5. 旧明文迁移 ----------------

def test_migration_moves_plaintext_to_encrypted_keys(clean_push_settings):
    db.set_setting(notify.BARK_LEGACY_SETTING, BARK_URL)
    db.set_setting(notify.TG_TOKEN_LEGACY_SETTING, TG_TOKEN)

    assert notify.migrate_plaintext_settings() == 2
    assert clean_push_settings.execute(
        "SELECT 1 FROM settings WHERE k IN (?, ?)",
        (notify.BARK_LEGACY_SETTING, notify.TG_TOKEN_LEGACY_SETTING)).fetchone() is None
    assert security.decrypt(db.get_setting(notify.BARK_SETTING)) == BARK_URL
    assert security.decrypt(db.get_setting(notify.TG_TOKEN_SETTING)) == TG_TOKEN
    assert notify._cfg()["bark_url"] == BARK_URL
    assert notify._cfg()["tg_token"] == TG_TOKEN

    # 幂等：第二次没有可迁移的条目
    assert notify.migrate_plaintext_settings() == 0


def test_cfg_reads_legacy_plaintext_before_migration(clean_push_settings):
    db.set_setting(notify.BARK_LEGACY_SETTING, BARK_URL)
    db.set_setting(notify.TG_TOKEN_LEGACY_SETTING, TG_TOKEN)

    cfg = notify._cfg()
    assert cfg["bark_url"] == BARK_URL
    assert cfg["tg_token"] == TG_TOKEN
    # 迁移前也算“已设置”，设置页不会误报未配置
    public = _public()
    assert public["notify_bark_set"] is True
    assert public["notify_tg_token_set"] is True


# ---------------- 6./7. 校验失败不写任何设置 ----------------

def test_invalid_bark_url_rejects_whole_request(clean_push_settings):
    before = db.get_setting("digest_hour", "8")
    with pytest.raises(mailhub_app.HTTPException) as exc_info:
        _save(notify_bark_url="ftp://x", digest_hour=3)
    assert exc_info.value.status_code == 400
    assert db.get_setting("digest_hour", "8") == before
    assert db.get_setting(notify.BARK_SETTING, "") == ""

    with pytest.raises(mailhub_app.HTTPException) as exc_info:
        _save(notify_bark_url="https://" + "a" * 500, digest_hour=3)
    assert exc_info.value.status_code == 400
    assert db.get_setting("digest_hour", "8") == before
    assert db.get_setting(notify.BARK_SETTING, "") == ""


def test_invalid_onepush_does_not_save_bark_credential(clean_push_settings):
    clean_push_settings.execute("DELETE FROM settings WHERE k=?", (notify.ONEPUSH_SETTING,))
    clean_push_settings.commit()

    with pytest.raises(mailhub_app.HTTPException) as exc_info:
        _save(notify_onepush_config="key: no-provider", notify_bark_url=BARK_URL)
    assert exc_info.value.status_code == 400
    assert db.get_setting(notify.BARK_SETTING, "") == ""
    assert db.get_setting(notify.ONEPUSH_SETTING, "") == ""


# ---------------- 8. 密钥轮换覆盖新键 ----------------

def test_rotate_encrypted_values_covers_bark_setting(clean_push_settings):
    # 模拟上一版用旧派生方式加密的密文
    old_cipher = Fernet(security._legacy_key(os.environ["MAILHUB_SECRET"])).encrypt(
        BARK_URL.encode()).decode()
    db.set_setting(notify.BARK_SETTING, old_cipher)
    assert notify._cfg()["bark_url"] == BARK_URL

    result = security.rotate_encrypted_values(db.get_conn())
    assert result["rotated"] >= 1

    rotated = db.get_setting(notify.BARK_SETTING)
    assert rotated != old_cipher
    assert security._needs_rotation(rotated) is False
    assert security._primary.decrypt(rotated.encode()).decode() == BARK_URL
