#!/usr/bin/env python3
"""parse_blocks.py - extract rom tables from DllSurvey block dumps into JSON.

Input: *.survey.blocks.txt from oracle/ghidra/DllSurvey.java v7.6+.
Output: per-binary JSON with named tables (rom_dict, rom_tables, delta_rules).

Anchors verified 2026-09-20 (see oracle/binary-map.md; DLL VA = ELF off + 0x1000):
  tvos18_chsrom: init 0xF5C0 s3 x26; final 0xF610 s5 x38; punct 0xF6D0 2B;
                 special 0xF710 (GB2312 word + pinyin, NUL sep); counters 0xF750 u32x4;
                 tones 0xF760 raw 0x20; math 0xF780 1B; digits 0xF790 NUL x9.
  tvos18_chtrom: same minus 0x10.
  tvos18_korrom: firstC 0x159F0 s3 x96; secondV 0x15A60 s3 x64; thirdC 0x15AB0 s4 x32;
                 first19 0x15B30 s3 x19; third28 0x15BB0 s4 x28; johab u16 0x15800;
                 hanja u16 0x15E50.
"""
import json
import os
import re
import sys

ROW = re.compile(r"^([0-9a-f]{8,}) ((?:[0-9a-f]{2} )*[0-9a-f]{2}) ?$")


class Dump:
    def __init__(self, rows):
        self.rows = rows  # dict va(row start) -> 16 bytes

    def get(self, va, n):
        out = bytearray()
        v = va
        while len(out) < n:
            # find the row that contains v: greatest key <= v
            key = None
            for k in sorted(self.rows):
                if k <= v:
                    key = k
                else:
                    break
            if key is None:
                break
            row = self.rows[key]
            off = v - key
            if off >= len(row):
                break
            take = min(len(row) - off, n - len(out))
            out += row[off : off + take]
            v += take
        return bytes(out)


def load_dump(path):
    rows = {}
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            m = ROW.match(line.strip())
            if not m:
                continue
            va = int(m.group(1), 16)
            rows[va] = bytes(int(b, 16) for b in m.group(2).split())
    return Dump(rows)


def rows(d, va, stride, n):
    """Decode n stride-delimited cells. Trailing NULs stripped for display."""
    res = []
    for i in range(n):
        cell = d.get(va + i * stride, stride)
        txt = cell.rstrip(b"\x00")
        try:
            s = txt.decode("ascii")
        except UnicodeDecodeError:
            s = "HEX:" + txt.hex()
        res.append({"va": hex(va + i * stride), "bytes": cell.hex(), "s": s})
    return res


def nul_strings(d, va, cap):
    raw = d.get(va, cap)
    out = []
    cur = bytearray()
    for b in raw:
        if b == 0:
            if cur:
                out.append(cur.decode("ascii", errors="replace"))
                cur = bytearray()
        else:
            cur.append(b)
    if cur:
        out.append(cur.decode("ascii", errors="replace"))
    return out


def special_words(d, va, cap):
    """GB2312 hanzi (2 bytes) + ascii pinyin, entries separated by NUL."""
    raw = d.get(va, cap)
    out = []
    i = 0
    n = len(raw)
    while i < n:
        if raw[i] == 0:
            i += 1
            continue
        if i + 1 < n and raw[i] > 0x80 and raw[i + 1] > 0x80:
            gb = raw[i : i + 2].hex()
            i += 2
            py = bytearray()
            while i < n and raw[i] != 0:
                py.append(raw[i])
                i += 1
            out.append({"gb": gb, "py": py.decode("ascii", errors="replace")})
        else:
            i += 1
    return out


def main():
    d = sys.argv[1]
    out_path = sys.argv[2]
    result = {}

    for root, _, files in os.walk(d):
        for fn in sorted(files):
            if not fn.endswith(".survey.blocks.txt"):
                continue
            data = load_dump(os.path.join(root, fn))
            base = fn[: -len(".survey.blocks.txt")]
            entry = {"layout": None, "tables": {}}
            if "chsrom" in base:
                entry["layout"] = "tvos18_chs"
                t = entry["tables"]
                t["initial"] = rows(data, 0xF5C0, 3, 26)
                t["final"] = rows(data, 0xF610, 5, 38)
                t["punct"] = rows(data, 0xF6D0, 2, 9)
                t["punct_raw"] = data.get(0xF6D0, 18).hex()
                t["special"] = special_words(data, 0xF710, 0x40)
                t["counters_u32"] = data.get(0xF750, 16).hex()
                t["tones_raw"] = data.get(0xF760, 32).hex()
                t["math"] = rows(data, 0xF780, 1, 10)
                t["digits"] = nul_strings(data, 0xF790, 0x40)
            elif "chtrom" in base:
                entry["layout"] = "tvos18_cht"
                t = entry["tables"]
                t["initial"] = rows(data, 0xF5B0, 3, 26)
                t["final"] = rows(data, 0xF600, 5, 38)
                t["punct"] = rows(data, 0xF6C0, 2, 9)
                t["special"] = special_words(data, 0xF700, 0x40)
                t["counters_u32"] = data.get(0xF740, 16).hex()
                t["tones_raw"] = data.get(0xF750, 32).hex()
                t["math"] = rows(data, 0xF770, 1, 10)
                t["digits"] = nul_strings(data, 0xF780, 0x30)
            elif "korrom" in base:
                entry["layout"] = "tvos18_kor"
                t = entry["tables"]
                t["firstC_list"] = rows(data, 0x159F0, 3, 96)
                t["secondV_list"] = rows(data, 0x15A60, 3, 64)
                t["thirdC_list_u"] = rows(data, 0x15AB0, 4, 32)
                t["first_19"] = rows(data, 0x15B30, 3, 19)
                t["third_28"] = rows(data, 0x15BB0, 4, 28)
                t["johab_u16"] = data.get(0x15800, 0x3C0).hex()
                t["hanja_u16"] = data.get(0x15E50, 0x100).hex()
            else:
                entry["layout"] = "unmapped_tbd"
            result[base] = entry

    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(result, fh, indent=1)
    for base, entry in result.items():
        n = sum(len(v) if isinstance(v, list) else 1 for v in entry["tables"].values())
        print("%-32s layout=%-14s rows=%d" % (base, entry["layout"], n))
    print("wrote", out_path)


if __name__ == "__main__":
    main()