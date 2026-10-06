#ifndef VV_PCM_LIMITS_H
#define VV_PCM_LIMITS_H
#include <stddef.h>
#include <stdint.h>
/* At most 60 seconds at the engine rate: 1.3 MiB in, 5.3 MiB resampled. */
#define VV_MAX_PCM_SAMPLES ((size_t)11025 * 60)
#define VV_MAX_TEXT_BYTES ((size_t)16384)
#endif
