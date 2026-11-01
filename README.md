  # Eloquence TTS for Android

  Eloquence is an Android text-to-speech engine that exposes the native
  [`openevv`](native/openevv) engine through Android's system TTS service. Speech
  synthesis runs locally on-device; generated audio is not sent to a remote speech
  service.

  ## Current capability

  The app currently advertises 10 shipped locales through
  [`VoiceRegistry.kt`](src/com/xw/vvtts/engine/VoiceRegistry.kt):
  `en-US`, `en-GB`, `de-DE`, `fr-FR`, `fr-CA`, `es-ES`, `es-MX`, `it-IT`, `ja-JP`,
  and `pl-PL`.

  The native `openevv` submodule carries additional engine languages, but Korean is
  not shipped or advertised in this Android build. See
  [docs/languages-voices-capabilities.md](docs/languages-voices-capabilities.md) for
  voice presets and locale mapping.

  ## Repository layout

  - `src/` — Kotlin service, engine, settings, detection, update flow, and UI
  - `jni/` — JNI bridge and ECI compatibility layer
  - `native/openevv/` — upstream speech engine as a git submodule
  - `language-models/` and `libs/` — packaged model data and bundled JVM libraries
  - `tests/` — host and native regression checks
  - `tools/` — asset generation, APK audit, and release helpers
  - `.github/workflows/` — CI and release automation

  ## Build and validation

  Android SDK/NDK builds are run in GitHub Actions. The main CI workflow is
  [.github/workflows/build.yml](.github/workflows/build.yml). The release flow is
  [.github/workflows/release.yml](.github/workflows/release.yml).

  Local checks that do not require an Android device or SDK install:

  - `bash tests/native/run.sh`
  - `python3 tests/contracts.py`
  - `python3 tools/prepare_assets.py --verify`

  Host regression checks also run in CI and require:
  `ANDROID_JAR`, `KOTLINC_CP` (Kotlin 1.9.25), and `KXML_JAR` (kxml2 2.3.0).

  ## Relevant documentation

  - [CONTRIBUTING.md](CONTRIBUTING.md) — contribution expectations and validation
  - [RELEASES.md](RELEASES.md) — release tags, signing, and asset requirements
  - [jni/README.md](jni/README.md) — JNI/native runtime contract
  - [docs/artifacts-contract.md](docs/artifacts-contract.md) — generated asset
    source-of-truth rules
  - [docs/repository-audit.md](docs/repository-audit.md) — historical audit notes
  - [LICENSE](LICENSE) — project license

  ## License and provenance

  The root project license is [LICENSE](LICENSE). Third-party speech data and the
  native `openevv` distribution have their own provenance and licensing notices;
  review them before redistribution.
