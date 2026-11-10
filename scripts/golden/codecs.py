"""导出 Python 编解码器的解码表，并生成金标用例。

`mail_client._decode_header` 用 `data.decode(charset or "utf-8", errors="replace")` 解编码字，
所以 Kotlin 端要对同样的字节得到同样的字符串。桌面 JDK 与 Android ICU 的 `Charset` 映射不同
（Android 的 Shift_JIS、Big5 都带厂商扩展，而且不同 Android 版本之间也不同），所以解码表全部
从 CPython 3.13.3 导出来，随 `:core` 打包，桌面和手机用同一份数据。

产物：

- android/core/src/main/resources/py/codecs/aliases.txt：编码名到规范名的映射（运行时资源）；
- android/core/src/main/resources/py/codecs/tables.bin：单字节表与多字节表（运行时资源，二进制）；
- android/core/src/test/resources/golden/codecs.json：金标用例（别名解析、逐编解码器的解码结果、
  gbk/big5/shift_jis/euc_kr 等编码的全码位比对分类表）。

    python scripts/golden/codecs.py           # 重新生成三个文件
    python scripts/golden/codecs.py --check   # 检查三个文件是否与当前 Python 一致

表的来源是**实测**：对每个编解码器逐字节 / 逐码位跑 `bytes.decode(name, errors="replace")`，
不抄任何映射表，所以与运行本脚本的 CPython 一定一致（CI 与本地都是 3.13.3，见 LOCAL_MODE.md）。

二进制表用「索引与码点的双差分 + varint」，并对同源编码做差分（gb18030 相对 gbk、
big5hkscs / cp950 相对 big5、cp932 相对 shift_jis、cp949 相对 euc_kr），
全部资源合计约 340 KB（其中 gb18030 四字节 BMP 区的映射表约 77 KB），不用 zlib：
zlib 的输出随构建它的 zlib 版本变化，`--check` 要求逐字节一致，所以只用手写的确定性编码。
"""
from __future__ import annotations

import base64
import codecs
import encodings
import encodings.aliases
import pkgutil
import random
import struct
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _common  # noqa: E402

RES_DIR = _common.REPO_ROOT / "android" / "core" / "src" / "main" / "resources" / "py" / "codecs"
ALIASES_FILE = RES_DIR / "aliases.txt"
TABLES_FILE = RES_DIR / "tables.bin"

MAGIC = b"MHCD"
VERSION = 1

# 编解码器的实现家族：Kotlin 端按家族选解码器，家族号必须与 codec/CodecTables.kt 一致。
FAMILY_SINGLE = 1       # 单字节表：256 项
FAMILY_DOUBLE = 2       # 无状态双字节：gbk、big5、shift_jis、cp932、cp949、cp950、big5hkscs
FAMILY_GB18030 = 3      # 双字节 + 四字节
FAMILY_EUC_KR = 4       # 带 KS X 1001:1998 的 8 字节组合序列
FAMILY_EUC_JP = 5       # 0x8E 单字节区 + 0x8F 三字节区
FAMILY_ISO2022_JP = 6   # 有状态：转义序列切换字符集
FAMILY_HZ = 7           # 有状态：~{ … ~}
FAMILY_UTF7 = 8
FAMILY_UTF8 = 9
FAMILY_UTF16 = 10       # 认 BOM，无 BOM 时按小端（CPython 在这些机器上也是本机序）
FAMILY_UTF16_LE = 11
FAMILY_UTF16_BE = 12
FAMILY_UTF32 = 13
FAMILY_UTF32_LE = 14
FAMILY_UTF32_BE = 15

# 显式指定家族的多字节 / 有状态编码；其余能通过单字节判定的一律按单字节导出。
FAMILIES = {
    "gb2312": FAMILY_DOUBLE,
    "gbk": FAMILY_DOUBLE,
    "gb18030": FAMILY_GB18030,
    "big5": FAMILY_DOUBLE,
    "big5hkscs": FAMILY_DOUBLE,
    "cp950": FAMILY_DOUBLE,
    "shift_jis": FAMILY_DOUBLE,
    "cp932": FAMILY_DOUBLE,
    "euc_jp": FAMILY_EUC_JP,
    "euc_kr": FAMILY_EUC_KR,
    "cp949": FAMILY_DOUBLE,
    "iso2022_jp": FAMILY_ISO2022_JP,
    "hz": FAMILY_HZ,
    "utf-7": FAMILY_UTF7,
    "utf-8": FAMILY_UTF8,
    "utf-16": FAMILY_UTF16,
    "utf-16-le": FAMILY_UTF16_LE,
    "utf-16-be": FAMILY_UTF16_BE,
    "utf-32": FAMILY_UTF32,
    "utf-32-le": FAMILY_UTF32_LE,
    "utf-32-be": FAMILY_UTF32_BE,
}

