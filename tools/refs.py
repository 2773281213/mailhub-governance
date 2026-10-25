"""Find class/method bytecode references to string literals or method names.

Usage:
  python refs.py DEX_DIR s:device_id s:redirectUrl
  python refs.py DEX_DIR m:getAuthConfigurationForNetEase
"""
import os
import struct
import sys


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
        n, o = struct.unpack_from("<2I", data, 56)
        self.strings = [read_str(data, x) for x in struct.unpack_from("<%dI" % n, data, o)]
        n, o = struct.unpack_from("<2I", data, 64)
        self.types = [self.strings[x] for x in struct.unpack_from("<%dI" % n, data, o)]
        n, o = struct.unpack_from("<2I", data, 88)
        self.method_ids = [struct.unpack_from("<HHI", data, o + 8 * i) for i in range(n)]
        n, o = struct.unpack_from("<2I", data, 96)
        self.class_defs_size, self.class_defs_off = n, o

    def methods_with_code(self):
        buf = self.d
        for i in range(self.class_defs_size):
            base = self.class_defs_off + i * 32
            cls_idx, _, _, _, _, _, data_off, _ = struct.unpack_from("<8I", buf, base)
            if not data_off:
                continue
            cls_name = self.types[cls_idx]
            off = data_off
            sf, off = uleb(buf, off)
            inf, off = uleb(buf, off)
            dm, off = uleb(buf, off)
            vm, off = uleb(buf, off)
            idx = 0
            for _ in range(sf + inf):
                diff, off = uleb(buf, off)
                _, off = uleb(buf, off)
                idx += diff
            idx = 0
            for _ in range(dm + vm):
                diff, off = uleb(buf, off)
                _, off = uleb(buf, off)
                code_off, off = uleb(buf, off)
                idx += diff
                if not code_off or idx >= len(self.method_ids):
                    continue
                insns = struct.unpack_from("<I", buf, code_off + 12)[0]
                yield cls_name, self.strings[self.method_ids[idx][2]], code_off + 16, insns * 2


def main():
    src = sys.argv[1]
    targets = sys.argv[2:]
    files = [(n, open(os.path.join(src, n), "rb").read())
             for n in sorted(os.listdir(src)) if n.endswith(".dex")]
    seen = set()
    for name, data in files:
        dex = Dex(data)
        needles = {}
        for t in targets:
            mode, _, val = t.partition(":")
            if mode == "s":
                try:
                    i = dex.strings.index(val)
                except ValueError:
                    continue
                if i > 0xFFFF:
                    continue
                needles.setdefault(struct.pack("<H", i), []).append("str:" + val)
            else:
                for i, (_c, _p, nidx) in enumerate(dex.method_ids):
                    if dex.strings[nidx] == val:
                        needles.setdefault(struct.pack("<H", i), []).append("mth:" + val)
        if not needles:
            continue
        for cls, meth, start, size in dex.methods_with_code():
            blob = data[start:start + size]
            for needle, labels in needles.items():
                if needle in blob:
                    key = (cls, meth, tuple(labels))
                    if key in seen:
                        continue
                    seen.add(key)
                    short = cls.lstrip("L").rstrip(";")
                    print(f"{name}\t{short}\t{meth}\t{','.join(labels)}")


if __name__ == "__main__":
    main()
