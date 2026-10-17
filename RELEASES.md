# Release contract

## Current release note

**⚠️ Warning — what changed in v1.0.10:**

- **⚠️ Language support changed.** The app now advertises **10 locales**:
  **en-US, en-GB, de-DE, fr-FR, fr-CA, es-ES, es-MX, it-IT, ja-JP, pl-PL.**
  **Chinese (Simplified `zh-CN` and Traditional `zh-TW`) is removed.** Their
  speech, detection, engine dispatch, language models, oracle data, and all
  build/documentation references have been dropped. Devices set to a removed
  locale fall back to the closest remaining voice rather than producing
  Chinese speech.

  The engine submodule carries **12 languages**; the native engine still holds
  `es-US`, `pt-BR`, and `fi-FI`. Those are not yet in `VoiceRegistry.kt`, and
  will be re-added — bringing the advertised count to **12** — when the engine
  is resynced (the current submodule keeps them available, so nothing is lost
  by this release).

- **Engine moved to a pinned submodule.** The bundled speech engine at
  `native/openevv` is now a **git submodule pinned to upstream
  `Mudb0y/openevv` commit `d2bc0c6`** (verified-good: all 12 engine languages
  and host synthesis passing), replacing the previously maintained fork and the
  in-tree vendored copy. The app's JNI bridge was adapted to the upstream engine
  API (`es_delete`); the only code touched is the app's own wrapper layers. The
  submodule is the single source of truth for the engine pin — future engine
  fresh uses `tools/vendor-openevv.sh` (preview) and is committed as its own
  pointer bump ahead of a release gate. If you relied on any fork-specific
  engine behavior, verify your dialect/pronunciation expectations against
  upstream output.

- **TalkBack voice fix.** The default voice reported to TalkBack now honors the
  explicitly pinned in-app voice (registry-advertised only), so the system's
  read-aloud uses your selected voice instead of overriding it. This changes
  the reported default voice for TalkBack in all locales.

- **Stability fix.** Fixed a startup failure (`dlopen`/undefined-engine-symbol)
  that could crash on first launch after upgrade; verify a clean start.

## Version and publication

- **Publication status checked 2026-10-12:** GitHub Releases carry `v1.0`,
  `v1.0.1`–`v1.0.9`; the latest is `v1.0.9`. Tags use `vMAJOR.MINOR[.PATCH]`;
  the APK `versionName` must match the tag, validated by `tools/publish_release.py`.
  `AndroidManifest.xml` currently identifies the checkout as version name `1.0.9`
  and version code `10`; these are source-tree values -- evidence of a published
  release comes from GitHub Releases plus the tag-triggered release workflow.
- Release tags use `vMAJOR.MINOR[.PATCH]`; each numeric component is limited
  to three digits. The APK `versionName` must match the tag.
- Android `versionCode` must increase independently. The semantic updater
  requires exactly one `<!-- android-version-code: N -->` marker in release
  notes. The publication helper reads this value from the signed APK
  manifest; the tag alone cannot predict it.
- The `v*` tag workflow builds APKs, signs them in the protected GitHub
  `release` environment, audits their ABI contents, then publishes the signed
  assets and APK-derived metadata. Configure required reviewers, restrict the
  environment to protected release tags, and provide the signing secrets
  before using the workflow.
- Ordinary CI creates artifacts with a temporary signing identity. Those
  artifacts are not upgrade-compatible with an installation signed by the
  production key. A change in production signing identity also prevents
  in-place upgrades.
- Publication is implemented in `.github/workflows/release.yml` and
  `tools/publish_release.py`; it has been validated end to end by the released
  `v1.0.5`–`v1.0.9` tags (each published three signed APKs and green CI). Treat
  a real tag-triggered release as the source of truth for the latest status.

## Assets

Production releases contain:

- `vvtts-arm64-v8a.apk`
- `vvtts-armeabi-v7a.apk`
- `vvtts-universal.apk`

The universal APK includes arm64-v8a, armeabi-v7a, and x86_64 libraries.
The separate `vvtts-test-x86_64.apk` is built for emulator testing and is not
published as a production asset. If the updater cannot select a compatible
asset, it falls back to the GitHub Release page rather than offering an
incompatible APK. See [`tools/audit_apk_abis.py`](tools/audit_apk_abis.py) and
[`docs/artifacts-contract.md`](docs/artifacts-contract.md).

## Release validation

Before release, check the relevant CI results and, when hardware is available,
verify:

1. Short speech in each of the 10 advertised locales (12 once the engine
   resync re-adds `es-US`, `pt-BR`, `fi-FI`) and the available voice
   presets.
2. Rapid TalkBack stop/restart, voice changes, and language changes without
   stale speech or a service failure.
3. TTS before the first device unlock after reboot.
4. An upgrade from a previously installed production-signed APK using Android's
   package installer.
5. Update selection when a compatible asset is present and fallback behavior
   when it is not.

Host tests and sanitizers cannot replace listening, device lifecycle, or
installation checks.

## Historical release notes

Older release notes and issue-era test descriptions may refer to languages,
limitations, and workflows that were later changed or are not part of the
current Android product. The current locale list is maintained in
[`VoiceRegistry.kt`](src/com/xw/vvtts/engine/VoiceRegistry.kt); build and test
lanes are defined by [the CI workflows](.github/workflows/build.yml) and
summarized in [the repository audit](docs/repository-audit.md). Update this
file with release-specific notes when publishing a release, without treating
historical notes as the current capability list.
