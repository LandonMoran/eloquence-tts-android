# APK Trimming Plan — vvTTS (Eloquence for Android)

> **For Hermes:** implement phase-by-phase; every phase ends with a build + measurement gate before the next starts. No phase ships to the user until the previous gate passes. Use the **laddered sub-agent execution** (§Ladder) so implementation of the next phase overlaps validation of the current one — never skip or weaken a gate to go faster.

**Goal:** Shrink the APK from ~60 MB (arm64) / ~104 MB (universal) to ~20 MB (arm64, all features kept) without changing synthesis output or TalkBack behavior — and validate both 32-bit (armeabi-v7a) and 64-bit (arm64-v8a) builds at every gate.
**[RE-BASELINED 2026-09-22 (user: "Part 1 part 3 only"):** the ~20/28 MB arm64 targets are arithmetically unreachable without dropping voices/languages/quality (forbidden), verified by measurement — Phase 2's `zip -9` already compresses the whole `.so` at 2.72:1 (better than P4's assumed 2.3:1), so P4's in-core deflate is zip-redundant (−0.2% on-disk). New targets: **arm64 ≈ 55–56 MB, universal ≈ 95–96 MB** (P1–P3 + symbol-strip + JSON-prune). See §Re-baseline.]

**Architecture:** Our port ships three wasteful payloads the original app never had: (1) 26 MB of dex, ~80% of it a dead fastutil.jar nobody imports; (2) a 118 MB monolithic native core where the original app packed 13 voices at 1–2 MB each; (3) 25 MB of lingua n-gram JSON models. The original BlindHelp app is 8 MB total — that is the proven ceiling, and nothing in our codebase needs to be bigger than ~20 MB.

**Tech stack:** bash build (build.sh + build_native.sh, no Gradle), D8 dexer, NDK cross-compile, GitHub Actions CI (3 ABIs + arm64 host smoke). Change surface is exactly four files plus one data-conversion tool.

**Scope (in):** dex dead weight, zip flags, res audit, language-model packing, in-core voice compression, emoji table generation, test-infra additions (x86_64 lab variant, arm32 lab run), delivery under Telegram limits.
**Scope (out):** synthesis algorithm changes, voice quality changes, UI rewrites, minSdk change for *shipping* (stays 28), Play Store release identity, Phase-7 per-language lib refactor (research backlog).

---

## Measured baseline (2026-09-21, CI run 35678192016)

| Payload | Size | On-disk in APK (est.) | Trim target |
|---|---|---|---|
| `lib/arm64-v8a/libvvttts_core.so` (118 MB raw tables) | 118 MB | ~52 MB (2.3:1 stored) | **~26 MB raw → ~12 MB on-disk** (zlib) |
| `classes*.dex` (4 files) | 25.9 MB | ~13 MB | **~2.5 MB dex → ~1.3 MB on-disk** |
| `language-models/` (10 langs, 38 files JSON) | 25.3 MB | ~10.4 MB | **~8 MB raw → ~3.5 MB on-disk** |
| `res/`, manifest, assets | — | ~0.5 MB | minor |
| **arm64 APK total** | — | **62.9 MB (measured)** | **~18–20 MB** |
| **armeabi-v7a APK total** | — | **60.6 MB (measured)** | **~17–19 MB** |
| **universal APK total** | — | **104.0 MB (measured)** | **~35–40 MB** |
| Reference: OG BlindHelp v1.3.3 | 19.2 MB raw | **8.0 MB** | (target model) |

### Dex forensics (why Phase 1 is the single biggest safe win)

Class-file weight of each jar fed to D8 (build.sh lines 61–68), verified 2026-09-21:

```
fastutil.jar        55.1 MB classes   ← imported NOWHERE in src/ (zero matches)
kotlin-reflect.jar   8.2 MB classes   ← imported NOWHERE (zero kotlin.reflect imports)
kotlin-stdlib.jar    4.2 MB classes   ← REQUIRED (Kotlin runtime)
lingua-slim.jar      0.3 MB classes   ← REQUIRED (LanguageDetector.kt imports it; jar is self-contained — unzip shows no gson/okio/moshi/fastutil/reflect classes inside)
moshi.jar / moshi-kotlin.jar / okio.jar  0.9 MB classes ← imported NOWHERE (zero com.squareup)
```

- The dead jars are not even on the compile classpath (`build.sh` line 48: `$ANDROID_JAR:out_classes:$LIBS_DIR/lingua-slim.jar:$LIBS_DIR/kotlin-stdlib-1.9.25.jar`) — the app compiles without them today. They exist only in the D8 dex line. Removing them is zero-risk to compilation; they remain in `libs/` on disk (not deleted) so the change is trivially revertible.
- Expected dex: 25.9 MB → ~2.5 MB. Expected APK: **−10 to −12 MB**.

