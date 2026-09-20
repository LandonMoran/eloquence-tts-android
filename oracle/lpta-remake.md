# CJK clean-room plan (chs / cht / kor) - where it actually stands

## Architecture (settled this session)

The Eloquence pipeline for a CJK language = [romanizer module] -> [shared
delta/evv engine]. The romanizer is the `-rom` dylib (chsrom/chtrom/korrom):
takes text in the script, hands the engine a phoneme string. docs/japanese.md
documents the same shape for jajp. The openevv engine reaches a romanizer
through the EvvRomOps table in src/eci/lang/eci_rom.h (addText,
processSentence, UCS2ToMBCS, setParam ...); ours are compiled in; a lang
module registers its romanizer from its bind function.

Everything below the romanizer (phoneme synthesis, prosody, output) is the
shared engine that already speaks the other 8 languages.

## The data (all extracted, on branch dll-survey)

- rom_tables.json: labeled chs/cht/kor starter tables (initial/final/punct/
  punct_raw/special/counters/tones/math/digits + kor jamo/johab/hanja) from
  parse_blocks.py, plus Windows DLL variants.
- oracle/rom_lift/: byte-for-byte lift of the NAMED tables out of the tvos18
  chsrom/chtrom/korrom dylibs - 349+349+125 tables, 1.36MB (StaticDict
  aChiInd/aChiLex/aChiBig5/aChiHomo + korData Hangul2Johab/Johab2Hangul/
  Hanja2Hangul). x86_64 slice: VA==fileoff, symbol address == file offset.
  manifest.json holds sym -> section/addr/end/size/sha.
- lang/{chs,cht,kor}/rom_tables_{tag}.c/.h: jajp-contract generated source
  (one aligned blob per contiguous run + named pointer & length per table),
  all cc -fsyntax-only clean.
- lpta/ payloads: .m2e_data bins (chs 18KB / cht 25KB / kor 151KB / jpn 12KB
  / fin 288KB), the aarch64 chs/cht/kor .so Ghidra decompiles (117 sections
  of apply_chi_*_rules chains calling the openevv-identical runtime
  primitives test_string_s/insert_2pt_s/if_testeq_v_lng/fence), and
  rules_*.ops.json (6811 ops/language).
- The vvtts-6.1-chs Windows DLLs contain NO rule bytecode either (compiled
  chains on all three platforms) - the tvOS chains + tables ARE the rules;
  there is no .dr text upstream of them.

## Findings

1. chs and cht rule code is op-for-op identical; their .m2e_data are
   byte-identical. The chs/cht difference is entirely inline tables in
   .m2e_text and one .m2e_cstring byte. One engine + per-lang tables.
2. kor has no apply_kor_*_rules: pure table/dictionary engine. Its whole
   conversion sits in korData tables (Hangul2Johab, Johab2Hangul,
   Hanja2Hangul) - all already lifted.
3. Ghidra image base 0x100000: a DAT_0019xxxx operand is `.m2e_data +
   (0x0009xxxx - 0x98000)`.
4. Dictionary tables use GB2312 2-byte codes + single-byte pinyin alphabet:
   aChiLexTB* = run of GB codes each followed by pinyin bytes; aChiIndTB* =
   index of offsets; aChiHomoTB* = homophone groups (GB pairs, 0x53
   separators); aChiBig5TB = Big5 columns of the same dict.
5. The chain-call vocabulary (test_string_s, insert_2pt_s, if_testeq_v_lng,
   fence) maps 1:1 onto openevv's src/delta/delta_rules_c.h - transcribed
   rules can be written in the openevv C-rule idiom.
6. jajp precedent: 30-file romanizer port (rom/jajp/...), machine rules
   emitted from the original objects. plpl precedent: a language without
   original material uses a sibling chassis + own tables + own census test
   cases. chs/cht/kor HAVE original tvos material, so the romanizer
   text-analysis (TextProcessor/ChiDict/PinYinOutput) can be transcribed
   from the dylib disassembly (symbols present) + the dict blobs above.

