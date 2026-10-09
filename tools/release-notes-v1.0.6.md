## v1.0.6 — never silent speech: collapse all-silent engine output to failure (#313)

### Fix: an utterance can no longer "progress" while speaking nothing

**Symptom (reported intermittently in #312-family testing):** during/after a stall or
recovery, an utterance kept progressing — its text advanced and timing paced on — but
its audio was inaudible. The utterance did not restart or replay; it simply produced no
sound while the state moved forward.

**Root cause (native-side):** when the synthesis engine reports success but voices nothing,
`vv_trim_silence` refuses to shrink an entirely-silent buffer (`n - lead < 16 → return`), so
a **nonempty all-silent PCM buffer** (every sample under ±700) survived, was resampled, and
handed to the Android framework **as valid speech**. The service loop saw real PCM, pacing
advanced, and the utterance "completed" with no audible output — engine failure surfaced as
silent progress.

**Fix:** `vv_synthesize` now collapses any all-silent engine result to a clean **failure**
(`NULL`) instead of delivering it as silent "speech." A new `vv_has_audio()` scans the
post-trim PCM with the same ±700 speech floor as `vv_trim_silence`; if **no** sample carries
voiced content, the result errors rather than advancing progress inaudibly. Legitimately
voiced chunks (even quiet or whispery) exceed ±700 somewhere, so they are never collapsed.

**Boundary consistency:** `vv_trim_silence` treats `|sample| >= 700` as voiced; the detector
was aligned to that inclusive boundary (exact ±700 survives as voiced, |699| is silent).

### Detail
- `jni/vvtts_core.c`: added `vv_has_audio()` (inclusive ±700 voiced floor); `vv_synthesize`
  returns `NULL` when the post-trim PCM carries no voiced sample — silent engine output is
  an error, never silent "speech."
- **No change** to watchdog/stall/retirement behavior, pacing, generation epochs, or
  cancellation — fast cancellation and long-progressing-utterance protection are preserved.
- **Independent of the v1.0.5 stall fixes** (#316/#317): unchanged, still in force.

### Tests / validation
- Native harness: `test_silent_engine_output` — `vv_has_audio` unit (all-zero → 0; one
  sample @800 → 1; exact +700/-700 → 1; 699 → 0) + integration: an all-silent engine
  result makes `nativeSynthesize` return `NULL`.
- Host contracts: all 11 pass; source contracts require `vv_has_audio` post-trim gating and
  the inclusive ±700 floor.
- CI: `build` (both lanes) and `chs-smoke` green on the merged PR (#318); CodeRabbit
  review approved.

### Not changed / needs device evidence
- The v1.0.5 fast-recovery trade-off is intact: on rapid stop→start over a long-draining
  chunk, `vv_settle` may still retire and recreate the engine (visible to TalkBack as an
  "eloquence TTS" init/restart). This is the deliberate mechanism that keeps the next
  utterance fast, not a crash. If it recurs frequently in daily use, that is on the
  device side (framework AudioTrack acceptance) and needs a repro/logcat — not addressable
  in service code.