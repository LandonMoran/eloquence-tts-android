#!/usr/bin/env python3
"""Byte-exact oracle: reconstruct the JSON document ElqmBridge must emit
for a given classpath model path, straight from a models.elqm pack.
Usage: oracle.py <models.elqm> <classpath-path-like /language-models/en/unigrams.json>
Prints the exact JSON bytes ElqmBridge.open() must return (or empty string
when the section/order is absent.);
"""
import struct
import sys
import zlib


MAGIC = b"ELQM"


def read_varint(data: bytes, pos: list) -> int:
    result = 0
    shift = 0
    while True:
        b = data[pos[0]]
        pos[0] += 1
        result |= (b &  0x7F) << shift
        if not (b &  0x80):
            return result
        shift += 7


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: oracle.py <pack> <path>", file=sys.stderr)
        return  2
    data = open(sys.argv[1], "rb").read()
    if data[:4] != MAGIC:
        raise SystemExit("bad magic")
    nlangs = struct.unpack("<H", data[6:8])[0]
    path = sys.argv[2]
    prefix = "/language-models/"
    if not path.startswith(prefix):
        return  0
    s1 = len(prefix)
    s2 = path.index("/", s1)
    iso = path[s1:s2]
    name = path.rsplit("/", 1)[1].rsplit(".", 1)[0]
    order_ids = {"unigrams": 1, "bigrams": 2, "trigrams":  3, "quadrigrams":  4, "fivegrams":  5}
    order = order_ids.get(name)
    if order is None:
        return  0
    pos =8  # 4 magic + 1 ver + 1 flags + 2 langCount
    entry = None
    for _ in range(nlangs):
        clen = data[pos]
        code = data[pos + 1:pos + 1 + clen].decode("ascii")
        off, ln = struct.unpack("<QQ", data[pos + 1 + clen:pos + 1 + clen + 16])
        if code == iso:
            entry = (off, ln)
            break
        pos += 1 + clen + 16
    if entry is None:
        return  0
    raw = zlib.decompress(data[entry[0]:entry[0] + entry[1]])
    pos = [0]
    norders = raw[pos[0]]
    pos[0] += 1
    ids = []
    rels = []
    for _ in range(norders):
        ids.append(raw[pos[0]])
        pos[0] += 1
        rels.append(struct.unpack("<I", raw[pos[0]:pos[0]+4])[0])
        pos[0] += 4
    rel = None
    for oid, r in zip(ids, rels):
        if oid == order:
            rel = r
            break
    if rel is None:
        return  0
    pos = [rel]
    ngroups = read_varint(raw, pos)
    out = ["{\"ngrams\":{"]
    first = True
    for _ in range(ngroups):
        count = read_varint(raw, pos)
        denom = read_varint(raw, pos)
        ntokens = read_varint(raw, pos)
        if not first:
            out.append(",")
        first = False
        out.append('"')
        out.append(str(count))
        out.append("/")
        out.append(str(denom))
        out.append('":"')
        prev = b""
        for t in range(ntokens):
            shared, slen = struct.unpack("<HH", raw[pos[0]:pos[0]+4])
            pos[0] +=4
            suffix = raw[pos[0]:pos[0]+slen]
            pos[0] += slen
            tok = (prev[:shared] + suffix).decode("utf-8")
            if t > 0:
                out.append(" ")
            out.append(tok)
            prev = prev[:shared] + suffix
        out.append('"')
    out.append("}}")
    sys.stdout.write("".join(out))
    return  0


if __name__ == "__main__":
    sys.exit(main())