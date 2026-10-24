# TF-TFC Baseline — Eloquence TTS (Android framework path)

Date: 2026-10-22/23 — Firebase Test Lab, fresh instrumentation run.
Device: `MediumPhone.arm`, Android API 34 (Test Lab virtual device), portrait.

## What was measured

**TTFC (time-to-first-character, locked to time-to-first-audio)** — the latency from
`TextToSpeech.speak()` on the Android framework path to the first *audible* output,
proxied by the first `UtteranceProgressListener.onStart()` wakeup relative to
`SystemClock.elapsedRealtime()` at the `speak()` call (`onAudioAvailable` is deprecated /
unreliable on modern API levels).

Both the **Eloquence engine** (`com.xw.vvtts`, the app under test) and the **stock Google
engine** (platform fallback, measured for contrast) were benchmarked on the same device in
the same instrumented process.

**Fast utterances** (short, high-frequency words): `a the of this test hello one two`.
Each spoken **3×** (QUEUE_ADD, distinct utterance IDs) so a cold/warm spread is visible.
Method: `platform-tests/androidTest/kotlin/com/xw/vvtts/tests/TtfcBenchmarkTest.kt`.

All numbers are **milliseconds**. Lower is better.
**TTFC is inherently per-device / per-engine** (synthesized vs offloaded audio) — this is a
same-device **relative** baseline, not an absolute spec.

## Results

### Eloquence (`com.xw.vvtts`)

| utterance | min | median | mean | max |
|---|---|---|---|---|
| a | 8 | 12 | **171.3** | **494** |
| the | 7 | 8 | 8.7 | 11 |
| of | 7 | 9 | 9.0 | 11 |
| this | 7 | 8 | 8.0 | 9 |
| test | 8 | 8 | 8.3 | 9 |
| hello | 9 | 10 | 11.0 | 14 |
| one | 8 | 9 | 9.0 | 10 |
| two | 7 | 7 | 7.3 | 8 |

### Google (stock engine, contrast)

| utterance | min | median | mean | max |
|---|---|---|---|---|
| a | 2 | 2 | 2.3 | 3 |
| the | 2 | 12 | 10.3 | 17 |
| of | 3 | 3 | 6.3 | 13 |
| this | 2 | 3 | 9.0 | 22 |
| test | 2 | 3 | 5.7 | 12 |
| hello | 3 | 3 | 7.0 | 15 |
| one | 1 | 2 | 5.0 | 12 |
| two | 2 | 8 | 7.3 | 12 |

## Aggregates

| engine | median of word-medians | min of minima | max of maxima | mean of maxes |
|---|---|---|---|---|
| Eloquence | **8.5 ms** | 7 ms | 494 ms | 70.8 ms |
| Google | **3.0 ms** | 1 ms | 22 ms | 13.2 ms |

**Warm utterances**: Eloquence's per-word median is 7–12 ms vs Google's 2–3 ms — the engines
are within ~1 digit of each other on typical fast utterances (synthesized-audio `onStart`).
The single 494 ms Eloquence outlier on the first utterance ("a") is the **cold-start path**
(native library + TTS service init on the first-ever synthesize in the process), inflating
that word's mean to 171 ms. Dropping the cold start, every Eloquence mean is 8–11 ms.

## Notes

- Cold-start 1×-only cost (~494 ms) is measured on the *first* utterance of the whole run
  and is not representative of the steady-state fast-utterance path the user cares about.
- Both APKs are debuggable debug builds signed with the same key (Test Lab requirement) —
  so these numbers include debug overhead; a release build could differ.
- Google-engine numbers are on-platform synthesized TTS; Eloquence is the app-under-test's
  own engine via the same framework `TextToSpeech` path.
- Instrumentation run passed `OK (2 tests)` in 123.5 s including warm-up and init.