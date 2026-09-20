#!/usr/bin/env python3
"""rulefire.log -> delta_rules candidate rows.

Parses a gdb rulefire.log into a per-input-syllable sequence of
(rule -> phones fired) captured during synthesis, and emits
tsv rows: syllable | rule | phone_ids | occurrence.

Usage: python3 fire_to_rules.py rulefire.log [syllable-hex]
"""
import re
import sys

TST_RE = re.compile(r'^TST\s+(0x[0-9a-f]+)(?:\s+"([^"]*)")?')
INS_RE = re.compile(r'^INS2\s+(0x[0-9a-f]+)\s+(0x[0-9a-f]+)\s+(0x[0-9a-f]+)')
APL_RE = re.compile(r'^APL\s+(\S+)')

def main(path):
    with open(path, errors='replace') as fh:
        lines = fh.read().splitlines()
    rows = []
    cur_rule = None
    for ln in lines:
        m = APL_RE.match(ln)
        if m:
            cur_rule = m.group(1)
            continue
        m = TST_RE.match(ln)
        if m:
            cur_rule = 'test:' + m.group(1)
            continue
        m = INS_RE.match(ln)
        if m:
            phones = get_phone_string(m.group(2), m.group(3))
            rows.append((cur_rule or '?', phones))
    print(f'# fires={len(rows)} rules={len(set(r for r, _ in rows))}')
    for rule, phones in rows:
        print(f'{rule}\t{phones}')

def get_phone_string(a, b):
    # Phone-feature pair: low 6 bits each of x1..x2 as phone id.
    x1 = int(a, 16)
    x2 = int(b, 16)
    p1 = x1 & 0x3F
    p2 = x2 & 0x3F
    return f'{{{p1},{p2}}}'

if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else '/tmp/rulefire.log')