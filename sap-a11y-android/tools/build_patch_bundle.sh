#!/usr/bin/env bash
# Builds the Morphe patch bundle (artifacts/patch/sapa11y.mpp) without Gradle.
#
# The Gradle project in patch/ is the canonical release build, but it resolves
# the patcher from maven.pkg.github.com/MorpheApp/registry, which needs a GitHub
# token with read:packages. This script compiles the same source against a
# published Morphe jar instead, so the bundle can be built from a clean machine.
#
#   MORPHE_JAR=~/morphe/morphe-desktop-2.2.0-all.jar bash tools/build_patch_bundle.sh
#
# The jar is any Morphe Desktop release (it bundles morphe-patcher). It is not
# committed: download it from https://github.com/MorpheApp/morphe-desktop/releases.
#
# Requirements: JDK 17+ and a Kotlin compiler that can read the jar's Kotlin
# metadata (2.4.0 for the 2.2.0 release; 2.2.x fails with a metadata error).
# Set KOTLINC=/path/to/kotlinc to pick a specific compiler.
set -euo pipefail

here=$(cd "$(dirname "$0")/.." && pwd)
: "${MORPHE_JAR:?set MORPHE_JAR to a Morphe Desktop or morphe-patcher jar}"

KOTLINC=${KOTLINC:-kotlinc}
command -v "$KOTLINC" >/dev/null 2>&1 || {
  echo "kotlinc not found; set KOTLINC=/path/to/kotlinc" >&2
  exit 1
}
if [ -n "${JAVA_HOME:-}" ]; then JAR="$JAVA_HOME/bin/jar"; else JAR=jar; fi

for input in \
  artifacts/native/arm64-v8a/libsapa11y.so \
  artifacts/native/armeabi-v7a/libsapa11y.so \
  artifacts/assets/sapa11y/WikiDictionary.json \
  artifacts/extensions/sapa11y.mpe; do
  [ -f "$here/$input" ] || {
    echo "missing $input; run native/build.sh and tools/build_extension.sh first" >&2
    exit 1
  }
done

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

"$KOTLINC" -nowarn -jvm-target 11 -classpath "$MORPHE_JAR" -d "$work/classes" \
  "$here"/patch/patches/src/main/kotlin/app/sapa11y/patches/*.kt

# The bundle layout: classes plus the files the patch copies into the APK. The
# paths are resolved by the patch through its own class loader, so they must sit
# at the jar root exactly as named here.
mkdir -p "$work/bundle" "$here/artifacts/patch"
cp -r "$work/classes/." "$work/bundle/"
cp -r "$here/artifacts/native" "$here/artifacts/assets" "$here/artifacts/extensions" "$work/bundle/"
(cd "$work/bundle" && "$JAR" cf "$here/artifacts/patch/sapa11y.mpp" .)

echo "built artifacts/patch/sapa11y.mpp"
