/* phprobe.c -- ask the engine for phonemes instead of sound, want to see
 * what chs actually produces when driven through the real API path. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "eci.h"

extern int et_insertIndex(void *h, long index);
extern int et_addText(void *h, const char *text);
extern int et_synthesize(void *h);
extern int ev_generatePhonemes(void *h, int32_t n, void *buf);
extern int ev_setOutputToPhonemeCallback(void *h, int32_t n, void *buf);
extern void chs_register(void);   /* chs rom constructor: registers family 6.
                                  Its archive member is only pulled intothe link
                                  when referenced, and nothing else refs it. */
extern void *evv_rom_maker(int32_t family, int32_t dialect);   /* non-null if chs rom registered */
extern int delta_rule_trace;    /* the rules VM's per-call tracer */

#define PHEMES 8192
static char pb[PHEMES];
static long pn = 0;

static int cb(ECIHand h, ECIMessage message, int param, void *data) {
    (void)h; (void)data;
    if (message == eciPhonemeBuffer) {
        int n = param > 0 ? param : 0;
        fprintf(stderr, "[phoneme msg: %d chars]\n", n);
        if (n > 0) {
            fwrite(pb, 1, n < PHEMES ? n : PHEMES - 1, stderr);
            fprintf(stderr, "\n");
            pn += n;
        }
        return eciDataProcessed;
    }
    if (message == eciWaveformBuffer)
        fprintf(stderr, "[waveform msg: %d samples]\n", param);
    return eciDataProcessed;
}

static void wait_done(ECIHand h) {
    for (int i = 0; i < 4000 && h && eciSpeaking(h); i++) {
        struct timespec ts = {0, 2000000L};
        nanosleep(&ts, NULL);
    }
}

int main(int argc, char **argv) {
    const char *word = argc > 1 ? argv[1] : "hello";
    long lang = 0x10000;
    const char *le = getenv("EVV_LANG");
    if (le && *le) lang = strtol(le, NULL, 0);

    ECIHand h;
        chs_register();   /* in static links the chs rom constructor never runs on its
                              own (archive member not pulled). Must be registered
                              *before* eciNewEx — the romanizer manager snapshots the
                              maker table at instantiation andcaches "no rom" otherwise. */
        h = eciNewEx(lang);
                if (!h) { fprintf(stderr, "FAIL: eciNewEx(%lx)\n", lang); return 1; }
                        if (getenv("RULE_TRACE")) delta_rule_trace =2;
    eciRegisterCallback(h, cb, NULL);
    eciSetParam(h, eciSynthMode, 1);
    ev_setOutputToPhonemeCallback(h, PHEMES, pb);
    eciClearInput(h);
    et_insertIndex(h, 4242);
    et_addText(h, word);
    int r = ev_generatePhonemes(h, PHEMES, pb);
    fprintf(stderr, "eciGeneratePhonemes -> %d (%ld phoneme chars total)\n", r, pn);
    wait_done(h);
    eciDelete(h);
    return 0;
}