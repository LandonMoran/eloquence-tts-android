/* uncosp_test.c -- crash-repro harness for the lineage's known killer words.
 * Each arg (default "uncosp") is synthesized through the real engine and the
 * process must survive + emit PCM, else exit non-zero. No dictionary
 * involvement:the word goes straight through delta + klatt, which is the
 * point -- these words used to crash Eloquence in the blob.;this harness
 * proves whether the same path survives in the rebuild, and catches
 * regressions when engine fixes land. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "eci.h"

#define SAMPLES 8192
static short chunk[SAMPLES];
static long total = 0;
static int peak = 0;

static int cb(ECIHand h, ECIMessage message, int param, void *data) {
    (void)h; (void)data;
    if (message != eciWaveformBuffer || param < 0) return eciDataProcessed;
    int n = param > SAMPLES ? SAMPLES : param;
    total += n;
    for (int i = 0; i < n; i++) {
        int a = chunk[i] < 0 ? -chunk[i] : chunk[i];
        if (a > peak) peak = a;
    }
    return eciDataProcessed;
}

static void wait_done(ECIHand h) {
    for (int i = 0; i < 4000 && h && eciSpeaking(h); i++) {
        struct timespec ts = {0, 2000000L};
        nanosleep(&ts, NULL);
    }
}

static int try_word(const char *w) {
    total = 0; peak = 0;
    ECIHand h = eciNewEx(0x10000);
    if (!h) { fprintf(stderr, "FAIL %s: eciNewEx\n", w); return 1; }
    eciRegisterCallback(h, cb, NULL);
    eciSetOutputBuffer(h, SAMPLES, chunk);
    eciSetParam(h, eciSampleRate, 1);
    eciClearInput(h);
    eciInsertIndex(h, 4242);
    eciAddText(h, w);
    eciSynthesize(h);
    wait_done(h);
    int ok = (total > 0) && (peak > 0);
    if (!ok) fprintf(stderr, "FAIL %s: no pcm (samples=%ld peak=%d)\n", w, total, peak);
    eciDelete(h);
    if (ok) printf("OK   %s (samples=%ld peak=%d)\n", w, total, peak);
    return ok ? 0 : 1;
}

int main(int argc, char **argv) {
    if (argc < 2) return try_word("uncosp");
    int bad = 0;
    for (int i = 1; i < argc; i++)
        bad |= try_word(argv[i]);
    return bad;
}