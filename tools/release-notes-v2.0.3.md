# Eloquence TTS for Android 2.0.3

This patch improves how Android speech-rate requests are applied.

## What changed

- Honor speech-rate requests above 300% instead of capping them before rate mapping.
- Scale rate changes from the active voice's configured base speed, preserving
  that voice's normal rate at 100% and correctly handling non-default baselines.
- Keep the engine's supported speed ceiling in the final native rate mapping.
