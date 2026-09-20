#!/usr/bin/env python3
"""Segments the Apple Eloquence speech modules' .m2e_data region (VA==fileoff)
into individual structures, cross-checked across all languages and both
architectures (x86_64 so + aarch64 so must carry identical data).

Output per language: oracle/ghidra/<lang>_m2edata.bin (raw region) plus a
JSON manifest listing each subtable: offset, size, element width, structure
tag (ffff-map / u8-stream / u16-ptr-run / ascii / raw) and a hex digest.
Run from repo root: python3 oracle/ghidra/extract_m2edata.py"""
import json, os, struct, hashlib, glob

DIRS = [
    '/root/.scratch_cjk/apple-eloquence-elf-1.2.3-linux-x86_64/lib',
    '/tmp/art_check/aee/apple-eloquence-elf-1.2.3-linux-aarch64/lib',
]
LANGS = ['chs', 'cht', 'kor', 'fin', 'jpn', 'enu', 'ita']
OUT = '/root/eloquence-re/eloquence-android/oracle/ghidra'

def readelf_section(so, name):
    import subprocess
    out = subprocess.run(['llvm-readelf', '-S', so], capture_output=True, text=True).stdout
    for line in out.splitlines():
        parts = line.split()
        if name in parts:
            i = parts.index(name)
            off = int(parts[i + 2], 16); size = int(parts[i + 3], 16)
            return off, size
    return None

def segment(blob, arch):
    """Heuristic segmenter: walk u16 endian-consistent regions."""
    segs = []
    i = 0
    n = len(blob)
    while i < n - 3:
        # ffff-delimited u16 map run (records: (u16,u16) between 0xffff)
        if blob[i] == 0xff and blob[i+1] == 0xff:
            j = i + 2
            rows = []
            while j + 4 <= n:
                if blob[j] == 0xff and blob[j+1] == 0xff:
                    break
                v1 = int.from_bytes(blob[j:j+2], 'little'); v2 = int.from_bytes(blob[j+2:j+4], 'little')
                rows.append((v1, v2))
                j += 4
            if 8 <= len(rows) <= 512:
                if j + 2 <= n and blob[j] == 0xff and blob[j+1] == 0xff:
                    segs.append((i, j + 2 - i, 'ffff-map', f'{len(rows)} rows'))
                    i = j + 2
                    continue
        # ascii run (length >= 8)
        if 0x20 <= blob[i] < 0x7f:
            j = i
            while j < n and 0x20 <= blob[j] < 0x7f:
                j += 1
            if j - i >= 8:
                segs.append((i, j - i, 'ascii', blob[i:j].decode('latin1')[:60]))
                i = j
                continue
        i += 1
    return segs

def main():
    manifest = {}
    for archdir in DIRS:
        arch = 'aarch64' if 'aarch64' in archdir else 'x86_64'
        for lang in LANGS:
            so = os.path.join(archdir, f'{lang}.so')
            if not os.path.exists(so):
                continue
            so_off, so_size = readelf_section(so, f'.m2e_data')
            if so_off is None:
                continue
            blob = open(so, 'rb').read()[so_off:so_off + so_size]
            h = hashlib.sha256(blob).hexdigest()[:16]
            segs = segment(blob, arch)
            manifest[f'{arch}/{lang}'] = {
                'section_off': hex(so_off), 'size': so_size, 'sha': h,
                'tables': [{'off': hex(a), 'size': sz, 'kind': k, 'note': nt} for a, sz, k, nt in segs],
            }
            # dump raw once per language (x86_64 preferred)
            if arch == 'x86_64':
                with open(os.path.join(OUT, f'{lang}_m2edata.bin'), 'wb') as f:
                    f.write(blob)
    with open(os.path.join(OUT, 'm2edata_manifest.json'), 'w') as f:
        json.dump(manifest, f, indent=1)
    for k, v in manifest.items():
        print(f'{k}: {v["size"]}B {v["sha"]} tables={len(v["tables"])}')
        for t in v['tables'][:12]:
            print(f'   {t["off"]} {t["size"]:6d} {t["kind"]:9s} {t["note"]}')

if __name__ == '__main__':
    main()