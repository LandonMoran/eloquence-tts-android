#!/usr/bin/env python3
# -*- coding: ascii -*-
"""Resolve merge conflicts in wt-pr190b. Kotlin: keep MAIN. jni: keep both."""
import re
MARK = re.compile(br'^<<<<<<< HEAD\n(.*?)^=======\n(.*?)^>>>>>>> origin/main\n', re.M | re.S)
def keep_main(m):
    return m.group(2)
def keep_both(m):
    return m.group(1) + m.group(2)
def resolve(path, how):
    with open(path, 'rb') as f:
        data = f.read()
    out, n = MARK.subn(keep_main if how == 'main' else keep_both, data)
    assert n > 0, path + ': no conflict blocks found?'
    assert MARK.search(out) is None, path + ': markers remain'
    with open(path, 'wb') as f:
        f.write(out)
    print(path.rsplit('/', 1)[-1], 'resolved', n, 'blocks')
resolve('/root/eloquence-re/wt-pr190b/src/com/xw/vvtts/services/VvTtsService.kt', 'main')
resolve('/root/eloquence-re/wt-pr190b/src/com/xw/vvtts/utils/LanguageDetector.kt', 'main')
resolve('/root/eloquence-re/wt-pr190b/jni/vvtts_core.c', 'both')
print('ALL DONE')