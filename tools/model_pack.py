#!/usr/bin/env python3
# -*- coding: ascii -*-
"""model_pack.py - pack Lingua ngram JSON language models into one .pack file.

The language-model JSON files in language-models/<lang>/<order>.json have
the Lingua grouped-frequency shape:

    {"language": "ENGLISH", "ngrams": {"<count>/<denominator>": "tok1 tok2 ..."}}

Every whitespace-separated token in the value string shares that count and
denominator.  This tool converts all JSON models for all languages into one
compact binary .pack file and proves the conversion is lossless (every
ngram -> count entry round-trips exactly).

Subcommands:

    pack      <models_dir> <out.pack>   write the packed file
    selftest  <models_dir>              pack + unpack in memory, assert lossless
    roundtrip <models_dir>              alias of selftest
    verify    <models_dir> <pack>       assert an existing .pack byte-matches a fresh pack

Binary format spec (version 2, zlib-compressed language sections):
1. File starts with 4-byte magic "ELQM", version byte 2, flags byte (bit 0 set = language sections zlib-compressed), then u16 LE language count.
2. Language index: per language, sorted by code: u8 code length + ASCII code + u64 LE byte offset + u64 LE compressed length of its section.
3. Language section: u8 order count, then per order (sorted): u8 order id (1=unigrams..5=fivegrams) + u32 LE offset relative to section start.
4. Order blob: varint group count, then per group: varint count + varint denominator + varint token count, then tokens.
5. Tokens are stored UTF-8, sorted, shared-prefix delta encoded: u16 LE shared bytes + u16 LE suffix bytes + suffix (vs previous token; first token shared=0).
6. All counts/denominators/token counts are LEB128 varints; all multi-byte ints little-endian; each inflated section yields the same layout, so a loader can seek by language then by order.
"""

import io
import json
import os
import struct
import sys
import zlib

MAGIC = b"ELQM"
VERSION = 2
FLAGS = 1  # bit 0: language sections are zlib-compressed
FLAG_COMPRESS = 1

ORDER_IDS = {
    "unigrams": 1,
    "bigrams": 2,
    "trigrams": 3,
    "quadrigrams": 4,
    "fivegrams": 5,
}
ORDER_NAMES = {v: k for k, v in ORDER_IDS.items()}

SPEC_LINES = (
    '1. File starts with 4-byte magic "ELQM", version byte 2, flags byte (bit 0 set = language sections zlib-compressed), then u16 LE language count.',
    "2. Language index: per language, sorted by code: u8 code length + ASCII code + u64 LE byte offset + u64 LE compressed length of its section.",
    "3. Language section: u8 order count, then per order (sorted): u8 order id (1=unigrams..5=fivegrams) + u32 LE offset relative to section start.",
    "4. Order blob: varint group count, then per group: varint count + varint denominator + varint token count, then tokens.",
    "5. Tokens are stored UTF-8, sorted, shared-prefix delta encoded: u16 LE shared bytes + u16 LE suffix bytes + suffix (vs previous token; first token shared=0).",
    "6. All counts/denominators/token counts are LEB128 varints; all multi-byte ints little-endian; each inflated section yields the same layout, so a loader can seek by language then by order.",
)

MAX_LEN = 0xFFFF  # u16 length field cap for a single token


def write_varint(buf, value):
    """Write an unsigned LEB128 varint to buf."""
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            buf.write(bytes((byte | 0x80,)))
        else:
            buf.write(bytes((byte,)))
            return


def read_varint(f):
    """Read an unsigned LEB128 varint from f."""
    result = 0
    shift = 0
    while True:
        byte = f.read(1)
        if not byte:
            raise EOFError("truncated varint")
        b = byte[0]
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result
        shift += 7
        if shift > 63:
            raise ValueError("varint too long")


