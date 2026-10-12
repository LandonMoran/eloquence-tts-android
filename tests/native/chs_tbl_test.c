/*
 * chs_tbl_test.c -- host regression test for the phrase-table bounds check
 * in chs_lookup() (#336 / CodeRabbit finding 2 and 6).
 *
 * A deliberately truncated phrase table must fail safely (return -1) without
 * reading p[0..3] past the end of a short lex table, under ASan/UBSan.
 * We include chsrom.c directly in this TU so the file-static chs_lookup()
 * and struct ChsRom/ChsDict are reachable with a hand-crafted table.
 *
 * Compiled host-side with the same ASan/UBSan flags as the robustness test;
 * see tests/native/run.sh and .github/workflows/build.yml (chs-smoke).
 */
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "eci_rom.h"

/* Reach the ROM internals in this translation unit. */
#include "../../native/openevv/rom/chs/chsrom.c"

static int failures = 0;
static void check(int ok, const char *what)
{
    if (!ok) { printf("FAIL: %s\n", what); failures++; }
    else      printf("ok  : %s\n", what);
}

int main(void)
{
    struct ChsRom rom;
    memset(&rom, 0, sizeof(rom));

    /* A lex table whose phrase window runs out of room before a 4-byte
     * terminator can be seen: 4 bytes of syllable payload, but a lexoff of 2
     * leaves only 2 bytes (< 4) in bounds at the phrase start. */
    uint8_t lex[4] = { 0x00, 0x20, 0x22, 0x44 };
    /* One index entry (6 bytes): ent[4]=2 => lexoff=2 into the lex table. */
    uint8_t ind[6] = { 0x00, 0x00, 0x00, 0x00, 0x02, 0x00 };

    /* Map GB index 0 (b0=0xB0,b1=0xA1) to table 0 via a single wide range. */
    rom.dict.range[0] = 0;
    rom.dict.range[1] = 0x7fffffff;
    rom.dict.ind[0]  = ind;  rom.dict.ind_n[0]  = 6;
    rom.dict.lex[0]  = lex;  rom.dict.lex_n[0]  = 4;

    char out[64];
    int on = -1;
    int r = chs_lookup(&rom, 0xB0, 0xA1, out, &on);

    /* The phrase window (2 bytes in bounds) is shorter than the 4 bytes the
     * walk needs, so the lookup must fail safely -- return -1 -- and must
     * never read the 4-byte window past the end of lex[] (ASan would trip
     * if the old p[0..3] probe were still there). */
    check(r == -1, "truncated phrase table fails lookup (return -1)");

    /* A well-formed phrase served from the real committed tables must still
       convert, so the bounds tightening did not regress valid input. We
       build through the public maker so this exercises the same code path. */
    EvvRomMaker make = evv_rom_maker(6, 0);
    if (make) {
        EvvRom *m = make(0);
        if (m && m->ops && m->ops->mbcs2Rom) {
            const unsigned char die7[] = { 0xB0, 0xA5, 0 };
            char *o = NULL;
            int32_t rr = m->ops->mbcs2Rom(m, (const char *)die7, &o);
            check(rr != 0, "valid die7 still converts (nonzero success)");
            check(o && !strcmp(o, "die7"), "die7 == \"die7\"");
            m->ops->release(m);
        } else {
            check(0, "chs rom make/ops available");
        }
    } else {
        check(0, "chs rom maker available (family 6, dialect 0)");
    }

    if (failures) { printf("chs_tbl_test: %d FAILURE(S)\n", failures); return 1; }
    printf("chs_tbl_test: all checks passed\n");
    return 0;
}