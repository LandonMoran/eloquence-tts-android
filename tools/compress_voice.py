#!/usr/bin/env python3
"""tools/compress_voice.py -- zlib-compress the Phase-4 'table C' oracle voice
tables into a self-describing C source (VCT1 container).

WHAT IT READS
-------------
Primary source: the C arrays emitted by oracle/fitter.py into
native/openevv/lang/chs/oracle_chs.c.  fitter.py emits the three byte blobs
("table C" = the payload byte arrays) from emit_offblob() (fitter.py:31-43;
the "static const uint8_t <name>[] = {" line is 38, bytes via emit_lines at
26-28, 12 hex tokens per line) called at fitter.py:119 (chs_oracle_phbuf_data),
120 (chs_oracle_genphon_data), 121 (chs_oracle_phidx_data).  In the emitted
oracle_chs.c the arrays sit at: phbuf line 23, genphon line 30, phidx line
263949.  genphon_data is the actual voiced frame payload (~3.0 MB here);
phidx_data the per-row phoneme-index names (13,862 B); phbuf_data is empty in
the current tables (0 B).

Fallback (used only if oracle_chs.c does not exist): regenerate the same three
byte strings from oracle/table/*.consolidated.tsv exactly the way fitter.py
does (fitter.py:48-108): parse rows, drop holes (empty phbuf+genphon and
pcm==0), sort by codepoint, dedup keeping the last row, then per column:
phbuf = concat(phb.hex()), genphon = concat(gph.hex()), phidx = concat(
(pxs.encode('ascii', errors='replace') + b'\\0').hex()).

VCT1 CONTAINER LAYOUT (byte-exact; all multi-byte ints LITTLE-ENDIAN)
---------------------------------------------------------------------
  [0..3]   magic "VCT1"                 (bytes 0x56 0x43 0x54 0x31)
  [4..7]   u32 count                    number of tables
  [8..]    count records, consecutive, each record:
             u32 table_id               stable id (emission order)
             u8  name_len               name length, no NUL
             name_len bytes of name     ASCII C symbol name
             u32 orig_len               decompressed byte count
             u32 comp_len               zlib stream byte count
             u32 blob_offset            ABSOLUTE offset from byte 0 of the
                                        container to this table's stream
  then:     count zlib streams, concatenated back to back; stream i is
            comp_len[i] bytes at blob_offset[i], a full RFC 1950 zlib stream
            (deflate + zlib header/trailer -- open with zlib inflateInit,
            not inflateInit2 raw) that decompresses to exactly orig_len[i]
            bytes.
The emitted C file carries this container verbatim as voice_comp_blob[] plus
a small manifest struct array (voice_comp_tables[]) that mirrors the records
(name NUL-padded) so the C loader can index tables directly.

DETERMINISM
-----------
Table order is fixed (table_id 0..2 in emission order), zlib.compress(level=9)
is deterministic for identical input on the same zlib build, and the emitter
writes no timestamps or paths -- same input -> byte-identical output.

USAGE
-----
  python3 tools/compress_voice.py                 # build + verify, exit 0/1
  python3 tools/compress_voice.py --verify        # verify an existing file only
  python3 tools/compress_voice.py --oracle-c PATH --out PATH

Always exits 1 on any decompress/byte mismatch (or hard error); the parent
loader can rely on exit 0 meaning "emitted file round-trips byte-exact".
"""

import argparse
import glob
import os
import re
import struct
import sys
import zlib

MAGIC = b"VCT1"
ZLEVEL = 9

# Stable table ids (emission order as fitter.py calls emit_offblob).
TABLES = [
    (0, "chs_oracle_phbuf_data"),
    (1, "chs_oracle_genphon_data"),
    (2, "chs_oracle_phidx_data"),
]

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_ORACLE_C = os.path.join(REPO, "native", "openevv", "lang", "chs", "oracle_chs.c")
DEFAULT_OUT = os.path.join(REPO, "tools", "oracle_chs_comp.c")
DEFAULT_TABLES = os.path.join(REPO, "oracle", "table")

# Emitted array shape from fitter.py emit_offblob / emit_lines:
#   static const uint8_t <name>[] = {
#       0x00, 0x01, ... (12 tokens per line)
#   };
ARRAY_RE = re.compile(r"static const uint8_t (\w+)\[\] = \{(.*?)\n\};", re.S)
TOKEN_RE = re.compile(r"0x([0-9a-fA-F]{2})")


def fail(msg):
    print("compress_voice: error: %s" % msg, file=sys.stderr)
    sys.exit(1)


def parse_c_array(body):
    """Decode '0xNN, 0xNN, ...' tokens to bytes."""
    return bytes(int(t, 16) for t in TOKEN_RE.findall(body))