# 明确不实现的规范名（Python 有、本移植没有）：Kotlin 端按「未知编码」处理。
# 解码不了的编码字退回原始文本，与 Python 抛 LookupError / TypeError 时 `_decode_header` 的退路一致。
UNSUPPORTED = [
    "base64", "bz2", "euc_jis_2004", "euc_jisx0213", "hex", "idna", "iso2022_jp_1",
    "iso2022_jp_2", "iso2022_jp_2004", "iso2022_jp_3", "iso2022_jp_ext",
    "iso2022_kr", "johab", "punycode", "quopri", "raw-unicode-escape", "rot-13",
    "shift_jis_2004", "shift_jisx0213", "undefined", "unicode-escape", "uu", "zlib",
]


# 只在 Windows 上存在的编码。金标要和部署在 Linux 上的服务端一致，所以在哪里生成都按「不存在」处理。
WINDOWS_ONLY = {"mbcs", "oem"}


def lookup_name(name: str) -> str:
    """`codecs.lookup(name).name`，Windows 专属的编码按未知编码处理（抛 LookupError）。"""
    found = codecs.lookup(name).name
    if found in WINDOWS_ONLY:
        raise LookupError(name)
    return found


def decode(data: bytes, name: str) -> str:
    return data.decode(name, "replace")


def strict(data: bytes, name: str) -> str | None:
    """严格解码一次：成功返回字符串，失败（UnicodeDecodeError）返回 None。

    判断「这个码位有没有映射」必须用严格解码：gb18030 的四字节区里 lseq 39417 就映射到
    U+FFFD，用 `errors="replace"` 分不清它是「映射到替换字符」还是「解码失败」。
    """
    try:
        return data.decode(name)
    except UnicodeDecodeError:
        return None


def canonical_names() -> list[str]:
    """encodings 包里所有模块对应的规范名（`codecs.lookup(x).name`）。"""
    names = set()
    for info in pkgutil.iter_modules(encodings.__path__):
        try:
            names.add(lookup_name(info.name))
        except LookupError:
            pass
    for value in encodings.aliases.aliases.values():
        try:
            names.add(lookup_name(value))
        except LookupError:
            pass
    return sorted(names)


def single_table(name: str) -> list[int] | None:
    """单字节编码返回 256 项的表（-1 表示该字节非法），否则返回 None。

    判定标准（不用手写名单）：每个字节单独解码都不超过 1 个字符，且整串 256 个字节的解码结果
    等于逐个字节结果的拼接；再抽查若干双字节组合，必须仍然是逐字节拼接（多字节编码会露馅）。
    """
    table = []
    for b in range(256):
        good = strict(bytes([b]), name)
        if good is None:
            table.append(-1)
            continue
        if len(good) != 1:
            return None
        table.append(ord(good))

    def unit(b: int) -> str:
        return "\ufffd" if table[b] < 0 else chr(table[b])

    expect = "".join(unit(b) for b in range(256))
    if decode(bytes(range(256)), name) != expect:
        return None
    for b1 in range(0x80, 0x100, 7):
        for b2 in (0x40, 0x80, 0xA1, 0xD4, 0xFF):
            if decode(bytes([b1, b2]), name) != unit(b1) + unit(b2):
                return None
    return table


def pair_map(name: str) -> tuple[dict[int, int], list[tuple[int, str]]]:
    """逐码位导出双字节映射：返回 (普通表, 解出多个字符的例外)。

    普通表是「(b1 << 8 | b2) → 码点」；解出两个字符的码位（big5hkscs 的 0x8862 等）放进例外。
    解不出单个字符的组合不在这里，由码位分类表（[pair_classes]）描述。
    """
    table: dict[int, int] = {}
    literals: list[tuple[int, str]] = []
    for b1 in range(0x80, 0x100):
        for b2 in range(0x100):
            index = (b1 << 8) | b2
            good = strict(bytes([b1, b2]), name)
            if good is None:
                continue
            if len(good) == 1:
                table[index] = ord(good)
            elif good != unit(b1, name) + unit(b2, name):
                literals.append((index, good))
    return table, literals


def unit(b: int, name: str) -> str:
    """单个字节在「已经至少有下一个字节可看」的上下文里的解码结果。"""
    if b < 0x80:
        return chr(b)
    r = decode(bytes([b]), name)
    return r if len(r) == 1 and r != "\ufffd" else "\ufffd"


