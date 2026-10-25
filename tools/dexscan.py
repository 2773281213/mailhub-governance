"""Lightweight DEX scanner: dump string pools + class defs and grep for patterns.

Usage: python dexscan.py APK PATTERN [PATTERN...]
Prints, per dex, matching strings and class names.
"""
import io
import re
import sys
import zipfile
import struct


def uleb128(buf, off):
    result = 0
    shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, off
        shift += 7


def read_string(buf, off):
    _, off = uleb128(buf, off)
    end = buf.index(b"\x00", off)
    return buf[off:end].decode("utf-8", "replace")


def dex_classes(data):
    """Return (strings, class_names)."""
    string_ids_size, string_ids_off = struct.unpack_from("<II", data, 56)
    type_ids_size, type_ids_off = struct.unpack_from("<II", data, 64)
    class_defs_size, class_defs_off = struct.unpack_from("<II", data, 96)

    offs = struct.unpack_from("<%dI" % string_ids_size, data, string_ids_off)
    strings = [read_string(data, o) for o in offs]

    type_offs = struct.unpack_from("<%dI" % type_ids_size, data, type_ids_off)
    type_names = [strings[o] for o in type_offs]

    classes = []
    for i in range(class_defs_size):
        class_idx = struct.unpack_from("<I", data, class_defs_off + i * 32)[0]
        if class_idx < len(type_names):
            classes.append(type_names[class_idx])
    return strings, classes


def main():
    apk = sys.argv[1]
    pats = [re.compile(p, re.I) for p in sys.argv[2:]] or [re.compile("netease", re.I)]
    zf = zipfile.ZipFile(apk)
    dexes = sorted(n for n in zf.namelist() if re.fullmatch(r"classes\d*\.dex", n))
    print("dex files:", dexes)
    for name in dexes:
        data = zf.read(name)
        strings, classes = dex_classes(data)
        hits = [s for s in set(strings) if any(p.search(s) for p in pats)]
        chits = [c for c in set(classes) if any(p.search(c) for p in pats)]
        if not hits and not chits:
            continue
        print(f"\n===== {name}  ({len(data)//1024} KiB, {len(strings)} strings) =====")
        if chits:
            print("-- classes --")
            for c in sorted(chits):
                print("  ", c)
        if hits:
            print("-- strings --")
            for s in sorted(hits):
                s = s.replace("\n", "\\n")
                print("  ", s[:400])


if __name__ == "__main__":
    main()
