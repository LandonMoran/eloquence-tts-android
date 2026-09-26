#!/usr/bin/env python3
import os
import subprocess
import sys

out = os.environ["GAPS_OUT"]
want = os.environ.get("HANZI_FILTER", "")
chars = []
for c in want:
    c = c.strip()
    if c == "":
        continue
    chars.append(c.encode("gb18030"))

got = []
out_mode = "ab" if os.environ.get("GAPS_APPEND") == "1" else "wb"
with open(out, out_mode) as fh:
    for c in chars:
        ok = False
        for attempt in range(3):
            if ok:
                break
            p = subprocess.run(
                ["/tmp/dump_engine", "0x60000"],
                input=c + b"\n",
                capture_output=True,
                timeout=120,
            )
            rows = p.stdout.splitlines()
            first = rows[0][:24].hex() if rows else b""
            err = p.stderr[:24].hex()
            print(
                "  try %d: rc=%d out=%d err=%d first=%s err_first=%s"
                % (attempt + 1, p.returncode, len(rows), len(p.stderr), first, err))
            if rows:
                fh.write(b"nl\t" + c + b"\n")
                for r in rows:
                    fh.write(r + b"\n")
                got.append(c)
                ok = True
                break
        if not ok:

            print("NO stdout for %r after 3 tries" % c)

ok_count = len(got)
total_count = len(chars)
print("captured", ok_count, "of", total_count)
if ok_count != total_count:
    sys.exit(1)
sys.exit(0)