def pair_classes(name: str) -> tuple[bytes, list[tuple[int, str]]]:
    """全码位分类表：每个 (b1, b2) 一个字节，取值含义见 CodecsGoldenTest。"""
    table, literals = pair_map(name)
    literal_index = {index: i for i, (index, _) in enumerate(literals)}
    classes = bytearray(0x10000)
    for b1 in range(0x100):
        for b2 in range(0x100):
            index = (b1 << 8) | b2
            r = decode(bytes([b1, b2]), name)
            if index in table:
                assert r == chr(table[index]), (name, hex(index), r)
                classes[index] = 0
            elif r == "\ufffd":
                classes[index] = 1
            elif r == unit(b1, name) + unit(b2, name):
                classes[index] = 2
            else:
                classes[index] = 3
                assert index in literal_index, (name, hex(index), r)
    return bytes(classes), literals


def jisx0208_map() -> dict[int, int]:
    """JIS X 0208 表：索引 (b1 << 8 | b2)，b1、b2 是 ISO-2022 的 0x21–0x7E。

    直接实测 iso2022_jp 的 `ESC $ B … ESC ( B`，这样 Python 内部对 0x2140（F/W 反斜线）
    的特殊处理和 0x213D（U+2015，Java 的 ISO-2022-JP 给的是 U+2014）都在表里，
    Kotlin 端不用再写特例。
    """
    table: dict[int, int] = {}
    for b1 in range(0x21, 0x7F):
        for b2 in range(0x21, 0x7F):
            good = strict(b"\x1b$B" + bytes([b1, b2]) + b"\x1b(B", "iso2022_jp")
            if good is not None and len(good) == 1:
                table[(b1 << 8) | b2] = ord(good)
    return table


def jisx0212_map() -> dict[int, int]:
    """JIS X 0212 表（euc_jp 的 0x8F 三字节区）：索引同样按 ^0x80 折到 0x21–0x7E。"""
    table: dict[int, int] = {}
    for b2 in range(0xA1, 0xFF):
        for b3 in range(0xA1, 0xFF):
            good = strict(bytes([0x8F, b2, b3]), "euc_jp")
            if good is not None and len(good) == 1:
                table[((b2 ^ 0x80) << 8) | (b3 ^ 0x80)] = ord(good)
    return table


def gb18030_ranges() -> dict[int, int]:
    """gb18030 四字节序列的 BMP 部分：lseq → 码点（实测 0x81–0x84 开头的全部组合）。"""
    table: dict[int, int] = {}
    for b1 in range(0x81, 0x85):
        for b2 in range(0x30, 0x3A):
            for b3 in range(0x81, 0xFF):
                for b4 in range(0x30, 0x3A):
                    lseq = (((b1 - 0x81) * 10 + (b2 - 0x30)) * 1260
                            + (b3 - 0x81) * 10 + (b4 - 0x30))
                    good = strict(bytes([b1, b2, b3, b4]), "gb18030")
                    if good is not None and len(good) == 1:
                        table[lseq] = ord(good)
    return table


# ---- 二进制表编码 ----


def varint(value: int, out: bytearray) -> None:
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return


def zigzag(value: int) -> int:
    return (value << 1) if value >= 0 else ((-value << 1) - 1)


def encode_deltas(entries: dict[int, int]) -> bytes:
    """u32 条数 + 索引与码点的双差分 varint 流：gbk 的两万多个码位只需约 48 KB。"""
    out = bytearray()
    items = sorted(entries.items())
    out += struct.pack(">I", len(items))
    prev_index = 0
    prev_value = 0
    for index, value in items:
        assert value >= 0
        varint(index - prev_index, out)
        varint(zigzag(value - prev_value), out)
        prev_index = index
        prev_value = value
    return bytes(out)


def encode_literals(literals: list[tuple[int, str]]) -> bytes:
    """解出多个字符的码位：u32 条数，然后逐条 u16 索引 + u8 字符数 + 每个字符 u32。"""
    out = bytearray(struct.pack(">I", len(literals)))
    for index, text in literals:
        out += struct.pack(">HB", index, len(text))
        for ch in text:
            out += struct.pack(">I", ord(ch))
    return bytes(out)


def patch(base: dict[int, int], over: dict[int, int]) -> dict[int, int]:
    """子表相对父表的差异：父表有、子表没有的码位记成 0，Kotlin 端把它当「不存在」。"""
    out = {}
    for index, value in over.items():
        assert value != 0
        if base.get(index) != value:
            out[index] = value
    for index in base:
        if index not in over:
            out[index] = 0
    return out


# ---- 资源组装 ----


def choseong() -> list[int]:
    """KS X 1001 的初声表（_codecs_kr.c 的 cgk2u_choseong），0xFF 表示无映射。"""
    none = 0xFF
    return [0, 1, none, 2, none, none, 3, 4, 5, none, none, none, none, none, none, none,
            6, 7, 8, none, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18]


def jungseong() -> list[int]:
    """KS X 1001 的中声表（cgk2u_jungseong）：0xBF–0xD3 依次对应 0–20。"""
    return list(range(21))


