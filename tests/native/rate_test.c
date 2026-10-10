/*
 * rate_test.c -- engine-native sample-rate regression (real engine, host).
 *
 * Locks the engine-native 44,100 Hz output the JNI bridge now uses by default
 * (eciSampleRate code 5).  The engine synthesises natively at 11,025 Hz and
 * up-samples to 44,100 itself, so this asserts the invariants the bridge and
 * the "same file, no matter what" claim rest on:
 *
 *   1. 44,100/11,025 length ratio is exactly 4.0x (wrong rate or a broken
 *      engine converter would break it),
 *   2. engine 44,100 == our own 4x sinc of the engine's 11,025, after the
 *      converter's fixed group delay -- bounded agreement (correlation and
 *      SNR), i.e. the resampler being OURS or the LIBRARY's yields the same
 *      audio,
 *   3. identical text -> byte-identical PCM run to run (determinism),
 *   4. a short utterance survives rate-aware trim + edge-fade with voiced,
 *      zero-ended 44,100 PCM (the path Kotlin actually plays).
 *
 * Built in CI against the openevv archive the APK ships (libevv-enus-chs.a,
 * host-built) plus the shared jni/vv_resample.c.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#include "eci.h"
#include "vv_resample.h"
extern int et_addText(void *h, const char *text);   /* engine text shim (eci_compat.c) */
extern int et_insertIndex(void *h, int index);
extern int et_synthesize(void *h);

#define FRAME 4096
#define ENUS  0x10000
#define R_11025 1    /* eciSampleRate code 1 == 11,025 Hz (engine native) */
#define R_44100 5    /* eciSampleRate code 5 == 44,100 Hz                  */

/* ---- PCM capture (engine writes into `frame`, then calls on_pcm) ---- */
typedef struct { short *p; size_t n, cap; } Pcm;
static short _frame[FRAME];

/** Append waveform callbacks to the Pcm in ud; abort on allocation failure. */
static int on_pcm(ECIHand h, ECIMessage m, int count, void *ud) {
    Pcm *o = (Pcm *)ud;
    (void)h;
    if (m != eciWaveformBuffer || count <= 0) return eciDataProcessed;
    if (o->n + (size_t)count > o->cap) {
        size_t c = o->cap ? o->cap * 2 : 65536;
        while (c < o->n + (size_t)count) c *= 2;
        short *p = (short *)realloc(o->p, c * sizeof(short));
        if (!p) return eciDataAbort;
        o->p = p; o->cap = c;
    }
    memcpy(o->p + o->n, _frame, (size_t)count * sizeof(short));
    o->n += (size_t)count;
    return eciDataProcessed;
}

/** Synthesise text at rate (code or raw Hz), exiting on engine setup/input
 * failure. The caller frees the returned PCM buffer. */
static Pcm synth(const char *text, int rate) {
    Pcm out = {0, 0, 0};
    ECIHand h = eciNewEx(ENUS);
    if (!h) { fprintf(stderr, "RATE TEST FAIL: eciNewEx\n"); exit(2); }
    eciRegisterCallback(h, on_pcm, &out);
    if (!eciSetOutputBuffer(h, FRAME, _frame)) {
        fprintf(stderr, "RATE TEST FAIL: setOutputBuffer\n"); eciDelete(h); exit(2);
    }
    if (eciSetParam(h, eciSampleRate, rate) < 0) {
        fprintf(stderr, "RATE TEST FAIL: sample rate %d\n", rate); eciDelete(h); exit(2);
    }
    eciClearInput(h);
    if (!et_insertIndex(h, 4242)) { fprintf(stderr, "RATE TEST FAIL: insertIndex\n"); eciDelete(h); exit(2); }
    if (!et_addText(h, text)) { fprintf(stderr, "RATE TEST FAIL: addText\n"); eciDelete(h); exit(2); }
    if (!et_synthesize(h)) { fprintf(stderr, "RATE TEST FAIL: synthesize\n"); eciDelete(h); exit(2); }
    while (eciSpeaking(h)) { }   /* asking whether it is still speaking pumps the queue */
    eciDelete(h);
    return out;
}

/** Compute normalized correlation of a[i + lag] and b[i] over their overlap.
 * Clamp negative lag to zero; return -2 for insufficient overlap or -1 for
 * zero variance. Lag is measured in output samples. */