def scan_models(models_dir):
    """Walk models_dir and return {lang_code: {order_id: file_path}}.

    Models are expected as <models_dir>/<lang>/<order>.json where <order>
    is one of the ORDER_IDS filenames.  JSON files directly inside
    models_dir (no language subdir) are skipped with a warning.
    """
    models_dir = os.path.abspath(models_dir)
    result = {}
    direct = []
    for root, _dirs, files in os.walk(models_dir):
        for name in sorted(files):
            if not name.endswith(".json"):
                continue
            path = os.path.join(root, name)
            stem = name[: -len(".json")]
            if stem not in ORDER_IDS:
                continue
            if root == models_dir:
                direct.append(path)
                continue
            lang = os.path.basename(root)
            result.setdefault(lang, {})[ORDER_IDS[stem]] = path
    for path in direct:
        sys.stderr.write("warning: skipping %s (not inside a language subdir)\n" % path)
    if not result:
        raise SystemExit(
            "error: no language model JSON files found under %s" % models_dir
        )
    return result


def parse_model_file(path):
    """Parse one Lingua model JSON into a sorted list of groups.

    Each group is (count, denominator, [tokens...]).  Raises ValueError if
    a token appears under two different (count, denominator) keys, which
    would make the packed form ambiguous.
    """
    with open(path, "r", encoding="utf-8") as fh:
        doc = json.load(fh)
    ngrams = doc.get("ngrams")
    if not isinstance(ngrams, dict):
        raise ValueError("%s: missing object field 'ngrams'" % path)
    groups = []
    seen = {}
    for key, value in ngrams.items():
        if not isinstance(value, str):
            raise ValueError("%s: group %r value is not a string" % (path, key))
        parts = key.split("/")
        if len(parts) != 2:
            raise ValueError(
                "%s: bad group key %r (expected count/denominator)" % (path, key)
            )
        count = int(parts[0])
        denominator = int(parts[1])
        tokens = value.split()
        for token in tokens:
            if token in seen:
                raise ValueError(
                    "%s: duplicate token %r under %r (already %r) - not lossless"
                    % (path, token, key, seen[token])
                )
            seen[token] = (count, denominator)
        if tokens:
            groups.append((count, denominator, tokens))
    groups.sort(key=lambda g: (g[0], g[1]))
    return groups


def build_order_blob(groups):
    """Serialize a parsed group list into the order-blob byte layout."""
    buf = io.BytesIO()
    write_varint(buf, len(groups))
    for count, denominator, tokens in groups:
        toks = sorted(tokens)
        write_varint(buf, count)
        write_varint(buf, denominator)
        write_varint(buf, len(toks))
        prev = b""
        for token in toks:
            tb = token.encode("utf-8")
            shared = 0
            limit = min(len(prev), len(tb))
            while shared < limit and prev[shared] == tb[shared]:
                shared += 1
            suffix = tb[shared:]
            if shared > MAX_LEN or len(suffix) > MAX_LEN:
                raise ValueError("token too long: %r" % token)
            buf.write(struct.pack("<HH", shared, len(suffix)))
            buf.write(suffix)
            prev = tb
    return buf.getvalue()


def build_lang_section(groups_by_order):
    """Serialize {order_id: groups} into the language-section byte layout."""
    blobs = {}
    for oid in sorted(groups_by_order):
        blobs[oid] = build_order_blob(groups_by_order[oid])
    buf = io.BytesIO()
    index_size = 1 + 5 * len(blobs)
    buf.write(bytes((len(blobs),)))
    offset = index_size
    for oid in sorted(blobs):
        buf.write(struct.pack("<BI", oid, offset))
        offset += len(blobs[oid])
    for oid in sorted(blobs):
        buf.write(blobs[oid])
    return buf.getvalue()


