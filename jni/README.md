# JNI and native bridge

`src/com/xw/vvtts/core/VvttsCore.kt` declares eight static JNI methods.
`vvtts_core.c` implements the native session and synthesis interface;
`eci_compat.c` adapts the engine's ECI API. The `openevv` engine and bridge
are statically linked into one `libvvtts_core.so` for each packaged ABI.

## Runtime contract

- Native engine audio is signed 16-bit mono PCM at 11,025 Hz. The bridge
  resamples it to 44,100 Hz for Android and fades both edges after
  resampling.
- Voice 0 is the active voice; standard source rows are 1–8. Eddy's
  historical voice number is explicitly mapped to the `openevv` row.
- Voice parameter ranges are gender 0–1, speed 0–250, and 0–100 for the
  other parameters. The engine sample rate remains ECI value 1.
- JNI handles are monotonic identifiers in a synchronized registry. Active
  calls hold references; shutdown removes the identifier before reclaiming
  session storage, so stale calls fail without dereferencing freed memory.
- Per-session operation guards serialize native synthesis and controls.
  Synthesis work owns legacy engine operations; stop publishes a cancellation
  generation and late results are discarded. A session that cannot settle is
  retired rather than having its buffers reused.
- Text input is limited to 16 KiB. Buffered engine audio is limited to
  60 seconds at the native sample rate. Oracle clips are checked before
  allocation.
- The 14 shipped locales must match `VoiceRegistry.kt`; `tests/contracts.py`
  checks parity. Traditional Chinese and Korean are not advertised by this
  Android build.

## Build and verification

GitHub Actions is the official Android SDK/NDK build environment. Do not run
Android builds locally. `build_native.sh` builds the Android bridge for the
selected ABI; the supported release ABIs and test-only ABI are defined by
`.github/workflows/build.yml`.

Host native checks do not build an Android APK:

```sh
bash tests/native/run.sh
```

This test uses JDK headers, GCC, AddressSanitizer, and
UndefinedBehaviorSanitizer to exercise JNI admission, cancellation, buffer
ownership, bounded oracle allocation, and compatibility failure propagation.
Device listening and rapid TalkBack stop/restart remain device-level release
checks.
