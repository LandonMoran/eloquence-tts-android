#!/usr/bin/env bash
# Build libsapa11y.so for the ABIs Super Auto Pets ships (arm64-v8a, armeabi-v7a).
#
# Needs the Android NDK. Set ANDROID_NDK, or ANDROID_HOME with an ndk/ inside.
set -eu
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK:-$HOME/tools/android/sdk}}"
NDK="${ANDROID_NDK:-${ANDROID_NDK_HOME:-$HOME/tools/android/ndk}}"
HOST="$(uname -s)"
case "$HOST" in
  Linux) HOST_TAG=linux-x86_64 ;;
  Darwin) HOST_TAG=darwin-x86_64 ;;
  *) echo "unsupported host $HOST" >&2; exit 1 ;;
esac
TC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[ -x "$TC/clang" ] || { echo "no NDK clang at $TC" >&2; exit 1; }

ROOT="$(cd .. && pwd)"
mkdir -p "$ROOT/artifacts/native"
for ABI in arm64-v8a armeabi-v7a; do
  case "$ABI" in
    arm64-v8a) TARGET=aarch64-linux-android ;;
    armeabi-v7a) TARGET=armv7a-linux-androideabi ;;
  esac
  OUT="$ROOT/artifacts/native/$ABI"
  mkdir -p "$OUT"
  # API 24 is the game's own minSdk; the bridge uses nothing newer.
  "$TC/${TARGET}24-clang++" -std=c++17 -O2 -fPIC -shared -fvisibility=hidden \
    -Wall -Wextra -Wno-unused-parameter \
    -o "$OUT/libsapa11y.so" src/sapa11y.cpp \
    -llog -ldl
  "$TC/llvm-strip" --strip-unneeded "$OUT/libsapa11y.so" 2>/dev/null || true
  echo "built $OUT/libsapa11y.so"
  "$TC/llvm-readelf" -h "$OUT/libsapa11y.so" | grep -E 'Class|Machine'
done
