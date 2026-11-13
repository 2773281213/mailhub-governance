"""普通设置的读取侧：默认值、类型与回退语义只在 settings_schema 里定义一次。

用 conftest.py 指定的临时 SQLite 库，全部合成数据；每个用例开始前删掉这 9 个键，
结束后按原值恢复，避免影响同进程里的其他测试。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.environ.setdefault("MAILHUB_SECRET", "test-secret-for-unit-tests-only")

import asyncio  # noqa: E402

import pytest  # noqa: E402

import app as mailhub_app  # noqa: E402
import db  # noqa: E402
import notify  # noqa: E402
import settings_schema  # noqa: E402
import sync  # noqa: E402

PLAIN_KEYS = (
    "clean_otp_days",
    "clean_server",
    "clean_bill_minor_days",
    "sync_read_state",
    "digest_hour",
    "notify_tg_chat",
    "notify_important",
    "notify_otp",
    "body_keep_days",
)

# 与 settings_schema.SETTINGS 独立的一份字面量：默认值若被改动，这里会立刻失败。
DEFAULTS = {
    "clean_otp_days": 0,
    "clean_server": False,
    "clean_bill_minor_days": 0,
    "sync_read_state": False,
    "digest_hour": 8,
    "notify_tg_chat": "",
    "notify_important": True,
    "notify_otp": False,
    "body_keep_days": 90,
}

READERS = {
    "clean_otp_days": settings_schema.get_int,
    "clean_server": settings_schema.get_bool,
    "clean_bill_minor_days": settings_schema.get_int,
    "sync_read_state": settings_schema.get_bool,
    "digest_hour": settings_schema.get_int,
    "notify_tg_chat": settings_schema.get_str,
    "notify_important": settings_schema.get_bool,
    "notify_otp": settings_schema.get_bool,
    "body_keep_days": settings_schema.get_int,
}


def _delete_plain_settings(conn):
    placeholders = ",".join("?" * len(PLAIN_KEYS))
    conn.execute(f"DELETE FROM settings WHERE k IN ({placeholders})", PLAIN_KEYS)


@pytest.fixture(autouse=True)
def empty_plain_settings():
    """每个用例开始前清空这 9 个键，结束后把它们恢复成用例开始前的样子。"""
    db.init_db()
    conn = db.get_conn()
    before = {
        row["k"]: row["v"]
        for row in conn.execute(
            f"SELECT k, v FROM settings WHERE k IN ({','.join('?' * len(PLAIN_KEYS))})",
            PLAIN_KEYS,
        )
    }
    _delete_plain_settings(conn)
    conn.commit()
    yield conn
    _delete_plain_settings(conn)
    for key, value in before.items():
        conn.execute("INSERT INTO settings(k, v) VALUES(?, ?)", (key, value))
    conn.commit()


# ---------------- 默认值表 ----------------

def test_defaults_match_literal_on_empty_db(empty_plain_settings):
    """9 个键都不存在时，按类型读取的结果等于字面量默认值（类型也要一致）。"""
    for key, expected in DEFAULTS.items():
        value = READERS[key](key)
        assert value == expected, key
        assert type(value) is type(expected), key


# ---------------- GET /api/settings ----------------

def test_get_settings_endpoint_returns_defaults(empty_plain_settings):
    """设置页接口在空库上返回的这 9 项就是默认值。"""
    data = asyncio.run(mailhub_app.get_settings())
    for key, expected in DEFAULTS.items():
        assert data[key] == expected, key
        assert type(data[key]) is type(expected), key


# ---------------- 调用点 ----------------

def test_notify_cfg_reads_plain_settings(empty_plain_settings):
    """notify._cfg() 的三项普通设置走同一张表，空库用默认值、写入后读新值。"""
    cfg = notify._cfg()
    assert cfg["tg_chat"] == ""
    assert cfg["important"] is True
    assert cfg["otp"] is False

    db.set_setting("notify_tg_chat", "-100123456")
    db.set_setting("notify_important", "0")
    db.set_setting("notify_otp", "1")
    cfg = notify._cfg()
    assert cfg["tg_chat"] == "-100123456"
    assert cfg["important"] is False
    assert cfg["otp"] is True


def test_read_state_sync_enabled_default_and_value(empty_plain_settings):
    """已读状态同步开关：空库默认关闭，写入 "1" 后开启。"""
    assert sync.read_state_sync_enabled() is False
    db.set_setting("sync_read_state", "1")
    assert sync.read_state_sync_enabled() is True


# ---------------- 回退语义 ----------------

def test_int_empty_string_falls_back_to_default(empty_plain_settings):
    db.set_setting("digest_hour", "")
    assert settings_schema.get_int("digest_hour") == 8


def test_int_non_numeric_still_raises(empty_plain_settings):
    """非数字文本照旧抛 ValueError，不静默回退。"""
    db.set_setting("digest_hour", "abc")
    with pytest.raises(ValueError):
        settings_schema.get_int("digest_hour")


def test_int_read_does_not_clamp_range(empty_plain_settings):
    """读取侧不钳制：库里是 99 就读出 99（写入侧才限制 0–23）。"""
    db.set_setting("digest_hour", "99")
    assert settings_schema.get_int("digest_hour") == 99


def test_bool_empty_string_is_false(empty_plain_settings):
    """空串不等于 "1"，布尔读取为 False——与重构前各调用点一致。"""
    db.set_setting("notify_important", "")
    assert settings_schema.get_bool("notify_important") is False


def test_str_is_returned_verbatim(empty_plain_settings):
    """字符串设置原样返回，不做 strip。"""
    db.set_setting("notify_tg_chat", "  -100123456  ")
    assert settings_schema.get_str("notify_tg_chat") == "  -100123456  "


# ---------------- 用错键 / 类型 ----------------

def test_unknown_key_raises(empty_plain_settings):
    for reader in (settings_schema.get_bool, settings_schema.get_int, settings_schema.get_str):
        with pytest.raises(KeyError):
            reader("no_such_setting")


def test_wrong_kind_raises(empty_plain_settings):
    with pytest.raises(TypeError):
        settings_schema.get_bool("digest_hour")
    with pytest.raises(TypeError):
        settings_schema.get_int("clean_server")
    with pytest.raises(TypeError):
        settings_schema.get_str("notify_important")


# ---------------- 写入侧：put_settings 按表写入 ----------------

# 每个键一次只发一个字段的请求：库里存的字符串必须与重构前逐字相同。
WRITE_CASES = {
    "clean_otp_days": (3, "3"),
    "clean_server": (True, "1"),
    "clean_bill_minor_days": (7, "7"),
    "sync_read_state": (True, "1"),
    "digest_hour": (7, "7"),
    "notify_tg_chat": ("  @chan  ", "@chan"),
    "notify_important": (False, "0"),
    "notify_otp": (True, "1"),
    "body_keep_days": (30, "30"),
}

# 先写进去的非默认值：空请求体不该动它们。
NON_DEFAULT = {
    "clean_otp_days": "9",
    "clean_server": "1",
    "clean_bill_minor_days": "6",
    "sync_read_state": "1",
    "digest_hour": "5",
    "notify_tg_chat": "-100999",
    "notify_important": "0",
    "notify_otp": "1",
    "body_keep_days": "365",
}


def _put(**kwargs):
    """直接调用接口函数：不传 new_password 时 request 用不到。"""
    body = mailhub_app.SettingsBody(**kwargs)
    asyncio.run(mailhub_app.put_settings(body))


def _stored_plain(conn):
    """读 settings 表里这 9 个键的原始字符串。"""
    rows = conn.execute(
        f"SELECT k, v FROM settings WHERE k IN ({','.join('?' * len(PLAIN_KEYS))})",
        PLAIN_KEYS,
    ).fetchall()
    return {row["k"]: row["v"] for row in rows}


def test_plain_keys_match_settings_table():
    """写入用的键列表必须与表完全一致，且与测试里的独立字面量一致。"""
    assert set(settings_schema.PLAIN_KEYS) == set(settings_schema.SETTINGS)
    assert len(settings_schema.PLAIN_KEYS) == len(set(settings_schema.PLAIN_KEYS))
    assert set(settings_schema.PLAIN_KEYS) == set(PLAIN_KEYS)


def test_put_settings_writes_each_plain_key(empty_plain_settings):
    """逐键写入：每个键各发一次只含该字段的请求，库里存的字符串等于期望值。"""
    for key, (value, expected) in WRITE_CASES.items():
        _put(**{key: value})
        assert _stored_plain(empty_plain_settings)[key] == expected, key


@pytest.mark.parametrize("key,value,expected", [
    ("digest_hour", 99, "23"),
    ("digest_hour", -3, "0"),
    ("clean_otp_days", -5, "0"),
    ("body_keep_days", -1, "0"),
    ("clean_bill_minor_days", -1, "0"),
    # body_keep_days 没有上限：大数原样存
    ("body_keep_days", 100000, "100000"),
])
def test_put_settings_clamps_int_range(empty_plain_settings, key, value, expected):
    """整数按表里的 lo / hi 钳制，只有下限的键不设上限。"""
    _put(**{key: value})
    assert _stored_plain(empty_plain_settings)[key] == expected


def test_empty_body_keeps_existing_values(empty_plain_settings):
    """不传不改：空请求体不会把 9 个键改回默认值。"""
    for key, value in NON_DEFAULT.items():
        db.set_setting(key, value)
    _put()
    assert _stored_plain(empty_plain_settings) == NON_DEFAULT


def test_false_and_zero_are_written(empty_plain_settings):
    """判断的是 is not None：False 与 0 都要落库，不能当没传。"""
    db.set_setting("clean_server", "1")
    db.set_setting("clean_otp_days", "5")
    _put(clean_server=False, clean_otp_days=0)
    stored = _stored_plain(empty_plain_settings)
    assert stored["clean_server"] == "0"
    assert stored["clean_otp_days"] == "0"


def test_put_then_get_settings_round_trip(empty_plain_settings):
    """写后读：GET /api/settings 返回的就是刚写进去的值。"""
    _put(clean_otp_days=14, clean_server=True, clean_bill_minor_days=7,
         sync_read_state=True, digest_hour=6, notify_tg_chat="  @chan  ",
         notify_important=False, notify_otp=True, body_keep_days=30)
    data = asyncio.run(mailhub_app.get_settings())
    assert data["clean_otp_days"] == 14
    assert data["clean_server"] is True
    assert data["clean_bill_minor_days"] == 7
    assert data["sync_read_state"] is True
    assert data["digest_hour"] == 6
    assert data["notify_tg_chat"] == "@chan"
    assert data["notify_important"] is False
    assert data["notify_otp"] is True
    assert data["body_keep_days"] == 30


def test_encode_three_kinds():
    """encode 直接用例：布尔、整数（含钳制）、字符串（strip）。"""
    assert settings_schema.encode("clean_server", True) == "1"
    assert settings_schema.encode("clean_server", False) == "0"
    assert settings_schema.encode("digest_hour", 99) == "23"
    assert settings_schema.encode("digest_hour", -3) == "0"
    assert settings_schema.encode("body_keep_days", 100000) == "100000"
    assert settings_schema.encode("notify_tg_chat", "  @chan  ") == "@chan"


def test_encode_unknown_key_raises():
    with pytest.raises(KeyError):
        settings_schema.encode("no_such_setting", 1)
