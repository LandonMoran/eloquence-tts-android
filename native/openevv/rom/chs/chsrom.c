#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "eci_rom.h"
#include "rom_tables_chs.h"

struct ChsDict {
    const uint8_t *ind[20];
    int32_t       ind_n[20];
    const uint8_t *lex[20];
    int32_t       lex_n[20];
    int32_t       range[21];
};

/* Which dictionary holds this GB index: binary search of ranges. */
static int which_tb(const struct ChsDict *d, int idx)
{
    int lo = 0, hi = 19, mid;
    if (idx < 0) return -1;
    while (lo <= hi) {
        mid = (lo + hi) / 2;
        if (idx < d->range[mid]) {
            hi = mid - 1;
        } else if (mid == 19 || idx < d->range[mid + 1]) {
            return mid;
        } else {
            lo = mid + 1;
        }
    }
    return -1;
}

/* (b0-0xB0)*0x5e + (b1-0xA1) inside the main GB zone. */
static int gb_index(uint8_t b0, uint8_t b1)
{
    if (b0 < 0xB0 || b0 > 0xF7)
        return -1;
    if (b1 < 0xA1 || b1 > 0xFE)
        return -1;
    return (int)(b0 - 0xB0) * 0x5e + (int)(b1 - 0xA1);
}

struct ChsRom {
    EvvRom          base;
    struct ChsDict  dict;
    int32_t         stopped;
    char            in_buf[4096];
    unsigned        in_buf_len;
    char           *out_ptr;
    char            out_buf[16384];
};

static int32_t chs_mbcs2Rom(EvvRom *r, const char *in, char **out);
static void chs_release(EvvRom *r) { (void)r; }
static int32_t chs_stop(EvvRom *r)     { (void)r; return 0; }
static int32_t chs_resume(EvvRom *r)   { (void)r; return 0; }
static int32_t chs_insertIndex(EvvRom *r)          { (void)r; return 0; }
static int32_t chs_setParam(EvvRom *r, int32_t a, int32_t b)
{ (void)r; (void)a; (void)b; return 0; }
static int32_t chs_getParam(EvvRom *r, int32_t a)  { (void)r; (void)a; return 0; }
static void     chs_clearErrors(EvvRom *r)         { (void)r; }
static uint32_t chs_progStatus(EvvRom *r)          { (void)r; return 0; }
static void     chs_errorMessage(EvvRom *r, char *o)
{ (void)r; if (o) o[0] = 0; }
static int32_t chs_addParam(EvvRom *r, const char *t, int32_t n)
{ (void)r; (void)t; (void)n; return 0; }

/* A pass-through romanizer: text in, text out, since the stub language
   has no rules yet to turn it into phonemes.  Buffer the latest stretch
   and hand it back unchanged on processSentence. */
static int32_t chs_addText(EvvRom *r, const char *text, int32_t len,
                           int32_t flag)
{
    struct ChsRom *rom = (struct ChsRom *)r;
    (void)flag;
    if (len <= 0)
        return 0;
    if ((unsigned)len > sizeof rom->in_buf)
        len = (int32_t)sizeof rom->in_buf;
    memcpy(rom->in_buf, text, (size_t)len);
    rom->in_buf_len = (unsigned)len;
    return len;
}

static int32_t chs_processSentence(EvvRom *r, char **out, int32_t annotated)
{
    struct ChsRom *rom = (struct ChsRom *)r;
    (void)annotated;
    if (rom->in_buf_len == 0) {
        *out = 0;
        return 0;
    }
    /* GB text in, pinyin phone string out: the dict walk and syllable
       speller above do the romanizing.  Answer 2 hands the converted
       text back to the engine for processing by the apply_chi rules. */
    chs_mbcs2Rom(r, rom->in_buf, out);
    rom->in_buf_len = 0;
    return 2;
    }

    /* No user dictionary in this rom. */
