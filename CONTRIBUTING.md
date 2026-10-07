# Contributing

## Before opening a pull request

- Keep changes focused and explain user-visible or runtime behavior changes.
- Update related documentation when implementation or release behavior changes.
- Add or update focused tests for changed behavior.
- Do not hand-edit generated outputs. Follow
  [`docs/artifacts-contract.md`](docs/artifacts-contract.md) to locate their
  sources of truth.
- Do not commit credentials, signing keys, or generated build artifacts.

## Build and test policy

Android SDK/NDK builds run in GitHub Actions; do not run Android builds locally.
The build workflow uses JDK 17, Kotlin 1.9.25, Android platform 34, and
Build Tools 35.0.0. The application manifest currently sets min SDK 28 and
target SDK 36. See [`jni/README.md`](jni/README.md) for the native bridge
contract.

Available local checks include:

```sh
bash tests/native/run.sh
python3 tests/contracts.py
python3 tools/prepare_assets.py --verify
```

`bash tests/host/run.sh` compiles the host application regressions but requires
`ANDROID_JAR`, `KOTLINC_CP` (Kotlin 1.9.25), and `KXML_JAR` (kxml2 2.3.0).
The main CI workflow runs these checks as well as the Android builds. A separate
`chs-smoke` job validates the Chinese oracle synthesis path; emulator jobs
exercise Android behavior.

## Architecture and source of truth

- `src/com/xw/vvtts/`: Kotlin service, engine, settings, and language detection.
- `jni/`: JNI and compatibility bridge for the C ECI interface.
- `native/openevv/`: C engine and its language modules.
- `oracle/`: Chinese audio table generation and related development material.
- `docs/languages-voices-capabilities.md`: summary of shipped locale and voice
  capabilities; `VoiceRegistry.kt` is the executable locale source of truth.
- `docs/repository-audit.md`: test commands and repository validation notes.

The native APK library is built from `openevv` plus the JNI bridge. Chinese
Simplified uses the generated oracle bank. See the root README and the native
bridge documentation for runtime details.

## Pull requests

Target `main`. Use a descriptive title and include the issue reference when
applicable. Summarize the change, its validation, and any checks that could not
be run. CI must pass before merge; do not merge without the repository
maintainer's approval.

## Releases

Release tags, asset names, signing, and publication requirements are specified
in [`RELEASES.md`](RELEASES.md). Update release documentation when those
contracts change. Production signing secrets belong only in the protected
GitHub `release` environment.
