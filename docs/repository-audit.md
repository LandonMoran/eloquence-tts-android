# Repository audit and issue resolution

## Publication status

The consolidation branch contains the application fixes and the workflow changes from PR #287. The earlier workflow-write rejection was resolved by merging the same workflow content already present on that repository branch. All seven contract tests pass together. PRs #240, #241 and #286 are also integrated into the branch history with their accepted fixes preserved and the tail-fade correction retained.

## Scope and limits

This pass inventoried 2,277 tracked paths at base `7e20b6b4c1084fd2b4a2b3a5d605ffaf1669ed63`. Review focused on the executable Android application, JNI/ECI boundary, native ownership, settings, update/install flow, build/release workflows and source generators. Model/resource data received format and regeneration checks. Historical `oracle/rom_lift` decompilation, Ghidra output, vendored binaries and language tables were inventoried and inspected by role; they were **not individually proven correct or all manually reviewed line by line**.

The source scan parsed 85 valid Python files, 52 JSON files and 20 XML files, and checked 11 shell scripts; the only Python syntax failure was an obsolete, unused `wt228_splice.py` migration script, now removed. Native C syntax checks passed for 316 translation units; unshipped Traditional Chinese/Korean lack generated rule headers, and the Windows port is outside the Android target. Actual Android compilation and APK validation run in CI, as required by this repository.

## Open issues

All 45 issues open at the start of this work are mapped below. Closing keywords belong to the replacement PR, so issues close upon merge.

