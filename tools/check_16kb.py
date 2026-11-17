#!/usr/bin/env python3
"""
Google Play 16 KB page size check (apps targeting Android 15+ with native libraries).

Usage:  python3 tools/check_16kb.py app.apk [bundle.aab ...]

For every 64-bit native library (arm64-v8a, x86_64) it checks:
  1. ELF: every PT_LOAD segment is aligned to at least 16 KB (0x4000).
  2. APK only: an uncompressed .so starts at a zip offset that is a multiple of 16 KB.
Exit code 1 + a list of the offending files when something fails (the file name tells which library/SDK brings it).
32-bit libraries (armeabi-v7a, x86) are not covered by the Google Play requirement and are skipped.
"""
import struct, sys, zipfile

PAGE = 16 * 1024
ABIS = ("arm64-v8a", "x86_64")


def elf_min_load_align(data):
    """Smallest p_align among PT_LOAD segments of a 64-bit little-endian ELF, or None if it is not such an ELF."""
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        return None
    e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)
    aligns = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", data, off)[0]
        if p_type == 1:  # PT_LOAD
            aligns.append(struct.unpack_from("<Q", data, off + 0x30)[0])
    return min(aligns) if aligns else None


def abi_of(name):
    parts = name.split("/")
    for i, p in enumerate(parts):
        if p == "lib" and i + 1 < len(parts):
            return parts[i + 1]
    return None


def check(path):
    bad, total = [], 0
    is_apk = path.lower().endswith(".apk")
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            if not info.filename.endswith(".so") or abi_of(info.filename) not in ABIS:
                continue
            total += 1
            problems = []
            align = elf_min_load_align(z.read(info))
            if align is None:
                problems.append("not a readable 64-bit ELF")
            elif align < PAGE:
                problems.append("ELF segment alignment is 0x%x (needs 0x%x)" % (align, PAGE))
            if is_apk:
                if info.compress_type != 0:
                    problems.append("stored compressed in the APK (needs uncompressed)")
                else:
                    with open(path, "rb") as f:
                        f.seek(info.header_offset + 26)
                        n, m = struct.unpack("<HH", f.read(4))
                    start = info.header_offset + 30 + n + m
                    if start % PAGE:
                        problems.append("zip offset %d is not a multiple of 16 KB" % start)
            if problems:
                bad.append((info.filename, problems))
    return total, bad


def main(paths):
    failed = False
    for p in paths:
        total, bad = check(p)
        print("%s: %d 64-bit native libraries checked" % (p, total))
        for name, problems in bad:
            failed = True
            print("  FAIL %s" % name)
            for pr in problems:
                print("       - " + pr)
        if not bad:
            print("  OK: all 64-bit native libraries are 16 KB compatible")
    return 1 if failed else 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))
