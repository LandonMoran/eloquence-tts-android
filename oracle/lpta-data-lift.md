# chs synth-data lift — status and unblock

As of 2026-09-21, the chs clean-room C path (libevv-enus-chs.a( synthesizes **zero** phonemes/PCM for ANY input. Every piece but one stage is present and verified;thiss file says where the gap is and what it takes to close it.

**UPDATE (2026-09-21 eve): route FOUND — sweep landed.** The "wall" below was superseded by the CI route: `oracle-fanout` ( zh-cn pieces 1-8, ubuntu-24.04-arm, public repo = free( → gather → **oracle/corpus/zh-cn-sweep.tsv** + **oracle/table/zh-cn.consolidated.tsv** ( hanzi<TAB>pcm<TAB>phbuf<TAB>genphon( — 18,037 rows,  ️3,718 nonzero-audio hanzi ( pieces 1-2,5-8; p3-p4 landed later(. Remaining:the **fitter** — consolidated rows → lang/chs runtime table the apply-chain engine consumes ( see cjk-module-pipeline.md's"fitter contract"( — then integration（0x60000 whitelist, charset, rom via evvRunStaticInitialisers(.

## What works (verified die same session(

- **Language module bound**: 0x60000 selects lang/chs (delta_languages[] has enus+chs;no crash,eciNewEx OK(.
- **Romanizer works**:  chs_register() → family-6 rom → "中文" → pinyin-ish ASCII ("ou6w","eng2en7zhie6s"( non-empty. NOTE: in static links the chsrom.o archive member is only pulled in when SOMETHING refs its exported `chs_register`;for the app,wire it into `evvRunStaticInitialisers` (port_ctors.c( per eloquence-engine-integration.(
- **Rule set**:  16/16 apply_chi_*_rules landed as `.dr`;bytecode rebuilt into the archive;rule_count=16, entry/native pointers bound (langdump after eciNewEx(.
- **Statement table**:  delta_link_chs.c (~53K( bound (state=216... hmm state_bytes also unchanged by before/after the link — see deltas below(.
- **INI**:  [6.0] section + Voice1-8 records present (359-byte blob(.

## The blocker (verified(

**The five IBM-COFF lifters** (tools/module/{globals,settings,link,sets}.py + tools/rules/consts.py( need `analysis/<tag>/*.obj`(**COFF objects with COFF symbol tables**( — e.g. `analysis/chs/glob.obj` for `--disassemble-symbols=_delta_new`. chs's consts/sets/globals are tiny stubs precisely because these objects don't exist:

| file | chs now | enus (same tree( |
|---|---|---|
| delta_consts_chs.c | ~0.9K | ~148K |
| delta_sets_chs.c | ~2.6K | ~886K |
| delta_globals_chs.c | ~0.9K | ~9.7K |
| eci_ini_chs.c | ~2.2K | ~14.8K |

## Why the .objs can't be had easily

- `analysis/` in the standalone tree is **empty** (retired 2026-09-06: "Nothing in this tree needs IBM"（ — for the OTHER languages the .objs are gone too, but the lifted files are committed.
- The IBM chs voice modules (reference/vvtts-6.1-chs/chs.syn,2MB;reference/vvtts-6.4.1-chs/app/chs50.syn( are **coff-i386 PE**: llvm-objdump reads sections finebut `nm` = "no symbols" — the COFF symbol table was stripped;the lifters key on symbol names (_delta_new etc(,so objcopy .syn→.obj cannot restore them.
- Recreating the .objs = "Wine/C++ extraction" per eloquence-voice-re — a project of its own.

## The unblock — ELF data lift (recommended(

The Apple-lifted **linux-x86_64 chs.so** (/root/.scratch_cjk/apple-eloquence-elf-1.2.3-linux-x86_64/lib/chs.so( keeps its dynsym symbols and carries all the same machine data (.m2e_data/.m2e_text_const( that the IBM .objs held. elflink.py already proved the link-table lift from ELF for kor（see references/apple64-elflink-lift.md(;ther consts/sets/globals lifts need the same treatment:

1. **Locate bases per build**:  derrive addresses from lea/adrp pairs (+const operand( in the chs.so functions + the lpta payloads ( oracle/ghidra/lpta/ — rules_chs.ops.json has the consts base 0x98eb4 for ARM64(**never reuse cross-build offsets: x86_64 differs** — verify per build(.
2. **Write ELF-aware lifters** (globals/sets/consts( modeled on elflink.py: walk the typed records in .m2e_data (pointer→distance regions copied at startup by src/delta/delta_low.c(,emit C arrays (or hand-compile the tables andreplace the stubs(.
3. **Integrate**:  rebuild libevv-enus-chs.a, re-run the phoneme probe (EVV_LANG=0x60000,+GB18030 chars( with RULE_TRACE=2 — accept phonemes when rule executions appear (enus trace shows thousands of delta_call_* events;chs currently shows ZERO(.
4. **Synth gate**:  samples>0 from the waveform path;then run the app-side checks (whitelist 0x60000 in vvtts_core.c charset GB18030 in the JNI, Kotlin zh-CN gate(.

## Cross-checks

- The rom's direct output (tele chs_lookup( — an unbounded-walk bug was fixed earlier (output cap( — verify pinyin sanity with t2c.txt (reference/vvtts-6.1-chs/t2c.txt(.
- enus control stays green: "hello"→`[.2hE.1lo]`(11 chars( — same binary,always re-run as control(.
- The DeltaProc_* stubs ARE the correct shape for this family (must return  ️0 per eloquence-engine-integration( — not themselves the gap.