def jongseong() -> list[int]:
    """KS X 1001 的终声表（cgk2u_jongseong），0xFF 表示无映射。"""
    none = 0xFF
    return [1, 2, 3, 4, 5, 6, 7, none, 8, 9, 10, 11, 12, 13, 14, 15,
            16, 17, none, 18, 19, 20, 21, 22, none, 23, 24, 25, 26, 27]


def build_tables() -> tuple[bytes, dict[str, int]]:
    """生成 tables.bin 与「规范名 → 家族」的映射。"""
    names = canonical_names()
    families: dict[str, int] = {}
    tables: list[dict] = []
    codecs_: list[dict] = []

    def find_table(name: str) -> int:
        return next(i for i, table in enumerate(tables) if table["name"] == name)

    def add_table(name: str, entries: dict[int, int], parent: str | None = None,
                  literals: list[tuple[int, str]] | None = None) -> None:
        tables.append({
            "name": name,
            "parent": None if parent is None else find_table(parent),
            "payload": encode_deltas(entries) + encode_literals(literals or []),
        })

    def add_codec(name: str, family: int, table_names: list[str]) -> None:
        families[name] = family
        codecs_.append({"name": name, "family": family,
                        "tables": [find_table(table) for table in table_names]})

    def pairs(name: str) -> dict[int, int]:
        return pair_map(name)[0]

    def add_singles(codec_name: str) -> str:
        """双字节编码的单字节表（Shift_JIS 的半角片假名、CP932 的 0x80 / 0xA0 / 0xFD–0xFF）。"""
        table = []
        for b in range(256):
            good = strict(bytes([b]), codec_name)
            table.append(ord(good) if good is not None and len(good) == 1 else -1)
        table_name = codec_name + "-single"
        add_table(table_name, {i: v for i, v in enumerate(table) if v >= 0})
        return table_name

    # 中文：gbk 是基准，gb18030 在它之上补 2149 个码位；gb2312 严格按 GB2312-80，单独存一份。
    gbk = pairs("gbk")
    add_table("gbk", gbk)
    add_codec("gbk", FAMILY_DOUBLE, ["gbk", add_singles("gbk")])
    add_table("gb2312", pairs("gb2312"))
    add_codec("gb2312", FAMILY_DOUBLE, ["gb2312", add_singles("gb2312")])
    # HZ 的 GB 模式用同一张 gb2312 表（CPython 的 hz 解码器按 ^0x80 索引它）。
    add_codec("hz", FAMILY_HZ, ["gb2312"])
    add_table("gb18030", patch(gbk, pairs("gb18030")), parent="gbk")
    # 四字节序列的 BMP 部分单独一张表：索引是 lseq（0–39419），不是 (b1 << 8 | b2)。
    add_table("gb18030-ranges", gb18030_ranges())
    add_codec("gb18030", FAMILY_GB18030, ["gb18030", "gb18030-ranges"])

    big5 = pairs("big5")
    add_table("big5", big5)
    add_codec("big5", FAMILY_DOUBLE, ["big5", add_singles("big5")])
    hk_table, hk_literals = pair_map("big5hkscs")
    add_table("big5hkscs", patch(big5, hk_table), parent="big5", literals=hk_literals)
    add_codec("big5hkscs", FAMILY_DOUBLE, ["big5hkscs", add_singles("big5hkscs")])
    add_table("cp950", patch(big5, pairs("cp950")), parent="big5")
    add_codec("cp950", FAMILY_DOUBLE, ["cp950", add_singles("cp950")])

    sjis = pairs("shift_jis")
    add_table("shift_jis", sjis)
    add_codec("shift_jis", FAMILY_DOUBLE, ["shift_jis", add_singles("shift_jis")])
    add_table("cp932", patch(sjis, pairs("cp932")), parent="shift_jis")
    add_codec("cp932", FAMILY_DOUBLE, ["cp932", add_singles("cp932")])

    # 日文：JIS X 0208 一份表给 iso2022_jp 与 euc_jp 共用，euc_jp 的 0x8E / 0x8F 另有规则。
    jisx0208 = jisx0208_map()
    check_euc_jp(jisx0208)
    add_table("jisx0208", jisx0208)
    add_codec("iso2022_jp", FAMILY_ISO2022_JP, ["jisx0208"])
    add_table("jisx0212", jisx0212_map())
    add_codec("euc_jp", FAMILY_EUC_JP, ["jisx0208", "jisx0212"])

    # 韩文：euc_kr 是基准，cp949 在它之上补 8823 个码位（UHC 扩展）。
    # 8 字节组合序列用到的初声 / 中声 / 终声表来自 _codecs_kr.c，0xFF 表示无映射（表里就是「不存在」）。
    kr = pairs("euc_kr")
    add_table("euc_kr", kr)
    jamo = choseong() + jungseong() + jongseong()
    add_table("euc-kr-jamo", {i: v for i, v in enumerate(jamo) if v != 0xFF})
    add_codec("euc_kr", FAMILY_EUC_KR, ["euc_kr", "euc-kr-jamo"])
    add_table("cp949", patch(kr, pairs("cp949")), parent="euc_kr")
    add_codec("cp949", FAMILY_DOUBLE, ["cp949", add_singles("cp949")])

    # 其余单字节编码：能通过单字节判定的一律导出 256 项表。
    for name in names:
        if name in families or name in UNSUPPORTED:
            continue
        table = single_table(name)
        if table is None:
            continue
        add_table(name, {i: v for i, v in enumerate(table) if v >= 0})
        add_codec(name, FAMILY_SINGLE, [name])

    # 没有表的家族：UTF-8 / UTF-16 / UTF-32 / UTF-7 / HZ。
    for name, family in FAMILIES.items():
        if name not in families:
            add_codec(name, family, [])

    return serialize(tables, codecs_), families


