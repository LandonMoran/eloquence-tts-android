/*
 * vv_resample.h -- pure, host-testable resample helpers.
 *
 * Owned by the Chinese (oracle) path, which bypasses the engine and so still
 * needs to lift 11,025 Hz clips to the 44,100 Hz Android playback rate.  The
 * engine-driven English/multi-language path no longer uses this 4x core:
 * the engine's own resampler produces 44,100 Hz when eciSampleRate is set
 * above native (see vvtts_core.c).  Keeping these helpers in a single
 * dependency-free unit makes them host-compilable for regression tests and
 * lets the oracle path share one, tested sink.
 *
 * Also holds the edge-silence trim and edge-fade used for both paths.  All
 * durations are expressed in fixed real-time (seconds/ms) so behavior is
 * unchanged regardless of the sample rate in play.
 */
#ifndef VV_RESAMPLE_H
#define VV_RESAMPLE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* 4x polyphase Kaiser-windowed-sinc constants. */
#define VV_RSP_TAPS 64      /* taps per polyphase branch */
#define VV_RSP_PHASES 4     /* upsampling factor */
#define VV_RSP_HALF (VV_RSP_TAPS / 2)
#define VV_RSP_CUTOFF 0.498f /* ~5500 Hz / 11025 Hz (cycles per input sample) */
#define VV_RSP_BETA 8.0f    /* Kaiser window shape (~50 dB stopband) */

/* Android playback rate and the engine's eciSampleRate code for it. */
#define VV_OUTPUT_HZ 44100  /* kotlin always advertises 44,100 Hz */
#define VV_ENGINE_RATE 5     /* eciSampleRate code 5 == 44,100 Hz (0-6 codes) */

/* Upsample a signed 16-bit mono buffer 4x (11,025 -> 44,100).
 * Returns 0 and sets *out (caller frees) / *outn = 4*in_n, or -1 on an
 * oversized input or allocation failure.  Mirrors the oracle input bound. */
int vv_resample_4x(const short *in, size_t in_n, short **out, size_t *outn);

/* Taper both PCM endpoints to ~2 ms of zeros (scaled to rate_hz). */
void vv_fade_edges(short *pcm, size_t n, int rate_hz);

/* Trim bounded edge silence in place (~2 s scan, ~2 ms padding) at rate_hz. */
void vv_trim_silence(short *pcm, size_t *pn, int rate_hz);

/* Force the one-time coefficient build (idempotent) and return the built
 * 4-x-polyphase table.  Exposed read-only so regression tests can compute an
 * independent reference for the same coefficients the resampler uses. */
void vv_resample_build(void);
const float (*vv_resample_table(void))[VV_RSP_TAPS];

#ifdef __cplusplus
}
#endif

#endif /* VV_RESAMPLE_H */