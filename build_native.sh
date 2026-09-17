#!/bin/bash
# build_native.sh -- Build the SINGLE native library the APK ships:
#   openevv (MIT" Eloquence engine, statically linked( + the JNI bridge
#   ( jni/vvtts_core.c(.  Purgs every legacy native .so from
#   native-libs/arm64-v8a/ and writes back ONLY libvvtts_core.so --
#   so no Apple code ships anywhere in the artifact.;
#
# Needs an Android NDK.  Set ANDROID_NDK (or ANDROID_NDK_HOME(;
# otherwise the script hunts under $ANDROID_SDK/ndk.  GitHub runner
# images ship NDK there (and in CI only -- never run APK/native builds
# on a dev box; CI is the compiler of record.;
set -eu
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-/usr/lib/android-sdk}"
NDK="${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}"
if [ -z "$NDK" ] && [ -d "$SDK/ndk" ]; then
  # Prefer the pinned toolchain when present; else whatever the image ships.
  for cand in "$SDK/ndk/26.3.11579264" "$SDK"/ndk/*/; do
    [ -n "$NDK" ] && continue
    [ -x "$cand/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang" ] && NDK="$cand"
  done
fi
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CLANG="$TC/aarch64-linux-android28-clang"
if [ ! -x "$CLANG" ]; then
  echo "ERROR: no Android NDK found (looked for $CLANG(.  Set" >&2
  echo "  ANDROID_NDK=/path/to/ndk (or install 'ndk;26.3.11579264' via sdkmanager(.  No" >&2
  echo "  native library, no APK." >&2
  exit 1
fi

OUT=native-libs/arm64-v8a
mkdir -p "$OUT"
rm -f "$OUT"/*.so

LANGS="lang/enus lang/engb lang/dede lang/frfr lang/frca lang/eses lang/esus lang/itit lang/jajp lang/plpl"
# Mirror the Makefile's SUF naming ( TAGS := notdir(LANGS), dash-joined; the
# suffix branch is taken once the set is bigger than just enus, and then it
# includes enus too -- checked against `make -p` database ( build/libevv-enus-....a(.
SUF=""
for l in $LANGS; do
  SUF="$SUF-${l#lang/}"
done

# 1. openevv: static archive, cross-compiled, PIC objects so they bind
# into a shared library.  RULES=c bakes the rules as generated C (faster
# warm-up and lower latency on-device than bytecode.;
make -j"$(getconf _NPROCESSORS_ONLN)" -C native/openevv \
    CC="$CLANG" \
    NM="$TC/llvm-nm" \
    RANLIB="$TC/llvm-ranlib" \
    LANGS="$LANGS" \
    RULES=c \
    CFLAGS=-fPIC \
    "build/libevv${SUF}.a"
LIBEVV="$(ls native/openevv/build/libevv*.a 2>/dev/null | head -1 )"
if [ -z "$LIBEVV" ]; then
  echo "ERROR: openevv archive not built (no native/openevv/build/libevv*.a(" >&2
  exit  ​1
fi

# 2. JNI bridge + engine static-linked into one .so.  -fvisibility=hidden
# keeps engine internals private; JNIEXPORT marks the 8 natives + OnLoad public.

"$CLANG" -shared -std=gnu99 -O2 -fPIC -fvisibility=hidden \
    -I native/openevv/include \
    -o "$OUT/libvvtts_core.so" \
    jni/vvtts_core.c \
    "$LIBEVV" \
    -lm

echo "native bridge OK:"
file "$OUT/libvvtts_core.so"
"$TC/llvm-nm" -D "$OUT/libvvtts_core.so" | grep ' Java_com_xw_vvtts_core' | sed 's/^[0-9a-fA-F]* //' | sort | head -12