def build_pack(groups_by_lang):
    """Serialize {lang: {order_id: groups}} into the full .pack byte layout.

    Each language section is zlib-compressed as a unit (FLAG_COMPRESS), so
    the index stores its byte offset and compressed length; a loader can
    seek a language's section and inflate only that piece.
    """
    codes = sorted(groups_by_lang)
    sections = []
    for code in codes:
        raw = build_lang_section(groups_by_lang[code])
        if FLAGS & FLAG_COMPRESS:
            raw = zlib.compress(raw, 9)
        sections.append(raw)
    header_size = 4 + 1 + 1 + 2  # magic + version + flags + lang count
    index_entries = []
    entry_size = 0
    for code in codes:
        entry = (
            struct.pack("<B", len(code))
            + code.encode("ascii")
            + struct.pack("<QQ", 0, 0)
        )
        index_entries.append(entry)
        entry_size += len(entry)
    # Sections are written after the whole index; each section's offset is
    # the running byte position past header + index + prior sections.
    offset = header_size + entry_size
    for i, code in enumerate(codes):
        index_entries[i] = (
            struct.pack("<B", len(code))
            + code.encode("ascii")
            + struct.pack("<QQ", offset, len(sections[i]))
        )
        offset += len(sections[i])
    top = io.BytesIO()
    top.write(MAGIC)
    top.write(bytes((VERSION, FLAGS)))
    top.write(struct.pack("<H", len(codes)))
    for entry in index_entries:
        top.write(entry)
    for section in sections:
        top.write(section)
    return top.getvalue()


def read_order_blob(f):
    """Read an order blob from f (already positioned at its start)."""
    ngroups = read_varint(f)
    groups = []
    for _ in range(ngroups):
        count = read_varint(f)
        denominator = read_varint(f)
        ntokens = read_varint(f)
        tokens = []
        prev = b""
        for _ in range(ntokens):
            shared, slen = struct.unpack("<HH", f.read(4))
            suffix = f.read(slen)
            if len(suffix) != slen:
                raise EOFError("truncated token")
            tb = prev[:shared] + suffix
            tokens.append(tb.decode("utf-8"))
            prev = tb
        groups.append((count, denominator, tokens))
    return groups


def read_lang_section(f, base):
    """Read a language section from f anchored at absolute byte position base."""
    f.seek(base)
    norders = f.read(1)[0]
    entries = []
    for _ in range(norders):
        oid = f.read(1)[0]
        ooff = struct.unpack("<I", f.read(4))[0]
        entries.append((oid, ooff))
    orders = {}
    for oid, ooff in entries:
        f.seek(base + ooff)
        orders[oid] = read_order_blob(f)
    return orders


def unpack_pack(data):
    """Unpack .pack bytes into {lang: {order_id: groups}}."""
    f = io.BytesIO(data)
    magic = f.read(4)
    if magic != MAGIC:
        raise ValueError("bad magic %r (expected %r)" % (magic, MAGIC))
    version = f.read(1)[0]
    flags = f.read(1)[0]
    if version != VERSION:
        raise ValueError("unsupported version %d" % version)
    nlangs = struct.unpack("<H", f.read(2))[0]
    entries = []
    for _ in range(nlangs):
        code_len = f.read(1)[0]
        code = f.read(code_len).decode("ascii")
        sec_off, sec_len = struct.unpack("<QQ", f.read(16))
        if code in [e[0] for e in entries]:
            raise ValueError("duplicate language %r" % code)
        entries.append((code, sec_off, sec_len))
    # Parse sections after the whole index is read: read_lang_section seeks,
    # which would corrupt the sequential index reads above.
    sections = {}
    for code, sec_off, sec_len in entries:
        f.seek(sec_off)
        sec_bytes = f.read(sec_len)
        if len(sec_bytes) != sec_len:
            raise EOFError("truncated section for %r" % code)
        if flags & FLAG_COMPRESS:
            sec_bytes = zlib.decompress(sec_bytes)
        sec_f = io.BytesIO(sec_bytes)
        sections[code] = read_lang_section(sec_f, 0)
    return sections


def expand_map(groups):
    """Flatten groups into {token: count} (the lossless contract)."""
    return {token: count for count, _denom, tokens in groups for token in tokens}


def groups_key(groups):
    """Order-independent structural key: (count, denom, sorted tokens)."""
    return [(c, d, tuple(sorted(t))) for c, d, t in groups]


def collect_models(models_dir):
    """Scan + parse all models; returns (parsed, json_bytes).

    parsed is {lang: {order_id: groups}} shared between pack and selftest.
    """
    langs = scan_models(models_dir)
    parsed = {}
    json_bytes = 0
    for lang in sorted(langs):
        parsed[lang] = {}
        for oid in sorted(langs[lang]):
            path = langs[lang][oid]
            json_bytes += os.path.getsize(path)
            parsed[lang][oid] = parse_model_file(path)
    return parsed, json_bytes


