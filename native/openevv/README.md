# openevv

`openevv` is the in-tree C ECI-compatible speech engine used by the Android
application. It contains the engine implementation and language modules under
`src/` and `lang/`; Android packages it together with the JNI bridge as
`libvvtts_core.so`.

The Android app advertises 14 locales. Thirteen use `openevv` language
modules; Simplified Chinese uses the generated oracle audio bank in
`lang/chs/oracle_chs.c`. The app's authoritative list is
[`VoiceRegistry.kt`](../../src/com/xw/vvtts/engine/VoiceRegistry.kt), not the
set of every dialect source tree present here. Traditional Chinese and Korean
are not shipped Android voices.

## Building and testing

Android cross-compilation is performed by the repository's GitHub Actions
workflows; do not run Android builds locally. The root `build_native.sh`
script compiles the engine and bridge for the requested Android ABI.

For host source-level tests, use the checks documented in
[`jni/README.md`](../../jni/README.md) and the `chs-smoke` job in
[`build.yml`](../../.github/workflows/build.yml). The upstream engine's
standalone build documentation is in [`docs/building.md`](docs/building.md);
its commands and platform examples do not describe Android APK installation.
The remaining engine references are indexed in [`docs/tree.md`](docs/tree.md).

## License and provenance

The engine directory has a separate provenance notice in [`NOTICE`](NOTICE).
Do not assume that the root MIT license applies to vendor-derived language
data. Read the notice and applicable third-party terms before redistribution.
