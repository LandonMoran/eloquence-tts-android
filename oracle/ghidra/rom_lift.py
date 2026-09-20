#!/usr/bin/env python3
"""Lift the named StaticDict/PinYin/Unicode tables out of the Apple Eloquence
rom dylibs (chsrom/chtrom/korrom) as byte-for-byte blobs.

The dylibs are fat: x86_64 slice first, and on that slice __TEXT has
vmaddr == fileoff, so every symbol table address is a file offset into the
boot slice. Sections __const and __data carry the dict/index tables.
Mirror of the jajp approach: bytes, not retyped; the code that reads them
is transcribed separately from the disassembly.
"""

import json
import os
import re
import subprocess
import sys

REF = '/root/eloquence-re/reference/apple-eloquence-tvos18.2'
OUT = '/root/eloquence-re/eloquence-android/oracle/rom_lift'


def sections(path):
    out = subprocess.run(
        ['llvm-objdump', '--macho', '--private-headers', path],
        capture_output=True, text=True).stdout
    secs = {}
    lines = out.splitlines()
    for i, line in enumerate(lines):
        if line.strip().startswith('sectname'):
            seg = next((lines[k].split()[-1] for k in range(i - 2, i)
                        if lines[k].strip().startswith('segname')), '__TEXT')
            name = line.split()[-1]
            addr = size = off = None
            for j in range(i + 1, min(i + 10, len(lines))):
                t = lines[j].strip()
                if t.startswith('addr '):
                    addr = int(t.split()[1], 16)
                elif t.startswith('size '):
                    size = int(t.split()[1], 16)
                elif t.startswith('offset '):
                    off = int(t.split()[1])
            if addr is not None:
                secs[seg + ',' + name] = (addr, size, off)
    return secs


def symbols(path):
    """name -> (section, addr, size). Sizes from address deltas within a
    section (objdump -t); Mach-O fat: first slice is the x86_64 one (VA==fileoff)."""
    out = subprocess.run(
        ['llvm-objdump', '--macho', '-t', path],
        capture_output=True, text=True).stdout
    raw = []  # (addr, section, name)
    for line in out.splitlines():
        if ':' in line[:20] or 'SYMBOL' in line:
            continue
        parts = line.split()
        if not parts or not re.match(r'^[0-9a-f]+$', parts[0]):
            continue
        addr = int(parts[0], 16)
        sec = next((p for p in parts if p.startswith('__TEXT,') or p.startswith('__DATA,')), None)
        name = next((p for p in reversed(parts)
                     if p.startswith('_') and not p.startswith(('__TEXT', '__DATA'))), None)
        if sec is None or name is None:
            continue
        raw.append((addr, sec, name))
    result = {}
    for sec in sorted({s for _, s, _ in raw}):
        group = sorted([(a, n) for a, s, n in raw if s == sec])
        for i, (addr, name) in enumerate(group):
            nxt = group[i + 1][0] if i + 1 < len(group) else None
            result[name] = (sec, addr, nxt)
    return result


KEEP = re.compile(r'(Chi|Dict|Lex|Ind|Big5|Homo|Pinyin|Jamo|johab|Roman|Tone|Unicode|CodeConv|PinYin|Count|Num|Digit|Punct|Space|Special|Kor|Hanja|Syllab)', re.I)


def main():
    os.makedirs(OUT, exist_ok=True)
    manifest = {}
    for mod in ['chsrom', 'chtrom', 'korrom']:
        path = os.path.join(REF, mod + '.dylib')
        secs = sections(path)
        syms = symbols(path)
        mod_out = os.path.join(OUT, mod)
        os.makedirs(mod_out, exist_ok=True)
        d = open(path, 'rb').read()
        sla = lambda sec: secs[sec][0] - secs[sec][2]  # addr->fileoff slide
        entries = []
        for name, (sec, addr, nxt) in sorted(syms.items(), key=lambda kv: kv[1][1]):
            if not KEEP.search(name):
                continue
            if sec not in secs:
                continue
            end = nxt if nxt is not None else secs[sec][0] + secs[sec][1]
            off = addr - sla(sec)
            data = d[off:min(end - sla(sec), len(d))]
            if len(data) == 0:
                continue
            fname = re.sub(r'[^\w.]+', '_', name).strip('_') + '.bin'
            fpath = os.path.join(mod_out, fname)
            with open(fpath, 'wb') as fp:
                fp.write(data)
            entries.append({
                'sym': name, 'sec': sec, 'addr': hex(addr),
                'end_addr': hex(end), 'size': len(data),
                'file': fname, 'sha': __import__('hashlib').sha256(data).hexdigest()[:16],
            })
        manifest[mod] = entries
        print(f'{mod}: {len(entries)} tables, {sum(e["size"] for e in entries)} bytes')
    with open(os.path.join(OUT, 'manifest.json'), 'w') as fp:
        json.dump(manifest, fp, indent=1)
    print('manifest:', os.path.join(OUT, 'manifest.json'))


if __name__ == '__main__':
    main()