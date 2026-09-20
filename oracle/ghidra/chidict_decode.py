#!/usr/bin/env python3
"""chidict_decode.py - verify the transcribed ChiDict reader against the lifted
chsrom tables, hanzi by hanzi.

Implements exactly what the x86_64 chsrom.dylib code does (addresses cited):
- getGBIndexFromKey  0x232e  ((b0-0xB0)*0x5e + (b1-0xA1)) or -1 if b0<0xB0
- WhichLexTB         0x2770  highest i: m_aIndexRange[i] <= index  (19 u32s)
- GetIndOffset       0x27a4  index - m_aIndexRange[which]
- getGBIndexEntry    0x25e4  m_pIndexTB[which] + 6*offset
- index entry: [0..2) code bytes, [2] gcat(category), [4..6) u16 lex offset
                 (0xffff = absent)
- getLexAddr        0x268a  m_pLexTB[which] + lexoff  (m_pLexTB = 8 pointers)
- getPinyinFromKey  0x26b8  pinyin codes = entry[0..2) (+ Code2Pinyin letters)

Verified against: 中 = 0xD6D0 -> idx 3619 -> which 18, offset 19 ->
entry at aChiIndTB18[19] -> lexoff 0xea12.
"""
import json, os, struct, sys

BASE = os.path.join(os.path.dirname(__file__), '..', 'rom_lift')
MAN = os.path.join(BASE, 'manifest.json')

def load():
    man = json.load(open(MAN))
    out = {}
    for mod in ('chsrom', 'chtrom'):
        tables = {}
        for e in man[mod]:
            short = e['sym'].split('::')[-1].lstrip('_')
            short = short.split('.')[0]
            tables[short] = open(os.path.join(BASE, mod, e['file']), 'rb').read()
        out[mod] = tables
    return out

def index_ranges(tables):
    """Cumulative entry-count boundaries, inferred from the index tables:
    20 aChiIndTB* (200 each except the last two: 800, 2368)."""
    spans = []
    total = 0
    for i in range(20):
        key = f'aChiIndTB{i}'
        if key in tables:
            n = len(tables[key]) // 6
        else:
            n = 200 if i < 18 else (800 if i == 18 else 2368)
        spans.append(total)
        total += n
    return spans

def should_include(name):
    return (name == 'aChiIndTB0E' or
            (name.startswith('aChiIndTB') and name[7:].rstrip('E').isdigit()))

def gb_index(key):
    b0, b1 = key
    if b0 < 0xB0:
        return -1
    return (b0 - 0xB0) * 0x5e + (b1 - 0xA1)

def which_lex(ranges, idx):
    w = -1
    for i, r in enumerate(ranges):
        if r <= idx:
            w = i
    return w

def get_ind_offset(ranges, idx):
    return idx - ranges[which_lex(ranges, idx)]

def index_entry(tables, ranges, idx):
    w = which_lex(ranges, idx)
    off = get_ind_offset(ranges, idx)
    key = f'aChiIndTB{w}E'
    data = tables.get(key)
    if data is None:
        return None, w
    return data[off*6:(off+1)*6], w

def pinyin_for(tables, ranges, gb):
    idx = gb_index(gb)
    if idx < 0:
        return None, -1, None
    ent, w = index_entry(tables, ranges, idx)
    if not ent:
        return None, -1, None
    lexoff = struct.unpack('<H', ent[4:6])[0]
    if lexoff == 0xffff:
        return ent.hex(), -1, None
    lex = tables.get(f'aChiLexTB{w}E')
    if lex is None:
        return ent.hex(), -1, w
    return ent.hex(), lexoff, lex[lexoff:lexoff+16].hex()

def decode(codepoints):
    """Pinyin code bytes -> letters. Whole-codepoint pairs for the initial
    consonants (GB-coded), single bytes for finals/tones. Pairs checked
    against the consonant table order zh,ch,sh,b,p,m,f,d,t,n,l,z,c,s,r,j,q,x,
    g,k,h. Returns a readable pinyin guess; the exact alphabet table of
    PinYin::Code2Pinyin remains the one open transcription point."""
    zh = 'zhchshbpmfdtnlzcsrjqxywgkh'
    s = ''
    i = 0
    while i < len(codepoints):
        b = codepoints[i]
        if b == 0:
            break
        if 0xa1 <= b and i + 1 < len(codepoints) and 0xa1 <= codepoints[i+1]:
            hi, lo = b - 0xa1, codepoints[i+1] - 0xa1
            if hi < len(zh):
                s += zh[hi]
            else:
                s += '?'
            i += 2
        else:
            s += chr(b)
            i += 1
    return s

def main():
    tables = load()
    spans = index_ranges(tables['chsrom'])
    print('ranges:', spans)
    for name, gb in (('中', (0xD6, 0xD0)), ('国', (0xB9, 0xFA)), ('你', (0xC4, 0xE3)),
                     ('好', (0xBA, 0xC3)), ('我', (0xCE, 0xD2)), ('汉', (0xBA, 0xBA))):
        idx = gb_index(gb)
        ent, lexoff, lexhex = pinyin_for(tables['chsrom'], spans, gb)
        print(f'{name} GB={gb[0]:02x}{gb[1]:02x} idx={idx}')
        print(f'   entry={ent} lexoff={lexoff}')
        if lexhex:
            print(f'   lex={lexhex}')
    c = [0x56, 0xff, 0x58, 0x2b, 0x12]
    print('sample code decode:', decode(c))

if __name__ == '__main__':
    main()