#define NODICT 0
static const EvvRomOps chs_ops = {
    .release         = chs_release,
    .addText         = chs_addText,
    .insertIndex     = chs_insertIndex,
    .processSentence = chs_processSentence,
    .stop            = chs_stop,
    .resume          = chs_resume,
    .UCS2ToMBCS      = (void *)0,
    .setParam        = chs_setParam,
    .getParam        = chs_getParam,
    .clearErrors     = chs_clearErrors,
    .progStatus      = chs_progStatus,
    .errorMessage    = chs_errorMessage,
    .addParam        = chs_addParam,
    .newDict         = NODICT,
    .deleteDict      = NODICT,
    .setDict         = NODICT,
    .loadDict        = NODICT,
    .saveDict        = NODICT,
    .lookupDictExt   = NODICT,
    .updateDictExt   = NODICT,
    .findFirstDictExt = NODICT,
    .findNextDictExt = NODICT,
    .mbcs2Rom        = chs_mbcs2Rom,
    .rom2Mbcs        = (void *)0,
};
/* The syllable speller.  The dylib's Code2Pinyin takes a code pointer
 * and emits the consonant spelling, the vowel spelling and the tone
 * digit.  The consonantal and vowel spellings here are the lifted
 * spelling tables; the code-value -> spelling-index mapping is the one
 * open SEAM and is pinned by the romcan phone tap. */

static const char *const chs_consonants[] = {
    "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n",
    "l", "z", "c", "s", "r", "j", "q", "x", "y", "w",
    "g", "k", "h", "v", "", "", "",
};
static const char *const chs_vowels[] = {
    "a", "ai", "an", "ang", "ao", "e", "ei", "en", "eng", "er",
    "i", "ia", "ian", "iang", "iao", "ie", "in", "ing", "iong", "iu",
    "o", "ong", "ou", "u", "ua", "uai", "uan", "uang", "ui", "un",
    "uo", "v", "ve", "vn", "ve", "uang", "", "",
};

