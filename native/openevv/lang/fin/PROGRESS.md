# fin (Finnish, family 9 dialect 0 = 0x90000) — lift PROGRESS

Mechanical language-module lift from the itit chassis, per docs/status.md + docs/language.md.

## What was done (all steps PASSED)
1. **Chassis copy**: `lang/fin/` created from `lang/itit/` — five text forms (`fin.globals`, `fin.settings`, `fin.statements`, `fin.sets`, `fin.consts`) + `rules/` (52 files), renamed `itit.*` → `fin.*`. No `.dict` carried over (optional per prescription).
2. **Settings patch**: header/branding patched (`# The engine's settings for fin`, `library Static Engine FIN`), section record `\n\n\n\n[9.0]` (was `[5.0]`), `Path=ecifin.syn` (was `eciitit.syn`), Concatenative/CallbackFlag/Version kept from chassis.
3. **Authentic records swapped in**: `Voice1-8` + `Phoneme0-32` (33 phonemes, Finnish declares 33) extracted via awk from the `[9.0]` section of `/root/eloquence-re/reference/jaws-fidelity-pack/ECI.INI`; the 4 chassis `Voice*Dataset*` lines omitted per prescription. Runtime voice params confirmed in probe output.
4. **gather.py**: `"fin": "Finnish"` added to NAMES.
5. **gather/codepoints**: `python3 tools/module/gather.py fin` and `codepoints.py fin` → `delta_lang_fin.c`, `delta_authored_fin.c`, `delta_rules_none_fin.c`, `delta_codepoints_fin.c` (0 codepoints of its own, as expected).
6. **Build chain**:
   - `make LANGS=lang/fin tables-write` — PASS (wrote delta_globals/eci_ini/delta_link/delta_sets/delta_consts)
   - `make LANGS=lang/fin` — PASS (1749/1749 rules → delta_rules_cNN_fin.c ×32; built `build/libevv-fin.a`, `build/evv`, `build/openevv-say`)
   - `make LANGS=lang/fin tables-check` — PASS: all five regenerated texts byte-identical to the tree's; `eci_ini_fin.c ... language 0x90000` (authentic fi-FI id from the `[9.0]` section)
   - `make LANGS=lang/fin probe` + `./build/probe-fin hei /tmp/fin_out p` — PASS: exit 0, engine says `language 0x90000` (param 9 = 589824 = 0x90000), spelling→phonemes works (`hei` → `[.1E.0i]`), WAV header written.

## Known notes
- Probe writes 0 audio samples — identical to the untouched itit chassis probe (baseline verified). The tree ships no voice datasets, so this is repo-wide norm, not a fin defect.
- Cosmetic chassis provenance comments and structural `store itit_evv_*` names remain as copied (see lang/ptb/PROGRESS.md for rationale). `stream eciitit.ddl`/`dictionary eciitit.ddl` in .sets kept as copied; tables-check round-trips them byte-identically.
- Settings generator (reproducible): `/tmp/gen2.py`; ECI dump: `/tmp/eci_9.txt` (from `[9.0]` section of jaws-fidelity-pack ECI.INI).