| Issue | Change | Verification |
|---|---|---|
| [#239](https://github.com/LandonMoran/eloquence-tts-android/issues/239) eliminate crackle from abrupt short-utterance PCM boundaries | JNI admission/cancellation, bounded buffers, checked ECI compatibility and PCM edges | ASan/UBSan bridge, oracle and compatibility suites |
| [#242](https://github.com/LandonMoran/eloquence-tts-android/issues/242) correct install-status signature permission declaration | Update flow ownership, Long/semantic versions, confirmation resolution and signature permission | Host updater/versions; permission contract; device installer check outstanding |
| [#243](https://github.com/LandonMoran/eloquence-tts-android/issues/243) derive CHECK_TTS_DATA voice list from the single voice registry | Engine/service lifecycle, SynthesisBudget, VoiceRegistry, text normalization and dead-code removal | Full Kotlin compile; host lifecycle/voices/text; native/registry contracts |
| [#244](https://github.com/LandonMoran/eloquence-tts-android/issues/244) fail engine initialization when ECI setup calls fail | JNI admission/cancellation, bounded buffers, checked ECI compatibility and PCM edges | ASan/UBSan bridge, oracle and compatibility suites |
| [#245](https://github.com/LandonMoran/eloquence-tts-android/issues/245) reject control calls on failed or retired synthesis sessions | JNI admission/cancellation, bounded buffers, checked ECI compatibility and PCM edges | ASan/UBSan bridge, oracle and compatibility suites |
| [#246](https://github.com/LandonMoran/eloquence-tts-android/issues/246) make dialect configuration match the shipped native registry | Engine/service lifecycle, SynthesisBudget, VoiceRegistry, text normalization and dead-code removal | Full Kotlin compile; host lifecycle/voices/text; native/registry contracts |
| [#247](https://github.com/LandonMoran/eloquence-tts-android/issues/247) fail the current utterance when required voice configuration writes fail | Engine/service lifecycle, SynthesisBudget, VoiceRegistry, text normalization and dead-code removal | Full Kotlin compile; host lifecycle/voices/text; native/registry contracts |
| [#248](https://github.com/LandonMoran/eloquence-tts-android/issues/248) make stop cancellation generation-safe | JNI admission/cancellation, bounded buffers, checked ECI compatibility and PCM edges | ASan/UBSan bridge, oracle and compatibility suites |
| [#249](https://github.com/LandonMoran/eloquence-tts-android/issues/249) reject invalid voice numbers instead of silently clamping them | JNI admission/cancellation, bounded buffers, checked ECI compatibility and PCM edges | ASan/UBSan bridge, oracle and compatibility suites |
| [#250](https://github.com/LandonMoran/eloquence-tts-android/issues/250) preserve ECI failure results in the compatibility shim | JNI admission/cancellation, bounded buffers, checked ECI compatibility and PCM edges | ASan/UBSan bridge, oracle and compatibility suites |
| [#251](https://github.com/LandonMoran/eloquence-tts-android/issues/251) validate PackageInstaller confirmation intent resolution | Update flow ownership, Long/semantic versions, confirmation resolution and signature permission | Host updater/versions; permission contract; device installer check outstanding |
| [#252](https://github.com/LandonMoran/eloquence-tts-android/issues/252) prevent semantic-version packing overflow and use one comparison result | Update flow ownership, Long/semantic versions, confirmation resolution and signature permission | Host updater/versions; permission contract; device installer check outstanding |
| [#253](https://github.com/LandonMoran/eloquence-tts-android/issues/253) make Lingua preload lifecycle-owned and cancellable | Engine/service lifecycle, SynthesisBudget, VoiceRegistry, text normalization and dead-code removal | Full Kotlin compile; host lifecycle/voices/text; native/registry contracts |
| [#254](https://github.com/LandonMoran/eloquence-tts-android/issues/254) correctly cache parsed VoiceConfig XML and avoid reparsing every utterance | MirroredPreferences, VoiceConfig/Profile, DictionaryImport; atomic CE/DE writes, cache, validation and bounded imports | Host preferences/dictionary regressions |
| [#255](https://github.com/LandonMoran/eloquence-tts-android/issues/255) fall back to device-protected preferences when credential storage is inaccessible | MirroredPreferences, VoiceConfig/Profile, DictionaryImport; atomic CE/DE writes, cache, validation and bounded imports | Host preferences/dictionary regressions |
| [#256](https://github.com/LandonMoran/eloquence-tts-android/issues/256) serialize mirrored preference rollback to prevent lost concurrent settings | MirroredPreferences, VoiceConfig/Profile, DictionaryImport; atomic CE/DE writes, cache, validation and bounded imports | Host preferences/dictionary regressions |
| [#257](https://github.com/LandonMoran/eloquence-tts-android/issues/257) settle a previous synthesis before replacing session-owned text and PCM state | JNI admission/cancellation, bounded buffers, checked ECI compatibility and PCM edges | ASan/UBSan bridge, oracle and compatibility suites |
| [#258](https://github.com/LandonMoran/eloquence-tts-android/issues/258) validate engine parameters against ECI parameter count and ranges | JNI admission/cancellation, bounded buffers, checked ECI compatibility and PCM edges | ASan/UBSan bridge, oracle and compatibility suites |
| [#259](https://github.com/LandonMoran/eloquence-tts-android/issues/259) reconcile Kotlin pitch range with native ECI pitch clamping | Engine/service lifecycle, SynthesisBudget, VoiceRegistry, text normalization and dead-code removal | Full Kotlin compile; host lifecycle/voices/text; native/registry contracts |
| [#260](https://github.com/LandonMoran/eloquence-tts-android/issues/260) keep version-code arithmetic in Long end-to-end | Update flow ownership, Long/semantic versions, confirmation resolution and signature permission | Host updater/versions; permission contract; device installer check outstanding |
| [#261](https://github.com/LandonMoran/eloquence-tts-android/issues/261) invalidate generated models.elqm when the packer changes | Pinned oracle inputs, validated fanout assembly, exact APK ABI checks, isolated signing and content fingerprints | Contracts, actionlint, regenerated asset comparison; Android CI |
| [#262](https://github.com/LandonMoran/eloquence-tts-android/issues/262) rebuild lingua-slim when the ElqmBridge source changes | Pinned oracle inputs, validated fanout assembly, exact APK ABI checks, isolated signing and content fingerprints | Contracts, actionlint, regenerated asset comparison; Android CI |
| [#263](https://github.com/LandonMoran/eloquence-tts-android/issues/263) scope production signing secrets to release builds | Pinned oracle inputs, validated fanout assembly, exact APK ABI checks, isolated signing and content fingerprints | Contracts, actionlint, regenerated asset comparison; Android CI |
| [#264](https://github.com/LandonMoran/eloquence-tts-android/issues/264) remove fallback signing passwords from build-apk workflow | Pinned oracle inputs, validated fanout assembly, exact APK ABI checks, isolated signing and content fingerprints | Contracts, actionlint, regenerated asset comparison; Android CI |
| [#265](https://github.com/LandonMoran/eloquence-tts-android/issues/265) cap Chinese oracle PCM allocation before malloc | JNI admission/cancellation, bounded buffers, checked ECI compatibility and PCM edges | ASan/UBSan bridge, oracle and compatibility suites |
| [#266](https://github.com/LandonMoran/eloquence-tts-android/issues/266) bound imported/stored dictionary size and stream SAF imports | MirroredPreferences, VoiceConfig/Profile, DictionaryImport; atomic CE/DE writes, cache, validation and bounded imports | Host preferences/dictionary regressions |
| [#267](https://github.com/LandonMoran/eloquence-tts-android/issues/267) cache compiled user-dictionary rules between utterances | MirroredPreferences, VoiceConfig/Profile, DictionaryImport; atomic CE/DE writes, cache, validation and bounded imports | Host preferences/dictionary regressions |
| [#268](https://github.com/LandonMoran/eloquence-tts-android/issues/268) fall back to preset defaults for invalid stored voice parameters | MirroredPreferences, VoiceConfig/Profile, DictionaryImport; atomic CE/DE writes, cache, validation and bounded imports | Host preferences/dictionary regressions |
| [#269](https://github.com/LandonMoran/eloquence-tts-android/issues/269) clamp persisted VoiceProfile preset IDs before exposing them | MirroredPreferences, VoiceConfig/Profile, DictionaryImport; atomic CE/DE writes, cache, validation and bounded imports | Host preferences/dictionary regressions |
| [#270](https://github.com/LandonMoran/eloquence-tts-android/issues/270) actually apply the persisted DSP mode or remove the dead setting | Engine/service lifecycle, SynthesisBudget, VoiceRegistry, text normalization and dead-code removal | Full Kotlin compile; host lifecycle/voices/text; native/registry contracts |
| [#271](https://github.com/LandonMoran/eloquence-tts-android/issues/271) remove or quarantine dead legacy synthesis paths and unused state | Engine/service lifecycle, SynthesisBudget, VoiceRegistry, text normalization and dead-code removal | Full Kotlin compile; host lifecycle/voices/text; native/registry contracts |
| [#272](https://github.com/LandonMoran/eloquence-tts-android/issues/272) check and handle native sample-rate parameter failures | Engine/service lifecycle, SynthesisBudget, VoiceRegistry, text normalization and dead-code removal | Full Kotlin compile; host lifecycle/voices/text; native/registry contracts |
| [#273](https://github.com/LandonMoran/eloquence-tts-android/issues/273) atomically mirror language settings into device-protected storage | MirroredPreferences, VoiceConfig/Profile, DictionaryImport; atomic CE/DE writes, cache, validation and bounded imports | Host preferences/dictionary regressions |
| [#274](https://github.com/LandonMoran/eloquence-tts-android/issues/274) return language-appropriate sample text from GET_SAMPLE_TEXT | Engine/service lifecycle, SynthesisBudget, VoiceRegistry, text normalization and dead-code removal | Full Kotlin compile; host lifecycle/voices/text; native/registry contracts |
| [#275](https://github.com/LandonMoran/eloquence-tts-android/issues/275) normalize ISO-3 language codes before onLoadLanguage warmup | Engine/service lifecycle, SynthesisBudget, VoiceRegistry, text normalization and dead-code removal | Full Kotlin compile; host lifecycle/voices/text; native/registry contracts |
| [#276](https://github.com/LandonMoran/eloquence-tts-android/issues/276) correct VvttsCore engine-parameter documentation | Correct native parameter documentation | Source/native range audit |
| [#277](https://github.com/LandonMoran/eloquence-tts-android/issues/277) unregister preference listeners when VvTtsService is destroyed | Engine/service lifecycle, SynthesisBudget, VoiceRegistry, text normalization and dead-code removal | Full Kotlin compile; host lifecycle/voices/text; native/registry contracts |
| [#278](https://github.com/LandonMoran/eloquence-tts-android/issues/278) serialize concurrent update-check flows before replacing currentDialog | Update flow ownership, Long/semantic versions, confirmation resolution and signature permission | Host updater/versions; permission contract; device installer check outstanding |
| [#279](https://github.com/LandonMoran/eloquence-tts-android/issues/279) preserve literal symbols after Chinese normalization budget is exhausted | Engine/service lifecycle, SynthesisBudget, VoiceRegistry, text normalization and dead-code removal | Full Kotlin compile; host lifecycle/voices/text; native/registry contracts |
| [#280](https://github.com/LandonMoran/eloquence-tts-android/issues/280) verify release APK universal-asset ABI contents match updater expectations | Pinned oracle inputs, validated fanout assembly, exact APK ABI checks, isolated signing and content fingerprints | Contracts, actionlint, regenerated asset comparison; Android CI |
| [#281](https://github.com/LandonMoran/eloquence-tts-android/issues/281) avoid retaining destroyed SettingsActivity through background update workers | Update flow ownership, Long/semantic versions, confirmation resolution and signature permission | Host updater/versions; permission contract; device installer check outstanding |
| [#282](https://github.com/LandonMoran/eloquence-tts-android/issues/282) restore non-blocking onStop cancellation after inline synthesis migration | Engine/service lifecycle, SynthesisBudget, VoiceRegistry, text normalization and dead-code removal | Full Kotlin compile; host lifecycle/voices/text; native/registry contracts |
| [#283](https://github.com/LandonMoran/eloquence-tts-android/issues/283) make oracle-assemble consume artifacts from the oracle-fanout run | Pinned oracle inputs, validated fanout assembly, exact APK ABI checks, isolated signing and content fingerprints | Contracts, actionlint, regenerated asset comparison; Android CI |
| [#284](https://github.com/LandonMoran/eloquence-tts-android/issues/284) pin and integrity-check the external oracle decompile inputs | Pinned oracle inputs, validated fanout assembly, exact APK ABI checks, isolated signing and content fingerprints | Contracts, actionlint, regenerated asset comparison; Android CI |
| [#285](https://github.com/LandonMoran/eloquence-tts-android/issues/285) make extra_logging use the same live settings source as the TTS service | MirroredPreferences, VoiceConfig/Profile, DictionaryImport; atomic CE/DE writes, cache, validation and bounded imports | Host preferences/dictionary regressions |

## Existing pull requests reviewed

- **#240**: retained the PCM boundary fix and corrected its tail fade, which left the last sample unchanged. Fade is also applied after resampling so emitted endpoints are exactly zero. Tiny buffers are covered by native regression tests.
- **#241**: incorporated the Direct Boot constructor fix into shared preference access, with inaccessible-CE fallback, atomic mirrored updates and serialization across processes.
- **#286**: incorporated the synthesis work budget so playback pacing does not exhaust the watchdog. The measurement is elapsed time waiting for synthesis, not CPU time.

These three PR heads and PR #287 are merged into the consolidation branch. Their GitHub PR status is not marked merged into main until the consolidated change lands.

## Additional findings addressed

- Replaced unsafe manual voice-slot indexing with the engine's canonical getter/copy functions; retained engine-owned allocation cleanup.
- Mapped Eddy's historical CSV row 9 to native standard row 5; preserved other presets.
- Enabled Latin cache reuse by comparing ThreadLocal values, rather than the ThreadLocal object.
- Applied dictionary rules to Chinese and used the shipped Polish module's UTF-8 input encoding.
- Removed nonexistent Android binding permissions that prevented system TTS discovery activities from being launched.
- Preserved PackageInstaller sessions while user confirmation is pending.
- Removed unsupported voice choices and corrected runtime/build documentation.

## Running checks

- `bash tests/native/run.sh`: JDK headers, GCC, ASan and UBSan required.
- `bash tests/host/run.sh`: set `ANDROID_JAR`, `KOTLINC_CP` (Kotlin 1.9.25) and `KXML_JAR` (kxml2 2.3.0). Compiles all application Kotlin and runs preferences, dictionary, voice/version, service lifecycle and updater ownership regressions. CI supplies these dependencies.
- `python3 tests/contracts.py`: native/registry parity, permission/signing contracts, malformed oracle pieces and exact APK ABI fixtures.
- `python3 tools/prepare_assets.py --verify`: regenerate models and the Lingua bridge and compare content.
- `python3 tools/audit_apk_abis.py`: inspect the four built APKs; CI runs this before upload. The universal artifact includes arm64-v8a, armeabi-v7a and x86_64.

## Release checks requiring a device or repository administration

Listen to short speech in all shipped languages; stress rapid TalkBack stop/restart and preset changes; verify speech after a reboot before first unlock; install an actual signed upgrade through the platform confirmation flow. Host stubs and sanitizers do not establish these hardware/system behaviors.

Configure the GitHub `release` environment with required reviewers, protected tag restrictions, and the production signing secrets. No production signing secret is used in ordinary branch/PR builds. This patch does not change repository environment protection settings.

## Follow-up audit

- Credential storage can throw from `Context.dataDir` even when UserManager reports unlocked. Reads now catch path-resolution failure before trying device storage; writes report failure rather than throwing out of the settings API.
- Dictionary import uses strict UTF-8/UTF-16 decoding, rejects malformed sequences and closes the stream, preventing silent replacement-character corruption.
- APK auditing rejects duplicate native-library ZIP entries, whose different payloads could otherwise collapse into one set entry and evade the exact-content check.

The follow-up adds regressions for all three cases.

## Closed-issue and history follow-up (2026-10-06)

The audit inventories all 225 issues (45 open, 180 closed), 570 commits reachable from the application branch before consolidation, and 2,315 current files at the initial follow-up checkpoint. CSV evidence ledgers are attached in the coding task Outputs. These are coverage records, not a claim that every historical revision was rebuilt or every reference/binary was semantically proven correct.

- **#43** was closed although `vv_find` still cast raw pointers. JNI now uses monotonic IDs, a synchronized registry, references held across operations, and deferred destruction. Stale IDs, repeated shutdown, concurrent shutdown during synthesis and control serialization have sanitizer coverage.
- **#187** remained incomplete after the lifecycle changes in `5db1a5b`: an epoch sampled only inside native open did not reject queued warmups, and cleanup could miss a retiring worker. Workers now retire before teardown and warmup validates its captured generation; tests cover queued work, shutdown during open and explicit reinitialization.
- **#99 / #52 / #69** remained incomplete in `cb79f1e`: the corpus loader bounded strings after `readLine()` allocated them, combining marks split words before normalization, and folding used the default locale. The parser now bounds each line during reading, preserves Unicode marks through tokenization, and folds corpus/input using Locale.ROOT.
- Dead-code removals: unused native-wrapper instance, Future/FutureTask/Callable imports, obsolete CF locale mapper, unused VoiceProfile context and redundant mutable sample-rate field. No supported voice, setting or speech feature was removed.

### Outstanding acceptance evidence

Issue #141's historical key is removed from the current tree but still exists in Git history. Rotation and whether distributed APKs used that identity require owner confirmation; this branch does not rewrite shared history or rotate production signing identity. Native initialization readiness (#138), Android service-rebind stress (#81/#97), device listening, reboot-before-first-unlock and signed installation still need Android/device acceptance evidence. Closure metadata is not treated as proof that these criteria passed.

## Additional adversarial pass

- Preserve preference edits made during Direct Boot after unlock. A device-storage marker keeps the newer DE copy authoritative until a successful unlocked transaction mirrors both files.
- Invalidate sessions on ECI clear/add/start failures so Kotlin retires a poisoned native handle rather than reusing it. Failure injection covers every operation and rejects subsequent controls/synthesis.
- Normalize embedded carriage returns before serializing dictionary entries so they cannot split the persisted record format.

These fixes have focused host/native regressions. New findings in this pass mean the repository is not claimed defect-free or "mostly bug-free"; device acceptance and signing-key rotation evidence remain outstanding.

The continuing audit also found CR-only imported dictionary files could merge records. The bounded importer now recognizes CR, LF and CRLF; tests cover all line endings with UTF-8, UTF-16LE and UTF-16BE BOMs. `docs/PR_HANDOFF.md` maintains the consolidated PR description; the PR is created from the authorized checkout rather than being blocked by the runtime.

## Open-issue reconciliation (2026-10-06, continuation)

Rechecked all **53 currently open issues** against consolidation commit
`64bc339` and this continuation. The original 45 issue implementations above
remain present. The eight later issues have the following status:

| Issue | Resolution | Regression evidence |
|---|---|---|
| #288 | **Implemented.** `release.yml` gains a `publish` job after `sign` that downloads `release-signed-apks` and runs `tools/publish_release.py` with the release tag, the signed directory and the aapt path. `contents: write` is granted only to the publication job; production signing secrets stay inside the protected `environment: release` signing job.  | Helper asset/version/create/update/error tests and workflow integration assertions pass; `actionlint` is clean. A real tag-triggered signed release has not been run yet; actual publication remains an acceptance check. |
| #289 | Existing engine serialization already queues warmup and synthesis on the same `SynthWorker.executor`; no new native lock is needed. | Host regression blocks synthesis, queues warmup, and verifies no native open can overlap it. Stop remains independent. |
| #290 | Service teardown releases its engine owner. Overlapping service instances are counted under the process engine lock; the last owner stops and shuts down the engine. The retirement executor also shuts down. | Host regression destroys a service during blocked synthesis, checks deferred handle closure, recreates a service, and verifies overlapping owners remain usable. |
| #291 | **Reported premise disproved.** Android Settings expects `TextToSpeech.LANG_AVAILABLE` (0), not `Activity.RESULT_OK` (-1), from `GET_SAMPLE_TEXT`. Preserve the existing result code. | Execute the actual sample activity with host Android boundary stubs; verify result code, localized sample extra, unsupported country/voice/variant, and finish. |
| #292 | Country-specific registry lookup now requires a shipped ISO-2/ISO-3 country match. Only absent/empty country requests fall back to the first language voice. | Every shipped ISO-2/ISO-3 locale, `spa/ARG`, `es/AR`, unknown country, language-only and case-insensitive lookup. |
| #293 | Require increasing Android versionCode and, when both names parse, increasing semantic version. The updater requires `<!-- android-version-code: N -->` in release notes. The installed #288 `publish` job runs `tools/publish_release.py`, which extracts this marker from signed APK manifests and checks all three assets; no real release has run yet, so the marker's first live use remains an acceptance check. Semantic tags without metadata fail closed; numeric legacy tags remain supported. | Lower/equal/higher Android codes, older/equal/newer semantic versions, Long codes, malformed/missing/duplicate metadata, publication tag/APK agreement. |
| #294 | Native shutdown now guarantees cleanup ownership after revoking the public handle. If deletion refuses, a process-owned queue retains the native session and retries independently of the Kotlin worker/service lifetime. | Native fault injection refuses deletion, repeats shutdown, rejects stale controls, then permits deletion and proves exactly one reclamation. |
| #295 | Failed initialization transfers its unpublished session to the same cleanup path. The cleanup worker must start before native allocation; session mutex initialization precedes ECI creation. | Fail callback, output-buffer and sample-rate setup while deletion also refuses; prove each retained session is eventually reclaimed. |

For #291, see AOSP's
[`TextToSpeechSettings.onSampleTextReceived`](https://github.com/aosp-mirror/platform_packages_apps_settings/blob/master/src/com/android/settings/tts/TextToSpeechSettings.java),
which tests `resultCode == TextToSpeech.LANG_AVAILABLE` before reading `sampleText`.
Returning `RESULT_OK` would make Settings discard the engine's localized sample.
This issue should be closed with that explanation, rather than changing the code to
match its proposed result constant.

The native cleanup queue deliberately retains sessions while the native engine
continues to refuse destruction. This prevents freeing live callback storage and
allows eventual recovery; it cannot force a permanently wedged engine to recover.

No release was published and no PR was merged during this continuation. The consolidated PR from this branch is open as a draft (see `docs/PR_HANDOFF.md`); #291 was closed with the AOSP `LANG_AVAILABLE` explanation, and #288 was reopened ahead of the PR's `Fixes #288` keyword so it closes when the PR lands. Device listening, reboot before unlock, actual signed installation and protected release environment configuration remain acceptance checks outside the host/native fixtures.
