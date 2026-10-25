"""Map DEX method/field names to owning classes.

Usage: python dexwhere.py APK_OR_DEX_DIR REGEX [REGEX...]
"""
import os
import re
import struct
import sys
import zipfile


def uleb(buf, off):
    res = shift = 0
    while True:
        b = buf[off]
        off += 1
        res |= (b & 0x7F) << shift
        if not b & 0x80:
            return res, off
        shift += 7


def read_str(buf, off):
    _, off = uleb(buf, off)
    end = buf.index(b"\x00", off)
    return buf[off:end].decode("utf-8", "replace")


class Dex:
    def __init__(self, data):
        self.d = data
        h = struct.unpack_from("<4I", data, 56)  # string_ids_size/off, type_ids_size/off
        n, o = h[0], h[1]
        self.strings = [read_str(data, x) for x in struct.unpack_from("<%dI" % n, data, o)]
        n, o = h[2], h[3]
        self.types = [self.strings[x] for x in struct.unpack_from("<%dI" % n, data, o)]
        n, o = struct.unpack_from("<2I", data, 80)
        self.field_ids = [struct.unpack_from("<HHI", data, o + 8 * i)[2] for i in range(n)]
        n, o = struct.unpack_from("<2I", data, 88)
        self.method_ids = [struct.unpack_from("<HHI", data, o + 8 * i)[2] for i in range(n)]
        n, o = struct.unpack_from("<2I", data, 96)
        self.class_defs_size, self.class_defs_off = n, o

    def class_data(self, off):
        buf = self.d
        sf, off = uleb(buf, off)
        inf, off = uleb(buf, off)
        dm, off = uleb(buf, off)
        vm, off = uleb(buf, off)
        out = []
        idx = 0
        for _ in range(sf + inf):
            diff, off = uleb(buf, off)
            _, off = uleb(buf, off)
            idx += diff
            if idx < len(self.field_ids):
                out.append(("field", self.strings[self.field_ids[idx]]))
        idx = 0
        for _ in range(dm + vm):
            diff, off = uleb(buf, off)
            _, off = uleb(buf, off)
            _, off = uleb(buf, off)
            idx += diff
            if idx < len(self.method_ids):
                out.append(("method", self.strings[self.method_ids[idx]]))
        return out

    def classes(self):
        buf = self.d
        for i in range(self.class_defs_size):
            base = self.class_defs_off + i * 32
            cls_idx, _acc, _sup, _if, _src, _ann, data_off, _sv = struct.unpack_from("<8I", buf, base)
            yield self.types[cls_idx], (self.class_data(data_off) if data_off else [])


def iter_dex(path):
    if os.path.isdir(path):
        for n in sorted(os.listdir(path)):
            if n.endswith(".dex"):
                yield n, open(os.path.join(path, n), "rb").read()
    else:
        zf = zipfile.ZipFile(path)
        for n in sorted(zf.namelist()):
            if re.fullmatch(r"classes\d*\.dex", n):
                yield n, zf.read(n)


def main():
    src = sys.argv[1]
    pats = [re.compile(p) for p in sys.argv[2:]]
    for name, data in iter_dex(src):
        dex = Dex(data)
        for cls, members in dex.classes():
            hits = {f"{k}:{m}" for k, m in members if any(p.search(m) for p in pats)}
            if hits:
                print(f"{name}\t{cls.lstrip('L').rstrip(';')}\t{', '.join(sorted(hits))[:400]}")


if __name__ == "__main__":
    main()