def extract_from_oracle_c(path):
    """Read the three emitted byte arrays out of oracle_chs.c.  Returns
    {name: bytes} plus a dict name -> source line of each array."""
    with open(path, "r", encoding="ascii") as fh:
        src = fh.read()
    blobs, lines = {}, {}
    for m in ARRAY_RE.finditer(src):
        name = m.group(1)
        if name in blobs:  # first definition wins (there is only one each)
            continue
        blobs[name] = parse_c_array(m.group(2))
        lines[name] = src.count("\n", 0, m.start()) + 1
    want = [n for _, n in TABLES]
    missing = [n for n in want if n not in blobs]
    if missing:
        fail("oracle C %s lacks arrays: %s" % (path, ", ".join(missing)))
    return blobs, lines


def extract_from_tsv(tables_dir):
    """Fallback: rebuild the exact same byte strings from the consolidated
    TSVs, mirroring fitter.py:48-108 (row parse, hole drop, sort, dedup)."""
    rows = []
    for path in sorted(glob.glob(os.path.join(tables_dir, "*.consolidated.tsv"))):
        with open(path, encoding="utf-8") as fh:
            for ln in fh:
                ln = ln.rstrip("\n")
                if not ln or "\t" not in ln:
                    continue
                f = ln.split("\t")
                if len(f) < 4:
                    continue
                hanzi, pcm_s, phb, gph, pxs = (f + [""] * 5)[:5]
                try:
                    pcm = int(pcm_s or "0")
                except ValueError:
                    pcm = 0
                phb_b = bytes.fromhex(phb) if phb else b""
                gph_b = bytes.fromhex(gph) if gph else b""
                if not phb_b and not gph_b and pcm == 0:
                    continue  # hole: nothing to play (fitter.py:64-65)
                rows.append([ord(hanzi.split("\t", 1)[0]), pcm, phb_b, gph_b, pxs])
    if not rows:
        fail("no rows in %s" % tables_dir)
    rows.sort(key=lambda r: r[0])
    dedup = {}
    for r in rows:
        dedup[r[0]] = r
    rows = sorted(dedup.values(), key=lambda r: r[0])

    # Per-column assembly matches fitter.py:102-108: concat each row's raw
    # payload bytes in sorted/codepoint order; phidx rows get an ascii
    # encode + trailing NUL exactly like pxb = pxs.encode(...) + b"\0".
    phd = b"".join(r[2] for r in rows)
    gpd = b"".join(r[3] for r in rows)
    pxd = b"".join(r[4].encode("ascii", errors="replace") + b"\0" for r in rows)
    return {TABLES[0][1]: phd, TABLES[1][1]: gpd, TABLES[2][1]: pxd}, {}


def get_originals(oracle_c, tables_dir):
    if os.path.isfile(oracle_c):
        return extract_from_oracle_c(oracle_c)
    print("compress_voice: note: %s missing; rebuilding tables from %s"
          % (oracle_c, tables_dir))
    return extract_from_tsv(tables_dir)


def build_container(originals):
    """Return (blob_bytes, records) where records = list of
    (table_id, name, orig_len, comp_len, blob_offset)."""
    comps = [zlib.compress(originals[name], ZLEVEL) for _, name in TABLES]
    recs_packed = []
    for (tid, name), comp in zip(TABLES, comps):
        recs_packed.append((tid, name, len(originals[name]), comp))

    blob = bytearray()
    blob += MAGIC
    blob += struct.pack("<I", len(recs_packed))
    rec_starts = []
    for tid, name, orig_len, comp in recs_packed:
        rec_starts.append(len(blob))
        nb = name.encode("ascii")
        blob += struct.pack("<IB", tid, len(nb))
        blob += nb
        blob += struct.pack("<III", orig_len, len(comp), 0)  # offset patched below

    off = len(blob)
    records = []
    for (tid, name, orig_len, comp), rec_start in zip(recs_packed, rec_starts):
        patch_at = rec_start + 4 + 1 + len(name) + 8  # tid u32 + nlen u8 + name + orig u32 + comp u32 -> offset field
        struct.pack_into("<I", blob, patch_at, off)
        blob += comp
        records.append((tid, name, orig_len, len(comp), off))
        off += len(comp)
    return bytes(blob), records


def fmt_bytes(n):
    if n >= 1024 * 1024:
        return "%.2f MB" % (n / (1024.0 * 1024.0))
    if n >= 1024:
        return "%.1f KB" % (n / 1024.0)
    return "%d B" % n


def c_byte_lines(data, indent="    ", per_line=12):
    lines = []
    for i in range(0, len(data), per_line):
        lines.append(indent + ", ".join("0x%02x" % c for c in data[i:i + per_line]) + ",")
    return lines


