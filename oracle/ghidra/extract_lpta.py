#!/usr/bin/env python3
"""Extract the LPTA rule machine data regions from the Apple tvos18 Eloquence
SPEECH dylibs (x86_64 slice): __data 0xb9000..0xbd890 holds the rule
statement streams, test-string tables, phone pool and the vstmtbl dispatch
table. Anchors cross-checked against oracle/binary-map.md."""
import struct, sys, json, os

def parse_macho(path, arch=0x01000007):
    whole = open(path, 'rb').read()
    if whole[:4] == b'\xca\xfe\xba\xbe':
        nfat = struct.unpack('>I', whole[4:8])[0]; off = 8; chosen = slice_off = None
        for _ in range(nfat):
            cputype, _, foff, fsize, _ = struct.unpack('>IIIII', whole[off:off+20]); off += 20
            if cputype == arch:
                chosen = (foff, fsize)
        if not chosen:
            raise SystemExit(f'arch {arch:#x} not in fat binary')
        slice_off = chosen[0]
    else:
        slice_off = 0
    data = whole[slice_off:]
    assert struct.unpack('<I', data[:4])[0] == 0xfeedfacf
    ncmds = struct.unpack('<I', data[16:20])[0]; off = 32; segs = []
    for _ in range(ncmds):
        cmd, cmdsize = struct.unpack('<II', data[off:off+8])
        if cmd == 0x19:  # LC_SEGMENT_64
            segname = data[off+8:off+24].split(b'\0')[0].decode()
            vmaddr, vmsize = struct.unpack('<QQ', data[off+24:off+40])
            fileoff, filesize = struct.unpack('<QQ', data[off+40:off+56])
            segs.append((segname, vmaddr, vmsize, fileoff, filesize))
        off += cmdsize
    def va2off(va):
        """absolute file offset by segment mapping"""
        for segname, vmaddr, vmsize, fileoff, filesize in segs:
            if vmaddr <= va < vmaddr + vmsize:
                if fileoff == 0 and segname.startswith('__'):
                    # zerofill-ish; fall through to low bits convention
                    pass
                return slice_off + (fileoff - vmaddr) + va
        return None
    return whole, data, va2off

REF = '/root/eloquence-re/reference/apple-eloquence-tvos18.2'
OUT = '/root/eloquence-re/eloquence-android/oracle/ghidra'
LANGS = ['chs', 'cht', 'kor']

summary = {}
for lang in LANGS:
    dll = os.path.join(REF, f'{lang}.dylib')
    whole, data, va2off = parse_macho(dll)
    # __data region 0xb9000..0xbd890 (18.5KB) per binary-map
    base, end = 0xb9000, 0xbd890
    blob = data[va2off(base):va2off(end)]
    assert len(blob) == 0x4890, (lang, hex(len(blob)))
    with open(os.path.join(OUT, f'{lang}_lpta_data.bin'), 'wb') as f:
        f.write(blob)
    # STMTYP text-function head (44-statement dispatch table is a compiler
    # structure; the vm semantics come from openevv src/delta)
    stmtyp_off = va2off(0xa0d80)
    with open(os.path.join(OUT, f'{lang}_stmttyp.bin'), 'wb') as f:
        f.write(data[stmtyp_off:stmtyp_off + 0x60])
    # rule stream head for the report
    rs = blob[0x160:0x260]
    summary[lang] = {
        'lpta_data': f'{lang}_lpta_data.bin',
        'size': len(blob),
        'rule_stream_head': rs[:80].hex(),
        'looks_seq_codes': len(set(rs[:64])) > 8,
    }
    print(json.dumps(summary[lang], indent=1))

with open(os.path.join(OUT, 'lpta_manifest.json'), 'w') as f:
    json.dump(summary, f, indent=1)
print('manifest written')