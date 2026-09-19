#!/usr/bin/env python3
"""Consolidate raw oracle dumps into per-row tables.

Each oracle-dump TSV is a stream of rows separated by 'nl<TAB><input line>'
markers (emitted by the workflow's per-line one-shot loop). This script
splits streams back into rows and writes:

  oracle/table/<name>.consolidated.tsv   - hanzi<TAB>pcm<TAB>phbuf_hex<TAB>genphon_hex
  oracle/table/<name>.rows.tsv           - human-readable per-row summary

Usage: python3 make_table.py [corpus-dir] [table-dir]
"""
import os
import sys
import glob

CORPUS = sys.argv[1] if len(sys.argv) > 1 else "oracle/corpus"
TABLES = sys.argv[2] if len(sys.argv) > 2 else "oracle/table"

os.makedirs(TABLES, exist_ok=True)


def parse(stream_path):
    """Split a dump stream into rows via 'nl' markers."""
    rows = []  # list of (input_line, {kind: value/hex})
    cur_line = None
    cur = None
    order = []
    with open(stream_path, encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            if line.startswith("nl\t"):
                if cur is not None:
                    rows.append((cur_line, order, cur))
                cur_line = line[3:]
                cur = {}
                order = []
                continue
            if cur is None:
                continue
            parts = line.split("\t")
            kind = parts[0]
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
        return r.split("\t").pop().split("|")[0]  # hex before the ASCII '|'
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
        total_pcm = 0
        with open(out_path, "w", encoding="utf-8") as out, open(rows_path, "w", encoding="utf-8") as rp:
            rp.write("input\tpcm\tphbuf_hex_len\tgenphon_hex_len\n")
            for line, order, rec in rows:
                pcm = 0
                for r in rec.get("pcm", []):
                    try:
                        pcm = int(r.split("\t")[1])
                    except (IndexError, ValueError):
                        pcm = 0
                ph = hex_of((line, order, rec), rec, "phbuf")
                gp = hex_of((line, order, rec), rec, "genphon")
                out.write(f"{line}\t{pcm}\t{ph}\t{gp}\n")
                rp.write(f"{line}\t{pcm}\t{len(ph) // 2}\t{len(gp) // 2}\n")
                total_pcm += pcm
                if pcm > 0:
                    n_nonzero += 1
        summary.append(
            f"{name}: rows={len(rows)} nonzero_pcm={n_nonzero} avg_samples={total_pcm // max(len(rows), 1)}"
        )
    print("\n".join(summary) if summary else "no tables produced")


if __name__ == "__main__":
    main()