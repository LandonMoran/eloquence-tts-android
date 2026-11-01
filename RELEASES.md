# Release contract

## Current release notes

This project currently ships a 13-locale-voice Android TTS build backed by all
12 native language modules. The advertised
locales are defined by [`VoiceRegistry.kt`](src/com/xw/vvtts/engine/VoiceRegistry.kt)
and are:
`en-US`, `en-GB`, `de-DE`, `fr-FR`, `fr-CA`, `es-ES`, `es-US`, `es-MX`, `it-IT`,
`ja-JP`, `pl-PL`, `pt-BR`, and `fi-FI`.

`es-US` and `es-MX` share the upstream `esus` module. Korean is not included in
the Android locale list or the packaged language model.

## Version and publication

- GitHub Releases are the source of build publication metadata.
- Release tags follow `vMAJOR.MINOR[.PATCH]` and the APK `versionName` must match
  the tag, as validated by `tools/publish_release.py`.
- Android `versionCode` is kept independent from the tag and must increase for a
  new release.
- Production signing takes place in the protected GitHub `release` environment.
  Ordinary CI builds use a temporary key and are not upgrade-compatible with a
  production-signed install.
- Release publication is implemented in `.github/workflows/release.yml` and
  `tools/publish_release.py`.

## Assets

Production releases publish the following APKs:

- `vvtts-arm64-v8a.apk`
- `vvtts-armeabi-v7a.apk`
- `vvtts-universal.apk`

The universal APK includes `arm64-v8a`, `armeabi-v7a`, and `x86_64` libraries.
The separate `vvtts-test-x86_64.apk` is built for emulator testing and is not a
production asset.

## Release validation

Before release, verify the relevant CI results and, when hardware is available,
check:

1. Short speech in each shipped locale and the available voice presets.
2. Rapid TalkBack stop/restart and voice/language changes without stale speech.
3. TTS before the first device unlock after a reboot.
4. Upgrade flow using a production-signed APK through the system installer.
5. Update selection for a compatible asset and fallback behavior when no
   compatible asset is available.

Host tests and sanitizers do not replace these device and installation checks.

## Historical notes

Older documentation may mention prior versions, removed languages, and workflow
history. The current capability list is defined by
[`VoiceRegistry.kt`](src/com/xw/vvtts/engine/VoiceRegistry.kt), and the active CI
workflow is in [.github/workflows/build.yml](.github/workflows/build.yml).
