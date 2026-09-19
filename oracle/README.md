# Oracle-based CJK rules for eloquence-tts-android

## Why this works (and why the module-lift could not
The Chinese/Taiwanese/Korean TTS rules live in the Apple dylibs as **compiled
C++ machine code** — not as extractable data tables. So there is nothing to lift into
openevv-style `delta_rules`. BUT the rules are a *function*: text → phonemes+. We can run
the genuine engine over large corpora and *learn* that function — a table-driven
reimplementation then reproduces it. The Apple binaries stay dev-side (oracle(**; the
shipped APK carries only our own generated C tables/rules. This mirrors how the shipped
13 languages were built (rules lifted from IBM objects)( — we just regenerate the CJK source
of truth empirically instead of by lifting.

## The oracle
`Mudb0y/Apple-Eloquence-ELF` converts the Apple tvOS-18.2 Eloquence dylibs
(which we already own in `reference/apple-eloquence-tvos18.2/`( to loadable ELF
shared objects. Release 1.2.3 ships x86_64 and aarch64 tarballs. The x86_64
one needs glibc >= 2.34 + libc++abi — fine on ubuntu-latest runners, not on this
el8 box; hence the GitHub Actions route..

Dialects (authoritative):
- 0x00060000 zh-CN `chs.so` + `chsrom.so`
- 0x00060001 zh-TW `cht.so` + `chtrom.so`
- 0x000A0000 ko `kor.so` + `korrom.so`
- 0x00080000 ja `jpn.so` + `jpnrom.so`

Key ABI (probed from his `eloquence_jni.c`/`engine.c`/`eci.h`):
- `eciNewEx(int dialect` → handle (one arg in the converted engine(
- `eciSetParam(eciWantPhonemeIndices, 1)` + `eciRegisterCallback` → callback
  receives `eciPhonemeBuffer` (msg with `ECIMouthData` sz[5]/wsz[5] frames( — the
  phoneme stream we need;`eciWaveformBuffer` (msg ( = the PCM.
- `eciAddText` + `eciSynthesize` + `eciSynchronize` + drain `eciSpeaking` (non-blocking
- The engine finds its language modules + romanizers through `eci.ini` sections
  `[6.0]` `Path=` + `Path_Rom=` etc ( — patch the shipped ini's `/usr/lib/eloquence` paths
- Useful extras: `eciGeneratePinyins`, `eciGeneratePhonemes`, `eciNewEx2`
  (the pinyin generator is the short-cut for the hanzi→pinyin rom table(.
- Apple quirk: sample rate 1 = 11025 Hz only (eciSampleRate, 2 rejects(.

## Pipeline (in-tree(
- `oracle/dump_engine.c` — dlopens `lib/eci.so`, drives a dialect, prints per-utterance
  TSV: `pcm\t<samples>` and `phbuf\t<frames>\t<hex>` (phoneme bytes(.
- `oracle/make_corpus.py` — generates the 27k-char hanzi sweep + starter sample
  sentences for zh-cn/zh-tw/ko/ja.
- `.github/workflows/oracle-dump.yml` — ubuntu-latest job: fetch x86_64 tarball,
  patch ini, build dump_engine, dump all four dialects, upload .tsv artifacts.

Run-on-manual: `gh workflow run oracle-dump.yml` — artifacts under
`oracle/corpus/*.tsv`.

## Next phases
1. **Format discovery** — first CI runs show what `phbuf` rows look like (indices vs
   ASCII phoneme names(; then bakes `eciGeneratePinyins` output for the sweep.
2. **Rom table** — hanzi → pinyin final IDs forth chs/cht (≈400 syllables x tones(;
   kor=algorithmic Hangul→jamo.
3. **Phoneme rules** — fit the syllable→phoneme mapping + tone-sandhi/boundary
   exceptions from corpus rows; author `delta_rules_chs.c`-style tables (or add a
   table-driven evaluator( same order-of-magnitude as jajp's ≈1749 rules.

4. **Verify + integrate** — probe-like harness compares our module's phoneme output
   against oracle held-out sentences (target ≥99.5%(; then `build_native.sh` + CI APK.。



## Licensing note
Oracle/.so files = Cerence/Apple-derived(,dev-only instrument,never shipped..
The generated tables/rules = our clean-room data derived from output observation** — the
shipped APK stays Apple-dependency-free, consistent with the project's posture..

## External references
- Mudb0y/Apple-Eloquence-ELF (1.2.3: x86_64+aarch64( — converted engines+
  recipe +`android/` reference app (the old iOS-lifted approach(.
- fastfinge/eloquence_64 (NVDA addon( — ships `chs.syn`/`jpn.syn`/`kor.syn` +
  rom DLLs + `t2s_data/` (zh-TW→zh-CN( — spare voice/rom data sources..
- HN threads (id=46731086/46733163(: explain Delta→C++ compiled rules architecture.>