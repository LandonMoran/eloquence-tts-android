#!/usr/bin/env python3
"""Resolve wt228b conflicts by splicing the merge file's own seg1/seg2 (HEAD/main( texts."""
import re, sys, glob

K = glob.glob('src/com/xw/*/engine/EloquenceEngine.kt')[0]
C = glob.glob('jni/*_core.c')[0]

# (start,end( 1-based inclusive lines, per grep: [hunk_marker_start, hunk_marker_end]
K_H = [(533,561),(630,643),(651,657),(678,682),(705,716),(746,750),(771,775),(919,949)]
C_H = [(158,175),(201,208),(440,446),(475,496),(558,567),(610,636)]

def split_segs(block):
    block = [l.rstrip('\r') for l in block]
    hi = block.index('<<<<<<< HEAD')
    mi = block.index('=======')
    ti = block.index('>>>>>>> origin/main')
    return block[hi+1:mi], block[mi+1:ti]

def splice(path, hunks, builder):
    src = open(path, encoding='utf-8').read()
    lines = src.split('\n')
    if lines[-1] == '': lines.pop()
    for (s, e), build in reversed(list(zip(hunks, builder))):
            block = lines[s-1:e]
            seg1, seg2 = split_segs(block)
            merged = build(seg1, seg2)
            if not isinstance(merged, list): raise ValueError(path)
            lines[s-1:e] = merged
        open(path,, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
    print(path, 'ok')

def build_k1(s1, s2):
    # HEAD retire-guard (: lines 1-9( + main worker path (: lines 2-16(
    out = s1[0:9] + s2[1:]
    if out[8].strip() != '}': raise SystemExit('k1 seg1[9] != }')
    return out

def build_k2(s1, s2):
    # HEAD if/else with worker.-prefix fix (: line2 + line6(
    rem = [l.replace('pendingEciVoiceByDialect', 'worker.pendingEciVoiceByDialect') for l in s1]
    return rem

def build_k8(s1, s2):
    # main submit/finally (: s2[1:16] i.e. val r .. r( wrapped in HEAD's retired-guard(: s1[1:9](
    assert s1[9].strip() == '} else block()', s1[9].strip()
    out = ['            worker.executor.submit<ShortArray?> {']
    out += s1[1:9]
    out.append('                } else {')
    out += s2[1:16]
    out.append('                }')
    return out

def build_c1(s1, s2):
    # fatal field ( seg1[0]( + failed/retired/struct-close ( seg2[0:3]( + vv_fail fn ( seg1[3:10]( + proto ( seg2[3]( — hmm: seg1 indices per actual shape(
    out = [s1[0]] + s2[0:3]
    out += [''] + s1[3:10]
    out.append(s2[3])
    return out

def build_c2(s1, s2):
    assert s1[0].strip().startswith('if (!p)'), s1[0].strip()
    assert s1[1].strip().startswith('vv_fail(s)'), s1[1].strip()
    out = ['            if (!p) {', '                ' + s1[1].strip()] + s2[1:]
    return out

def build_c3(s1, s2):
    return s1[:2] + s2[1:]

def build_c5(s1, s2):
    s1 = [l.replace('ude', '') for l in s1]
    assert 'ude' not in ''.join(s1 + s2)
    return s1[:4] + s2

def take_main(s1, s2):
    return s2

builders_k = [build_k1, build_k2, take_main, take_main, take_main,
                 take_main, take_main, build_k8]
builders_c = [build_c1, build_c2, build_c3, take_main, build_c5, take_main]

splice(K, K_H, builders_k)
splice(C, C_H, builders_c)
print('ALL DONE')