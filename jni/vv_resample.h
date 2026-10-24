/*
 * vv_resample.h -- pure, host-testable resample helpers.
 *
 * The synthesis path does NOT call vv_resample_4x: the engine converts to
 * 44,100 Hz itself (eciSampleRate code 5, set in vvtts_core.c), so the bridge
 * only trims silence and tapers the edges of what the engine emitted.  The
 * 4x helpers below are kept because they are the reference the engine's own
 * converter is checked against (tests/native/bridge_test.c and
 * tests/native/rate_test.c), not because the shipped path uses them.
 *
 * Also holds the edge-silence trim and edge-fade used by that path.  All
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

/** Upsample a signed 16-bit mono buffer 4x (11,025 -> 44,100).
 * Reference implementation for the host tests; the shipped bridge does not
 * call it (the engine converts to 44,100 itself).  Returns 0 and sets *out
 * (caller frees) / *outn = 4*in_n, or -1 on an oversized input or allocation
 * failure. Empty input sets *outn to zero without changing *out; failure
 * leaves both outputs unchanged. */
int vv_resample_4x(const short *in, size_t in_n, short **out, size_t *outn);

/** Taper both PCM edges in place toward zero over at most 2 ms at rate_hz. */
void vv_fade_edges(short *pcm, size_t n, int rate_hz);

/** Trim edge silence in place and update *pn (~2 s scan, ~2 ms padding at rate_hz).
 * Returns 1 when it tapered the kept edges, 0 when the buffer is unchanged, so
 * callers that always want a tapered start/end only fade when this answered 0. */
int vv_trim_silence(short *pcm, size_t *pn, int rate_hz);

/** Initialize the shared coefficient table once, safely across threads. */
void vv_resample_build(void);

/** Return the initialized, read-only polyphase table for regression references.
 * The table has static lifetime and must not be freed by the caller. */
const float (*vv_resample_table(void))[VV_RSP_TAPS];

#ifdef __cplusplus
}
#endif

#endif /* VV_RESAMPLE_H */
