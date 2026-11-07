# Eloquence TTS for Android 2.0.5

Updates the embedded OpenEVV engine from v0.6.1 to v0.6.2. The Android app
keeps the same ECI/JNI interface, voice controls, sample-rate setup, and twelve
shipped language modules; the new library improves how incoming text is spoken.

## What changed

- OpenEVV no longer ends long sentences at its old ~360-character limit.
  Android still sends bounded, sentence-aware chunks (70/130 characters, with
  up to 160 characters of sentence-boundary grace) to keep stop responsive, so
  this upstream limit change does not alter current Android chunking.
- Standalone punctuation in German, Spanish, French, Italian, and Polish is read
  more like ETI Eloquence 6.1. Lone hyphens are handled more naturally in
  English, German, French, Italian, and Polish.
- OpenEVV's English rules read `ñ` as n+y and its built-in dictionary now finds
  entries written with `ñ`; `gif` is pronounced with a hard g.
- OpenEVV speeds release of large native dictionaries by adding about 2.03 MiB
  of zero-initialized bitmap storage to 64-bit builds. The Android app's custom
  dictionary remains Kotlin-based and does not use that native dictionary API.

## Compatibility

- The release keeps the app's existing native API, output-rate behavior, voice
  settings, and twelve-language build configuration; no Android-side API
  migration is required.