def report_sizes(json_bytes, pack_bytes):
    mbj = json_bytes / (1024.0 * 1024.0)
    mbp = pack_bytes / (1024.0 * 1024.0)
    pct = 100.0 * (1.0 - pack_bytes / float(json_bytes))
    print("json total: %d bytes (%.2f MB)" % (json_bytes, mbj))
    print("pack total: %d bytes (%.2f MB)" % (pack_bytes, mbp))
    print("reduction: %.2f%%" % pct)


def run_selftest(models_dir):
    """Pack in memory, unpack, and assert losslessness against the originals."""
    parsed, json_bytes = collect_models(models_dir)
    pack_bytes = build_pack(parsed)
    unpacked = unpack_pack(pack_bytes)
    ok = True
    entry_total = 0
    print("selftest: %d languages, pack %d bytes vs json %d bytes"
          % (len(parsed), len(pack_bytes), json_bytes))
    for lang in sorted(parsed):
        lang_ok = True
        count_ngrams = 0
        for oid in sorted(parsed[lang]):
            orig = parsed[lang][oid]
            count_ngrams += sum(len(t) for _c, _d, t in orig)
            got = unpacked.get(lang, {}).get(oid)
            if got is None:
                lang_ok = False
                print("  FAIL %s %s: order missing" % (lang, ORDER_NAMES[oid]))
                continue
            if expand_map(orig) != expand_map(got):
                lang_ok = False
                print("  FAIL %s %s: ngram->count mismatch"
                      % (lang, ORDER_NAMES[oid]))
            elif groups_key(orig) != groups_key(got):
                lang_ok = False
                print("  FAIL %s %s: group (count,denom,tokens) mismatch"
                      % (lang, ORDER_NAMES[oid]))
        entry_total += count_ngrams
        status = "PASS" if lang_ok else "FAIL"
        ok = ok and lang_ok
        print("  %-3s %s  (%d ngrams)" % (lang, status, count_ngrams))
    report_sizes(json_bytes, len(pack_bytes))
    print("total ngrams packed: %d" % entry_total)
    print("SELFTEST: %s" % ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


def run_pack(models_dir, out_path):
    """Pack all models to out_path and print size comparison."""
    parsed, json_bytes = collect_models(models_dir)
    pack_bytes = build_pack(parsed)
    out_dir = os.path.dirname(os.path.abspath(out_path)) or "."
    if not os.path.isdir(out_dir):
        raise SystemExit("error: output directory does not exist: %s" % out_dir)
    tmp = "%s.tmp" % out_path
    with open(tmp, "wb") as fh:
        fh.write(pack_bytes)
    os.replace(tmp, out_path)
    print("wrote %s (%d bytes)" % (out_path, len(pack_bytes)))
    report_sizes(json_bytes, len(pack_bytes))
    return 0


def run_verify(out_path, models_dir):
    """Check an existing .pack file byte-matches a fresh pack of models_dir."""
    with open(out_path, "rb") as fh:
        existing = fh.read()
    parsed, json_bytes = collect_models(models_dir)
    fresh = build_pack(parsed)
    same = existing == fresh
    print("verify: %s vs pack of %s" % (out_path, models_dir))
    print("result: %s (%d bytes, %d fresh)" % (
        "IDENTICAL" if same else "DIFFERENT", len(existing), len(fresh)))
    if same:
        print("json total: %d bytes (-%.2f%% -> %d bytes)"
              % (json_bytes, 100.0 * (1.0 - len(existing) / float(json_bytes)),
                 len(existing)))
    return 0 if same else 1


def usage():
    print(__doc__)
    print("Binary format spec (version 2):")
    for line in SPEC_LINES:
        print("  " + line)


def main(argv):
    if len(argv) < 2:
        usage()
        return 2
    cmd = argv[1]
    if cmd == "pack" and len(argv) == 4:
        return run_pack(argv[2], argv[3])
    if cmd in ("selftest", "roundtrip") and len(argv) == 3:
        return run_selftest(argv[2])
    if cmd == "verify" and len(argv) == 4:
        return run_verify(argv[2], argv[3])
    usage()
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))