static double corr_at(const short *a, size_t na, const short *b, size_t nb, long lag) {
    if (lag < 0) lag = 0;
    if ((size_t)lag >= na || (size_t)lag >= nb) return -2.0;
    size_t use = (na - (size_t)lag < nb) ? (na - (size_t)lag) : nb;
    if (use < 8) return -2.0;
    double ma = 0, mb = 0;
    for (size_t i = 0; i < use; i++) { ma += a[i + lag]; mb += b[i]; }
    ma /= (double)use; mb /= (double)use;
    double sA = 0, sB = 0, dot = 0;
    for (size_t i = 0; i < use; i++) {
        double da = a[i + lag] - ma, db = b[i] - mb;
        sA += da * da; sB += db * db; dot += da * db;
    }
    if (sA <= 0 || sB <= 0) return -1.0;
    return dot / sqrt(sA * sB);
}

/** Compute agreement SNR in dB over a[i + lag] and b[i], using a as reference.
 * Negative lag is clamped to zero; callers must supply lag < na and nb > 0. */
static double snr_at(const short *a, size_t na, const short *b, size_t nb, long lag) {
    if (lag < 0) lag = 0;
    size_t use = (na - (size_t)lag < nb) ? (na - (size_t)lag) : nb;
    double s = 0, e = 0;
    for (size_t i = 0; i < use; i++) {
        double av = a[i + lag], bv = b[i];
        s += av * av; e += (av - bv) * (av - bv);
    }
    return 10 * log10((s / use) / (e / use + 1e-12));
}

static int fails = 0;
#define CHECK(cond, msg) do {                                          \
    if (!(cond)) { fprintf(stderr, "RATE TEST FAIL: %s\n", msg); fails++; } \
} while (0)

/** Verify rate ratio, determinism, sinc agreement, and short-utterance edges;
 * return 0 on success or 1 if any regression assertion fails. */
int main(void) {
    static const char *sentence = "Hello, this is a regression test of the audio pipeline.";
    static const char *shorty   = "Hello.";

    /* A) engine-only: determinism + exact 4x ratio on a sentence */
    Pcm n  = synth(sentence, R_11025);
    Pcm f1 = synth(sentence, R_44100);
    Pcm f2 = synth(sentence, R_44100);
    fprintf(stderr, "debug: n1=%zu 44.1k=%zu ratio=%.6f\n", n.n, f1.n,
            (double)f1.n / (double)n.n);
    CHECK(n.n > 0 && f1.n > 0, "non-empty synthesis");
    CHECK(f1.n == f2.n && memcmp(f1.p, f2.p, f1.n * sizeof(short)) == 0,
          "44,100 output byte-deterministic run to run");
    CHECK(fabs((double)f1.n / (double)n.n - 4.0) < 0.005,
          "44,100/11,025 length ratio is 4.0x");

    /* B) our 4x sinc of the 11,025 must reproduce the engine 44,100 */
    {
        short *ours = NULL; size_t on = 0;
        CHECK(vv_resample_4x(n.p, n.n, &ours, &on) == 0 && on == n.n * 4,
              "our 4x sinc produces a 4x-length result");
        long best = 0; double bc = -1;
        for (long lag = 0; lag <= 512; lag++) {
            double c = corr_at(f1.p, f1.n, ours, on, lag);
            if (c > bc) { bc = c; best = lag; }
        }
        double snr = snr_at(f1.p, f1.n, ours, on, best);
        fprintf(stderr, "debug: best_lag=%ld xcorr=%.6f snr=%.1f dB\n", best, bc, snr);
        CHECK(bc > 0.999, "engine 44,100 correlates with our 4x of its 11,025 (>.999)");
        CHECK(best >= 300 && best <= 512, "converter group delay in [300,512] 44.1k samples");
        CHECK(snr > 26.0, "aligned agreement exceeds 26 dB SNR");
        free(ours);
    }

    /* C) short utterance survives rate-aware trim + edge-fade, zero-ended */
    {
        Pcm s = synth(shorty, R_44100);
        CHECK(s.n > 0, "short utterance produces 44,100 PCM");
        vv_trim_silence(s.p, &s.n, 44100);
        vv_fade_edges(s.p, s.n, 44100);
        size_t voiced = 0;
        for (size_t i = 0; i < s.n; i++)
            if (s.p[i] > 300 || s.p[i] < -300) voiced++;
        CHECK(voiced > 0, "short utterance is voiced after trim+fade");
        CHECK(s.n >= 2 && s.p[0] == 0 && s.p[s.n - 1] == 0,
              "edge fade zeroes the endpoints");
        free(s.p);
    }

    free(n.p); free(f1.p); free(f2.p);

    if (fails) { fprintf(stderr, "RATE TEST FAIL: %d assertion(s)\n", fails); return 1; }
    printf("RATE TEST OK: 44,100 output 4.0x, deterministic, agrees with our sinc, short intact\n");
    return 0;
}