def emit_c(out_path, originals, lines_info, oracle_c_basename):
    blob, records = build_container(originals)
    max_name = max(len(n) for _, n in TABLES)
    nlen = max(max_name + 2, 24)

    src_site = []
    if lines_info:
        src_site = ["   arrays read from %s (lines: %s)"
                    % (oracle_c_basename,
                       "; ".join("%s @ %d" % (n, lines_info[n]) for _, n in TABLES))]
    else:
        src_site = ["   arrays rebuilt from oracle/table/*.consolidated.tsv"
                    " (fitter.py-equivalent row pipeline)"]

    out = []
    out.append("/* Generated by tools/compress_voice.py. Do not edit. */")
    out.append("/*")
    out.append(" * VCT1 container: zlib(level=%d)-compressed Phase-4 oracle voice tables." % ZLEVEL)
    out.append(" * Source of truth: oracle/fitter.py emit_offblob (fitter.py:31-43, byte")
    out.append(" * arrays emitted at :38 via emit_lines :26-28; call sites :119 phbuf,")
    out.append(" * :120 genphon, :121 phidx).")
    out.extend(src_site)
    out.append(" *")
    out.append(" * Byte-exact layout (all multi-byte ints LITTLE-ENDIAN):")
    out.append(" *   [0..3]   magic \"VCT1\"              (0x56 0x43 0x54 0x31)")
    out.append(" *   [4..7]   u32 count")
    out.append(" *   [8..]    count records, each:")
    out.append(" *            u32 table_id")
    out.append(" *            u8  name_len")
    out.append(" *            name_len bytes of name     (ASCII, no NUL)")
    out.append(" *            u32 orig_len               (decompressed size)")
    out.append(" *            u32 comp_len               (zlib stream size)")
    out.append(" *            u32 blob_offset            (absolute offset from byte 0)")
    out.append(" *   then:    count zlib streams, back to back; stream i = comp_len[i]")
    out.append(" *            bytes at blob_offset[i], RFC 1950 zlib (open with")
    out.append(" *            inflateInit, NOT raw), decompresses to orig_len[i] bytes.")
    out.append(" *")
    out.append(" * voice_comp_blob[] below IS that container verbatim.  The manifest")
    out.append(" * voice_comp_tables[] mirrors the records (name NUL-padded to")
    out.append(" * VOICE_COMP_NLEN) so the loader can index tables without walking")
    out.append(" * the packed records.  Both are guaranteed consistent: --verify")
    out.append(" * round-trips every stream through zlib.decompress and byte-compares")
    out.append(" * against the original tables.")
    out.append(" */")
    out.append("#include <stdint.h>")
    out.append("#include <stddef.h>")
    out.append("")
    out.append("#define VOICE_COMP_MAGIC 0x31544356u   /* 'V''C''T''1' as LE u32 */")
    out.append("#define VOICE_COMP_COUNT %du" % len(records))
    out.append("#define VOICE_COMP_NLEN  %du" % nlen)
    out.append("")
    out.append("typedef struct {")
    out.append("    uint32_t table_id;    /* 0=phbuf 1=genphon 2=phidx (emission order) */")
    out.append("    uint8_t  name_len;    /* on-disk name length (no NUL) */")
    out.append("    char     name[%d];    /* C symbol name, NUL-padded */" % nlen)
    out.append("    uint32_t orig_len;    /* decompressed byte count */")
    out.append("    uint32_t comp_len;    /* zlib stream byte count */")
    out.append("    uint32_t blob_offset; /* absolute offset in voice_comp_blob[] */")
    out.append("} voice_comp_table;")
    out.append("")
    out.append("static const voice_comp_table voice_comp_tables[VOICE_COMP_COUNT] = {")
    for tid, name, orig_len, comp_len, off in records:
        out.append("    { %d, %d, \"%s\", %d, %d, %d }," % (tid, len(name), name, orig_len, comp_len, off))
    out.append("};")
    out.append("")
    out.append("static const uint8_t voice_comp_blob[] = {")
    out.extend(c_byte_lines(blob))
    out.append("};")
    out.append("")
    body = "\n".join(out) + "\n"

    tmp = out_path + ".tmp"
    with open(tmp, "w", encoding="ascii") as fh:
        fh.write(body)
    os.replace(tmp, out_path)
    print("compress_voice: wrote %s (%s total, %d tables)"
          % (out_path, fmt_bytes(len(body)), len(records)))
    return blob, records