def check_euc_jp(jisx0208: dict[int, int]) -> None:
    """确认 euc_jp 的双字节部分就是 JIS X 0208（0x8E 半角片假名除外），否则表会少码位。"""
    extra = []
    for b1 in range(0xA1, 0xFF):
        for b2 in range(0xA1, 0xFF):
            r = decode(bytes([b1, b2]), "euc_jp")
            index = ((b1 ^ 0x80) << 8) | (b2 ^ 0x80)
            if len(r) == 1 and r != "\ufffd" and jisx0208.get(index) != ord(r):
                extra.append((b1, b2))
    if extra:
        raise AssertionError(f"euc_jp 的双字节表与 JIS X 0208 不一致：{extra[:8]}")


def serialize(tables: list[dict], codecs_: list[dict]) -> bytes:
    out = bytearray(MAGIC + bytes([VERSION]))
    out += struct.pack(">H", len(tables))
    for table in tables:
        name = table["name"].encode("utf-8")
        out += bytes([len(name)]) + name
        out += struct.pack(">h", -1 if table["parent"] is None else table["parent"])
        out += struct.pack(">I", len(table["payload"])) + table["payload"]
    out += struct.pack(">H", len(codecs_))
    for codec in codecs_:
        name = codec["name"].encode("utf-8")
        out += bytes([len(name)]) + name
        out += bytes([codec["family"], len(codec["tables"])])
        for index in codec["tables"]:
            out += struct.pack(">H", index)
    return bytes(out)


def build_aliases() -> str:
    """编码名 → 规范名，两张表分开存（Python 的查找规则对两者不同）。

    `encodings.search_function` 的规则：先查别名表 `aliases.get(norm)`，查不到再查
    `aliases.get(norm.replace('.', '_'))`；别名也查不到时才 `import encodings.<norm>`，
    而带 `.` 的名字在这一步直接跳过（所以 `utf.8` 是未知编码，`iso.8859.1` 却因为
    别名表里有 `iso_8859_1` 而认得）。因此别名表与模块名表必须分开：

        a<TAB>别名（归一化后的写法）<TAB>规范名
        m<TAB>encodings 模块名<TAB>规范名
    """
    lines = []
    for key in sorted(encodings.aliases.aliases):
        if normalize(key) != key:
            continue
        try:
            name = lookup_name(key)
        except LookupError:
            continue
        lines.append(f"a\t{key}\t{name}")
    for info in sorted(pkgutil.iter_modules(encodings.__path__), key=lambda i: i.name):
        try:
            name = lookup_name(info.name)
        except LookupError:
            continue
        lines.append(f"m\t{info.name}\t{name}")
    return "\n".join(lines) + "\n"


def normalize(name: str) -> str:
    """Python `encodings.normalize_encoding`：非 ASCII 字母数字丢弃但不插入下划线。"""
    chars = []
    punct = False
    for ch in name:
        if ch.isalnum() or ch == ".":
            if punct and chars:
                chars.append("_")
            if ch.isascii():
                chars.append(ch)
            punct = False
        else:
            punct = True
    return "".join(chars)