---

## Phase 0 — Measurement gates (do first, keep for every phase)

**Files:**
- Modify: `build.sh` (append size report)
- Modify: `.github/workflows/build.yml` (print report; later phases add assert-on-regression)

**Step 1.** Add a size report to the end of `build.sh` (after line 114):

```bash
echo "=== SIZE REPORT (arm64) ==="
unzip -l vvtts_signed.apk | awk '
  /classes.*dex/ {dex+=$1} /lib\// {lib+=$1} /language-models/ {m+=$1}
  END {printf "dex=%d lib=%d models=%d total=%d\n", dex, lib, m, dex+lib+m}'
ls -l vvtts_signed.apk | awk '{printf "APK_ON_DISK=%d\n", $5}'
```

**Step 2.** Verify CI prints the report (push + watch run).
Expected: `APK_ON_DISK≈62.9M` (arm64), `dex≈25.9M`, `lib≈117M` (uncompressed inside zip), `models≈25.3M` — matching the baseline table.

**Step 3 (sub-agent candidate, "size-report" card §Ladder):** after Phase 1 confirms the pattern works, promote the report into a tiny CI job that archives a `sizes.json` per run (artifact) so history is queryable: `gh run view <id> | jq` → per-phase deltas documented, not guessed.

**Gate:** baseline numbers recorded and CI-visible. Do not start Phase 1 until the report matches the table. **Every later phase reuses the same gate pattern: build → read report → diff vs previous phase → functional check → record.**

---

## Phase 1 — Remove dead dex jars (−10 to −12 MB, zero behavior change)

**Files:**
- Modify: `build.sh:61-68` (the D8 invocation)

**Step 1.** Replace lines 61–68 with:

```bash
"$D8" --min-api 28 --output out_dex @/tmp/class_files.txt \
  "$LIBS_DIR/lingua-slim.jar" \
  "$LIBS_DIR/kotlin-stdlib-1.9.25.jar" 2>&1
```

**Step 2.** Confirm nothing else references the dead libs:
Run: `grep -rn "fastutil\|moshi\|okio\|kotlin.reflect\|com.squareup" src/ gen/` — expected: no matches (already verified; re-run as regression guard).

**Step 3.** Build + measure all ABIs:
Run: `ABI=arm64-v8a bash build.sh && ABI=armeabi-v7a bash build.sh && ABI=universal bash build.sh`
Expected: dex ≈ 2–3 MB; arm64 APK ≈ 48–50 MB; arm32 ≈ 46–48 MB; universal ≈ 88–92 MB.

**Step 4.** Functional gate: install arm64 APK on Pixel (see Validation), speak English AND Chinese, confirm auto-language detection intact (proves lingua still loads at runtime). `chs-smoke` passes automatically (it links native directly — unaffected), so the runtime install is the real gate here.

**Gate:** dex ≤ 3 MB **and** English+Chinese TTS spoken correctly on-device (or lab x86_64 variant if device not yet reached).

**Rollback:** `git checkout build.sh` — jars never deleted from `libs/`.

---

## Phase 2 — zip -9 + resource lean + install-size audit (−1 to −4 MB)

**Files:**
- Modify: `build.sh:107` (zip flags)
- Possibly: `AndroidManifest.xml` (extractNativeLibs audit — see Step 3)
- Possibly: delete unused res (audit first)

**Step 1.** Maximum deflate on the assembly step:
Change line 107 from `zip -r ../vvttts_unsigned.apk ...` to `zip -9 -r ...` (same archive, max compression; ~0.5–2 MB on the 60 MB payload, zero runtime cost).

**Step 2.** Resource audit for dead weight:
Run `aapt dump resources vvttts_signed.apk | grep -c "string/"` and cross-check `res/` tree against strings referenced in `src/` (scriptable: grep `R.string.<x>` and `@string/<x>` usage vs values files).
Decision rule: `values-zh-rCN/` + `values-zh-rTW/` stay (Chinese UI is a feature — user-maintained). Remove **only** genuinely unreferenced drawables/strings; when in doubt, keep (resource bugs break TalkBack menus, not just visuals).

**Step 3.** `extractNativeLibs` audit (sub-agent candidate, "manifest-audit" card):
Check `AndroidManifest.xml`'s `application` tag: if `android:extractNativeLibs="true"` (or unset on an old-style manifest), flipping to `"false"` makes the installer keep the .so compressed inside the APK and load it directly — saves install size and ~2× disk usage. **Verify on device before keeping** (some OEMs mishandle it; TalkBack + TTS must work after reboot, not just immediately after install). If anything misbehaves, revert — it's a nice-to-have, not a size lever we depend on.

**Gate:** arm64 APK ≈ 46–49 MB; TalkBack UI strings unchanged (Step 2 removed nothing referenced); installs + speaks on device with any manifest change.

