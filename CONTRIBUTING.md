# Contributing

## Before opening a pull request

- Keep the change focused and explain user-visible or runtime behavior changes.
- Update related documentation when behavior, build steps, or release metadata changes.
- Add or update focused tests for changed behavior.
- Do not hand-edit generated outputs. Follow
  [`docs/artifacts-contract.md`](docs/artifacts-contract.md) to find the source of
  truth.
- Do not commit credentials, signing keys, or generated build artifacts.

## Build and verification policy

Android SDK/NDK builds run in GitHub Actions. The current workflow is
[.github/workflows/build.yml](.github/workflows/build.yml) and uses JDK 17,
Kotlin 1.9.25, Android platform 36, and Build Tools 35.0.0. The manifest sets
min SDK 28 and target SDK 36.

The local checks that should run before opening or updating a PR are:

```sh
bash tests/native/run.sh
python3 tests/contracts.py
python3 tools/prepare_assets.py --verify
```

`bash tests/host/run.sh` also compiles the host regressions; it requires
`ANDROID_JAR`, `KOTLINC_CP` (Kotlin 1.9.25), and `KXML_JAR` (kxml2 2.3.0).

## Source of truth

- `src/com/xw/vvtts/` — Kotlin service, engine, settings, and language logic
- `jni/` — JNI bridge and compatibility layer for the native ECI interface
- `native/openevv/` — native engine submodule; local clones need
  `git submodule update --init --recursive`
- `docs/languages-voices-capabilities.md` — current shipped locale and voice mapping
- `src/com/xw/vvtts/engine/VoiceRegistry.kt` — executable locale source of truth
- `docs/repository-audit.md` — repository validation notes and historical context

## Pull requests and releases

Target `main` with a descriptive title and include the issue reference when
applicable. Summarize the change, the validation run, and any checks that could
not be executed.

Release tags, asset names, signing, and publication requirements are in
[`RELEASES.md`](RELEASES.md). Production signing secrets belong only in the
protected GitHub `release` environment.
