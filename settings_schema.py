"""普通设置的类型、默认值和取值范围：读写两边都从这张表取。

只收「普通」设置。密钥类（AI Key、推送令牌、OAuth 客户端密钥）、管理密码、账单策略、
AI 配置、一次性标记都不在这里：它们各有专门的读写流程。
"""
from dataclasses import dataclass

from db import get_setting


@dataclass(frozen=True)
class Setting:
    key: str
    kind: str              # "bool" | "int" | "str"
    default: str           # settings 表里没有这个键时的值，写法与表里存的一样（字符串）
    lo: int | None = None  # 整数下限（含）；None 表示不限
    hi: int | None = None  # 整数上限（含）；None 表示不限


SETTINGS = {s.key: s for s in (
    Setting("clean_otp_days", "int", "0", lo=0),
    Setting("clean_server", "bool", "0"),
    Setting("clean_bill_minor_days", "int", "0", lo=0),
    Setting("sync_read_state", "bool", "0"),
    Setting("digest_hour", "int", "8", lo=0, hi=23),
    Setting("notify_tg_chat", "str", ""),
    Setting("notify_important", "bool", "1"),
    Setting("notify_otp", "bool", "0"),
    Setting("body_keep_days", "int", "90", lo=0),
)}


def _lookup(key: str) -> Setting:
    """取出键的定义：键名写错时立刻报错，不静默回退。"""
    try:
        return SETTINGS[key]
    except KeyError:
        raise KeyError(f"未在 settings_schema 中定义的设置键：{key}") from None


def _definition(key: str, kind: str) -> Setting:
    """取出键的定义并核对类型：键名或类型写错时立刻报错，不静默回退。"""
    setting = _lookup(key)
    if setting.kind != kind:
        raise TypeError(
            f"设置 {key} 的类型是 {setting.kind}，不能用 {kind} 读取")
    return setting


def get_bool(key: str) -> bool:
    """布尔设置：库里的值等于 "1" 才算开启（空串视为关闭，与各调用点原有写法一致）。"""
    setting = _definition(key, "bool")
    return get_setting(key, setting.default) == "1"


def get_int(key: str) -> int:
    """整数设置：缺键或空串用默认值；非数字文本照旧抛 ValueError；读取不钳制范围。"""
    setting = _definition(key, "int")
    return int(get_setting(key, setting.default) or setting.default)


def get_str(key: str) -> str:
    """字符串设置：原样返回，不做 strip。"""
    setting = _definition(key, "str")
    return get_setting(key, setting.default)


def encode(key: str, value) -> str:
    """把请求里的值转成 settings 表里存的字符串，整数按表里的范围钳制。"""
    setting = _lookup(key)
    if setting.kind == "bool":
        # 布尔只认真假，与写入侧原来的 "1" if value else "0" 一致
        return "1" if value else "0"
    if setting.kind == "int":
        # 先按下限、再按上限钳制；表里没写的边界表示不限
        if setting.lo is not None:
            value = max(setting.lo, value)
        if setting.hi is not None:
            value = min(setting.hi, value)
        return str(value)
    return value.strip()


# 由 settings_schema 表驱动写入的普通设置，顺序与 put_settings 里原来的出现顺序一致。
PLAIN_KEYS = ("clean_otp_days", "clean_server", "sync_read_state", "digest_hour",
              "notify_tg_chat", "notify_important", "notify_otp", "body_keep_days",
              "clean_bill_minor_days")

# 写入侧的键列表必须与 SETTINGS 表完全一致：漏一个键就会静默不保存。
assert set(PLAIN_KEYS) == set(SETTINGS), "PLAIN_KEYS 必须与 SETTINGS 的键一致"
assert len(PLAIN_KEYS) == len(set(PLAIN_KEYS)), "PLAIN_KEYS 里有重复的键"