---

## Phase 3 — Language models: packed binary + prune (−3 to −7 MB)

**Files:**
- Create: `tools/model_pack.py` (build-time converter + `--verify` self-test)
- Modify: `src/com/xw/vvtts/utils/LanguageDetector.kt` (loader for packed format)
- Modify: `build.sh` (run converter before APK assembly, ship packed dir)
- Delete decision (user-controlled flag, not a code change): `MODELS=minimal` → pack only `en`, `zh` (+ `es` if desired)

**Step 1.** Converter (`model_pack.py`), fully spec'd for a sub-agent:
- Input: `language-models/<lang>/*.json` — lingua n-gram JSON with `"ngram": "<freq>"` maps.
- Output: one binary file per language: header (magic `VVN1`, lang tag, ngram-count, UTF-8 table offset), then a sorted token table (UTF-8, length-prefixed), then counts as varints. No JSON quoting/whitespace.
- Self-test: `--verify` re-parses its own output and asserts key-set equality with the JSON input (round-trip must be exact; any mismatch = exit non-zero, fail CI).
- Target: 25.3 MB JSON → ~8–10 MB raw (before APK zip).

**Step 2.** Loader: replace the JSON read in `LanguageDetector.kt` with a direct byte-array parse of the packed file (same map semantics; ~120 lines). Keep `lingua-slim.jar` as the scorer. (If the scorer's only JSON use was our loader — worth one sub-agent probe — dropping the jar removes its 0.3 MB dex too; optional.)

**Step 3.** Prune flag: default keeps all 10 languages (feature parity); `MODELS=minimal bash build.sh` packs only en+zh(+es) → ~3–4 MB raw. Never silently drop languages in a shipping build.

**Step 4.** Build + measure: models on-disk ≈ 3.5–7 MB (all langs) / ~3 MB (minimal); arm64 APK ≈ 40–44 MB (all) / ~37 MB (minimal).

**Gate:** auto-detect routes mixed 中英 text correctly on-device (mixed-sentence speak switches voices mid-stream); `model_pack.py --verify` passes for every packed language; host probe (`tools/model_pack.py --verify --lang all`) returns correct detection for a fixed 10-language corpus.

---

## Phase 4 — In-core voice compression (THE main event, −20 to −30 MB)

> **SUPERSEDED 2026-09-22 (measured, user-approved re-baseline):** this phase's premise — "zip squeezes the .so 2.3:1, deflate-inside-core compounds to 3.9:1" — was tested against the shipped artifact: `zip -9` already compresses the whole lib at **2.72:1** (117.8 MB → 43.3 MB member), and re-deflating `.rodata` (65.6 MB → 28.1 MB zlib9) then re-zipping yields only **−174 KB on-disk** (0.4%). The outer zip captures the same redundancy the plan wanted to capture in-core; double-deflate does not compound. Remaining real on-disk levers measured: symbol-strip −1.3 MB (landed), legacy-JSON-prune −7.7 MB (landed). Keeping this section as research (per-language lazy libs of P7 are the real path to ~15 MB).

**Files:**
- Modify: `oracle/fitter.py` (or new `tools/compress_voice.py`) — compress tables at build time
- Modify: `native/…` voice-table loader — inflate on first use
- Modify: `oracle/chs_smoke.c` (or add `oracle/decompress_smoke.c`) — round-trip gate

**Why:** the 118 MB `.so` is raw dumped tables from the 117 MB chsrom DLL; zip already squeezes it 2.3:1 inside the APK, which means the data is highly redundant. Storing it zlib-compressed *inside the core* and inflating at load moves that 2.3:1 permanently into the shipped payload (deflate-inside-APK-zip compounds to ~3.9:1 effective).

**Step 1.** Wrap emitted table C arrays: zlib each table at build time (python `zlib.compress`), emit `oracle_chs.c` carrying compressed blobs; add `zlib_inflate()` in the native loader at init (zlib ships with the NDK toolchain — no new external dep). Inflate **lazily, per table, on first use** — not all at boot — to cap startup memory; keep a `#define VOICE_TABLE_CACHE_FILE` escape hatch (decompress-to-app-cache once, mmap after) if inflate-at-every-startup shows measurable TTS latency on a low-end device.

**Step 2.** Round-trip gate in smoke: inflate → `memcmp` against the pre-compression reference tables (keep the uncompressed artifact from the last build as CI A/B). Synthesis must be bit-identical to pre-change output.

**Step 3.** Build + measure: raw core 118 MB → ~55–60 MB; arm64 APK ≈ 20–28 MB; arm32 ≈ 20–26 MB; universal ≈ 35–45 MB.

**Step 4.** On-device: English + Chinese sound identical to the current build (A/B on Pixel, same sentence, spoken twice — record both, compare by ear + golden file bytes); Chinese voice responds to speed/pitch settings changes (those paths sit above the table layer — quick menu check).
**Step 5 — On-the-fly speech-parameter reactivity (user directive 2026-09-22, part of this phase's gate):** rate AND pitch must apply LIVE, in both directions, with no restart and no re-init:
- App slider rate↑ → engine speed/rate rises immediately (audible within the current utterance, not on the next one).
- App slider pitch moves → pitch changes immediately, same way.
- TalkBack/system speech rate change (Settings.Secure TTS_DEFAULT_RATE/PITCH) → engine follows immediately.
- UI state stays in sync both ways: the app's sliders always reflect the engine's current rate/pitch, and system/TalkBack slider positions mirror the app's values (round-trip, no drift on restart).
- If any direction is missing or lazy, fix it: audit the settings→engine propagation path (child card `settings-params`, §Ladder) and land the fix on the implement track while the parent validates P1–P2 gates. TalkBack is ground truth for what "visible slider" means.

**Gate:** `chs_smoke` round-trip passes **and** on-device synthesis golden-equal (bytes) **and** settings-driven voice changes still audible.

**Execution note:** this is the one phase where a naive flash-tier sub-agent would do real damage (native loader, memory discipline, machine-code table layout). If delegated at all → pin K3 (see §Ladder tiers); otherwise parent-implemented with a child doing only the Python compression wrapper.

---

## Phase 5 — Emoji expander: data-driven + stronger fallback (function win, ~size-neutral)

**Files:**
- Modify: `src/com/xw/vvtts/utils/EmojiExpander.kt` (3,853 lines of hardcoded names today)
- Create: `tools/gen_emoji.py` (CLDR emoji-test.txt → compact Kotlin table)

**Step 1.** Generate the table at build time from Unicode CLDR (same source the current file was hand-rolled from): output a compact `HashMap` literal or asset — removes 270 KB of hand-maintained Kotlin source, keeps every current name (diff the generated table against today's literals: byte-for-byte name equality required).

**Step 2.** Add `Character.getName(codepoint)` fallback for any emoji outside the generated table (Android's built-in Unicode names — zero data cost), and drop bare ZWJ/VS16/skin-tone components before hitting the engine (current behavior, preserved).

**Step 3.** Verify on device: TalkBack+TTS reads common emoji (🤣, 🫡, 🏳️‍🌈, 👍🏽) with sane expansions and no pauses/silence on unknown glyphs. Accessibility is the user's ground truth — his brother samples a fixed 10-emoji list; a TalkBack sweep over a rainbow + ZWJ test string must produce speech for every glyph.

**Gate:** no regression in the 10-emoji sample list; no silence on unknown glyphs; generated table == hand-rolled table for all existing entries.

---

## Phase 6 — Optional: D8 shrink of remaining dex (risky, guarded trial)

**Files:** `build.sh` (D8 invocation), new `proguard-rules.pro`

**Step 1.** Trial only: run D8 `--release` with keep-rules for `com.xw.vvtts.**`, all `native` JNI entry points, and lingua's classes (`-keep class com.github.pemistahl.lingua.**`).

**Step 2.** Measure + runtime-test. Expected: −1 to −3 MB. If lingua breaks (reflection), revert immediately — Phase 1 already delivered the real dex win.

**Gate:** full functional pass on-device, both ABIs, or revert. (User's working answer to qu.2 is "skip unless we need the last bytes" — treat as default-skip.)

---

## Phase 7 — Long-term research (documented, not scheduled)

**OG-style per-language voice libs:** the original packs each voice as its own 1–2 MB `.so` (`libEloquenceENG.so`, `libEloquenceDEU.so`, …). Re-architecting our single 118 MB core into per-language cores + lazy load could reach the OG's actual footprint (sub-15 MB APK) and enable "voice pack" downloads. Depends on the oracle/Ghidra reverse-engineering of the voice table format — the already-open research thread, not a build task. Keep open; revisit after Phases 1–4 land.

---

## §Re-baseline (2026-09-22, user "Part 1 part 3 only")

**Why:** size targets were set on the assumption that libs ship ~2.3:1-compressed. Measurement of the post-P2 artifact showed this was already beaten by Phase 2's zip -9 and that in-core deflate cannot compound with it.

**Evidence (measured on vvttts-arm64-v8a.apk @ a45f0b4+P2+P3):**
- lib member compressed at **2.72:1** (raw 117,775,952 → 43,335,111 B) — deflate level effectively banked
- `.rodata` = 65.6 MB of real data (256 distinct bytes); zlib9 → 28.1 MB (0.408)
- full double-deflate simulation: member 43,335,111 → 43,161,092 B (**−174 KB, +0.4%**)
- symtab+strtab ≈ 5.5 MB raw → **−1.3 MB on-disk** via `llvm-strip --strip-unneeded`
- legacy lingua JSONs riding in APK alongside `models.elqm` (validated byte-identical by P3 gold-compare): **−7.7 MB on-disk** by shipping only elqm

**New targets:** arm64 ≈ 55–56 MB; universal ≈ 95–96 MB (both after push; exact from CI size report + `APK_ON_DISK`).

**New gate additions:** build_native.sh strips before the `file`/`nm -D` checks (JNI dynsym must survive — checked each build); build.sh asserts JSON-pruned packaging. Phase 4/5/6 exec notes archived here for research; nothing shipped by them was reverted (they never shipped).

---

## §LADDER — Laddered execution with sub-agents (the speed architecture)

### Why
Every phase has a hard serial dependency on its own gate: we must measure phase N's APK before phase N+1's numbers mean anything (sizes are cumulative — 5 changes in one APK = no attribution). But *implementation* of phase N+1 does not depend on phase N's gate. So: **while the parent validates phase N, a sub-agent builds phase N+1 on a side branch. The ladder's rungs are gates; the climb is parallel.**

### The DAG

```
TIME ─────────────────────────────────────────────────────────────►
GATE TRACK (parent owns, strictly serial, one build at a time):
  [P1 build+measure] → [P1 VALIDATE] → [P2 build] → [P2 VALIDATE] → [P4 build] → [P4 VALIDATE] → ...
IMPLEMENT TRACK (sub-agents, one child at a time, branch-per-phase):
                 └─ child: P2 impl ──┐ (merges only after P1 gate ✓)
                                    └─ child: P3 impl ──┐ (merges after P2 gate ✓ / folds into P4 queue)
PREP TRACK (sub-agents, parallel, touches shipping APK NOTHING):
  [x86_64 test variant]  [arm32 lab workflow]  [size-report tooling]
  [model_pack.py + verify]  [gen_emoji.py]  [manifest/extractNativeLibs audit]
```

- **GATE TRACK:** parent only. One shipping-APK change is built and measured per run — this is what keeps the size ledger honest. Never mix two phase changes into one shipped build.
- **IMPLEMENT TRACK:** one child, one phase, on a branch cut from the **last validated commit** (never from in-flight trunk). When its phase is next in line and the previous gate passed, the child's diff merges and gets built. If its phase gets reordered/skipped, the child is steered or stopped (its work is a branch — nothing lost).
- **PREP TRACK:** fully parallel children producing *enablers*: tools, workflows, test variants. They touch zero shipped bytes, so they can all run while the gate track crawls. Their outputs are consumed by later phases (model_pack.py by P3, lab variants by every validation, etc.).

### Concurrency limit (hard operational rule)
Rate limits are real and measured: parallel child batches instant-429. **Max 2 children in flight at any moment** (one implement-track, one prep-track). A third queues until one reports. This still gives the ladder exactly what it's for: the next rung is being built while the current one is validated.

### Sub-agent task cards (dispatch = copy the card into `delegate_task` context verbatim)

**Card: size-report** (Phase 0, prep) — flash-tier safe.
Goal: append the size-report block to end of `build.sh` at repo /root/eloquence-re/eloquence-android (absloute paths in the file are fine), matching this exact awk over `unzip -l vvtts_signed.apk`: sum `classes*.dex`, `lib/`, `language-models/` entries and print `dex=N lib=N models=N total=N` plus `APK_ON_DISK=N`. Do NOT modify any other line. Output: the diff and the printed report for a locally-built `vvttts_signed.apk` if one exists at repo root, else the final bash block. Report: yes/stale/no.

**Card: manifest-audit** (Phase 2, prep) — flash-tier safe.
Goal: read /root/eloquence-re/eloquence-android/AndroidManifest.xml; report (1) current `android:extractNativeLibs` value or absence, (2) `targetSdkVersion`/`minSdkVersion`, (3) whether anything in the app (BroadcastReceiver/Service docs, TTS engine setup) would plausibly require unpacked `.so` files — search src/ for `System.loadLibrary`, `getApplicationInfo().nativeLibraryDir`, exec of native paths. Output: findings list + recommendation with rationale. No code changes.

**Card: model_pack.py** (Phase 3, prep) — flash-tier safe, parent reviews heavily.
Goal: write /root/eloquence-re/eloquence-android/tools/model_pack.py per Phase 3 Step 1 spec: read `language-models/<lang>/*.json` (format: JSON maps of `"ngram" → "<freq>"` strings), emit one packed binary per language — magic `VVN1`, lang tag, count, offset to sorted UTF-8 length-prefixed token table, then varint counts. Must include `--verify` (round-trip key-set equality vs input JSON, exit 1 on mismatch) and `--lang` filter for minimal mode. Run it on the real language-models dir; assert output totals ≤ 45% of input bytes. Output: tool path + per-language input→output byte table.

**Card: lab-variant-builds** (Phase 0/2 prep, + every phase) — flash-tier safe (yaml + one line in build_native.sh).
Goal: in /root/eloquence-re/eloquence-android add CI jobs so every run artifacts: `vvttts-x86_64.apk` (test-only; never listed as shipping) and the existing arm64/arm32/universal. Reuse the existing `ABI=x86_64 bash build_native.sh`+`build.sh` pattern already proven by the x86_64 smoke jobs. Output: build.yml diff + job names.

**Card: arm32-lab-workflow** (Phase 0/2 prep) — flash-tier safe.
Goal: in /root/shevery-emu-lab add a second workflow `arm32-session.yml` copied from `emu-session` (emu.yml) but with `arch: armeabi-v7a` and `api-level: 25`, plus a `mode: park` equivalent (park only — the shevery test script must NOT run; vvttts drives it externally via adb). Name it so the lab README's "manual runs only" stance is preserved. Output: workflow file diff.

**Card: gen_emoji.py** (Phase 5, prep) — flash-tier safe.
Goal: write /root/eloquence-re/eloquence-android/tools/gen_emoji.py that reads a CLDR emoji-test.txt (path given as arg) and emits a Kotlin `HashMap<String,String>` literal matching the current hand-rolled table's structure in EmojiExpander.kt (read that file's table section first). The generator must also emit `--diff` mode comparing generated names to the existing file's names (report mismatches, change nothing). Output: tool + mismatch report (expected: zero or documented).

### Tiers (which child is allowed to do what, per measured model strengths)
- **Flash-tier children (V4Pro/Flash):** Python tools, bash, YAML workflows, audits, table generation, report scripts — any task where a bug is cheap and caught by the next gate.
- **K3-pinned children (Nvidia):** the ONE risky native task (Phase 4 loader) if delegated — pin via the delegation-pins skill; otherwise parent does it.
- **Gemini-cookie:** validation passes only (independent second-opinion on diffs/gates), never implementation.
- **Parent (me):** the gate track end-to-end — builds, measurements, device tests, golden compares, merge decisions. A gate is never delegated. (Rule from delegation practice: child summaries are self-reports; every artifact they claim is re-verified by the parent before it counts — file exists, byte sizes match, CI job green, EF: no trust without handle.)

### Merging and branches
- Every phase lands as its own commit on the working branch (main, per current workflow), built, measured, gated. Children's branches merge **only** after (a) their phase is next, and (b) the previous gate passed. Cherry-pick or merge — never let a child force-push anything.
- If a gate FAILS: stop, fix on the gate track first. The implement child for the *next* phase is steered to rebase onto the fixed commit or stopped — cascading work on top of a broken gate is how ladders collapse.
- If a child's phase is reordered or skipped (e.g. user drops P3): stop that child immediately (steer/stop), keep its branch for later.

### Ladder timeline (estimated wall-clock, one CI build ~20–30 min + validation)
Prep track starts immediately (P0→P2 enablers, all parallel within the 2-child cap) → P1 lands at first build → ladder alternates implement-while-validate through Phase 4 → P2/P3/P4 gates ≈ 2–3 CI cycles after their merges → Phase 5 folds in during P4 validation. From greenlight: **~6–10 CI cycles to a ~20 MB arm64 APK** validated on both ABIs, versus 6+ fully serial cycles for the same work.

---

## Validation plan — 32-bit AND 64-bit (the "validate it entirely" requirement)

Every phase ships BOTH `vvttts-armeabi-v7a.apk` and `vvttts-arm64-v8a.apk` (+ universal; + x86_64 test variant). Per phase, run the checks in this order — the parent owns V1/V3/V4; V2 is where the ladder's parallel children and the emu-lab come in.

**V1 — Static ABI verification (CI-fail gate, automated, every build):**
```bash
unzip -p vvttts-armeabi-v7a.apk lib/armeabi-v7a/libvvttts_core.so > /tmp/a32.so
unzip -p vvttts-arm64-v8a.apk  lib/arm64-v8a/libvvttts_core.so  > /tmp/a64.so
readelf -h /tmp/a32.so | grep -E "Class|Machine"   # ELF32 / ARM
readelf -h /tmp/a64.so | grep -E "Class|Machine"   # ELF64 / AArch64
unzip -t vvttts-armeabi-v7a.apk && unzip -t vvttts-arm64-v8a.apk
apksigner verify --print-certs vvttts-armeabi-v7a.apk; apksigner verify --print-certs vvttts-arm64-v8a.apk
aapt dump badging vvttts-armeabi-v7a.apk | grep native-code   # lists armeabi-v7a
```
Expected: 32-bit APK contains ONLY `armeabi-v7a` (ELF32/ARM); 64-bit contains ONLY `arm64-v8a` (ELF64/AArch64); both signed and verified. Promote to a CI job in Phase 0/2 prep ("V1 job").

**V2 — Emulator lab runtime smoke (user's Shevery emu-lab, /root/shevery-emu-lab):**
The lab's `emu-session` workflow (KVM `ubuntu-latest` runner, `reactivecircus/android-emulator-runner@v2`, `api_level`/`target`/`arch` inputs, Pixel 6 profile) connects to the tailnet; drive it from here with `adb -H <runner-ts-ip>`. Dispatch from the lab dir:
```bash
gh workflow run emu-session -f api_level=35 -f target=google_apis_playstore -f mode=park -f hold_minutes=90
```
Park mode installs nothing — push APKs in from outside: `adb -H <ip> install -r <local-apk>`, `pm grant` runtime perms, start synthesis, `logcat` sweep for `FATAL EXCEPTION|Fatal signal|SIGSEGV|backtrace:`, confirm app stays foreground. Same crash-repro/boot-receiver discipline as ci-emulator-lab.
- **Which APK runs on which image (hard rule):** lab images are `x86_64`. Our shipped APKs are ARM-only — ARM-only APKs on x86_64 images ghost-install (`adb install` says Success, `pm path` empty, "REPLACED but missing application info") or fail with `INSTALL_FAILED_NO_MATCHING_ABIS`. Three lab lanes:
  1. **x86_64 behavior lane (every commit):** the test-only `vvttts-x86_64.apk` (approved by user, 2026-09-21, testing only — Shevery never needed it because its APKs ship all four ABIs; vvttts ships ARM-only and x86 runs only x86). Exercises every dex/Java/Kotlin path, JNI bridge, TTS service lifecycle in a real Android runtime.
  2. **arm32 lane (true 32-bit ARM, CI-side):** the `arm32-session.yml` lab workflow (`arch: armeabi-v7a`, `api-level: 25` — the last arm32 image line) + a **test-only flexed build** `--min-api 25` (shipped APK stays 28). This boots the *actual* `armeabi-v7a` `libvvttts_core.so` — genuine 32-bit ARM machine code in CI. User decision (2026-09-21): *"do whatever you have to do to make arm32 work"* — this is the path; if API-25 boots prove flaky/slow on the x86_64 host, fall back to the device lane (V3).
  3. **arm64:** no emulator path exists (no linux/aarch64 emulator binary, no arm64-v8a image on x86_64 hosts) — Pixel device is the arm64 runtime lane, full stop.

**V3 — On-device runtime (ground truth, both ABIs):**
- Install `vvttts-arm64-v8a.apk` on Pixel; speak English + Chinese + mixed; verify auto-detect, voice switching, settings changes.
- Install `vvttts-armeabi-v7a.apk` on a 32-bit ARM-capable device (brother's old phone) — the definitive 32-bit gate; the arm32 lab emulator (V2.2) is the CI-side complement.
- **Synthesis golden compare:** before/after each phase, dump the same sentence to the same audio file on the same device, compare byte-for-byte (voice tables must be bit-identical; only compression of *storage* may change, never *output*).

**V4 — Host oracle smoke (already in CI):** `chs-smoke` on the arm64 runner links openevv + chs tables directly and validates synthesis logic + audio math — catches table corruption from Phase 4 before any device install. Extended with the compression round-trip gate (Phase 4 Step 2).

**V5 — On-the-fly parameter reactivity (user directive 2026-09-22; required from Phase 4's device pass onward):** rate↑ applies instantly mid-utterance; pitch slider applies instantly; TalkBack/system rate change applies instantly without restart; app sliders and system sliders stay mutually in sync. Reproduce-first: if a direction is dead or lazy, code the live path (child card `settings-params`) before P4 closes. TalkBack behavior is the ground truth.

**Per-phase validation matrix** (which lanes must be green before the phase is recorded as done):

| Phase | V1 static | V4 smoke | V2 lab x86_64 | V2 arm32 lab | V3 device | Note |
|---|---|---|---|---|---|---|
| P0 | ✓ (report sanity) | ✓ | — | — | — | baseline only |
| P1 | ✓ | ✓ | ✓ | as available | ✓ (detection) | lingua runtime is the risk |
| P2 | ✓ | ✓ | ✓ | as available | ✓ | manifest change needs device+reboot test |
| P3 | ✓ | ✓ | ✓ | as available | ✓ (mixed 中英) | `--verify` must pass all langs |
| P4 | ✓ | ✓ (round-trip gate) | ✓ | ✓ (arm32 core!) | ✓ (golden bytes) | the arm32 lane matters most here |
| P5 | ✓ | ✓ | ✓ | as available | ✓ (emoji list) | TalkBack ground truth |

---

## Risk register

| Risk | Mitigation |
|---|---|
| Phase 4 breaks Chinese synthesis | Round-trip memcmp gate in chs_smoke BEFORE shipping; keep old uncompressed .so as CI artifact for A/B |
| Removing dead jars breaks lingua at runtime | Compile classpath already excludes them; guard = on-device detection smoke (V2/V3) |
| R8 shrink breaks lingua reflection | Phase 6 optional + guarded; revert on first break; Phase 1 already captures 80% of dex savings |
| 32-bit validation flaky in CI | Static ELF checks are the automated floor; arm32 lab emulator (V2.2) is CI-side; brother's phone is the definitive gate; user explicitly approved "whatever it takes" for arm32 |
| Model pruning hurts auto-detect | Default keeps all 10 langs; `MODELS=minimal` opt-in and measured |
| ZIP -9 or extractNativeLibs breaks install | Decompress-time/extract changes are runtime-tested on device before keeping; revertible one-liners |
| Sub-agent self-reports ≠ truth | Parent re-verifies every artifact handle (file exists, bytes match, CI green) before it counts; children never push/merge |
| Flash-tier child breaks Kotlin/native code | Tier rule: no flash child touches Phase-4 loader or core Kotlin logic; those are parent or K3-pinned |
| Parallel children hit rate limits | Hard cap 2 children in flight; a third queues (measured: parallel batches = instant 429s) |
| CI keystore is per-build throwaway | Fine for sideload; if Play ever matters, release identity is a separate decision (never auto-sign Play uploads with it) |

## Open questions (user decisions)

1. **Model languages:** keep all 10 (feature parity) or ship en+zh(+es) minimal via `MODELS=minimal` build flag? (I recommend: default all, flag for minimal — already the plan's default.)
2. **Phase 6 R8:** worth the risk for −1 to −3 MB after Phase 1? (I recommend: skip unless we need the last bytes — default-skip.)
3. **Delivery target — DECIDED 2026-09-22 (re-baselined, §Re-baseline):** ~20–28 MB unreachable without dropping content; arm64 ≈ 55–56 MB accepted (P1–P3 + strip + JSON-prune). Phase 4 shelved as zip-redundant; Phase 7 (per-language libs) remains the sub-15 MB research path.
4. **Lab test variants — DECIDED (user, 2026-09-21):** test-only `ABI=x86_64` APK in the CI matrix, never a shipping artifact. (Shevery comparison: its APKs ship all four ABIs — x86 image runs them natively; vvttts ships ARM-only → ghost-install without the variant.)
5. **arm32 testing — DECIDED (user, 2026-09-21):** *"do whatever you have to do to make arm32 work."* Implemented as: test-only `--min-api 25` build + `arm32-session.yml` lab workflow (api 25, armeabi-v7a) + brother's phone as backstop. Shipped APK stays `--min-api 28`.

## Execution order (laddered)

1. **Prep track launches immediately** (≤2 children): size-report, manifest-audit, model_pack.py, gen_emoji.py, lab-variant-builds, arm32-lab-workflow — all against the baseline commit.
2. **Gate track:** Phase 0 report → Phase 1 (dead jars) → Phase 2 (zip-9/res/manifest) → **Phase 4 (core compression — do before 3; it dominates size)** → Phase 3 (model packing) → Phase 5 (emoji) → (Phase 6 trial, default-skip) → Phase 7 backlog.
3. Implement track rides along: while the parent validates phase N, the child implements phase N+1 (or the next prep output gets consumed). One build in flight at a time; numbers stay attributable.
4. **Workflow purge (user directive, 2026-09-22):** delete the one-shot research/probe workflows that served their purpose and now fail on every push — `arm64-probe.yml`, `lpta-trace.yml`, `lpta-ghidra.yml`, `dll-survey.yml`, `uncosp-repro.yml` (git rm in `.github/workflows/`). KEEP: `build.yml` (the gate), `emu-test.yml` (functional crash test), `oracle-assemble/dump/fanout.yml` (Phase-4 machinery). All deletions recoverable from git history — intentional, not lossy.
5. **Plan re-read discipline:** full plan re-read at every phase boundary and whenever a user directive changes scope (e.g. this purge) — done 2026-09-22.
6. **Live rate/pitch reactivity (user directive 2026-09-22):** every settings surface must affect speech on the fly — app sliders (rate, pitch) and TalkBack/system sliders, both directions, immediate, always in sync (§P4 Step 5, V5). Ladder: child codes the `settings-params` fix on its branch while the parent walks the P1/P2 gates; the fix merges before Phase 4's device pass and is verified on the Pixel with TalkBack as ground truth.