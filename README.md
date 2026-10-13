# Eloquence TTS for Android

Eloquence TTS is an Android text-to-speech engine based on the native
[`openevv`](native/openevv/README.md) engine. It is designed for Android's
system Text-to-Speech interface, including screen readers such as TalkBack.
Synthesis runs locally; speech text is not sent to a speech service.

## Supported languages and voices

The app advertises these 13 locales: English (United States and United
Kingdom), German (Germany), French (France and Canada), Spanish (Spain,
United States, and Mexico), Italian (Italy), Japanese (Japan), Polish
(Poland), Portuguese (Brazil), and Finnish (Finland).

Eight named voice presets are available: Reed, Shelley, Sandy, Rocko, Flo,
Grandma, Grandpa, and Eddy. A preset selects an ECI voice row and its default
parameters; users can adjust the available voice parameters. The actual
language, locale, and capability mapping is maintained in
[`VoiceRegistry.kt`](src/com/xw/vvtts/engine/VoiceRegistry.kt) and summarized
in [Languages, voices, and capabilities](docs/languages-voices-capabilities.md).
Korean is not a shipped Android voice.

Other capabilities include:

- Automatic language detection, using Unicode checks, in-memory n-gram data,
  and Lingua classification. Mixed-language input is divided into language
  segments before synthesis.
- System TTS controls for speech rate and pitch, plus app voice and volume
  settings. Voice presets also expose the engine's eight ECI voice parameters.
- Direct Boot support: the TTS service is marked `directBootAware` and uses
  device-protected storage so it can initialize before the first unlock.
- A GitHub Releases update checker. Its asset and version requirements are
  documented in [RELEASES.md](RELEASES.md); the workflow has been validated by
  production tags through `v1.0.8`. As checked on 2026-10-11, GitHub Releases
  carry `v1.0` and `v1.0.1`–`v1.0.8`; tags themselves do not include APKs — the
  signed installable APKs are published as release assets.

## Architecture

Android's `TextToSpeechService` in `src/` selects and segments text, applies
normalization and voice settings, then serializes synthesis work through
`EloquenceEngine`. The engine calls the JNI bridge in `jni/`, which statically
links the C `openevv` engine and exposes a single `libvvtts_core.so` per ABI.
The bridge returns signed 16-bit mono PCM. Native engine audio is 11,025 Hz;
the bridge resamples it to 44,100 Hz for Android playback.

All shipped locales synthesize from the in-tree `openevv` engine; Chinese
Simplified is removed. The bridge's lifecycle, cancellation, size, and
encoding constraints are described in [`jni/README.md`](jni/README.md).

## Repository layout

| Path | Purpose |
| --- | --- |
| `src/` | Kotlin Android service, engine, settings, detection, and utilities |
| `jni/` | Native ECI compatibility layer and JNI bridge |
| `native/openevv/` | C speech engine, language modules, and engine documentation |
| `language-models/`, `libs/` | Packaged language-detection data and JVM libraries |
| `res/`, `AndroidManifest.xml` | Android resources and application/service configuration |
| `tests/` | Host regressions, native boundary tests, lifecycle tests, and contracts |
| `tools/` | Asset generation, APK auditing, and release publication helpers |
| `build.sh`, `build_native.sh` | Android APK and native-library build scripts |

## Installation and builds

Android SDK/NDK builds are run in GitHub Actions; do not build Android APKs
locally. The main build workflow compiles the native bridge and APKs for
`arm64-v8a`, `armeabi-v7a`, and a universal package. An `x86_64` APK is
test-only and is not a release asset. See
[`build.yml`](.github/workflows/build.yml) for the current build lane.

When a signed APK is available from a GitHub Release, choose the asset that
matches the device ABI and install it through Android's package installer.
Release signing and publication are described in [RELEASES.md](RELEASES.md).
Ordinary CI artifacts use temporary signing keys and cannot be used as
upgrade-compatible production installs.

## Testing

Run the checks that do not require an Android SDK/NDK:

- `bash tests/native/run.sh` runs the native bridge and compatibility tests
  with GCC address and undefined-behavior sanitizers.
- `python3 tests/contracts.py` checks repository contracts, including voice
  registry/native parity and release/ABI rules.
- `python3 tools/prepare_assets.py --verify` checks generated language-model
  and bridge assets.
- `bash tests/host/run.sh` compiles application sources and runs host
  regressions. It requires the Android API jar, Kotlin 1.9.25 compiler
  classpath, and kxml2 2.3.0 jar (`ANDROID_JAR`, `KOTLINC_CP`, and `KXML_JAR`).

Emulator and Android APK tests run in GitHub
Actions. Device listening, TalkBack cancellation, and pre-unlock Direct Boot
checks remain important release validation; see
[`RELEASES.md`](RELEASES.md).

## Troubleshooting

- If the engine cannot start, verify that the installed APK is a signed
  release build for the device ABI and that its installation has not been
  replaced by an ordinary CI-signed artifact.
- If a locale is unavailable, compare it with the 13 entries in
  [`VoiceRegistry.kt`](src/com/xw/vvtts/engine/VoiceRegistry.kt). Defined
  dialect constants do not necessarily mean that a locale is shipped.
- If generated engine data or language assets differ from their
  sources, follow [the generated-artifact contract](docs/artifacts-contract.md)
  and the [repository audit](docs/repository-audit.md); do not edit generated
  C or packed assets by hand.
- For native handle, cancellation, PCM, and JNI limits, see
  [`jni/README.md`](jni/README.md). For update assets and version metadata,
  see [RELEASES.md](RELEASES.md).

## Licensing and provenance

The root [MIT license](LICENSE) applies to the project material it covers; it
does not grant rights to third-party speech data or vendor-derived material.
The `openevv` distribution includes its own provenance and licensing notice in
[`native/openevv/NOTICE`](native/openevv/NOTICE). Other referenced speech data
has separate provenance and may have separate rights. Review the applicable
notices before redistribution.

## Contributing and releases

Contributions should keep documentation and tests aligned with executable
source-of-truth files. Android builds belong in GitHub Actions; host and native
checks can be run locally when their stated dependencies are available. See
[CONTRIBUTING.md](CONTRIBUTING.md) for pull-request expectations and
[RELEASES.md](RELEASES.md) for versioning, asset names, signing, and publication
status.
