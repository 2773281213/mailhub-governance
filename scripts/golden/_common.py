"""金标语料导出的公共部分：用 Python 原实现跑合成输入，写成 Kotlin 测试回放的 JSON。

每个模块一个脚本（scripts/golden/<模块>.py），脚本里只写「怎么造用例」，调用 run()：

    python scripts/golden/rules.py           # 重新生成 JSON
    python scripts/golden/rules.py --check   # 重新生成并与已提交的 JSON 比较，不一致时退出码为 1

JSON 结构（android/core/src/test/resources/golden/<模块>.json）：

    {"module": "rules", "generator": "scripts/golden/rules.py",
     "cases": [{"id": "...", "fn": "classify_detailed", "args": {...}, "expected": ...}, ...]}

约定：
- 输入只用合成数据：tests/classification_corpus.py 与各测试文件里的 fixture，或脚本里手写的合成样例。
  不准出现真实邮件、地址或验证码。
- id 在一个文件内唯一；同一组输入跑多个函数时用不同的 fn 区分。
- args 与 expected 只能含 JSON 能表达的值；元组写成列表，集合排序后写成列表。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = REPO_ROOT / "android" / "core" / "src" / "test" / "resources" / "golden"


def prepare_imports() -> None:
    """导入服务端模块之前调用：与 tests/conftest.py 相同，强制临时数据库和测试密钥。

    ai.py 会间接导入 security.py，后者在导入时就要求 MAILHUB_SECRET；
    db.py 在导入时读取 MAILHUB_DB。导出脚本不能碰真实数据库。
    """
    root = tempfile.mkdtemp(prefix="mailhub-golden-")
    os.environ["MAILHUB_DB"] = os.path.join(root, "mailhub.db")
    os.environ.setdefault("MAILHUB_SECRET", "golden-export-secret-not-for-production-0123456789")
    os.environ.setdefault("MAILHUB_PUBLIC_URL", "https://mailhub.test")
    for path in (REPO_ROOT, REPO_ROOT / "tests"):
        if str(path) not in sys.path:
            sys.path.insert(0, str(path))


def render(module: str, cases: list[dict]) -> str:
    """固定的序列化格式：UTF-8、键排序、单空格缩进、LF 换行，保证重新生成时逐字节稳定。"""
    seen: set[str] = set()
    for case in cases:
        missing = {"id", "fn", "args", "expected"} - case.keys()
        if missing:
            raise ValueError(f"用例缺少字段 {sorted(missing)}：{case.get('id')!r}")
        if case["id"] in seen:
            raise ValueError(f"用例 id 重复：{case['id']!r}")
        seen.add(case["id"])
    doc = {"module": module, "generator": f"scripts/golden/{module}.py", "cases": cases}
    return json.dumps(doc, ensure_ascii=False, indent=1, sort_keys=True) + "\n"


def run(module: str, build_cases) -> None:
    """build_cases() 返回用例列表；按命令行参数写文件或检查漂移。"""
    parser = argparse.ArgumentParser(description=f"导出 {module} 的金标语料")
    parser.add_argument("--check", action="store_true",
                        help="不写文件，只检查已提交的 JSON 是否与当前 Python 实现一致")
    args = parser.parse_args()

    text = render(module, build_cases())
    out = OUT_DIR / f"{module}.json"
    if args.check:
        current = out.read_bytes().decode("utf-8") if out.exists() else ""
        if current != text:
            print(f"{out.relative_to(REPO_ROOT)} 与 Python 实现不一致，"
                  f"请运行 python scripts/golden/{module}.py 重新生成并检查差异", file=sys.stderr)
            sys.exit(1)
        print(f"{module}: {len(json.loads(text)['cases'])} 条用例与 Python 实现一致")
        return
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print(f"已写入 {out.relative_to(REPO_ROOT)}（{len(json.loads(text)['cases'])} 条用例）")
