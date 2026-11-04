# Eloquence TTS for Android 2.0.4

This patch makes Android's speech-rate setting scale against OpenEVV's native
words-per-minute curve.

## What changed

- Preserve the selected voice's configured speed at 100%.
- Map Android rate percentages to the corresponding words-per-minute target
  using OpenEVV's own ECI conversion, so 200% and 300% requests scale as
  intended instead of relying on an external measured curve.
- Increment the Android version code to 16.
