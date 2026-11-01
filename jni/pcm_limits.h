#ifndef VV_PCM_LIMITS_H
#define VV_PCM_LIMITS_H
#include <stddef.h>
#include <stdint.h>
/* Bound on the test-only 4x helper's input: at most 60 seconds at the engine's
 * native rate (1.3 MiB), which becomes 5.3 MiB of 44,100 Hz output.  The
 * bridge itself caps its accumulated PCM by rate instead -- 60 seconds at the
 * session's *output* rate (see VvtsSession.maxPcm in vvtts_core.c), because the
 * engine produces 44,100 Hz natively. */
#define VV_MAX_PCM_SAMPLES ((size_t)11025 * 60)
/* Longest UTF-8/Shift_JIS byte sequence the bridge accepts for one utterance. */
#define VV_MAX_TEXT_BYTES ((size_t)16384)
#endif
