# Native bridge

`vvtts_core.c` exposes the eight `VvttsCore` JNI methods. `eci_compat.c` adapts the legacy openevv ABI, including checked construction/deletion and parameter error returns. The engine is statically linked into one `libvvtts_core.so` per ABI.

## Runtime contract

- Native engine PCM is signed 16-bit mono at 11,025 Hz, resampled to 44,100 Hz for Android. Both buffer edges fade to zero after resampling.
- Voice 0 is active; standard source rows are 1–8. Eddy is native row 5. Kotlin's historical Apple row 9 is mapped explicitly.
- Voice parameter ranges are gender 0–1, speed 0–250, other parameters 0–100. Engine sample rate stays at 1 and real-world units at 0. Pitch UI 0–50–100 maps to 0–preset–100.
- JNI handles are monotonic IDs in a locked registry, with references held by active calls. Shutdown unpublishes the ID before reclaiming storage; stale calls fail without dereferencing freed memory.
- Per-session operation guards serialize synthesis and controls. Only the synthesis owner calls legacy engine operations. Stop publishes an atomic generation; queued and late results are discarded. Failure to settle retires the session; its buffers are not reused.
- Text is limited to 16 KiB and buffered native audio to 60 seconds. Oracle clips are bounded before allocation.
- The 14 shipped dialects match `VoiceRegistry.kt`; `tests/contracts.py` enforces parity. Traditional Chinese and Korean are not advertised.

## Build and verification

GitHub Actions is the official Android SDK/NDK build machine. Do not run Android builds locally. `build_native.sh` builds arm64-v8a, armeabi-v7a or x86_64 according to `ABI`.

Host source checks are allowed: `bash tests/native/run.sh` uses a JDK, GCC, ASan and UBSan to exercise JNI admission, cancellation, buffer ownership, bounded oracle allocation and compatibility failure propagation. Real-device listening and rapid TalkBack stop/restart checks remain release gates.
