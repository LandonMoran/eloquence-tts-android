#!/usr/bin/env python3
"""Parse Ghidra decompiled apply_*_rules chains into structured op sequences.

Input: oracle/ghidra/lpta/decomp/ghidra-decomp-<lang>.so.txt
Output: oracle/ghidra/lpta/rules_<lang>.ops.json
  one op = {fn, args:[...], refs:[DAT ids seen], line} preserving order.
The FUN_0015a1xx names are unresolved until the runner decompiles the
helpers; the transcription pass then maps them via helper_<lang>.json.
"""
import re
import json
import sys
import os

HERE = os.path.dirname(os.path.abspath(__file__))
DECOMP = os.path.join(HERE, 'lpta', 'decomp')
OUT = os.path.join(HERE, 'lpta')

CALL = re.compile(r'^(\w+)\((.*)\)(?:;\s*)?$')
ARGC = re.compile(r'((?:0x)?[0-9a-fA-F]+|"[^"]*"|&DAT_[0-9a-fA-F]+'
                  r'|(?:au|extraout)\.?_[0-9a-fA-F_]+|\([\d, ]*\)|lVar\d+|uVar\d+'
                  r'|iVar\d+|bVar\d+|[A-Za-z_]\w+)')
STMTL = re.compile(r'^\s*(if|goto|else|return|joined|LAB_)')


def split_args(s: str):
    """Ghidra C args, comma-split outside parens; keep strings/DAT refs whole."""
    args, depth, cur = [], 0, ''
    for ch in s:
        if ch in '([':
            depth += 1
        elif ch in ')]':
            depth -= 1
        if ch == ',' and depth == 0:
            args.append(cur.strip()); cur = ''
        else:
            cur += ch
    if cur.strip():
        args.append(cur.strip())
    return args


def is_leaf(stmt: str) -> bool:
    t = stmt.strip()
    if not t or t.startswith(('}', '{', '===', '//', ';')): return True
    if STMTL.match(t): return False
    return CALL.match(t) is not None or '=' in t


def parse_section(lines, name, addr):
    ops, cur = [], []
    datrefs = set()
    for ln in lines:
        t = ln.strip()
        m = CALL.match(t)
        if m and not t.startswith(('if', 'else', 'return', 'goto')):
            fn = m.group(1)
            args = split_args(m.group(2) or '')
            for a in args:
                if '&DAT_' in a: datrefs.add(a.replace('&', ''))
            ops.append({'fn': fn, 'args': args, 'line': t})
        else:
            for a in re.findall(r'&DAT_[0-9a-fA-F]+', t):
                datrefs.add(a.replace('&', ''))
            if is_leaf(t):
                if ops and not ops[-1].get('brk'):
                    pass
            elif t:
                ops.append({'fn': None, 'args': [], 'ctrl': t[:60], 'line': t})
    return ops, sorted(datrefs)


def main():
    lang = sys.argv[1] if len(sys.argv) > 1 else 'chs'
    src = os.path.join(DECOMP, f'ghidra-decomp-{lang}.so.txt')
    with open(src) as f:
        text = f.read()
    blocks, cur, curname, curaddr = [], [], None, None
    for line in text.splitlines():
        m = re.match(r'^=== (\w+) @ ([0-9a-fA-F]+) ===$', line.strip())
        if m:
            if curname:
                blocks.append((cur, curname, curaddr))
            curname, curaddr, cur = m.group(1), m.group(2), []
        elif curname is not None:
            cur.append(line)
    if curname:
        blocks.append((cur, curname, curaddr))
    out = {}
    for lines, nm, addr in blocks:
        if nm.startswith('apply_') or nm.startswith('test') or \
           nm.startswith('insert_') or nm.startswith('if_'):
            ops, refs = parse_section(lines, nm, addr)
            if ops or refs:
                out[nm] = {'addr': addr, 'refs': refs,
                           'n_lines': len(lines), 'ops': ops}
    with open(os.path.join(OUT, f'rules_{lang}.ops.json'), 'w') as f:
        json.dump(out, f, indent=0)
    names = list(out)
    print(f'{lang}: {len(names)} funcs, '
          f'{" ".join(n for n in names if "apply_" in n)}')
    print('total ops:', sum(v['n_lines'] for v in out.values()))


if __name__ == '__main__':
    main()