def main() -> None:
    tables, families = build_tables()
    aliases = build_aliases().encode("utf-8")
    check = "--check" in sys.argv
    stale = []
    for path, data, textual in ((ALIASES_FILE, aliases, True), (TABLES_FILE, tables, False)):
        if check:
            current = path.read_bytes() if path.exists() else b""
            # aliases.txt 是文本资源。Windows 上 core.autocrlf=true 会把签出的工作区文件换成
            # CRLF（.gitattributes 只给 *.json 定了 eol=lf，本任务不许改它），而提交的内容是 LF。
            # 比较前把 CRLF 还原成 LF，免得把签出行为当成资源漂移；tables.bin 是二进制，不处理。
            if textual:
                current = current.replace(b"\r\n", b"\n")
            if current != data:
                stale.append(path)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    if check:
        if stale:
            for path in stale:
                print(f"{path.relative_to(_common.REPO_ROOT)} 与 Python 不一致，请重新生成", file=sys.stderr)
            sys.exit(1)
        print(f"资源与 Python 一致：aliases.txt {len(aliases)} 字节，"
              f"tables.bin {len(tables)} 字节")
    _common.run("codecs", lambda: build_cases(families))


def build_cases(families: dict[str, int]) -> list[dict]:
    cases: list[dict] = []
    cases.extend(lookup_cases(families))
    cases.append({
        "id": "unsupported",
        "fn": "unsupported",
        "args": {},
        "expected": sorted(name for name in canonical_names() if name not in families),
    })
    cases.extend(decode_cases(families))
    cases.extend(pair_cases(families))
    return cases


def lookup_cases(families: dict[str, int]) -> list[dict]:
    cases = []
    seen = set()

    def add(name: str) -> None:
        if name in seen:
            return
        seen.add(name)
        try:
            expected = lookup_name(name)
        except LookupError:
            expected = None
        # Python 认得、本移植没实现的规范名单独放在 unsupported 用例里
        if expected is not None and expected not in families:
            return
        cases.append({"id": f"lookup_{len(cases):03d}", "fn": "lookup",
                      "args": {"name": name}, "expected": expected})

    for name in sorted(families):
        add(name)
    # 别名表里的每一个键（含大小写不规范的 csHPRoman8 这种）、每一个 encodings 模块名
    for key in sorted(encodings.aliases.aliases):
        add(key)
    for info in pkgutil.iter_modules(encodings.__path__):
        add(info.name)
    for name in ("utf-8", "UTF-8", "Utf.8", "utf 8", "u8", "cp65001", "utf", "utf8",
                 "iso.8859.1", "ISO-8859-1", "iso8859_1", "latin1", "latin_1", "LATIN-1", "8859",
                 "us-ascii", "US_ASCII", "646", "ansi_x3.4-1968", "iso-ir-100",
                 "gb2312", "GB_2312-80", "csGB2312", "chinese", "euc-cn", "gbk", "936", "cp936",
                 "gb18030", "gb-18030", "big5", "big-5", "cn-big5", "csBig5",
                 "shift_jis", "Shift-JIS", "sjis", "x-sjis", "csshiftjis", "ms_kanji",
                 "windows-31j", "cp932", "MS_Kanji",
                 "euc-kr", "euc_kr", "euckr", "ks_c_5601-1987", "ksc5601", "korean",
                 "cp949", "uhc", "949", "windows-949",
                 "euc-jp", "euc_jp", "iso-2022-jp", "csiso2022jp", "iso_2022_jp", "ISO2022JP",
                 "hz-gb-2312", "hz", "utf-7", "utf7", "unicode-1-1-utf-7", "csUnicode11UTF7",
                 "utf-16", "utf_16", "utf-16-le", "utf-16-be", "UTF-32", "utf_32_be",
                 "cp1252", "windows-1252", "1252", "cp1255", "windows-1255", "hebrew",
                 "koi8-r", "koi8_r", "koi8-u", "mac-roman", "macintosh", "mac_roman",
                 "cp437", "ibm437", "cp850", "latin-1", "tis-620", "iso-8859-9", "iso8859-9",
                 "unknown-charset", "x-unknown", "", "  ", "utf-9", "cp99999", "gb2312x",
                 "utf-32-le!", "ÜTF-8", "utf..8", "-utf-8-", "gb_2312.80"):
        add(name)
    return cases


def decode_cases(families: dict[str, int]) -> list[dict]:
    cases: list[dict] = []

    def add(codec: str, data: bytes) -> None:
        if not data:
            return
        expected = decode(data, codec)
        # CPython 的 utf-7 解码器在「高代理后面没有低代理」时会把孤立代理码点原样写进结果，
        # 这种字符串无法用 UTF-8 的 JSON 表示；这类用例跳过，行为在 Kotlin 测试里单独断言。
        if any(0xD800 <= ord(ch) <= 0xDFFF for ch in expected):
            return
        cases.append({"id": f"{codec}_{len(cases):04d}", "fn": "decode",
                      "args": {"codec": codec, "hex": data.hex()},
                      "expected": expected})

    for name in sorted(families):
        family = families[name]
        if family == FAMILY_SINGLE:
            add(name, bytes(range(0x100)))
            bad = bytes(b for b in range(0x80, 0x100) if decode(bytes([b]), name) == "\ufffd")
            if bad:
                add(name, bad)
            add(name, bytes(range(0x80)))
            continue
        for data in multibyte_samples(name, family):
            add(name, data)
    # 任务里点名的输入
    for index, (codec, hexs) in enumerate(SPECIFIC):
        cases.append({"id": f"case_{index:03d}_{codec}", "fn": "decode",
                      "args": {"codec": codec, "hex": hexs},
                      "expected": decode(bytes.fromhex(hexs), codec)})
    return cases


