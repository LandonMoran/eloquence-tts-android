#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
java_home="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
test_out="$(mktemp -d)"
trap 'rm -rf "$test_out"' EXIT
flags=(-std=gnu11 -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer -Itests/native/stubs -Ijni -Inative/openevv/include -Inative/openevv/src -I"$java_home/include" -I"$java_home/include/linux")
gcc "${flags[@]}" tests/native/bridge_test.c -lm -lpthread -o "$test_out/bridge"
gcc "${flags[@]}" tests/native/oracle_test.c -o "$test_out/oracle"
gcc "${flags[@]}" -ffunction-sections -fdata-sections tests/native/compat_test.c -Wl,--gc-sections -lpthread -o "$test_out/compat"
"$test_out/bridge"
"$test_out/oracle"
"$test_out/compat"
