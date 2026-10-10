/*
 * vv_resample.c -- implementations for vv_resample.h.
 *
 * Self-contained (stdlib/math/pthread only) so it compiles and runs on a
 * host for regression tests as well as on Android.
 */
#include "vv_resample.h"
#include "pcm_limits.h" /* VV_MAX_PCM_SAMPLES: oracle native-rate input bound */

#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <pthread.h>

/** Approximate the modified Bessel I0 function with at most 15 series terms. */
static float vv_rsp_bessel_i0(float x) {
    float sum = 1.0f, term = 1.0f;
    for (int k = 1; k <= 15; k++) {
        term *= (x / (2.0f * k)) * (x / (2.0f * k));
        sum += term;
        if (term < 1e-12f) break;
    }
    return sum;
}

/* Static polyphase coefficient table, built exactly once (pthread_once so a
 * warm-up thread and the first utterance cannot race the build). */
static float vv_rsp_coeff[VV_RSP_PHASES][VV_RSP_TAPS];
static pthread_once_t vv_rsp_once = PTHREAD_ONCE_INIT;

/** Build the Kaiser-windowed sinc table with unity DC gain per phase. */
static void vv_rsp_build(void) {
    const float L2 = (float)VV_RSP_HALF;
    const float fc = VV_RSP_CUTOFF;
    for (int p = 0; p < VV_RSP_PHASES; p++) {
        float sum = 0.0f;
        for (int k = 0; k < VV_RSP_TAPS; k++) {
            /* Tap position t = (k - L2) - (p/4); phase p advances the output
             * grid by p/4 sample.  Window spans t/L2 in [-1,1]. */
            float t = ((float)k - L2) - ((float)p / (float)VV_RSP_PHASES);
            float s = t / L2;
            if (s > 1.0f) s = 1.0f; else if (s < -1.0f) s = -1.0f;
            float w = vv_rsp_bessel_i0(VV_RSP_BETA * (float)sqrt(1.0f - s * s))
                      / vv_rsp_bessel_i0(VV_RSP_BETA);
            float sinc = (t == 0.0f)
                ? (float)(2.0 * 3.14159265358979323846 * fc)
                : (float)(sin(2.0 * 3.14159265358979323846 * fc * t) / t);
            vv_rsp_coeff[p][k] = sinc * w;
            sum += vv_rsp_coeff[p][k];
        }
        /* Normalize: preserve unity DC gain per phase (upsample must not
         * change overall loudness). */
        for (int k = 0; k < VV_RSP_TAPS; k++)
            vv_rsp_coeff[p][k] /= sum;
    }
}

/**
 * Upsample in_n signed 16-bit mono samples to an allocated buffer of 4*in_n
 * samples; the caller frees *out. Return 0 on success or -1 on an oversized
 * input or allocation failure. Empty input sets *outn to zero, leaving *out
 * unchanged; failures leave both outputs unchanged.
 */
int vv_resample_4x(const short *in, size_t in_n, short **out, size_t *outn) {
    if (in_n == 0) { *outn = 0; return 0; }
    /* Overflow-proof sizing before ANY allocation: the 4x output must fit a
     * jsize array (INT32_MAX) and the zero-pad input buffer must survive
     * in_n + 2*HALF without wrapping size_t.  Check the inputs first. */
    if (in_n > VV_MAX_PCM_SAMPLES) return -1;
    if (in_n > (SIZE_MAX - 2 * (size_t)VV_RSP_HALF)) return -1;

    pthread_once(&vv_rsp_once, vv_rsp_build);

    size_t out_n = in_n * VV_RSP_PHASES;
    short *o = (short *)malloc(out_n * sizeof(short));
    if (!o) return -1;
    for (size_t i = 0; i < in_n; i++) {
        /* All phases at this source position use the same zero-padded input
         * interval; calculate its bounds once. */
        const size_t first_tap = i < VV_RSP_HALF ? VV_RSP_HALF - i : 0;
        const size_t available = in_n + VV_RSP_HALF - i;
        const size_t tap_end = available < VV_RSP_TAPS ? available : VV_RSP_TAPS;
        for (int p = 0; p < VV_RSP_PHASES; p++) {
            float acc = 0.0f;
            for (size_t k = first_tap; k < tap_end; k++) {
                const size_t sample = i + k - VV_RSP_HALF;
                acc += vv_rsp_coeff[p][k] * (float)in[sample];
            }
            /* Round-to-nearest with 16-bit clipping. */
            float v = acc;
            if (v > 32767.0f) v = 32767.0f;
            else if (v < -32768.0f) v = -32768.0f;
            o[i * VV_RSP_PHASES + (size_t)p] =
                (short)(v >= 0.0f ? v + 0.5f : v - 0.5f);
        }
    }
    *out = o;
    *outn = out_n;
    return 0;
}

