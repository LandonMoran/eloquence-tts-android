# Eloquence TTS for Android 2.0.2

This patch fixes a speech-output regression introduced in 2.0.1.

## What changed

- Removed inline pause and phrase-prediction annotations from synthesis input.
  The OpenEVV engine could speak these control markers (such as `p100` and
  `pp0`) aloud on every utterance.
