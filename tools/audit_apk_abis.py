#!/usr/bin/env python3
"""Fail release packaging when updater asset names disagree with native contents."""
import sys
import zipfile
from pathlib import Path

ASSETS = {
    "vvtts-arm64-v8a.apk": {"arm64-v8a"},
    "vvtts-armeabi-v7a.apk": {"armeabi-v7a"},
    "vvtts-universal.apk": {"arm64-v8a", "armeabi-v7a", "x86_64"},
    "vvtts-test-x86_64.apk": {"x86_64"},
}


def audit(path):
    """Reject APKs with duplicate, empty, missing, or unexpected native libraries for their asset name."""
    path = Path(path)
    expected = ASSETS[path.name]
    with zipfile.ZipFile(path) as apk:
        native_entries = [entry for entry in apk.infolist()
                          if entry.filename.startswith("lib/") and not entry.is_dir()]
        libraries = {entry.filename for entry in native_entries}
        if len(libraries) != len(native_entries):
            raise ValueError(f"{path.name}: duplicate native library ZIP entries")
        wanted = {f"lib/{abi}/libvvtts_core.so" for abi in expected}
        if libraries != wanted:
            raise ValueError(f"{path.name}: expected {sorted(wanted)}, found {sorted(libraries)}")
        if any(apk.getinfo(name).file_size == 0 for name in libraries):
            raise ValueError(f"{path.name}: empty native library")
    print(f"PASS {path.name}: {', '.join(sorted(expected))}")


if __name__ == "__main__":
    for argument in sys.argv[1:] or list(ASSETS):
        audit(argument)