static void chs_emit_syllable(char **dst, int c0, int c1)
{
    int tone, final, vowel;
    const char *s;

    if (c0 != 0xff) {
        s = chs_consonants[c0 % 26];
        if (s[0]) { *(*dst)++ = s[0]; *(*dst)++ = s[1]; }
    }
    tone = (c1 >> 5) & 7;
    final = c1 & 0x1f;
    if (tone == 0) tone = 5;
    vowel = final % 38;
    s = chs_vowels[vowel];
    while (*s) *(*dst)++ = *s++;
    *(*dst)++ = (char)('0' + tone);
}
static int chs_lookup(struct ChsRom *rom, uint8_t b0, uint8_t b1,
                        char *out, int *outn)
{
    int idx = gb_index(b0, b1);
    int wh, off;
    const uint8_t *ent, *p;
    int c0, c1;
    char *d = out;

    if (idx < 0) return -1;
    wh = which_tb(&rom->dict, idx);
    if (wh < 0) return -1;
    off = idx - rom->dict.range[wh];
    ent = rom->dict.ind[wh] + 6 * off;

    /* A nonzero lex offset means a phrase entry; else the syllable
     * codes are embedded in the index entry itself. */
    if (ent[4] || ent[5]) {
        int lexoff = ent[4] | (ent[5] << 8);
        p = rom->dict.lex[wh] + lexoff;
        while (p[0] || p[1] || p[2] || p[3]) {
            c0 = p[0]; c1 = p[1];
            if (c0 == 0xff) break;
            chs_emit_syllable(&d, c0, c1);
            p += 2;
        }
    } else {
        c0 = ent[0]; c1 = ent[1];
        chs_emit_syllable(&d, c0, c1);
        if (ent[2] || ent[3]) {
            c0 = ent[2]; c1 = ent[3];
            chs_emit_syllable(&d, c0, c1);
        }
    }
    *outn = (int)(d - out);
    return 0;
}
static void dict_ranges2(struct ChsDict *d)
{
    int32_t run = 0;
    int i;
    d->ind[0] = chs_s_ZN10StaticDict10aChiIndTB0;
    d->ind[1] = chs_s_ZN10StaticDict10aChiIndTB1;
    d->ind[2] = chs_s_ZN10StaticDict10aChiIndTB2;
    d->ind[3] = chs_s_ZN10StaticDict10aChiIndTB3;
    d->ind[4] = chs_s_ZN10StaticDict10aChiIndTB4;
    d->ind[5] = chs_s_ZN10StaticDict10aChiIndTB5;
    d->ind[6] = chs_s_ZN10StaticDict10aChiIndTB6;
    d->ind[7] = chs_s_ZN10StaticDict10aChiIndTB7;
    d->ind[8] = chs_s_ZN10StaticDict10aChiIndTB8;
    d->ind[9] = chs_s_ZN10StaticDict10aChiIndTB9;
    d->ind[10] = chs_s_ZN10StaticDict11aChiIndTB10;
    d->ind[11] = chs_s_ZN10StaticDict11aChiIndTB11;
    d->ind[12] = chs_s_ZN10StaticDict11aChiIndTB12;
    d->ind[13] = chs_s_ZN10StaticDict11aChiIndTB13;
    d->ind[14] = chs_s_ZN10StaticDict11aChiIndTB14;
    d->ind[15] = chs_s_ZN10StaticDict11aChiIndTB15;
    d->ind[16] = chs_s_ZN10StaticDict11aChiIndTB16;
    d->ind[17] = chs_s_ZN10StaticDict11aChiIndTB17;
    d->ind[18] = chs_s_ZN10StaticDict11aChiIndTB18;
    d->ind[19] = chs_s_ZN10StaticDict11aChiIndTB19;

    for (i = 0; i < 20; i++) {
    d->ind_n[0] = chs_s_ZN10StaticDict10aChiIndTB0_n;
    d->ind_n[1] = chs_s_ZN10StaticDict10aChiIndTB1_n;
    d->ind_n[2] = chs_s_ZN10StaticDict10aChiIndTB2_n;
    d->ind_n[3] = chs_s_ZN10StaticDict10aChiIndTB3_n;
    d->ind_n[4] = chs_s_ZN10StaticDict10aChiIndTB4_n;
    d->ind_n[5] = chs_s_ZN10StaticDict10aChiIndTB5_n;
    d->ind_n[6] = chs_s_ZN10StaticDict10aChiIndTB6_n;
    d->ind_n[7] = chs_s_ZN10StaticDict10aChiIndTB7_n;
    d->ind_n[8] = chs_s_ZN10StaticDict10aChiIndTB8_n;
    d->ind_n[9] = chs_s_ZN10StaticDict10aChiIndTB9_n;
    d->ind_n[10] = chs_s_ZN10StaticDict11aChiIndTB10_n;
    d->ind_n[11] = chs_s_ZN10StaticDict11aChiIndTB11_n;
    d->ind_n[12] = chs_s_ZN10StaticDict11aChiIndTB12_n;
    d->ind_n[13] = chs_s_ZN10StaticDict11aChiIndTB13_n;
    d->ind_n[14] = chs_s_ZN10StaticDict11aChiIndTB14_n;
    d->ind_n[15] = chs_s_ZN10StaticDict11aChiIndTB15_n;
    d->ind_n[16] = chs_s_ZN10StaticDict11aChiIndTB16_n;
    d->ind_n[17] = chs_s_ZN10StaticDict11aChiIndTB17_n;
    d->ind_n[18] = chs_s_ZN10StaticDict11aChiIndTB18_n;
    d->ind_n[19] = chs_s_ZN10StaticDict11aChiIndTB19_n;
        d->range[i] = run;
        run += d->ind_n[i] / 6;
    }
    d->lex[0] = chs_s_ZN10StaticDict10aChiLexTB0;
    d->lex[1] = chs_s_ZN10StaticDict10aChiLexTB1;
    d->lex[2] = chs_s_ZN10StaticDict10aChiLexTB2;
    d->lex[3] = chs_s_ZN10StaticDict10aChiLexTB3;
    d->lex[4] = chs_s_ZN10StaticDict10aChiLexTB4;
    d->lex[5] = chs_s_ZN10StaticDict10aChiLexTB5;
    d->lex[6] = chs_s_ZN10StaticDict10aChiLexTB6;
    d->lex[7] = chs_s_ZN10StaticDict10aChiLexTB7;
    d->lex[8] = chs_s_ZN10StaticDict10aChiLexTB8;
    d->lex[9] = chs_s_ZN10StaticDict10aChiLexTB9;
    d->lex[10] = chs_s_ZN10StaticDict11aChiLexTB10;
    d->lex[11] = chs_s_ZN10StaticDict11aChiLexTB11;
    d->lex[12] = chs_s_ZN10StaticDict11aChiLexTB12;
    d->lex[13] = chs_s_ZN10StaticDict11aChiLexTB13;
    d->lex[14] = chs_s_ZN10StaticDict11aChiLexTB14;
    d->lex[15] = chs_s_ZN10StaticDict11aChiLexTB15;
    d->lex[16] = chs_s_ZN10StaticDict11aChiLexTB16;
    d->lex[17] = chs_s_ZN10StaticDict11aChiLexTB17;
    d->lex[18] = chs_s_ZN10StaticDict11aChiLexTB18;
    d->lex[19] = chs_s_ZN10StaticDict11aChiLexTB19;

    for (i = 0; i < 20; i++) {
    d->lex_n[0] = chs_s_ZN10StaticDict10aChiLexTB0_n;
    d->lex_n[1] = chs_s_ZN10StaticDict10aChiLexTB1_n;
    d->lex_n[2] = chs_s_ZN10StaticDict10aChiLexTB2_n;
    d->lex_n[3] = chs_s_ZN10StaticDict10aChiLexTB3_n;
    d->lex_n[4] = chs_s_ZN10StaticDict10aChiLexTB4_n;
    d->lex_n[5] = chs_s_ZN10StaticDict10aChiLexTB5_n;
    d->lex_n[6] = chs_s_ZN10StaticDict10aChiLexTB6_n;
    d->lex_n[7] = chs_s_ZN10StaticDict10aChiLexTB7_n;
    d->lex_n[8] = chs_s_ZN10StaticDict10aChiLexTB8_n;
    d->lex_n[9] = chs_s_ZN10StaticDict10aChiLexTB9_n;
    d->lex_n[10] = chs_s_ZN10StaticDict11aChiLexTB10_n;
    d->lex_n[11] = chs_s_ZN10StaticDict11aChiLexTB11_n;
    d->lex_n[12] = chs_s_ZN10StaticDict11aChiLexTB12_n;
    d->lex_n[13] = chs_s_ZN10StaticDict11aChiLexTB13_n;
    d->lex_n[14] = chs_s_ZN10StaticDict11aChiLexTB14_n;
    d->lex_n[15] = chs_s_ZN10StaticDict11aChiLexTB15_n;
    d->lex_n[16] = chs_s_ZN10StaticDict11aChiLexTB16_n;
    d->lex_n[17] = chs_s_ZN10StaticDict11aChiLexTB17_n;
    d->lex_n[18] = chs_s_ZN10StaticDict11aChiLexTB18_n;
    d->lex_n[19] = chs_s_ZN10StaticDict11aChiLexTB19_n;
    }
}

