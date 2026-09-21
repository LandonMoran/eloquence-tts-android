# ptb (Portuguese-Brazil, family 7 dialect 0 = 0x70000) — lift PROGRESS

Mechanical language-module lift from the itit chassis, per docs/status.md + docs/language.md.

## What was done (all steps PASSED)
1. **Chassis copy**: `lang/ptb/` created from `lang/itit/` — five text forms (`ptb.globals`, `ptb.settings`, `ptb.statements`, `ptb.sets`, `ptb.consts`) + `rules/` (52 files incl. `symbols`, `it_evv.h`, all rule files), renamed `itit.*` → `ptb.*`. No `.dict` carried over (optional per prescription).
2. **Settings patch**: header/branding patched (`# The engine's settings for ptb`, `library Static Engine PTB`), section record `\n\n\n\n[7.0]` (was `[5.0]`), `Path=eciptb.syn` (was `eciitit.syn`), Concatenative/CallbackFlag/Version kept from chassis.
3. **Authentic records swapped in**: `Voice1-8` + `Phoneme0-35` (36 phonemes, pt-BR declares 36) extracted via awk from the `[7.0]` section of `/root/eloquence-re/reference/jaws-fidelity-pack/ECI.INI`; the 4 chassis `Voice*Dataset*` lines were omitted per prescription. Runtime voice params confirmed in probe output (Voice1 = 0 50 65 30 0 0 50 92).
4. **gather.py**: `"ptb": "Portuguese (Brazil)"` added to NAMES.
5. **gather/codepoints**: `python3 tools/module/gather.py ptb` and `codepoints.py ptb` → `delta_lang_ptb.c`, `delta_authored_ptb.c`, `delta_rules_none_ptb.c`, `delta_codepoints_ptb.c` (0 codepoints of its own, as expected).
6. **Build chain**:
   - `make LANGS=lang/ptb tables-write` — PASS (wrote delta_globals/eci_ini/delta_link/delta_sets/delta_consts)
   - `make LANGS=lang/ptb` — PASS (1749/1749 rules → delta_rules_cNN_ptb.c ×32; built `build/libevv-ptb.a`, `build/evv`, `build/openevv-say`)
   - `make LANGS=lang/ptb tables-check` — PASS: all five regenerated texts byte-identical to the tree's; `eci_ini_ptb.c ... language 0x70000` (authentic pt-BR id from the `[7.0]` section)
   - `make LANGS=lang/ptb probe` + `./build/probe-ptb ola /tmp/ptb_out p` — PASS: exit 0, engine says `language 0x70000`, spelling→phonemes works (`ola` → `[.1c.0la]`, `bom-dia` → `[.1bcm] [.0di.0a]`), WAV header written.

## Known notes
- Probe writes 0 audio samples — identical to the untouched itit chassis probe (baseline verified: `./build/probe-itit ciao` → "0 samples"). The tree ships no voice datasets, so this is repo-wide norm, not a ptb defect.
- Cosmetic chassis provenance comments (`# ... as itit declares them.`) remain in .globals/.sets/.statements/.consts headers, and `store itit_evv_*` names in .consts are structural (the rules' legacy external symbols — the link table binds them); renaming would break the rules correspondence. `stream eciitit.ddl`/`dictionary eciitit.ddl` names in .sets were kept as copied (task's patch list named only the .settings; tables-check round-trips them byte-identically).
- Settings generator (reproducible): `/tmp/gen2.py`; ECI dumps: `/tmp/eci_7.txt` (from `[7.0]`, 437 Voice*/Phoneme* lines means the section body), `/tmp/eci_9.txt` (`[9.0]`).