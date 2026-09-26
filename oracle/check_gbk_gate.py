#!/usr/bin/env python3
import sys

TEST_HANZI = "一零零一二三四五六七八九百悄咪群合有新"

def gbk_key(ch):
    b = ch.encode("gb18030")
    if len(b) == 2:
        return (b[0] << 8) | b[1]
    if len(b) == 4:
        return (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]
    print("bad char", repr(ch), file=sys.stderr)
    sys.exit(2)

def main(path):
    want = {}
    for ch in TEST_HANZI:
        want[gbk_key(ch)] = ch
    found = {}
    capture = False
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if not capture:
                if "chs_oracle_gbk_keys[] = {" in line:
                    capture = True
                continue
            for tok in line.split(","):
                tok = tok.strip()
                if tok.startswith("0x"):
                    found[int(tok, 16)] = True
            if "};" in line:
                break
    missing = sorted(want.keys() - found.keys())
    if missing:
        print("GBK-GATE-FATAL: missing audio keys for " + str(len(missing)) + " of " + str(len(want)) + " test hanzi")
        for k in missing:
            print("  " + hex(k) + " " + want[k])
        sys.exit(1)
    print("GBK-GATE-OK: all " + str(len(want)) + " test hanzi have audio keys")

if __name__ == "__main__":
    main(sys.argv[1])