"""一次检查全部金标语料是否与当前 Python 实现一致（CI 用）。

逐个以 --check 运行 scripts/golden/ 下的导出脚本（跳过 _ 开头的公共模块和本文件），
任何一个不一致就以非零退出。新加的导出脚本放进这个目录就会自动纳入检查。

金标由 Python 3.13 生成；不同 Python 版本的 Unicode 数据库不同，会造成假的漂移，
所以 CI 里只在 3.13 上运行本脚本。
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def main() -> int:
    scripts = sorted(p for p in HERE.glob("*.py") if not p.name.startswith("_") and p.name != "check_all.py")
    failed = []
    for script in scripts:
        result = subprocess.run([sys.executable, str(script), "--check"])
        if result.returncode != 0:
            failed.append(script.name)
    if failed:
        print(f"金标与 Python 实现不一致：{', '.join(failed)}", file=sys.stderr)
        return 1
    print(f"全部 {len(scripts)} 个金标脚本一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
