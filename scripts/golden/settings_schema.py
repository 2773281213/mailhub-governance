"""导出普通设置定义表及读写/编码行为金标到 settings_schema.json。"""
from __future__ import annotations

import os
import tempfile

import _common

_TEMP_DIR = tempfile.mkdtemp(prefix="mailhub_golden_settings_")
os.environ["MAILHUB_DB"] = os.path.join(_TEMP_DIR, "golden.db")

_common.prepare_imports()

import db  # noqa: E402
import settings_schema  # noqa: E402

_INT_ENCODE_INPUTS = (-5, 0, 7, 23, 99, 100000)
_BOOL_ENCODE_INPUTS = (True, False)
_STR_ENCODE_INPUTS = ("", "  @chan  ")
_STORED_INPUTS = (None, "", "1", "0", "7", "99")


def _build_cases() -> list[dict]:
    """构造表定义、encode 与单键读取的全部金标用例。"""
    db.init_db()
    conn = db.get_conn()
    cases: list[dict] = [
        {
            "id": "settings_table",
            "fn": "settings",
            "args": {},
            "expected": [
                {
                    "key": s.key,
                    "kind": s.kind,
                    "default": s.default,
                    "lo": s.lo,
                    "hi": s.hi,
                }
                for s in settings_schema.SETTINGS.values()
            ],
        },
        {
            "id": "plain_keys",
            "fn": "plain_keys",
            "args": {},
            "expected": list(settings_schema.PLAIN_KEYS),
        },
    ]

    for setting in settings_schema.SETTINGS.values():
        if setting.kind == "int":
            inputs = _INT_ENCODE_INPUTS
        elif setting.kind == "bool":
            inputs = _BOOL_ENCODE_INPUTS
        else:
            inputs = _STR_ENCODE_INPUTS

        for idx, value in enumerate(inputs):
            cases.append({
                "id": f"encode:{setting.key}:{idx}",
                "fn": "encode",
                "args": {"key": setting.key, "value": value},
                "expected": settings_schema.encode(setting.key, value),
            })

    getters = {
        "bool": ("get_bool", settings_schema.get_bool),
        "int": ("get_int", settings_schema.get_int),
        "str": ("get_str", settings_schema.get_str),
    }
    for setting in settings_schema.SETTINGS.values():
        fn_name, getter = getters[setting.kind]
        for stored in _STORED_INPUTS:
            conn.execute("DELETE FROM settings")
            conn.commit()
            if stored is not None:
                db.set_setting(setting.key, stored)
            try:
                expected = getter(setting.key)
            except Exception:
                # Python 会抛出异常的输入（例如非数字整数值）不纳入金标
                continue
            tag = "missing" if stored is None else ("empty" if stored == "" else stored)
            cases.append({
                "id": f"{fn_name}:{setting.key}:{tag}",
                "fn": fn_name,
                "args": {"key": setting.key, "stored": stored},
                "expected": expected,
            })

    return cases


def main() -> int:
    """运行金标生成或校验入口。"""
    return _common.run("settings_schema", _build_cases)


if __name__ == "__main__":
    raise SystemExit(main())
