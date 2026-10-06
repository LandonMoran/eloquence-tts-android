#!/usr/bin/env python3
"""Assemble an explicit set of fanout pieces; record every contribution."""
import argparse
import hashlib
import json
from pathlib import Path


def assemble(root, dialect, pieces, output):
    """Validate and concatenate requested waveform pieces, returning and writing their hash manifest."""
    if dialect not in ("zh-cn", "zh-tw", "ko", "ja"):
        raise ValueError("Unknown dialect")
    if len(set(pieces)) != len(pieces) or not pieces or any(p not in range(1, 9) for p in pieces):
        raise ValueError("Pieces must be unique integers from 1 to 8")
    if dialect == "ja" and pieces != [1]:
        raise ValueError("Japanese fanout has one unsplit piece")
    contributions = []
    contents = []
    for piece in sorted(pieces):
        matches = list(Path(root).glob(f"*/oracle-piece-{dialect}-p{piece}/output.tsv"))
        if len(matches) != 1:
            raise ValueError(f"Expected exactly one {dialect} piece {piece}; found {len(matches)}")
        data = matches[0].read_bytes()
        rows = data.decode("utf-8").splitlines()
        if not any(row.startswith("pcm\t") and len(row.split('\t')) > 1 and int(row.split('\t')[1]) > 0 for row in rows):
            raise ValueError(f"Piece {piece} contains no waveform output")
        contents.append(data if data.endswith(b'\n') else data + b'\n')
        contributions.append({"piece": piece, "source": str(matches[0].relative_to(root)),
                              "sha256": hashlib.sha256(data).hexdigest(), "rows": len(rows), "bytes": len(data)})
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(b''.join(contents))
    manifest = {"dialect": dialect, "pieces": contributions, "output_sha256": hashlib.sha256(output.read_bytes()).hexdigest()}
    output.with_suffix('.manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('root', type=Path)
    parser.add_argument('dialect')
    parser.add_argument('pieces', help='comma-separated piece numbers')
    parser.add_argument('output')
    args = parser.parse_args()
    assemble(args.root, args.dialect, [int(p) for p in args.pieces.split(',')], args.output)
