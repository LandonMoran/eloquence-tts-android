# JNI and native bridge

`src/com/xw/vvtts/core/VvttsCore.kt` declares eight static JNI methods.
`vvtts_core.c` implements the native session and synthesis interface;
`eci_compat.c` adapts the engine's ECI API. The `openevv` engine and bridge
are statically linked into one `libvvtts_core.so` for each packaged ABI.

## Runtime contract

- Output is signed 16-bit mono PCM at 44,100 Hz, which is what Android is
  told. The engine produces that rate itself: the session is opened with
  `eciSampleRate` code 5 and the engine's own converter runs, so the bridge
  does no resampling on the synthesis path (`vv_resample_4x` is host-test
  only). What the bridge still does to every result is trim edge silence and
  taper the edges (`vv_trim_silence`, falling back to `vv_fade_edges`).
- A later `nativeSetParam(eciSampleRate, ...)` is allowed and tracks the
  output rate, so trim/fade/cap stay attached to what the engine really
  emits. Either the numbered code (0–6) or a rate in hertz is accepted.
- Voice 0 is the active voice; standard source rows are 1–8. Eddy's
  historical voice number is explicitly mapped to the `openevv` row.
- Voice parameter ranges are gender 0–1, speed 0–250, and 0–100 for the
  other parameters.
- JNI handles are monotonic identifiers in a synchronized registry. Active
  calls hold references; shutdown removes the identifier before reclaiming
  session storage, so stale calls fail without dereferencing freed memory.
- Per-session operation guards serialize native synthesis and controls.
  Synthesis work owns legacy engine operations; stop publishes a cancellation
  generation and late results are discarded. A session that cannot settle is
  retired rather than having its buffers reused.
- Text input is limited to 16 KiB. Buffered engine audio is limited to
  60 seconds at the current output rate; exceeding it fails the utterance
  (the session is retired, never silently truncated).
- A fully silent engine result (no sample beyond the ±700 speech floor) is a
  failure, not speech: the framework must never advance progress over an
  empty waveform.
- The 10 shipped locales (12 once `es-US`, `pt-BR`, `fi-FI` are re-added on the
  next engine resync) must match `VoiceRegistry.kt`; `tests/contracts.py`
  checks parity. Korean is removed from this build (dispatch, detection,
  language-model, and engine `lang/kor` sources).

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
ownership, and compatibility failure propagation.

`tests/native/run.sh` also runs `tests/native/rate_test.c`, but only when a
host engine archive exists (it links the real engine to check its 44,100 Hz
converter against ours, determinism, and the 60-second cap):

```sh
make -C native/openevv LANGS="lang/enus" RULES=c build/libevv.a
bash tests/native/run.sh
```

Without the archive the script prints a `SKIP rate_test` line, so the gap is
visible in the log rather than silent.

Device listening and rapid TalkBack stop/restart remain device-level release
checks.
