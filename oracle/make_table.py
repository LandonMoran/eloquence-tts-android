#!/usr/bin/env python3
"""Consolidate raw oracle dumps into per-row tables.

Each oracle-dump TSV is a stream of rows separated by 'nl<TAB><input line>'
markers (emitted by the workflow's per-line one-shot loop). This script
splits streams back into rows and writes:

  oracle/table/<name>.consolidated.tsv   - hanzi<TAB>pcm<TAB>phbuf_hex<TAB>genphon_hex
  oracle/table/<name>.rows.tsv           - human-readable per-row summary

Input payloads are in the probe's output encoding (GB18030/BIG5/UTF-8(,
not UTF-8, so the stream is split on byte markers and the hanzi column
decoded per --encoding. 

Usage: python3 make_table.py [corpus-dir] [table-dir] [--encoding gb18030]
"""
import os
import sys
import glob

CORPUS = sys.argv[1] if len(sys.argv) > 1 else "oracle/corpus"
TABLES = sys.argv[2] if len(sys.argv) > 2 else "oracle/table"
ENC = "gb18030"
if "--encoding" in sys.argv:
    ENC = sys.argv[sys.argv.index("--encoding") + 1]

os.makedirs(TABLES, exist_ok=True)


def decode_hanzi(raw: bytes):
    for e in (ENC, "utf-8", "gb18030"):
        try: return raw.decode(e)
        except (UnicodeDecodeError, LookupError):
            pass
    return raw.decode("utf-8", errors="replace")


def parse(stream_path):
    """Split a dump stream into rows via b'nl\\t' markers on the byte stream."""
    rows = []
    with open(stream_path, "rb") as fh:
        cur_line = None
        cur = None
        order = []
        for raw in fh:
            line = raw.rstrip(b"\r\n")
            if line.startswith(b"nl\t"):
                if cur is not None:
                    rows.append((cur_line, order, cur))
                cur_line = decode_hanzi(line[3:])
                cur = {}
                order = []
                continue
            if cur is None:
                continue
            parts = line.split(b"\t")
            kind = parts[0].decode("ascii", errors="replace")
            if kind not in cur:
                cur[kind] = []
                order.append(kind)
            cur[kind].append(line)
    if cur is not None:
        rows.append((cur_line, order, cur))
    return rows


def hex_of(row, part, key):
    """Extract hex payload for 'pcm'/'genphon'/'phbuf' records."""
    for r in row[2].get(key, []):
        return r.split(b"\t").pop().split(b"|")[0].decode("ascii", errors="replace")
    return ""


def main():
    summary = []
    for path in sorted(glob.glob(os.path.join(CORPUS, "*.tsv"))):
        name = os.path.basename(path).replace(".tsv", "")
        rows = parse(path)
        if not rows:
            continue
        out_path = os.path.join(TABLES, name + ".consolidated.tsv")
        rows_path = os.path.join(TABLES, name + ".rows.tsv")
        n_nonzero = 0
        n_badmask = 0
        total_pcm = 0
        with open(out_path, "w", encoding="utf-8") as out, open(rows_path, "w", encoding="utf-8") as rp:
            rp.write("input\tpcm\tphbuf_hex_len\tgenphon_hex_len\n")
            for line, order, rec in rows:
                pcm = 0
                for r in rec.get("pcm", []):
                    try:
                        pcm = int(r.split(b"\t")[1])
                    except (IndexError, ValueError):
                        pcm = 0
                ph = hex_of((line, order, rec), rec, "phbuf")
                gp = hex_of((line, order, rec), rec, "genphon")
                if gp and len(gp) != 512:
                    n_badmask += 1
                out.write(f"{line}\t{pcm}\t{ph}\t{gp}\n")
                rp.write(f"{line}\t{pcm}\t{len(ph) // 2}\t{len(gp) // 2}\n")
                total_pcm += pcm
                if pcm > 0:
                    n_nonzero += 1
        summary.append(
            f"{name}: rows={len(rows)} nonzero_pcm={n_nonzero} badmask={n_badmask} avg_samples={total_pcm // max(len(rows), 1)}"
        )
    print("\n".join(summary) if summary else "no tables produced")


if __name__ == "__main__":
    main()