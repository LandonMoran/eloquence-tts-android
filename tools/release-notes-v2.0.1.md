# Eloquence TTS for Android 2.0.1

This update expands the available voices, improves pronunciation and text
handling, and fixes audio and speech-engine lifecycle issues.

## What changed

- Added Finnish (`fi-FI`), Brazilian Portuguese (`pt-BR`), and US Spanish
  (`es-US`) voices. The app now offers 13 locale voices backed by all 12
  OpenEVV language modules; `es-MX` and `es-US` share the upstream Spanish
  module.
- Improved reading of emoji, flags, keycaps, abbreviations, numbers, times, and
  Roman numerals. Speech-rate changes now follow the engine's measured,
  non-linear rate curve, and accented Western text is normalized before native
  synthesis to avoid characters being dropped.
- Updated the OpenEVV engine to 0.6.1. It includes a built-in US-English
  pronunciation dictionary with expanded and corrected word coverage, reads
  bracketed asides more naturally, and fixes clipped or carried-over audio at
  the end of high-sample-rate utterances.
- Fixed Android bridge handling for text input, cancellation, audio tapering,
  and engine teardown. Update preferences now persist correctly, and users'
  notification opt-outs are honored when notifications are delivered.
- Added battery-optimization status and a shortcut to the relevant Android
  settings. The TTS service is now protected by Android's expected binding
  permission.

Existing voice presets and supported locales remain available; the three new
locales are additive.
