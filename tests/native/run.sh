#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
java_home="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
test_out="$(mktemp -d)"
trap 'rm -rf "$test_out"' EXIT
flags=(-std=gnu11 -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer -Itests/native/stubs -Ijni -Inative/openevv/include -Inative/openevv/src -I"$java_home/include" -I"$java_home/include/linux")
gcc "${flags[@]}" tests/native/bridge_test.c jni/vv_resample.c -lm -lpthread -o "$test_out/bridge"
gcc "${flags[@]}" -ffunction-sections -fdata-sections tests/native/compat_test.c jni/vv_resample.c -Wl,--gc-sections -lm -lpthread -o "$test_out/compat"
"$test_out/bridge"
"$test_out/compat"

# rate_test.c drives the REAL engine and is the only check that the engine's own
# 44,100 Hz converter agrees with ours, so run it whenever a host engine archive
# is available (make -C native/openevv RULES=c LANGS="lang/enus"). Without one
# the test cannot link, and skipping it silently is how it stayed dormant.
engine_archive="$(ls native/openevv/build/libevv*.a 2>/dev/null | head -1 || true)"
if [ -n "$engine_archive" ]; then
  gcc "${flags[@]}" -Itests/native/stubs tests/native/rate_test.c jni/eci_compat.c jni/vv_resample.c \
    "$engine_archive" -lm -lpthread -o "$test_out/rate"
  "$test_out/rate"
else
  echo "SKIP rate_test: no host engine archive (build it with: make -C native/openevv LANGS=\"lang/enus\" RULES=c build/libevv.a)"
fi
