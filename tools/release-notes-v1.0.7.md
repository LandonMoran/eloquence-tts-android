## v1.0.7 — comma-grouped magnitudes expand to words instead of "...hundred" (#319)

### Fix: "1,000,000" no longer mispronounced into a trailing "...hundred"

**Symptom:** Eloquence's native reader corrupts comma-grouped magnitudes whose comma-groups are all zeros — `"1,000,000"` / `"$1,000,000"` were being spoken as a number ending in a trailing *"...hundred"* instead of *"one million"*. The mispronunciation occurred regardless of the number-reading mode (with reading OFF too), so it was not a mode toggle issue.



**Root cause (native-side):** when a magnitude's trailing comma-group(s) are all zeros ("000"), the native tokenizer dropped them and folded the reading into a trailing "...hundred". The text survived intact — only the spoken form was corrupted



**Fix:** `EloquenceEngine.kt` now expands a comma-grouped magnitude with >= 3 comma-triads and a trailing all-zero group run into its full spoken form (`spellGroupedMagnitude`), before Eloquence's reader touches it (an utterance grouping `1,000,000` → "one million"`.Boundary-aware: the match must not start or end inside a larger comma-delimited numeric run — runs flanked by digits(,or beginning right after a comma(e.g. the tail of `1234,000,000,000`(),are left untouched so malformed/overlong numbers keep their original reading. User-dictionary rules that fully match a grouped run are preserved unchanged so the user's override can still be applied (`applyDict` runs after the expansion`.



### Detail
- `src/com/xw/vvtts/engine/EloquenceEngine.kt`: added `spellGroupedMagnitude()`( word-spelling for >= 3 comma-triads with an all-zero trailing run(,`groupedRun` regex with digit- and comma-boundary lookarounds(,and passing `userDict` through so fully-matching user-dictionary rules win.

- English dialects only (`EN_US`/`EN_GB`(`; non-English dialects keep their original grouped number. Magnitudes beyond 12 digits) (>999,999,999,999( are left to the native reader. Shorter grouped numbers (`1,150`,`205,558,107`( — no all-zero trailing run( — unchanged.



### Tests / validation
- CI: `build`( both lanes(,and `chs-smoke` and `smoke` green on the merged PR (#319); CodeRabbit review **approved** (4 findings →  ̂1 →  ̂0 through the re-review loop;two boundary/ dictionary fixes included(.
- Emulator `crash_test.sh` harness lane remains flaky/red on a separate AVD-step exit-1 — tracked separately,not a compile or logic failure of this change.



### Not changed / needs device evidence
- The v1.0.6 silent-speech collapse (#313)and the v1.0.5 stall fixes (#316/#317) are intact and still in force — this change is independent and only touches grouped-magnitude expansion.



### Backport / follow-ups
- A focused device pass (TalkBack utterance of `1,000,000`, `$1,000,000`, `12,345,678` in varied punctuation could confirm spoken output(;the native-side fix is what CI and code review validate today.