#!/usr/bin/env python3
"""Merge raw oracle dump streams into one per-hanzi consolidated table.

The export pipeline produces two stream families per sweep:
  conv: pinyins + genphon (SPR phoneme frames) + pcm(0)   [DUMP_NOSYNTH]
  pcm:  pcm sample count + phidx rows                     [full synth]

Rows arrive grouped under 'nl<TAB><hanzi>' markers (see make_table.py).
A hanzi may appear in multiple streams; the merged row takes the best
payload from each side:
  pcm      <- max seen (pcm-mode count wins over conv's 0)
  genphon  <- first non-empty (conv)
  phbuf    <- first non-empty
  phidx    <- first non-empty (pcm mode)
  pinyins  <- first non-empty (conv)

Usage: python3 merge_oracle.py <out-dir-with-*tsv> <out-table.tsv> [--encoding gb18030]
"""
import os
import sys
import glob


def decode_hanzi(raw: bytes, enc: str):
    for e in (enc, "utf-8", "gb18030"):
        try:
            return raw.decode(e)
        except (UnicodeDecodeError, LookupError):
            pass
    return raw.decode("utf-8", errors="replace")


def parse_stream(path, enc):
    """Yield (hanzi, {kind: [rows]}) per nl marker group.

    Stream layout per swept line is '[rows for char X] nl<TAB>X' — the nl
    marker CLOSES (and names) the group of rows that precede it. So when an
    nl marker arrives, the accumulated rows belong to THAT marker's hanzi.
    """
    cur_line = None
    cur = None
    with open(path, "rb") as fh:
        for raw in fh:
            line = raw.rstrip(b"\r\n")
            if line.startswith(b"nl\t"):
                hanzi = decode_hanzi(line[3:], enc)
                if cur is not None and cur:
                    yield hanzi, cur
                cur_line = hanzi
                cur = {}
                continue
            if cur is None:
                continue
            parts = line.split(b"\t")
            kind = parts[0].decode("ascii", errors="replace")
            if kind not in cur:
                cur[kind] = []
            cur[kind].append(line)
    if cur_line is not None:
        yield cur_line, cur


def hex_of(rec, key):
    for r in rec.get(key, []):
        return r.split(b"\t").pop().split(b"|")[0].decode("ascii", errors="replace")
    return ""


def phidx_of(rec):
    seq = []
    for r in rec.get("phidx", []):
        parts = r.split(b"\t")
        if len(parts) >= 2:
            seq.append(parts[1].decode("ascii", errors="replace"))
    return " ".join(seq)


def pinyins_of(rec):
    for r in rec.get("pinyins", []):
        parts = r.split(b"\t")
        if len(parts) >= 3:
            return parts[1].decode(), parts[2].split(b"|")[0].decode("ascii", errors="replace")
    return "", ""


def main():
    corpus = sys.argv[1] if len(sys.argv) > 1 else "oracle/corpus"
    out = sys.argv[2] if len(sys.argv) > 2 else "oracle/table/zh-cn-all.consolidated.tsv"
    enc = "gb18030"
    if "--encoding" in sys.argv:
        enc = sys.argv[sys.argv.index("--encoding") + 1]

    merged = {}
    n_streams = 0
    for path in sorted(glob.glob(os.path.join(corpus, "*.tsv"))):
        n_streams += 1
        for hanzi, rec in parse_stream(path, enc):
            m = merged.setdefault(hanzi, {})
            pcm = 0
            for r in rec.get("pcm", []):
                try:
                    v = int(r.split(b"\t")[1])
                    if v > pcm:
                        pcm = v
                except (IndexError, ValueError):
                    pass
            m["pcm"] = max(m.get("pcm", 0), pcm)
            for key in ("genphon", "phbuf"):
                v = hex_of(rec, key)
                if v and not m.get(key):
                    m[key] = v
            if not m.get("phidx"):
                m["phidx"] = phidx_of(rec)
            py, pyn = pinyins_of(rec)
            if py and not m.get("pinyins"):
                m["pinyins"] = f"{py}\t{pyn}"

    with open(out, "w", encoding="utf-8") as fh:
        for hanzi in sorted(merged):
            m = merged[hanzi]
            fh.write(f"{hanzi}\t{m.get('pcm', 0)}\t{m.get('phbuf', '')}\t{m.get('genphon', '')}\t{m.get('phidx', '')}\n")

    n_gph = sum(1 for m in merged.values() if m.get("genphon"))
    n_pcm = sum(1 for m in merged.values() if m.get("pcm", 0) > 0)
    n_pyx = sum(1 for m in merged.values() if m.get("pinyins"))
    print(f"streams={n_streams} unique_hanzi={len(merged)} genphon={n_gph} pcm>0={n_pcm} pinyins={n_pyx}")


if __name__ == "__main__":
    main()