#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
java_home="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
test_out="$(mktemp -d)"
trap 'rm -rf "$test_out"' EXIT
flags=(-std=gnu11 -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer -Itests/native/stubs -Ijni -Inative/openevv/include -Inative/openevv/src -I"$java_home/include" -I"$java_home/include/linux")
gcc "${flags[@]}" tests/native/bridge_test.c jni/vv_resample.c -lm -lpthread -o "$test_out/bridge"
gcc "${flags[@]}" tests/native/oracle_test.c jni/vv_resample.c -lm -lpthread -o "$test_out/oracle"
gcc "${flags[@]}" -ffunction-sections -fdata-sections tests/native/compat_test.c jni/vv_resample.c -Wl,--gc-sections -lm -lpthread -o "$test_out/compat"
# chs ROM robustness tests: compile the ROM sources directly under
# ASan/UBSan so the fix code itself is instrumented (as CI does).
gcc -std=gnu11 -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer \
  -Itests/native/stubs -Inative/openevv/include -Inative/openevv/src \
  tests/native/chs_rom_test.c \
  native/openevv/src/eci/lang/eci_romedll.c \
  native/openevv/rom/chs/chsrom.c native/openevv/rom/chs/rom_tables_chs.c \
  -lm -lpthread -o "$test_out/chs_rom"
"$test_out/bridge"
"$test_out/oracle"
"$test_out/compat"
"$test_out/chs_rom"
