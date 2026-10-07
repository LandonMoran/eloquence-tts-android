# Chinese oracle audio bank

The Android build uses a table-driven oracle audio bank for Simplified Chinese
(`zh-CN`). This directory contains the consolidated input tables, corpus and
research material, and the generator that emits the native C table. The
generated bank is compiled into `openevv` and accessed by the JNI bridge; no
converted Apple engine library is loaded by the Android runtime.

## Sources and generated output

- `table/*.consolidated.tsv` contains the consolidated source rows.
- `corpus/` contains acquisition and validation inputs; it is not directly
  imported by the native build.
- `merge_build.py` merges the consolidated tables with compatible legacy rows
  from the checked-in generated C and writes
  `native/openevv/lang/chs/oracle_chs.c`.
- `check_gbk_gate.py` checks the generated table's GBK coverage.

Do not edit `oracle_chs.c` by hand. To generate and validate a temporary
output:

```sh
python3 oracle/merge_build.py oracle/table \
  native/openevv/lang/chs/oracle_chs.c /tmp/oracle_chs.c
python3 oracle/check_gbk_gate.py /tmp/oracle_chs.c
```

Review generated differences before updating the tracked output. The Android
CI `chs-smoke` lane regenerates the table, runs the GBK gate, builds the host
engine with `enus` and `chs`, and executes the oracle smoke test. The Android
runtime ships Simplified Chinese only; Traditional Chinese and Korean research
files or dialect constants do not make those locales supported app voices.

## Research notes and provenance

Other files under `oracle/` document investigation of external reference
engines and exploratory reverse-engineering work. They are research records,
not the build instructions or a declaration of shipped Android capabilities.
The APK build links the in-tree `openevv` source and generated tables; it does
not load Apple dylibs. The repository's generated content and underlying
speech data may have different provenance and rights. See the root README,
the applicable repository notices, and the individual research notes before
redistribution.
