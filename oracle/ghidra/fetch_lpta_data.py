#!/usr/bin/env python3
"""lpta payload extraction from the MachO2ELF speech modules (aarch64).

Provenance (all verified this session):
* chs.so / cht.so / kor.so / fin.so / jpn.so / enu.so ... from
  github.com/Mudb0y/Apple-Eloquence-ELF 1.2.3 (linux-aarch64), which is a
  Mach-O -> ELF conversion of Apple's Eloquence tvOS 18.2 speech modules.
* The rule functions apply_*_rules are compiled statement chains calling the
  same runtime primitives openevv's delta.c exposes (insert_2pt_s,
  test_string_s, if_testeq_v_lng, ...) [_ventproc/_vretproc/_get_parm etc.].
* Ghidra (grid runner) imports with image base 0x100000: a label DAT_0019xxxx
  resolves to .m2e_data + (0x0009xxxx - 0x98000); DAT_0018xxxx resolves into
  .m2e_text_const / .m2e_data by section walk.
* Per-lang rule tables = tail of .m2e_data (chs: 0xbd160+ ffff-delimited u16
  maps; kor: .m2e_data at 0xd5000, 0x252a0 bytes - jamo/dict tables).
"""
import struct, subprocess, json, sys, os

LANG_ARCH = {'chs': 'c2f6874a5d4573200c65d64e67dd4b26',  # sha256 of lib (unused placeholder)
             'cht': None, 'kor': None, 'jpn': None, 'fin': None}

LIB = '/tmp/aee-fresh/apple-eloquence-elf-1.2.3-linux-aarch64/lib'
OUT = '/root/eloquence-re/eloquence-android/oracle/ghidra/lpta'


def sections(so):
    out = subprocess.run(['llvm-readelf', '-S', '-W', so],
                         capture_output=True, text=True).stdout
    secs = {}
    for line in out.splitlines():
        p = line.split()
        if len(p) >= 7 and p[0].startswith('[') and p[1].strip(']').isdigit():
            secs[p[2]] = (int(p[4], 16), int(p[5], 16), int(p[6], 16))
    return secs


def main():
    os.makedirs(OUT, exist_ok=True)
    manifest = {}
    for lang in LANG_ARCH:
        so = os.path.join(LIB, f'{lang}.so')
        if not os.path.exists(so):
            print(f'skip {lang}: missing'); continue
        d = open(so, 'rb').read()
        secs = sections(so)
        if '.m2e_data' not in secs:
            print(f'skip {lang}: no .m2e_data'); continue
        md = (secs['.m2e_data'][1], secs['.m2e_data'][2])  # off,size
        blob = d[md[0]:md[0] + md[1]]
        with open(os.path.join(OUT, f'{lang}.m2e_data.bin'), 'wb') as f:
            f.write(blob)
        # head + tail (rule maps) snapshots for quick reference
        manifest[lang] = {
            'm2e_data_off': hex(md[0]), 'm2e_data_size': hex(md[1]),
            'head32': blob[:32].hex(),
            'tail64': blob[-64:].hex(),
            'sections': {k: [hex(x) for x in v] for k, v in
                         sorted(secs.items())},
        }
    with open(os.path.join(OUT, 'lpta_manifest.json'), 'w') as f:
        json.dump(manifest, f, indent=1)
    print(json.dumps({k: v['m2e_data_size'] for k, v in manifest.items()},
                     indent=0))


if __name__ == '__main__':
    main()