7. ChiDict API (chsrom.dylib x86_64 VAs): ctor 0x21c0,<initDicts
   0x221a/0x22a2(load), initStaticDicts 0xc05a, BIG5ToGBConverter
   0x23f6, getGBIndexFromKey 0x232e, getGBKeyFromIndex 0x23d8,<
   getGBIndexEntryFromKey 0x25e4, wordLookup 0x249c, WhichLexTB<
   0x2626/0x2770, getLexAddr 0x268a, getWordGcat 0x26f0,<
   GetIndOffset 0x27a4, getPinyinFromKey 0x26b8. The whole dict layer
   spans ~0x21c0-0x27a4 (~1.5KB of x86 code) - tiny; transcribe
   directly. TextProcessor::*, PinYinOutput::*, eciGeneratePinyins<
   0x4e8e, UnicodeConverter 0x37fa stay named in the same table.

## Remaining work (order matters)

1. Transcribe the dict LOOKUP (ChiDict) from the dylib disassembly - the
   readers of aChiIndTB/aChiLexTB + the pinyin-byte alphabet - as
   romdict.c (symbols: ConverterInterface at 0x2194, size ~0xa00).
2. Transcribe TextProcessor (segmentation, comma/space/punct/digits) +
   PinYinOutput (tone/syllable assembly) - names known in the symbol table.
3. Walk the apply_chi_*_rules ops JSON to drive the STRING emission (the
   LPTA half of eciGeneratePinyins behavior) - only where the phone-string
   assembly needs the context rules.
4. Write the EvvRomOps front-door (addText/processSentence/stop/resume/
   UCS2ToMBCS/setParam/getParam) per language - the actual integration.
5. Author the machine-module texts (statements/globals/sets/settings/
   consts/eci_ini) - partly mechanical from the dylib cstrings + chassis
   (settings give the phone inventory + voice params; eci.ini in
   reference/vvtts-6.1-chs/ is text).
6. Wire into evv.py/Makefile: NAMES += chs cht kor, LANGS += lang/chs ...;
   build_native.sh rebuild; add probe cases (matrix.sh) with own baselines
   (no oracle audio). Build + validate on the runner; validator checks pcm
   content (audio, not exit codes).

## Payloads committed under oracle/ghidra/

- lpta/{chs,cht,kor,jpn,fin}.m2e_data.bin - LPTA rule-table/phone pools
- lpta/decomp/ghidra-decomp-{chs,cht,kor}.so.txt - decompiled rule chains
- lpta/rules_{chs,cht,kor}.ops.json - op chains per apply_chi_*_rules
- oracle/rom_lift/ - named static-dict/kor tables lifted byte-for-byte
- rom_tables.json + parse_blocks.py - labeled rom tables
- fetch_lpta_data.py, extract_lpta.py, extract_m2edata.py, rom_lift.py,
  rom_tables_gen.py, parse_lpta_ops.py, DecompileLpta.java + V2 - tools

## Pitfalls

- Ghidra image base 0x100000 for the aarch64 ELFs - resolve DAT_0019xxxx
  via .m2e_data + 0x9810 (see fetch_lpta_data.py docstring).
- x86_64 slice VAs == file offsets; arm64 slice section offsets carry
  virtualisation bits - read the x86_64 slice (or the ELF conversion) for
  direct byte work.
- llvm-readelf -S tokenizes '[ 9]' as '[' + '9]' - match section names by
  membership, not fixed columns.
- upstream-openevv/lang/cht etc. are UNTRACKED chassis copies - never
  treat them as genuine lifts.

## Reference anchors

- tvos18 dylibs: reference/apple-eloquence-tvos18.2/ (symbol table present:
  llvm-objdump --macho -t)
- x86_64 .so dir: /root/.scratch_cjk/apple-eloquence-elf-1.2.3-linux-x86_64/lib/
- aarch64 ELF libs: /tmp/art_check/aee/apple-eloquence-elf-1.2.3-linux-aarch64/lib/
- openevv: upstream-openevv/ (docs/japanese.md precedent); android copy:
  native/openevv/ - chase eci_rom.h, eci_romanizer.c, lang/jajp/.
- Chase in dylib symbols: ChiDict::*, TextProcessor::*, PinYinOutput::*,
  eciGeneratePinyins, ConverterInterface::*.