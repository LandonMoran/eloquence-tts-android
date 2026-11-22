# Native decompilation of the game

`dump.cs` comes from the IL2CPP metadata: it knows every class, field offset and
method signature, but no method body. The bodies live in `libil2cpp.so`, so the
native half is decompiled here. Together they answer "what does the game
actually do when this is called", which is what the bridge needs to be written
against.

Everything below runs offline on files already extracted from the APK; no
device, no game process and no memory dump is involved.

## Inputs

| File | Where it comes from | This build |
| --- | --- | --- |
| `libil2cpp.so` | `lib/arm64-v8a/libil2cpp.so` in the APK | 104,770,672 B, sha256 `9ac8a939094dd149b1844f200a52047bd6fe95efa809ab277f1d0d3d1e0ec912` |
| `global-metadata.dat` | `assets/bin/Data/Managed/Metadata/global-metadata.dat` | 15,540,404 B, sha256 `d66a25b56e013fc072cc343d0b93e6dd34a24bfd2e3f9498ffae24fedd0f0f8d` |
| `dump.cs` | `Il2CppDumper` output (metadata half) | 1,157,766 lines, sha256 `dda843d783c59ab97ef5f3fc79e6af54302e0a61cf21a65fac4ae58729b8dc30` |
| `script.json`, `il2cpp.h` | also `Il2CppDumper` (old address → name map) | regenerated with the dump |

`RVA` numbers are per build. Re-extract them from the same `dump.cs` the bridge
is checked against (`tests/reference/members.json` records its digest).

## Regenerating the metadata half

```
Il2CppDumper libil2cpp.so global-metadata.dat out/
python3 tools/extract_reference.py out/dump.cs        # tests/reference/members.json
python3 tools/decompile_targets.py out/dump.cs > targets.tsv
```

`decompile_targets.py` emits `rva<TAB>Class$$Method<TAB>declaration` for the
classes the bridge resolves plus the shop, board, battle and view types it walks
through (1,444 methods on this build), in the order the dump lists them.

## Decompiling the native half

```
mkdir -p proj out
JAVA_HOME=/path/to/jdk21 GHIDRA_HEADLESS_MAXMEM=10G \
  ghidra/support/analyzeHeadless proj sap \
    -import libil2cpp.so \
    -scriptPath tools/ghidra \
    -postScript SapDecompile.java targets.tsv out \
    -max-cpu $(nproc) -analysisTimeoutPerFile 5400
```

`SapDecompile.java` disassembles each RVA, creates the function, names it
`Class$$Method` and writes `out/Class$$Method.c` plus `out/index.tsv`
(`rva, name, file-or-status, declaration`). The full auto-analysis before the
script is what makes call sites resolve to real functions; with `-noanalysis`
the decompiler turns cross-function calls into branches and the output is close
to unreadable.

Throughput on 8 cores: analysis of the 104 MB library dominates (tens of
minutes); afterwards each method decompiles in about a second.

## What the output is used for

* Confirming that a handler really takes `(container, space)`, which is how the
  shop actions pass them (`HangarMain::HandleMinionShopClick` and friends).
* Confirming which fields a getter reads, for the numbers the tree speaks
  (`ItemModel::get_Price`, `MinionModel::SellValue`).
* Checking a name that `dump.cs` lists but the bridge never resolved at runtime
  (a method that moved to a base class, an explicit interface implementation
  with a mangled name).

It is a static view: it cannot prove what the game does at runtime, and it says
nothing about object identity or which overload the CLR would pick. Those remain
device checks (see `docs/TESTING.md` in the documentation repository).
