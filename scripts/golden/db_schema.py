"""导出 `db.py` 建表结果的金标：`sqlite_master` 与每张表的 `PRAGMA table_info`。

本机模式的库结构必须与服务器逐条相同，L7 同步、L8 治理、L9 接口才能把 `sync.py`、
`governance.py`、`app.py` 里的 SQL 原样搬过来。所以这里直接跑 Python 的 `db.init_db()`，
把结果导出成 `android/core/src/test/resources/golden/db_schema.json`：

- 一个 `sqlite_master` 用例：`type, name, tbl_name, sql` 的完整清单（按 type、name 排序，
  多一条或少一条都算失败）；
- 每张表、每个索引、每个触发器各一条 `object` 用例：`sqlite_master` 的那一行，
  表另外附上 `PRAGMA table_info`（列名、类型、默认值、非空、主键）。

Kotlin 的 `DbSchemaGoldenTest` 对新建的库做同样的导出并逐条比较：`sql` 文本要求逐字相同，
`table_info` 的每个字段都要相同。

    python scripts/golden/db_schema.py           # 重新生成 JSON
    python scripts/golden/db_schema.py --check   # 与已提交的 JSON 比较，不一致时退出码为 1

注意：金标里没有邮件、地址或验证码，只有表结构。
"""
from __future__ import annotations

import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _common  # noqa: E402

# db.py 在导入时读 MAILHUB_DB，必须先指向临时库，否则会碰真实数据库。
_common.prepare_imports()

import db  # noqa: E402


def _quote(name: str) -> str:
    """把标识符放进双引号里（PRAGMA 的语法不支持 ? 占位符）。"""
    return '"' + name.replace('"', '""') + '"'


def _export(conn: sqlite3.Connection) -> list[dict]:
    """按 type、name 排序导出 sqlite_master，并给每张表附上 table_info。"""
    masters = conn.execute(
        "SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name"
    ).fetchall()
    objects = []
    for row in masters:
        columns = []
        if row["type"] == "table":
            columns = [
                {
                    "cid": c["cid"],
                    "name": c["name"],
                    "type": c["type"],
                    "notnull": c["notnull"],
                    "dflt_value": c["dflt_value"],
                    "pk": c["pk"],
                }
                for c in conn.execute(f"PRAGMA table_info({_quote(row['name'])})")
            ]
        objects.append({
            "type": row["type"],
            "name": row["name"],
            "tbl_name": row["tbl_name"],
            "sql": row["sql"],
            "table_info": columns,
        })
    return objects


def build_cases() -> list[dict]:
    db.init_db()
    conn = sqlite3.connect(db.DB_PATH)
    conn.row_factory = sqlite3.Row
    try:
        objects = _export(conn)
    finally:
        conn.close()

    masters = [
        {"type": o["type"], "name": o["name"], "tbl_name": o["tbl_name"], "sql": o["sql"]}
        for o in objects
    ]
    cases = [{
        "id": "sqlite_master",
        "fn": "sqlite_master",
        "args": {},
        "expected": masters,
    }]
    for obj in objects:
        cases.append({
            "id": f"{obj['type']}/{obj['name']}",
            "fn": "object",
            "args": {"type": obj["type"], "name": obj["name"]},
            "expected": obj,
        })
    return cases


if __name__ == "__main__":
    _common.run("db_schema", build_cases)