static EvvRom *chs_make(const char *unused)
{
    struct ChsRom *rom = (struct ChsRom *)calloc(1, sizeof(*rom));
    if (!rom) return 0;
    (void)unused;
    rom->base.ops = &chs_ops;
    dict_ranges2(&rom->dict);
    return &rom->base;
}

void __attribute__((constructor)) chs_register(void)
{
    evv_rom_provide(6, 0, chs_make);
}
/* Main entry: GB text in, pinyin phone string out. */
static int32_t chs_mbcs2Rom(EvvRom *r, const char *in, char **out)
{
    struct ChsRom *rom = (struct ChsRom *)r;
    char *dst = rom->out_buf;
    char *dend = rom->out_buf + sizeof(rom->out_buf) - 4;
    const uint8_t *p = (const uint8_t *)in;
    char tmp[64];
    int n = 0;

    while (*p && dst < dend) {
        if (*p >= 0x20 && *p < 0x80) {
            *dst++ = (char)*p++;
            continue;
        }
        if (*p >= 0x80 && p[1] >= 0x80) {
            if (chs_lookup(rom, p[0], p[1], tmp, &n) == 0) {
                if (dst + n < dend) {
                    memcpy(dst, tmp, (size_t)n);
                    dst += n;
                }
            } else {
                *dst++ = '?';
            }
            p += 2;
            continue;
        }
        *dst++ = (char)*p++;
    }
    *dst = 0;
    *out = rom->out_buf;
    return 0;
}
