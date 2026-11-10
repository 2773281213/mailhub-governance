# 内置 HTML 解析器副本（CPython 3.14.3）

本目录存放 CPython 3.14.3 的两个标准库文件原样副本：

| 文件 | 来源 |
|---|---|
| `parser.py` | `Lib/html/parser.py`（CPython 3.14.3，Windows x86-64 官方安装包） |
| `_markupbase.py` | `Lib/_markupbase.py`（同上，`parser.py` 会 `import _markupbase`） |

## 为什么放在这里

`security_scan.py` 用 `html.parser.HTMLParser` 提取 HTML 正文里的链接。Python 3.13.3 的
旧实现遇到 `<![x]>`、`--!>`、`</script y>`、`</style/>` 这类写法会丢掉后面全部链接，
几个字符就能关掉整封信的链接检查（LOCAL_MODE.md 第四节第 5 条）。金标必须按 Python
3.14.3 的 HTML5 语义生成，而 CI 固定在 3.13.3 上跑，所以：

`scripts/golden/security_scan.py` 在导入 `security_scan` **之前**，把 `sys.modules` 里的
`html.parser` 和 `_markupbase` 换成这两个副本。这样在 CI 固定的 Python 3.13.3 上也能得到
3.14.3 的解析行为，不需要安装第二个 Python。`parser.py` 另外用到 `html.unescape` 与
`html.entities.html5`；这两个文件在 3.13.3 与 3.14.3 里内容相同，所以不用再复制。

## 许可与改动

两个文件都是 CPython 源码，按 PSF License Agreement（Python Software Foundation
License, https://docs.python.org/3/license.html）分发；版权归 Python Software Foundation
及其贡献者所有。文件内容逐字节保留，未做任何修改（包括模块开头的说明与注释）。
副本只用于生成金标语料，不参与 `:core` 的构建，也不被 `ruff` 检查（`--exclude scripts/golden/vendor`）。