def walk_container(blob):
    """Parse a VCT1 blob back into records + streams; raises ValueError on
    any structural problem."""
    if blob[:4] != MAGIC:
        raise ValueError("bad magic %r" % blob[:4])
    (count,) = struct.unpack_from("<I", blob, 4)
    off = 8
    recs = []
    for _ in range(count):
        (tid,) = struct.unpack_from("<I", blob, off)
        (nlen,) = struct.unpack_from("<B", blob, off + 4)
        name = blob[off + 5:off + 5 + nlen].decode("ascii")
        orig_len, comp_len, boff = struct.unpack_from("<III", blob, off + 5 + nlen)
        if boff + comp_len > len(blob):
            raise ValueError("stream %d (%s) out of range" % (tid, name))
        recs.append({"table_id": tid, "name": name, "orig_len": orig_len,
                     "comp_len": comp_len, "blob_offset": boff})
        off += 5 + nlen + 12
    streams = {}
    for r in recs:
        streams[r["name"]] = blob[r["blob_offset"]:r["blob_offset"] + r["comp_len"]]
    return recs, streams


def report(records, originals, streams=None):
    """Per-table orig->comp table + total savings %.  streams (dict name ->
    compressed bytes) optional; when absent, comp sizes come from records."""
    print("")
    print("%-4s %-24s %12s -> %12s  %7s  %8s" % ("id", "table", "orig", "comp", "ratio", "saved"))
    print("-" * 78)
    tot_o = tot_c = 0
    for tid, name, orig_len, comp_len, off in records:
        tot_o += orig_len
        tot_c += comp_len
        ratio = (comp_len / orig_len) if orig_len else 0.0
        saved = (1.0 - ratio) * 100.0 if orig_len else 0.0
        rat_s = "%.3f" % ratio if orig_len else "  -"
        sav_s = "%.1f%%" % saved if orig_len else "  -"
        print("%-4d %-24s %12s -> %12s  %7s  %8s" % (tid, name, fmt_bytes(orig_len), fmt_bytes(comp_len), rat_s, sav_s))
    pct = (1.0 - tot_c / tot_o) * 100.0 if tot_o else 0.0
    print("-" * 78)
    print("total: %12s -> %12s   saved %.2f%%  (zlib level %d)" % (fmt_bytes(tot_o), fmt_bytes(tot_c), pct, ZLEVEL))
    return tot_o, tot_c


def verify(emitted_path, originals):
    with open(emitted_path, "r", encoding="ascii") as fh:
        src = fh.read()
    m = re.search(r"static const uint8_t voice_comp_blob\[\] = \{(.*?)\n\};", src, re.S)
    if not m:
        fail("voice_comp_blob[] not found in %s" % emitted_path)
    blob = parse_c_array(m.group(1))

    recs, streams = walk_container(blob)
    if len(recs) != len(originals):
        fail("record count %d != original table count %d" % (len(recs), len(originals)))

    mismatches = []
    rows = []
    for r in recs:
        name = r["name"]
        if name not in originals:
            mismatches.append("%s: unknown table in container" % name)
            continue
        try:
            dec = zlib.decompress(streams[name])
        except zlib.error as e:
            mismatches.append("%s: zlib.decompress failed: %s" % (name, e))
            continue
        if len(dec) != r["orig_len"]:
            mismatches.append("%s: orig_len %d != decompressed %d"
                              % (name, r["orig_len"], len(dec)))
        if dec != originals[name]:
            mismatches.append("%s: byte mismatch (decompressed %d vs original %d)"
                              % (name, len(dec), len(originals[name])))
        rows.append((r["table_id"], name, r["orig_len"], r["comp_len"], r["blob_offset"]))

    rows.sort(key=lambda x: x[0])
    tot_o, tot_c = report(rows, originals)

    print("")
    if mismatches:
        for mm in mismatches:
            print("VERIFY MISMATCH: %s" % mm)
        print("compress_voice: verify FAILED (%d mismatches)" % len(mismatches))
        return 1
    print("compress_voice: verify OK - %d tables, all streams round-trip byte-exact" % len(recs))
    return 0


def main(argv):
    ap = argparse.ArgumentParser(description="Compress Phase-4 oracle voice tables into a VCT1 C container.")
    ap.add_argument("--oracle-c", default=DEFAULT_ORACLE_C, help="emitted oracle C (default: %(default)s)")
    ap.add_argument("--out", default=DEFAULT_OUT, help="output C (default: %(default)s)")
    ap.add_argument("--tables-dir", default=DEFAULT_TABLES, help="consolidated TSV dir fallback (default: %(default)s)")
    ap.add_argument("--verify", action="store_true", help="verify an existing emitted file without rebuilding")
    args = ap.parse_args(argv)

    originals, lines_info = get_originals(args.oracle_c, args.tables_dir)
    want = [n for _, n in TABLES]
    missing = [n for n in want if n not in originals]
    if missing:
        fail("missing source tables: %s" % ", ".join(missing))
    # fixed, deterministic order
    ordered = {n: originals[n] for n in want}

    if args.verify:
        if not os.path.isfile(args.out):
            fail("--verify: %s does not exist (build it first)" % args.out)
        return verify(args.out, ordered)

    emit_c(args.out, ordered, lines_info, os.path.basename(args.oracle_c))
    return verify(args.out, ordered)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))