/**
 * Taper both PCM edges in place over at most 2 ms at rate_hz, limited to n/2
 * samples per edge. Leave PCM unchanged when the fade length is zero.
 */
void vv_fade_edges(short *pcm, size_t n, int rate_hz) {
    unsigned fade_u = rate_hz > 0 ? (unsigned)((rate_hz * 2u) / 1000u) : 0u;
    size_t fade = n / 2 < (size_t)fade_u ? n / 2 : (size_t)fade_u;
    if (fade == 0) return;
    for (size_t i = 0; i < fade; i++) {
        pcm[i] = (short)((int)pcm[i] * (int)i / (int)fade);
        pcm[n - 1 - i] = (short)((int)pcm[n - 1 - i] * (int)i / (int)fade);
    }
}

/**
 * Trim edge samples below magnitude 700 in place and update *pn. Scan at most
 * 2 s per edge, retaining and tapering 2 ms of padding at rate_hz (11,025 Hz
 * when nonpositive). Leave short buffers and near-empty results unchanged.
 */
void vv_trim_silence(short *pcm, size_t *pn, int rate_hz) {
    size_t n = *pn;
    if (n < 32) return;
    const int TH = 700;
    unsigned rate_u = rate_hz > 0 ? (unsigned)rate_hz : 11025u;
    size_t cap = (n < 2 * (size_t)rate_u) ? n : (2 * (size_t)rate_u);
    size_t lead = 0;
    while (lead < n && lead < cap && pcm[lead] > -TH && pcm[lead] < TH) lead++;
    if (n - lead < 16) return;
    size_t tail = 0;
    while (tail < n - 1 && tail < cap && pcm[n - 1 - tail] > -TH && pcm[n - 1 - tail] < TH) tail++;
    /* Keep ~2 ms of natural padding, then taper ~2 ms to zero at both ends. */
    const size_t pad = (size_t)((rate_u * 2u) / 1000u);
    lead = lead > pad ? lead - pad : 0;
    tail = tail > pad ? tail - pad : 0;
    if (lead > n || tail > n - lead) return;
    size_t keep = n - lead - tail;
    if (keep < 16) return;
    memmove(pcm, pcm + lead, keep * sizeof(short));
    size_t fade = keep / 2 < pad ? keep / 2 : pad;
    for (size_t k = 0; k < fade; k++) {
        pcm[k] = (short)((int)pcm[k] * (int)k / (int)fade);
        pcm[keep - 1 - k] = (short)((int)pcm[keep - 1 - k] * (int)k / (int)fade);
    }
    *pn = keep;
}

/** Initialize the shared coefficient table once, safely across threads. */
void vv_resample_build(void) { pthread_once(&vv_rsp_once, vv_rsp_build); }

/** Return the initialized, read-only coefficient table with static lifetime. */
const float (*vv_resample_table(void))[VV_RSP_TAPS] {
    vv_resample_build();
    return vv_rsp_coeff;
}
