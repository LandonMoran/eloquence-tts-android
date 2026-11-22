#!/usr/bin/env bash
# Contract tests for the parts of the port that run outside the game.
#
# The game cannot be started in a build environment, so these tests cover what
# the port itself decides: the node JSON the native side hands to Java, the
# geometry a tap is aimed with, how a shop slot is spoken, the shop rules (roll
# cost, free rolls, sell refunds, merge thresholds) and post-action consistency
# against before/after snapshots. They need only a JDK and tests/lib/json.jar.
set -euo pipefail
cd "$(dirname "$0")/.."

# The bridge calls the game's members by name, which no compiler checks. The
# reference check resolves every class and member the shop/board paths use
# against the decompiled game dump stored in tests/reference, so a rename, a
# changed arity or a static/instance flip fails here instead of on a phone.
command -v python3 >/dev/null || { echo "python3 is needed for the reference check" >&2; exit 1; }
echo "--- reference"
python3 tests/reference/check.py

JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk17}"
export PATH="$JAVA_HOME/bin:$PATH"
JSON_JAR="tests/lib/json.jar"
SRC="extension/src/main/java/app/sapa11y/extension"
SUITES=(SapTreeTest ShopRulesTest ShopSnapshotTest ShopBoardTest)

[ -f "$JSON_JAR" ] || {
    echo "missing $JSON_JAR (maven org.json:json:20240303)" >&2
    exit 1
}

rm -rf build/tests && mkdir -p build/tests

# The snapshot reading order is plain C++ with no Android or il2cpp dependency,
# so it is tested on the host as well. The comparator it replaced was
# intransitive, and std::sort with an invalid comparator is undefined
# behaviour, which is not something a Java test can catch.
CXX=$(command -v g++ || command -v clang++ || true)
[ -n "$CXX" ] || { echo "no host C++ compiler (g++ or clang++) for tests/native" >&2; exit 1; }
"$CXX" -std=c++17 -O1 -Wall -Wextra -Werror -Inative/src \
    -o build/tests/native_order tests/native/test_order.cpp
"$CXX" -std=c++17 -O1 -Wall -Wextra -Werror -Inative/src \
    -o build/tests/native_optional tests/native/test_optional.cpp
# Which HangarMain handler a clicked slot goes to. A swapped pair here breaks
# buying while every signature check still passes.
"$CXX" -std=c++17 -O1 -Wall -Wextra -Werror -Inative/src \
    -o build/tests/native_shop_slots tests/native/test_shop_slots.cpp
echo "--- native order"
./build/tests/native_order
echo "--- native optional"
./build/tests/native_optional
echo "--- native shop slots"
./build/tests/native_shop_slots

# Compile the pure classes with the tests; anything Android-typed stays out.
# -Xlint:all with -Werror: the tests double as a no-new-warnings check.
"$JAVA_HOME/bin/javac" -source 11 -target 11 -Xlint:all -Werror \
    -Xlint:-options -Xlint:-serial \
    -classpath "$JSON_JAR" -d build/tests \
    "$SRC/SapNode.java" "$SRC/SapText.java" "$SRC/ShopRules.java" "$SRC/ShopView.java" \
    "$SRC/BoardView.java" \
    tests/src/SapTreeTest.java tests/src/ShopRulesTest.java tests/src/ShopSnapshotTest.java \
    tests/src/ShopBoardTest.java

# The suites share no state, so they run at the same time on whatever cores the
# machine has; no affinity is set. Each status is kept: a bare `wait` would
# report only the last job.
declare -a pids=()
for suite in "${SUITES[@]}"; do
    "$JAVA_HOME/bin/java" -classpath "build/tests:$JSON_JAR" "$suite" \
        > "build/tests/$suite.log" 2>&1 &
    pids+=($!)
done

status=0
for i in "${!pids[@]}"; do
    if ! wait "${pids[$i]}"; then
        echo "${SUITES[$i]}: FAILED" >&2
        status=1
    fi
done

for suite in "${SUITES[@]}"; do
    echo "--- $suite"
    cat "build/tests/$suite.log"
done
exit "$status"
