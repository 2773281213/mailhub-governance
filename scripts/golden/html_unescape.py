"""导出 Python html.unescape 用到的表和金标用例。

html.unescape 在 security_scan（HTMLParser 的 convert_charrefs）和 mail_client.html_to_text 里都要用。
Kotlin 端的 PyHtml.unescape 照 CPython 的实现移植，表从这里导出，保证与当前 Python 一致：

- android/core/src/main/resources/py/html_unescape.json：HTML5 命名实体表、非法数字引用的替换表、
  要删除的码点集合（运行时资源）；
- android/core/src/test/resources/golden/html_unescape.json：金标用例。

    python scripts/golden/html_unescape.py           # 重新生成两个文件
    python scripts/golden/html_unescape.py --check   # 检查两个文件是否与当前 Python 一致
"""
from __future__ import annotations

import html
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _common  # noqa: E402

TABLE = _common.REPO_ROOT / "android" / "core" / "src" / "main" / "resources" / "py" / "html_unescape.json"


def render_table() -> str:
    doc = {
        "entities": html.entities.html5,
        "invalid_charrefs": {str(k): v for k, v in html._invalid_charrefs.items()},
        "invalid_codepoints": sorted(html._invalid_codepoints),
    }
    return json.dumps(doc, ensure_ascii=False, indent=0, sort_keys=True) + "\n"


SAMPLES = [
    "没有实体",
    "&amp; &lt;b&gt; &quot;x&quot; &#39;y&#39;",
    "&nbsp;价格&yen;100&nbsp;",
    "&amp &lt3 &notit; &notin; &not",           # 无分号与最长前缀匹配
    "&AMP; &Amp; &amp;amp;",
    "&#65;&#x42;&#X43;&#0067",
    "&#0; &#13; &#128; &#150; &#159;",           # 非法数字引用的替换表
    "&#xD800; &#x110000; &#99999999999999999999;",
    "&#1; &#x7f; &#xFFFE; &#x10FFFF;",           # 要删除的码点
    "&#x1F600; &#128512;",
    "& ; &# &#x; &#xG; &;",
    "&abcdefghijklmnopqrstuvwxyzabcdefghijklmn;",  # 超过 32 个字符的名字
    "&😀😀; &a😀;",
    "&\t; &\n; a&&amp;b",
    "&lt;&lt;&lt;" * 3,
]


def build_cases() -> list[dict]:
    return [{"id": f"s{i:02d}", "fn": "unescape", "args": {"s": s}, "expected": html.unescape(s)}
            for i, s in enumerate(SAMPLES)]


def main() -> None:
    table = render_table()
    if "--check" in sys.argv:
        current = TABLE.read_bytes().decode("utf-8") if TABLE.exists() else ""
        if current != table:
            print(f"{TABLE.relative_to(_common.REPO_ROOT)} 与 Python 不一致，请重新生成", file=sys.stderr)
            sys.exit(1)
    else:
        TABLE.parent.mkdir(parents=True, exist_ok=True)
        with open(TABLE, "w", encoding="utf-8", newline="\n") as f:
            f.write(table)
    _common.run("html_unescape", build_cases)


if __name__ == "__main__":
    main()
