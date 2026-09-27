#!/usr/bin/env python3
# Regenerates EmojiExpander* tables from Unicode emoji-test.txt + CLDR tts annotations.
# Usage: python3 tools/generate_emoji.py
# Outputs: EmojiExpander.kt (en), EmojiExpanderZhHans.kt (zh-CN), EmojiExpanderZhHant.kt (zh-TW).
import re, urllib.request, ssl, glob, os, subprocess

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UTIL = os.path.join(ROOT, 'src', 'com', 'xw', '*', 'utils')
HERE = os.path.dirname(os.path.abspath(__file__))
EMOJI_TEST_RAW = os.path.join(HERE, 'emoji-test.txt')
CTX = ssl.create_default_context()


def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'hermes'})
    buf = urllib.request.urlopen(req, timeout=60, context=CTX).read()
    return buf.decode('utf-8')


def norm(seq):
    """Strip VS16 (U+FE0F) - CLDR annotation keys omit it."""
    return ''.join(c for c in seq if c != '\ufe0f')


def emoji_seqs(text):
    seqs = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        parts = line.split(';')
        if len(parts) < 2:
            continue
        status = parts[1].strip().split()[0]
        if status not in ('fully-qualified', 'minimally-qualified'):
            continue
        codes = parts[0].strip().split()
        seq = ''.join(chr(int(c, 16)) for c in codes)
        seqs.append(seq)
    return seqs


def cldr_names(locale):
    """CLDR tts short names keyed by normalized (VS16-stripped) sequence."""
    url = 'https://raw.githubusercontent.com/unicode-org/cldr/main/common/annotations/' + locale + '.xml'
    xml = fetch(url)
    names = {}
    pat = re.compile(r'<annotation cp="([^"]*)"([^>]*)>([^<]*)</annotation>')
    for m in pat.finditer(xml):
        cp_value = m.group(1).strip()
        attrs = m.group(2)
        text = re.sub(r'\s+', ' ', m.group(3)).strip()
        if not cp_value or not text:
            continue
        if 'type="tts"' not in attrs and 'tts="' not in attrs:
            continue  # keyword-only annotation; skip
        chars = []
        for token in cp_value.split():
            chars.extend(ord(ch) for ch in token)
        key = ''.join(chr(v) for v in chars)
        names[key] = text
    return names


def component_names(locale_names):
    """tts name per single codepoint (for composed fallback."""
    comp = {}
    for k, v in locale_names.items():
        if len(k) == 1:
            comp[k] = v
    return comp


def compose(seq, comp):
    """Compose a name from per-codepoint names (skip unnamed components."""
    parts = []
    for ch in seq:
        nm = comp.get(ch)
        if nm and nm not in parts:
            parts.append(nm)
    if not parts:
        return None
    return ', '.join(parts)


def kotlin_escape(seq):
    """UTF-16 code-unit escapes - backslash-u, matching the existing table."""
    b = seq.encode('utf-16-be')
    out = []
    for i in range(0, len(b), 2):
        u = (b[i] << 8) | b[i + 1]
        out.append(chr(92) * 2 + 'u%04X' % u)
    return ''.join(out)


def emit(entries, out_path, cls_name, doc_first):
    """Write a full EmojiExpander*.kt from scratch."""
    gitp = subprocess.check_output(['git', 'ls-files', 'src/com/xw/*/utils/EmojiExpander.kt'], cwd=ROOT, text=True).strip()
    tpl = subprocess.check_output(['git', 'show', 'HEAD:' + gitp], cwd=ROOT, text=True)
    head = tpl.split('private val NAMES')[0]
    tail = tpl.split('private val SORTED_KEYS')[1]
    out = head + 'private val NAMES: Map<String, String> = buildMap {\n'
    items = sorted(entries.items(), key=lambda kv: len(kv[0]), reverse=True)
    chunk = 500
    for i in range(0, len(items), chunk):
        out += '            putAll(mapOf(\n'
        for k, v in items[i:i + chunk]:
            out += f'                "{kotlin_escape(k)}" to "{v}",\n'
        out += '            ))\n'
    out += '        }\n        private val SORTED_KEYS' + tail
    out = out.replace('class EmojiExpander {', 'class ' + cls_name + ' {')
    out = out.replace('CLDR English short names', doc_first)
    with open(out_path, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(out)
        n = out.count('\n')
    assert len(entries) == 4992


def build(seqs, locale_names):
    comp = component_names(locale_names)
    entries = {}
    for seq in seqs:
        name = locale_names.get(norm(seq))
        if name is None:
            name = compose(seq, comp)
        if name is None:
            name = 'emoji'
        entries[seq] = name
    return entries


def main():
    if os.path.exists(EMOJI_TEST_RAW):
        data = open(EMOJI_TEST_RAW, encoding='utf-8').read()
    else:
        data = fetch('https://unicode.org/Public/emoji/latest/emoji-test.txt')
        with open(EMOJI_TEST_RAW, 'w', encoding='utf-8') as fh:
            fh.write(data)
    seqs = emoji_seqs(data)
    print('emoji-test sequences:', len(seqs))
    jobs = [('en', 'en', 'EmojiExpander', 'CLDR English short names'),
              ('zh', 'zh-Hans', 'EmojiExpanderZhHans', 'CLDR zh-Hans short names'),
              ('zh_Hant', 'zh-Hant', 'EmojiExpanderZhHant', 'CLDR zh-Hant short names')]
    for locale, doc, cls, docfirst,in jobs:
        lnames = cldr_names(locale)
        entries = build(seqs, lnames)
        util_dir = os.path.dirname(glob.glob(os.path.join(UTIL, 'EmojiExpander.kt'))[0])
        out_path = os.path.join(util_dir, cls + '.kt')
        emit(entries, out_path, cls, docfirst)
        direct = sum(1 for s in seqs if norm(s) in lnames)
        missing = len(seqs) - direct
        pass


if __name__ == '__main__':
    main()
