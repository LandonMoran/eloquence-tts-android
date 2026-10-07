# Bundled JVM libraries

The application build uses the JAR files in this directory. They are checked
into the repository so the build can use the expected versions.

| File | Purpose | Coordinate |
| --- | --- | --- |
| `lingua-slim.jar` | Lingua language classification, with the repository's ELQM bridge | `com.github.pemistahl:lingua:1.2.2` |
| `kotlin-stdlib-1.9.25.jar` | Kotlin standard library | `org.jetbrains.kotlin:kotlin-stdlib:1.9.25` |
| `kotlin-reflect.jar` | Kotlin reflection used by Moshi Kotlin | `org.jetbrains.kotlin:kotlin-reflect:1.9.25` |
| `fastutil.jar` | Collections used by Lingua | `it.unimi.dsi:fastutil:8.5.12` |
| `moshi.jar`, `moshi-kotlin.jar` | JSON and Kotlin serialization support | `com.squareup.moshi:moshi:1.15.0`, `com.squareup.moshi:moshi-kotlin:1.15.0` |
| `okio.jar` | I/O support used by the bundled libraries | `com.squareup.okio:okio:2.10.0` |

`build.sh` passes these dependencies to D8 to include them in the application
DEX files. The Android build and dependency versions are defined by the build
script and GitHub Actions workflow.

## Language models

The repository contains the JSON source models under `language-models/`.
`tools/model_pack.py` packs the configured models into
`language-models/models.elqm`; `tools/prepare_assets.py` generates and verifies
that file and the patched Lingua bridge. The APK includes the ELQM model data
but excludes the JSON source files. A missing or stale generated artifact is a
build/verification issue; there is no JSON fallback packaged in the APK.

Run `python3 tools/prepare_assets.py --verify` to verify the committed
generated files without updating them. Read the
[generated-artifact contract](../docs/artifacts-contract.md) before changing
inputs or outputs.
