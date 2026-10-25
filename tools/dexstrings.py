"""Extract printable string runs from a (possibly header-mangled) dex file.

Usage: python dexstrings.py classes.dex out.txt
Writes "offset<TAB>string" lines for every printable ASCII run of length >= 4.
"""
import re
import sys


def main():
    data = open(sys.argv[1], "rb").read()
    out = open(sys.argv[2], "w", encoding="utf-8", newline="\n")
    seen = set()
    for m in re.finditer(rb"[\x20-\x7e]{4,400}", data):
        s = m.group().decode("ascii", "ignore")
        key = (m.start(), s)
        if key in seen:
            continue
        seen.add(key)
        out.write(f"{m.start()}\t{s}\n")
    out.close()
    print("runs:", len(seen))


if __name__ == "__main__":
    main()