def multibyte_samples(name: str, family: int) -> list[bytes]:
    """多字节编码的定向 + 随机样例：合法序列、未定义码位、截断、非法前导字节。"""
    samples: list[bytes] = []
    if family == FAMILY_DOUBLE:
        table, literals = pair_map(name)
        good = sorted(table)[::max(1, len(table) // 12)][:12]
        for index in good:
            pair = bytes([index >> 8, index & 0xFF])
            samples.append(pair)
            samples.append(pair[:1])
        for index, _ in literals:
            samples.append(bytes([index >> 8, index & 0xFF]))
        # 未定义码位与非法前导字节
        for b1 in (0x80, 0x81, 0x8E, 0xA1, 0xC6, 0xE0, 0xF0, 0xFE, 0xFF):
            for b2 in (0x00, 0x20, 0x40, 0x7F, 0x80, 0xA1, 0xFE, 0xFF):
                samples.append(bytes([b1, b2]))
    else:
        samples.extend(structured_samples(name, family))
    rng = random.Random("mailhub-codecs-" + name)
    biased = [0x00, 0x0A, 0x0E, 0x0F, 0x1B, 0x20, 0x21, 0x2B, 0x2D, 0x30, 0x39, 0x40, 0x41,
              0x7E, 0x7F, 0x80, 0x81, 0x82, 0x84, 0x8E, 0x8F, 0x90, 0xA1, 0xA4, 0xB0,
              0xBF, 0xC0, 0xC2, 0xC6, 0xD4, 0xE0, 0xEB, 0xED, 0xF0, 0xF4, 0xFD, 0xFE, 0xFF,
              0x24, 0x28, 0x29, 0x42, 0x4A]
    for _ in range(120):
        length = rng.randint(1, 12)
        data = bytes(rng.choice(biased) if rng.random() < 0.55 else rng.randrange(256)
                     for _ in range(length))
        samples.append(data)
    return samples


def structured_samples(name: str, family: int) -> list[bytes]:
    """有明确结构的样例：每个分支的合法 / 截断 / 非法写法。"""
    out: list[bytes] = []
    if family == FAMILY_GB18030:
        for hexs in ("81308130", "8130", "81", "8130A1", "813081", "90308130", "E3329A35",
                     "8431A437", "FE39FE39", "FF", "FF31", "8130FF30", "8080", "A4D4"):
            out.append(bytes.fromhex(hexs))
    elif family == FAMILY_EUC_KR:
        for hexs in ("A4D4A4B6A4D0A4D4", "A4D4A4B6A4D0A4D4A1A1", "A4D4A4B6A4D0A4D5",
                     "A4D4A4B6A4D0", "A4D4A4B6", "A4D4", "A4D4A4B6A4D0A4D4A4D4",
                     "A4D4A1A1A1A1A1A1", "A4D4B0A1B0A1B0A1", "A4A1A4D4A4A1A4A1A4A1",
                     "A4D4A4A1A4A1A4A1A4A1", "B0A1", "B0", "A4FF", "8E8E", "8E",
                     "A4D4A4D4A4D4A4D4", "A4D4A4D4A4D4", "A4D4A1A1A4D4A1A1A4D4A1A1"):
            out.append(bytes.fromhex(hexs))
    elif family == FAMILY_EUC_JP:
        for hexs in ("8EA1", "8E", "8EA0", "8F", "8FA1", "8FA1A1", "8FA2AF", "A1A1", "A1",
                     "8FEDA1", "B0A1", "FF", "A4D4", "A1C0", "8FA2A1A1"):
            out.append(bytes.fromhex(hexs))
    elif family == FAMILY_ISO2022_JP:
        for hexs in ("1B2442213D1B2842", "1B2442213D", "1B2442", "1B244221", "1B", "1B28",
                     "1B2842", "1B28424142", "1B24402140", "1B24402141", "1B244221401B2842",
                     "1B284A4142", "1B2A42", "1B2142", "1B2442213D1B2442213D",
                     "1B26401B24421B2442213D", "414243", "1B4E", "1B4E41", "7F", "8080",
                     "1B2442213D1B41", "1B2442" + "213D" * 4 + "1B2842", "1B28494141",
                     "1B24424141" * 3, "1B2442" + "2121" * 2):
            out.append(bytes.fromhex(hexs))
    elif family == FAMILY_HZ:
        for hexs in ("7E7B", "7E7B4131", "7E7B41317E7D", "7E7E", "7E7D", "7E0A", "7E", "7E41",
                     "7E7B2141", "7E7B7E7D", "413132", "7E7BFF", "7E7B41", "7E7B41317E7B4131",
                     "7E7B7E7B41317E7D"):
            out.append(bytes.fromhex(hexs))
    elif family == FAMILY_UTF7:
        for hexs in ("2B414745415967426A2D", "2B2D", "2B414745415967426A", "2B", "2B21",
                     "612B414745415967426A2D62", "2B44433031446859", "2B4443303144685932",
                     "2B3244336D5A77", "E4B8", "FF", "2B2F2F2F2D", "2B41412D2B41412D",
                     "2B414745415967426A2D2B414745415967426A2D", "41422B2D4344"):
            out.append(bytes.fromhex(hexs))
    elif family == FAMILY_UTF8:
        for hexs in ("F4908080", "F48FBFBF", "F490", "EDA080", "ED9FBF", "C0AF", "E4B8", "F0",
                     "F5", "E08080", "F0908080", "EFBFBF", "80", "FF", "41", "C2", "E18480",
                     "F09F9880", "C3A9"):
            out.append(bytes.fromhex(hexs))
    elif family in (FAMILY_UTF16, FAMILY_UTF16_LE, FAMILY_UTF16_BE):
        for hexs in ("FFFE4100", "FEFF0041", "4100", "0041", "41", "00D800DC", "00D8",
                     "00D84100", "DC0000", "4100FFFE", "FFFE", "FEFF", "FF", "41FF",
                     "FFFE00D8", "41004200", "00D841", "410000D841"):
            out.append(bytes.fromhex(hexs))
    elif family in (FAMILY_UTF32, FAMILY_UTF32_LE, FAMILY_UTF32_BE):
        for hexs in ("FFFE000041000000", "0000FEFF00000041", "41000000", "00000041", "41",
                     "4100", "410000", "0000D800", "00D80000", "00001100", "11000000",
                     "FFFF0000", "0000FFFF", "FFFE0000FFFE0000", "4100000041000000"):
            out.append(bytes.fromhex(hexs))
    return out


# 任务描述里点名的输入，逐个写死在金标里，防止回归。
SPECIFIC = [
    ("shift_jis", "8540"),
    ("windows-1255", "D9FF41"),
    ("gb18030", "FF31"),
    ("gbk", "FF31"),
    ("euc_kr", "A4D4A4B6A4D0A4D4"),
    ("euc_kr", "A4D4A4B6A4D0"),
    ("euc_kr", "A4D4"),
    ("utf-8", "F4908080"),
    ("iso-2022-jp", "1B2442213D1B2842"),
    ("gb2312", "D7FA"),
    ("gb2312", "D7"),
    ("gb2312", "D7FAD6D0"),
    ("gbk", "D7FAD6D0"),
    ("big5", "A15A"),
    ("big5hkscs", "8862"),
    ("cp932", "8160"),
    ("shift_jis", "8160"),
    ("euc_jp", "8FA2AF"),
    ("cp949", "A4D4"),
    ("utf-16", "FFFE41004200"),
    ("utf-16-le", "41004200"),
    ("utf-16-be", "00410042"),
    ("utf-32", "FFFE000041000000"),
    ("utf-7", "2B414745415967426A2D"),
    ("hz", "7E7B41317E7D"),
    ("gb2312", "A1A4"),
    ("gbk", "A1A4"),
    ("gb2312", "D7FAD6D0B9FAB2FAD5F3"),
    ("gb18030", "90308130"),
    ("cp950", "A15A"),
]


def pair_cases(families: dict[str, int]) -> list[dict]:
    """全码位比对：gbk、big5、shift_jis、euc_kr（另含 cp932、cp949、gb2312）。

    每个 (b1, b2) 一个分类字节，zlib 压缩后 base64 存进金标；Kotlin 端逐码位还原并断言。
    这里的 zlib 只用于测试夹具（不影响 `--check` 的资源比对），`--check` 用同一份 Python 重新生成。
    """
    codes = [name for name in ("gbk", "gb2312", "big5", "shift_jis", "euc_kr", "cp932", "cp949")
             if families.get(name) in (FAMILY_DOUBLE, FAMILY_EUC_KR)]
    cases = []
    for name in codes:
        classes, literals = pair_classes(name)
        cases.append({
            "id": f"pairs_{name}",
            "fn": "pairs",
            "args": {"codec": name},
            "expected": {
                "classes": base64.b64encode(zlib.compress(classes, 9)).decode("ascii"),
                "literals": [[f"{index:04x}", text] for index, text in literals],
            },
        })
    return cases


if __name__ == "__main__":
    main()
