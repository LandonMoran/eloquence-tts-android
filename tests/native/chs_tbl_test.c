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

    /* Map GB index 0 (b0=0xB0,b1=0xA1) to table 0. which_tb() binary-searches
     * the range[] boundaries, so every range after the first must be large
     * enough that index 0 always lands in bucket 0 (post-memset they were 0,
     * which made which_tb pick the LAST table instead -- an empty one). */
    for (int i = 0; i <= 20; i++)
        rom.dict.range[i] = i == 0 ? 0 : 0x7fffffff;
    rom.dict.ind[0]  = ind;  rom.dict.ind_n[0]  = 6;
    rom.dict.lex[0]  = lex;  rom.dict.lex_n[0]  = 4;

    check(which_tb(&rom.dict, gb_index(0xB0, 0xA1)) == 0,
          "GB index 0 selects table 0");

    char out[64];
    int on = -1;
    int r = chs_lookup(&rom, 0xB0, 0xA1, out, &on);

    /* The phrase window (2 bytes in bounds) is shorter than the 4 bytes the
     * walk needs, so the lookup must fail safely -- return -1 -- and must
     * never read the 4-byte window past the end of lex[] (ASan would trip
     * if the old p[0..3] probe were still there). */
    check(r == -1, "truncated phrase table fails lookup (return -1)");

    /* A phrase that ends with a single trailing 0xff escape byte -- even in
     * the last in-bounds 1-3 bytes -- is a VALID terminator and must be
     * accepted, not rejected for lacking a 4-byte zero block. off=1 skips a
     * pad byte; the walk emits one syllable ('zh' + tone vowel) then arrives
     * at 0xff with only 3 bytes remaining in the table. */
    {
        struct ChsRom rom2;
        memset(&rom2, 0, sizeof(rom2));
        for (int i = 0; i <= 20; i++)
            rom2.dict.range[i] = i == 0 ? 0 : 0x7fffffff;
        uint8_t lex2[6] = { 0x55, 0x00, 0x20, 0xff, 0x00, 0x00 };
        uint8_t ind2[6] = { 0x00, 0x00, 0x00, 0x00, 0x01, 0x00 }; /* lexoff=1 */
        rom2.dict.ind[0]  = ind2;  rom2.dict.ind_n[0]  = 6;
        rom2.dict.lex[0]  = lex2;  rom2.dict.lex_n[0]  = 6;
        char o2[64]; int n2 = -1;
        int r2 = chs_lookup(&rom2, 0xB0, 0xA1, o2, &n2);
        check(r2 == 0, "trailing 0xff escape accepted (valid terminator)");
        if (r2 == 0 && n2 > 0) { o2[n2] = 0; check(o2[0]=='z' && o2[1]=='h' && n2>=3, "escape phrase converted"); }
    }

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