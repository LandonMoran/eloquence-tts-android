# LPTA payload & the clean-room remake roadmap (chs / cht / kor / jpn)

State after the 2026-09-2x push. Cutting through what has been established
and what the remake still needs.

## What the reference binaries are

| file | role |
|---|---|
| `reference/apple-eloquence-tvos18.2/{chs,cht,kor}.dylib` | Apple tvOS 18.2 speech modules, Mach-O fat (x86_64 + arm64). x86_64 slice VAs == file offsets. |
| Apple-Eloquence-ELF 1.2.3 (Mudb0y) | Mach-O → ELF conversions of the same dylibs, `linux-aarch64` (+x86_64). Ghidra imports the aarch64 ones with image base **0x100000** — `DAT_0019xxxx` ⇒ `.m2e_data + (0x0009xxxx − 0x98000)`. |
| `reference/vvtts-6.1-chs/` | i386 Windows ELoquence 6.1: `eci.dll` (engine), `chsrom.dll` (rom), `chs.syn`, `eci.ini` (31KB settings **text**), `t2c.txt` (variant-char table). |
| `reference/eloquence-win/` | 6.2-era Windows installer leftovers. |
| `upstream-openevv/lang/` | The openevv tree. **`cht` there = itit chassis copy (glob.dr identical), `chs` = stub with `delta_rules_none`, `kor` = absent.** No real CJK lift exists anywhere upstream or on this box. |

## Facts that make the remake possible (all verified this session)

1. `apply_chi_*_rules` (14 of them in chs/cht) are **compiled rule statements**, not a bytecode interpreter: prologue = `ventproc`/`vretproc`/`get_parm`/`push_ptr_init` (activation record + setjmp landing), body = chains of calls to runtime primitives with literal table operands.
2. The runtime primitives have the **same names and signatures as openevv's own `delta.c`**: `test_string_s(d, st, n, str)`, `insert_2pt_s(d, f, n, str, …)`, `if_testeq_v_lng(d, loc, x)`, `insert_2pt`, `test_string`… So Apple's engine and openevv descend from the same IBM LPTA code; the Apple rule content transcribes 1:1 into openevv rules.
3. Rule table operands live in `.m2e_data` (per-lang offsets in `lpta/lpta_manifest.json`); refs from the decompiled chains resolve to **byte-string phone sequences** (e.g. chs `.m2e_data+0xedf` = `+!*"5'& -$)(#,` — the phone alphabet for pinyin). The `ffff`-delimited u16 maps (chs tail 0xbd160) are char→phone maps.
4. kor has **no `apply_kor_*_rules`** — text rules live in its `.m2e_data` (0x24f00, ~151KB: jamo/johab/dict tables). kor needs a table-port, not a chain transcription.
5. `lpta-ghidra.yml` (oracle/ghidra/DecompileLpta.java) already dispatches on GH runners (aarch64 libs); artifacts `ghidra-decomp-{chs,cht,kor}.so` downloaded into `lpta/decomp/`.
6. chs vs cht: `apply_chi_*` op chains are **identical** and `.m2e_data` is byte-identical (0 diff); the language difference lives in inline tables inside `.m2e_text` (687K/732K differ — real dict/test-table data there) + 1 byte in `.m2e_cstring`. A single transcribed rule engine + per-lang table data serves both.
7. fin.so contains the same `ffff`-delimited u16 table structure (pattern match verified) — the extraction techniques generalize to every language in the release.

## Payloads committed under oracle/ghidra/

- `lpta/{chs,cht,kor,jpn,fin}.m2e_data.bin` — the LPTA rule-table/phone pools
- `lpta/decomp/ghidra-decomp-{chs,cht,kor}.so.txt` — decompiled rule chains
- `lpta/lpta_manifest.json` — per-lang .m2e_data offsets/sizes + full section maps
- `rom_tables.json` + `parse_blocks.py` — chs/cht/kor rom tables (initial/final/punct/special/digits/tones/johab maps)
- `fetch_lpta_data.py`, `extract_lpta.py`, `extract_m2edata.py` — extraction tools

## Roadmap

1. **chs/cht rules (in progress)**: transcription engine — parse each
   `apply_chi_*_rules` decomp chain (op + operands) + resolve DAT refs →
   strings → emit notation `.dr` → `make rulecode` → delta_rules_c*.c.
   Iterate a diff-verifier: run the evv engine on sample pinyin for chs and
   byte-compare vs probe audio.
2. **kor tables**: parse the 0x24f00 bin (jamo/johab/syllable tables + dict) →
   delta_globals/statements/sets; kor rules are table-driven so this is
   mostly a structured port + the char classes.
3. **settings/statements/etc. per lang**: derive from `eci.ini`, `chs.syn`
   phone inventory, and the decompiled kernel (`.m2e_text` strings).
4. **Build & validate on GH runners** (`lpta-ghidra.yml` / `oracle-fanout.yml`):
   `make per-lang`, tables-check, probe — validator asserts pcm content
   (audio), not exit codes.
5. Wire `kor/chs/cht` into `build_native.sh` `LANGS`, drop Apple binaries.

## Pitfalls

- Ghidra image base 0x100000 for the aarch64 ELFs — resolve `DAT_0019xxxx`
  via `.m2e_data` + 0x9810 (see fetch_lpta_data.py docstring).
- x86_64 slice VAs == file offsets; arm64 slice section offsets carry
  virtualisation bits — read the x86_64 slice (or the ELF conversion) for
  any direct byte work.
- `llvm-readelf -S` tokenizes `[ 9]` as `[` + `9]` — match section names by
  membership, not fixed columns.
- `upstream-openevv/lang/cht` etc. are *untracked* chassis copies — never
  treat them as genuine lifts.