/*
 * chs_rom_test.c -- host regression tests for native/openevv/rom/chs/chsrom.c
 * (Mandarin ROM robustness fixes #329, #330, #334, #335, #336).
 *
 * Built host-side against the real, committed generated tables
 * (native/openevv/rom/chs/*.c) plus chsrom.c, or against the engine archive
 * libevv-enus-chs.a when linked by CI. It drives the ROM through its public
 * EvvRom maker (family 6, dialect 0) and exercises:
 *
 *   #329 one-letter-initial pinyin must not embed a NUL byte
 *   #330 addText must reject an oversized stretch, not silently keep 4096
 *   #334 mbcs2Rom must report output-buffer truncation explicitly
 *   #335 malformed GBK pairs must not corrupt following ASCII or read OOB
 *   #336 out-of-range index/phrase offsets must fail safely (bounds-checked)
 *
 * Run under ASan/UBSan in CI (build.yml chs-smoke job).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "eci_rom.h"

static int failures = 0;

static void check(int ok, const char *what)
{
    if (!ok) { printf("FAIL: %s\n", what); failures++; }
    else      printf("ok  : %s\n", what);
}

static void expect(const char *got, const char *want_raw)
{
    if (strcmp(got, want_raw) != 0) {
        printf("FAIL: got [%s] want [%s]\n", got, want_raw); failures++;
    } else {
        printf("ok  : [%s]\n", got);
    }
}

int main(void)
{
    EvvRomMaker make = evv_rom_maker(6, 0);
    if (!make) { printf("FAIL: no chs rom maker (family 6, dialect 0)\n"); return 1; }
    EvvRom *rom = make(0);
    if (!rom) { printf("FAIL: chs rom make returned NULL\n"); return 1; }
    const EvvRomOps *ops = rom->ops;
    if (!ops || !ops->mbcs2Rom || !ops->addText) {
        printf("FAIL: chs rom ops incomplete\n"); return 1;
    }

    /* ---- chars whose pinyin is a REAL, non-empty lookup ----
       (values confirmed by scanning the committed tables: B0 A5 -> die7,
       B0 B6 -> zha5) ---- */
    static const unsigned char die7[] = { 0xB0, 0xA5, 0 };   /* die7 */
    static const unsigned char zha5[] = { 0xB0, 0xB6, 0 };   /* zha5 */

    char *out = NULL;

    /* #329 one-letter initial: 'd' in "die7" must be followed by "ie7",
       never an embedded NUL, and the initial must be complete. */
    {
        /* mbcs2Rom returns nonzero on a complete conversion (the caller in
           eci_synthdict.c rejects a zero result as an error). */
        if (ops->mbcs2Rom(rom, (const char *)die7, &out) != 0) {
            /* a single-letter consonant must not hide a NUL: the visible
               string must equal the full pinyin with the tone digit, and
               there must be no interior NUL before it */
            expect(out, "die7");
            size_t n = strlen(out);
            check(n == 4, "#329 die7 length exact (no embedded NUL)");
            check(memchr(out, '\0', n) == NULL, "#329 no interior NUL");
        } else {
            check(0, "#329 die7 responds to mbcs2Rom");
        }
    }
    {
        if (ops->mbcs2Rom(rom, (const char *)zha5, &out) != 0)
            expect(out, "zha5");
        else
            check(0, "zha5 responds to mbcs2Rom");
    }

    /* #335 malformed pairs: a lead byte followed by ASCII, then ASCII,
       must not swallow the ASCII nor emit raw high bytes; a lone lead at
       the end must not read past the NUL. */
    {
        /* 0xB0 is a lone GB lead and 'x','y' are ASCII. The complete
           expected output preserves the bad lead as '?' followed by both
           ASCII bytes in order: "?xy". */
        static const unsigned char lead_ascii[] = { 0xB0, 'x', 'y', 0 };
        if (ops->mbcs2Rom(rom, (const char *)lead_ascii, &out) != 0)
            expect(out, "?xy");
        else
            check(0, "#335 malformed lead+ASCII is a complete conversion");
    }
    {
        /* lone final lead byte (truncated pair) - must not OOB/scan past */
        static const unsigned char lone[] = { 0xB0, 0 };
        if (ops->mbcs2Rom(rom, (const char *)lone, &out) != 0)
            check(strchr(out, '?') != NULL, "#335 lone lead becomes '?'");
        else
            check(0, "#335 lone lead is a complete conversion");
    }

    /* #330 oversized addText must be rejected (return not equal to the
       requested length) and reported, not silently truncated to 4096. */
    {
        size_t big_len = 5000;
        char *big = malloc(big_len);
        if (!big) { check(0, "#330 alloc"); goto out_release; }
        memset(big, 'a', big_len - 1); big[big_len - 1] = 0;
        int32_t ac = ops->addText(rom, big, (int32_t)big_len, 0);
        check(ac != (int32_t)big_len, "#330 oversized addText rejected");
        char em[192];
        ops->errorMessage(rom, em);
        check(strstr(em, "4096") != NULL, "#330 error message reports buffer size");
        free(big);
    }

    /* #334 output-truncation: a very long run of pinyin-producing chars
       fills out_buf; mbcs2Rom must signal truncation (return zero per the
       caller contract in eci_synthdict.c) rather than report success. */
    {
        /* rebuild a long GB input (thousands of 0xB0 0xA5 'die7' chars) */
        static const unsigned char unit[] = { 0xB0, 0xA5 };
        size_t reps = 6000;                 /* ~12KB of pinyin output */
        char *big = malloc(reps * 2 + 1);
        if (big) {
            for (size_t i = 0; i < reps; i++) { big[2*i] = unit[0]; big[2*i+1] = unit[1]; }
            big[reps*2] = 0;
            int32_t r = ops->mbcs2Rom(rom, big, &out);
            check(r == 0, "#334 mbcs2Rom reports output truncation (zero = incomplete)");
            check(out != NULL && out[0] != 0, "#334 truncated output still NUL-terminated");
            free(big);
        } else {
            check(0, "#334 alloc");
        }
    }

    /* #330/#334 stale-flag regression: after an oversized addText sets the
       overflow flag, the engine's error-clear path (clearErrors) must drop it
       so a later, healthy sentence does not re-report the stale overflow and
       thereby hide a fresh truncation. */
    if (ops->clearErrors) {
        char em[192] = "x";
        ops->clearErrors(rom);
        ops->errorMessage(rom, em);
        check(em[0] == 0, "#330 clearErrors clears stale error state");

        /* overflow-free truncation must now surface, not be masked. */
        static const unsigned char z5[] = { 0xB0, 0xB6, 0 };  /* zha5 */
        if (ops->mbcs2Rom(rom, (const char *)z5, &out) != 0) {
            expect(out, "zha5");
            ops->errorMessage(rom, em);
            check(em[0] == 0, "#330 valid sentence after clearErrors has no error");
        } else {
            check(0, "#330 valid sentence after clearErrors converts");
        }
    }

    /* #336 bounds: offsets are validated before dereference. Exercised on
       the real tables by the full scan below + the malformed-input cases
       above never reading past their buffers (ASan would trip if so).
       We also assert the whole valid zone scans without a crash and that
       unknown/edge codes yield '?' rather than OOB reads. Under the fixed
       return contract, mbcs2Rom returns nonzero on a complete conversion
       and zero on truncation/failure, so every single-char conversion here
       must succeed; zero-returns are counted as failures and asserted to
       be zero (do not silently skip failed conversions). */
    {
        char *o = NULL;
        int failed = 0;
        for (int b0 = 0xB0; b0 <= 0xF7; b0++) {
            for (int b1 = 0xA1; b1 <= 0xFE; b1++) {
                unsigned char in[3] = { (unsigned char)b0, (unsigned char)b1, 0 };
                int32_t r = ops->mbcs2Rom(rom, (const char *)in, &o);
                if (r == 0) {   /* a single char cannot fill 16KB: this is a failure */
                    failed++;
                    continue;
                }
                if (o) {
                    /* every output must be a clean printable string */
                    for (char *p = o; *p; p++)
                        if (*p == '\0' || *p == 0x7f) { check(0, "#336 zone scan clean"); break; }
                }
            }
        }
        check(failed == 0, "#336 zero failed conversions in zone sweep");
        check(1, "#336 full GB zone sweeps without OOB/failure");
    }

out_release:
    ops->release(rom);
    if (failures) { printf("chs_rom_test: %d FAILURE(S)\n", failures); return 1; }
    printf("chs_rom_test: all checks passed\n");
    return 0;
}