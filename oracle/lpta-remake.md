# CJK clean-room plan (chs / cht / kor) - where it actually stands

## The chsrom dict reader - complete transcription spec (2026-09-20)

All addresses: x86_64 slice of tvos18 chsrom.dylib (VA==fileoff).

### getCharType (0x156c) - DECODED + cross-checked on the data
```
cl = *p; if (!cl) return 0;                 // EOS
if (cl >= 0) return getAnsiCharType(p);      // ASCII path (module's own)
al = p[1]; if (al >= 0) return 9;            // lead + ASCII second
if ((cl + 0x5f) <= 0x0e && al > 0xa0) return 0x3f;  // A1-AF sym/punct band
if (al < 0xa1) return 8;
return (cl + 0x50) >= 0x4f ? 8 : 0x40;       // 0x40 = hanzi (lead >= B0)
```
Counters: _countHanziChars counts type==0x40; _countCharsFromBytes 1B/ASCII + 2B/GB.

### ChiDict core (0x232e-0x27a4)
- getGBIndexFromKey 0x232e: ((b0-0xb0)&ff)*0x5e + ((b1-0xa1)&ff)  [<0xb0 -> -1]  VERIFIED on 中=0xD6D0 -> idx 3619
- getGBKeyFromIndex 0x23d8: idx/0x5e + 0xb0, idx%0x5e + 0xa1 (inverse, used in loops)
- BIG5ToGBConverter 0x23f6: per char; <0x81 copy 1B; else m_pConvertTB[big5Index<<1] (big5Index = ((b0-0x81))*0x... two-band, from getBig5IndexFromKey 0x2638ish); invalid -> space
- getBIG5ToGBTableOffsetFromKey 0x24?? : big5Index<<1
- wordLookup 0x249c (type,key,cnt): type1=GB main: getGBIndexEntryFromKey; cnt==1 -> return the 6B index entry verbatim; else lexoff u16@+4 (0xffff=none) + m_pLexTB[which] + off -> Lexicon::getLexicon. type2=symb: m_nSizeofSymbEntry/m_pSymbTB/m_nSymbEntry + implicit key<<1 -> binaryLookupWord. type3=homo: walk m_aSizeofHomoEntry/m_aHomoEntryN over m_pHomoTB (2-size class: (ent>>1)<<1 for 2B, else 4B) -> binaryLookupWord at m_pHomoTB + acc.
- getGBIndexEntryFromKey 0x25e4: idx=getGBIndexFromKey; which=WhichLexTB(idx) (m_aIndexRange[19] cumulative: [0,200,...,3600,4400]); off=GetIndOffset 0x27a4 = idx-ranges[which]; return m_pIndexTB[which] + 6*off. VERIFIED: which=18, off=19, entry=56 ff 58 2b 12 ea, lexoff u16@+4 = 0xea12.
- getPinyinFromKey 0x26b8: entry -> if (!(b[2] || b[0])) return 0; Code2Pinyin(entry, out, 1); out[7]=0.
- getWordGcat 0x26f0: category byte @+2 (0x58 for 中); type-1 gcat check `(b[2]!=0 || b[0]!=0)`.
- PinYin::Code2Pinyin 0x1940: per word-pair (2 code bytes):
    code0 != 0xff: strcpy(consonantTB + 3*code0)        -- consonantTB = initial[26] table (3B cells: "zh:ch:..."-style) @ c087e-ish
    code1: q=code1/0x32, r=code1%0x32; q=='5' -> "5"; else sprintf("%s%c", vowelTB + 5*r, q|0x30)   -- vowelTB = final[38]-style 5B cells
    => pinyin syllable = 2 bytes (consonant-code + (vowel-code with tone digit))
- getToneFromPinyin 0x1c67: tone digit parse from the pinyin string ("...1".."5").

### Open item (one function)
initStaticDicts 0xc05a / DictData instance pointer arrays: m_pIndexTB[20] and
m_pLexTB[20] assignment order (is m_pLexTB[i] == &aChiLexTB[i], or the
lex-table reordering by WhichLexTB group?). The u16@+4 lexoff 0xea12 for 中
lands in the concat span at lex7+0x39b2 (bytes 01 00 00 00 03 00 00 00 ...)
which looks like a 4-byte-count prefix - bind the arrays to resolve.
After that, the dict reader is 100% transcribed: sprache the C.

## What has landed (committed, branches dll-survey + oracle-dev)

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